package com.amotz.rateswidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One row ready for display. */
data class Row(val item: Item, val price: String, val change: Double?, val unit: String)

/**
 * Public, key-free data sources:
 *  - Fiat (and fallback for everything): Coinbase exchange rates  api.coinbase.com/v2/exchange-rates
 *  - Crypto price + 24h change:          Coinbase Exchange        api.exchange.coinbase.com/products/X-USD/stats
 *  - Precious metals spot:               Gold API                 api.gold-api.com/price/XAU
 * All raw prices are stored as USD per unit; the ILS rate is stored separately.
 */
object Repo {
    private const val UA = "RatesWidget/1.0 (Android)"
    const val OZ_G = 31.1034768

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("rates", Context.MODE_PRIVATE)

    // ---------- settings ----------
    fun selected(ctx: Context): Set<String> = prefs(ctx).getStringSet("selected", null) ?: Catalog.defaults
    fun setSelected(ctx: Context, s: Set<String>) = prefs(ctx).edit().putStringSet("selected", HashSet(s)).apply()
    fun dispIls(ctx: Context) = prefs(ctx).getBoolean("dispIls", false)      // crypto & metals in ₪ instead of $
    fun gram(ctx: Context) = prefs(ctx).getBoolean("gram", false)            // metals per gram instead of ounce
    fun intervalMin(ctx: Context) = prefs(ctx).getInt("interval", 30)        // 0 = manual
    fun updatedAt(ctx: Context) = prefs(ctx).getLong("updatedAt", 0L)
    fun lastError(ctx: Context) = prefs(ctx).getString("lastError", null)

    // ---------- network ----------
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    @Volatile private var running = false

    /** Blocking. Returns null on success or an error message. */
    fun refresh(ctx: Context): String? {
        if (running) return null
        running = true
        try {
            val sel = selected(ctx)
            val p = prefs(ctx)
            val e = p.edit()

            // 1) Coinbase exchange rates: units per 1 USD
            var rates: JSONObject? = null
            var err: String? = null
            try {
                rates = JSONObject(get("https://api.coinbase.com/v2/exchange-rates?currency=USD"))
                    .getJSONObject("data").getJSONObject("rates")
            } catch (t: Throwable) { err = "שערי מטבע: ${t.message}" }

            fun fromRates(code: String): Double? =
                rates?.optString(code, "")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { 1.0 / it }

            val ils = rates?.optString("ILS", "")?.toDoubleOrNull()
            if (ils != null) snapshot(p, e, "ILSRATE", ils)

            for (it in Catalog.byCat(Cat.FIAT)) {
                val usd = if (it.code == "USD") 1.0 else fromRates(it.code)
                if (usd != null) snapshot(p, e, it.code, usd)
            }

            // 2) crypto + metals in parallel
            val pool = Executors.newFixedThreadPool(6)
            val jobs = Catalog.items.filter { it.code in sel && it.cat != Cat.FIAT }.map { item ->
                Callable {
                    try {
                        if (item.cat == Cat.CRYPTO) {
                            val j = JSONObject(get("https://api.exchange.coinbase.com/products/${item.code}-USD/stats"))
                            val last = j.getString("last").toDouble()
                            val open = j.getString("open").toDouble()
                            Triple(item.code, last, if (open > 0) (last - open) / open * 100 else null)
                        } else {
                            val j = JSONObject(get("https://api.gold-api.com/price/${item.code}"))
                            Triple(item.code, j.getDouble("price"), null)
                        }
                    } catch (t: Throwable) {
                        fromRates(item.code)?.let { Triple(item.code, it, null) }   // fallback
                    }
                }
            }
            val results = try { pool.invokeAll(jobs, 15, TimeUnit.SECONDS) } finally { pool.shutdownNow() }
            for (f in results) {
                val r = try { f.get() } catch (t: Throwable) { null } ?: continue
                snapshot(p, e, r.first, r.second)
                if (r.third != null) e.putFloat("c24_${r.first}", r.third!!.toFloat()) else e.remove("c24_${r.first}")
            }

            if (rates != null || results.isNotEmpty()) e.putLong("updatedAt", System.currentTimeMillis())
            if (err == null) e.remove("lastError") else e.putString("lastError", err)
            e.apply()
            return err
        } catch (t: Throwable) {
            prefs(ctx).edit().putString("lastError", t.message ?: "שגיאה").apply()
            return t.message ?: "שגיאה"
        } finally { running = false }
    }

