package com.amotz.rateswidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/** Time span of a history view. ttl = how long a saved copy is considered fresh. */
enum class Span(val he: String, val range: String, val interval: String, val ttlMin: Long, val intraday: Boolean) {
    D1("יום", "1d", "5m", 5, true),
    D5("5 ימים", "5d", "30m", 30, true),
    M1("חודש", "1mo", "60m", 60, true),
    M6("חצי שנה", "6mo", "1d", 6 * 60, false),
    Y1("שנה", "1y", "1d", 12 * 60, false),
    Y5("5 שנים", "5y", "1wk", 24 * 60, false),
    Y10("10 שנים", "10y", "1wk", 24 * 60, false),
    MAX("הכל", "max", "1mo", 24 * 60, false),
}

class Series(val t: LongArray, val v: DoubleArray) {
    val size get() = t.size
    fun map(f: (Double) -> Double) = Series(t, DoubleArray(size) { f(v[it]) })

    /** Combine with another series, pairing each point with [o]'s latest value at or before its time. */
    fun zip(o: Series, f: (Double, Double) -> Double): Series {
        if (o.size == 0) return this
        var j = 0
        val out = DoubleArray(size)
        for (i in 0 until size) {
            while (j + 1 < o.size && o.t[j + 1] <= t[i]) j++
            out[i] = f(v[i], o.v[j])
        }
        return Series(t, out)
    }
}

/**
 * Price history from Yahoo Finance chart API (public, no key), saved on the device per symbol and span
 * under files/hist/. Saved copies are shown instantly and refreshed when older than the span's ttl.
 */
object History {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val METAL_FUT = mapOf("XAU" to "GC=F", "XAG" to "SI=F", "XPT" to "PL=F", "XPD" to "PA=F")

    private fun file(ctx: Context, sym: String, span: Span): File {
        val dir = File(ctx.filesDir, "hist").apply { mkdirs() }
        return File(dir, sym.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + span.name + ".json")
    }

    private fun readSaved(ctx: Context, sym: String, span: Span): Pair<Series, Long>? = try {
        val j = JSONObject(file(ctx, sym, span).readText())
        val t = j.getJSONArray("t"); val v = j.getJSONArray("v")
        Series(LongArray(t.length()) { t.getLong(it) }, DoubleArray(v.length()) { v.getDouble(it) }) to j.getLong("at")
    } catch (e: Throwable) { null }

    private fun download(ctx: Context, sym: String, span: Span): Series {
        val c = URL("https://query1.finance.yahoo.com/v8/finance/chart/$sym?range=${span.range}&interval=${span.interval}")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        val body = try {
            if (c.responseCode == 404) throw FileNotFoundException(sym)
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }

        val r = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: JSONArray()
        val cl = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close") ?: JSONArray()
        val tl = ArrayList<Long>(); val vl = ArrayList<Double>()
        for (i in 0 until minOf(ts.length(), cl.length())) {
            if (cl.isNull(i)) continue
            val x = cl.getDouble(i)
            if (x.isNaN() || x <= 0) continue
            tl.add(ts.getLong(i) * 1000); vl.add(x)
        }
        // make the last point the live price, so the chart ends where the list does
        val meta = r.optJSONObject("meta")
        val live = meta?.optDouble("regularMarketPrice", Double.NaN) ?: Double.NaN
        val liveT = (meta?.optLong("regularMarketTime", 0L) ?: 0L) * 1000
        if (!live.isNaN() && live > 0 && liveT > (tl.lastOrNull() ?: 0L)) { tl.add(liveT); vl.add(live) }
        if (tl.size < 2) throw RuntimeException("no data")

        val s = Series(tl.toLongArray(), vl.toDoubleArray())
        try {
            file(ctx, sym, span).writeText(
                JSONObject().put("at", System.currentTimeMillis())
                    .put("t", JSONArray(tl)).put("v", JSONArray(vl)).toString()
            )
        } catch (_: Throwable) {}
        return s
    }

    /** Saved copy if fresh (or if [savedOnly]); otherwise download, falling back to any saved copy. */
    private fun raw(ctx: Context, sym: String, span: Span, savedOnly: Boolean, force: Boolean): Series? {
        val saved = readSaved(ctx, sym, span)
        if (savedOnly) return saved?.first
        if (!force && saved != null && System.currentTimeMillis() - saved.second < span.ttlMin * 60_000) return saved.first
        return try { download(ctx, sym, span) } catch (e: FileNotFoundException) { throw e } catch (e: Throwable) {
            saved?.first ?: throw e
        }
    }

    /** Is the saved copy for this item/span missing or stale? */
    fun stale(ctx: Context, item: Item, span: Span): Boolean {
        val at = readSaved(ctx, primarySymbol(ctx, item), span)?.second ?: return true
        return System.currentTimeMillis() - at > span.ttlMin * 60_000
    }

    private fun primarySymbol(ctx: Context, item: Item) = when (item.cat) {
        Cat.FIAT -> if (item.code == "USD") "USDILS=X"
                    else if (noDirect(ctx, item.code)) "USD${item.code}=X" else "${item.code}ILS=X"
        Cat.CRYPTO -> "${item.code}-USD"
        Cat.METAL -> METAL_FUT.getValue(item.code)
    }

    private fun noDirect(ctx: Context, code: String) = Repo.prefs(ctx).getBoolean("noDirect_$code", false)

    /**
     * The item's history in the units the app shows (₪ per unit / per 100 for fiat, $ or ₪ for crypto & metals,
     * ounce or gram for metals). Null when nothing is available ([savedOnly] with no saved copy).
     */
    fun series(ctx: Context, item: Item, span: Span, savedOnly: Boolean = false, force: Boolean = false): Series? {
        val ils = Repo.dispIls(ctx)
        fun g(sym: String) = raw(ctx, sym, span, savedOnly, force)
        return when (item.cat) {
            Cat.FIAT -> {
                val base: Series? = if (item.code == "USD") g("USDILS=X") else {
                    var direct: Series? = null
                    if (!noDirect(ctx, item.code)) {
                        try { direct = g("${item.code}ILS=X") } catch (e: FileNotFoundException) {
                            Repo.prefs(ctx).edit().putBoolean("noDirect_${item.code}", true).apply()
                        }
                    }
                    direct ?: run {
                        val usdX = g("USD${item.code}=X"); val usdIls = g("USDILS=X")
                        if (usdX == null || usdIls == null) null else usdX.zip(usdIls) { x, u -> u / x }
                    }
                }
                base?.map { it * item.per }
            }
            Cat.CRYPTO -> {
                val s = g("${item.code}-USD") ?: return null
                if (ils) g("USDILS=X")?.let { u -> s.zip(u) { a, b -> a * b } } ?: s else s
            }
            Cat.METAL -> {
                var s = g(METAL_FUT.getValue(item.code)) ?: return null
                if (ils) s = g("USDILS=X")?.let { u -> s.zip(u) { a, b -> a * b } } ?: s
                if (Repo.gram(ctx)) s.map { it / Repo.OZ_G } else s
            }
        }
    }

    /** Download and save long-range history for the selected items (called at most once a day). */
    fun prefetch(ctx: Context) {
        val p = Repo.prefs(ctx)
        if (System.currentTimeMillis() - p.getLong("histPrefetch", 0L) < 20 * 3600_000L) return
        p.edit().putLong("histPrefetch", System.currentTimeMillis()).apply()
        val sel = Repo.selected(ctx)
        for (item in Catalog.items.filter { it.code in sel }) {
            for (span in listOf(Span.Y1, Span.MAX)) {
                try { series(ctx, item, span) } catch (_: Throwable) {}
                Thread.sleep(250)
            }
        }
    }
}