    /** Keeps today's last value and the last value from the previous day (for daily change). */
    private fun snapshot(p: SharedPreferences, e: SharedPreferences.Editor, code: String, v: Double) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val lastDay = p.getString("d_$code", null)
        if (lastDay != null && lastDay != today) {
            val last = p.getFloat("v_$code", 0f)
            if (last > 0) e.putFloat("prev_$code", last)
        }
        e.putString("d_$code", today).putFloat("v_$code", v.toFloat())
    }

    private fun value(p: SharedPreferences, code: String) = p.getFloat("v_$code", 0f).toDouble().takeIf { it > 0 }
    private fun prev(p: SharedPreferences, code: String) = p.getFloat("prev_$code", 0f).toDouble().takeIf { it > 0 }

    // ---------- display ----------
    fun rows(ctx: Context, cat: Cat, onlySelected: Boolean): List<Row> {
        val p = prefs(ctx)
        val sel = selected(ctx)
        val ils = value(p, "ILSRATE")
        val ilsPrev = prev(p, "ILSRATE") ?: ils
        val inIls = dispIls(ctx) && ils != null
        val perGram = gram(ctx)
        return Catalog.byCat(cat).filter { !onlySelected || it.code in sel }.map { item ->
            val usd = value(p, item.code)
            val usdPrev = prev(p, item.code)
            when (item.cat) {
                Cat.FIAT -> {
                    val v = if (usd != null && ils != null) usd * ils else null
                    val v0 = if (usdPrev != null && ilsPrev != null) usdPrev * ilsPrev else null
                    Row(item, v?.let { "₪" + fmt(it) } ?: "—", pct(v, v0), "₪ ל־1 ${item.code}")
                }
                Cat.CRYPTO -> {
                    val f = if (inIls) ils!! else 1.0
                    val c24 = if (p.contains("c24_${item.code}")) p.getFloat("c24_${item.code}", 0f).toDouble()
                              else pct(usd, usdPrev)
                    Row(item, usd?.let { (if (inIls) "₪" else "$") + fmt(it * f) } ?: "—", c24, item.code)
                }
                Cat.METAL -> {
                    val f = if (inIls) ils!! else 1.0
                    val f0 = if (inIls) ilsPrev!! else 1.0
                    val g = if (perGram) OZ_G else 1.0
                    val v = usd?.let { it * f / g }
                    val v0 = usdPrev?.let { it * f0 / g }
                    Row(item, v?.let { (if (inIls) "₪" else "$") + fmt(it) } ?: "—", pct(v, v0),
                        if (perGram) "לגרם" else "לאונקיה")
                }
            }
        }
    }

    private fun pct(v: Double?, v0: Double?) = if (v != null && v0 != null && v0 > 0) (v / v0 - 1) * 100 else null

    fun fmt(v: Double): String = when {
        v >= 10000 -> String.format(Locale.US, "%,.0f", v)
        v >= 10 -> String.format(Locale.US, "%,.2f", v)
        v >= 1 -> String.format(Locale.US, "%.4f", v)
        else -> String.format(Locale.US, "%.5f", v)
    }

    fun fmtPct(c: Double?): String = when {
        c == null -> ""
        c > 0 -> String.format(Locale.US, "▲%.2f%%", c)
        c < 0 -> String.format(Locale.US, "▼%.2f%%", -c)
        else -> "0.00%"
    }

    fun timeStr(ms: Long): String =
        if (ms == 0L) "—" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
