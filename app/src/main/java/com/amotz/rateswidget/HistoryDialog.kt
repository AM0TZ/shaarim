package com.amotz.rateswidget

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.FileNotFoundException

/** Popup with an item's price history and span chips (day … all). */
class HistoryDialog(private val act: Activity, private val item: Item) {

    private val CARD = Color.parseColor("#161B22")
    private val LINE = Color.parseColor("#2A3441")
    private val TEXT = Color.parseColor("#E6EDF3")
    private val MUTED = Color.parseColor("#8B949E")
    private val UP = Color.parseColor("#3FB950")
    private val DOWN = Color.parseColor("#F85149")
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val dialog = Dialog(act)
    private val chart = ChartView(act)
    private val change = TextView(act)
    private val status = TextView(act)
    private val chips = LinkedHashMap<Span, TextView>()
    private var span = Repo.prefs(act).getString("histSpan", null)?.let { n -> Span.values().firstOrNull { it.name == n } } ?: Span.Y1
    @Volatile private var token = 0

    private val prefix get() = when {
        item.cat == Cat.FIAT -> "₪"
        Repo.dispIls(act) -> "₪"
        else -> "$"
    }

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat(); setStroke(1, LINE) }
        }

        // header: icon, name, close
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon(), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.addView(tv(item.he, 17f, TEXT, true))
        names.addView(tv(unitLabel(), 12f, MUTED))
        head.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(tv("✕", 18f, MUTED).apply {
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(head)

        // current price + change over span
        val row = Repo.rows(act, item.cat, onlySelected = false).first { it.item.code == item.code }
        val priceLine = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(0, dp(12), 0, dp(4))
        }
        priceLine.addView(tv(row.price, 26f, TEXT, true, mono = true).apply { textDirection = View.TEXT_DIRECTION_LTR })
        change.apply { textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(dp(10), 0, 0, dp(4)) }
        priceLine.addView(change)
        root.addView(priceLine)

        chart.span = span
        chart.prefix = prefix
        root.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)).apply { topMargin = dp(6) })

        // span chips
        val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        Span.values().forEach { s ->
            val c = tv(s.he, 14f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { select(s) }
            }
            chips[s] = c
            chipRow.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(2) })
        }
        root.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(chipRow)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        status.apply { textSize = 11f; setTextColor(MUTED); setPadding(0, dp(10), 0, 0) }
        root.addView(status)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((act.resources.displayMetrics.widthPixels - dp(24)), WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        dialog.setOnDismissListener { token++ }
        dialog.show()
        select(span)
    }

    private fun select(s: Span) {
        span = s
        Repo.prefs(act).edit().putString("histSpan", s.name).apply()
        chips.forEach { (k, c) ->
            val on = k == s
            c.setTextColor(if (on) TEXT else MUTED)
            c.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            c.background = if (on) GradientDrawable().apply { setColor(LINE); cornerRadius = dp(14).toFloat() } else null
        }
        chart.span = s
        val my = ++token

        // 1) saved copy instantly
        val saved = try { History.series(act, item, s, savedOnly = true) } catch (_: Throwable) { null }
        display(saved, s)
        if (saved == null) chart.message = "טוען…"

        // 2) download if missing/stale
        if (saved == null || History.stale(act, item, s)) {
            status.text = "מעדכן…"
            Thread {
                val res = try { Result.success(History.series(act, item, s)) } catch (e: Throwable) { Result.failure(e) }
                act.runOnUiThread {
                    if (my != token) return@runOnUiThread
                    res.onSuccess { display(it, s); footer(null) }
                    res.onFailure { e ->
                        if (saved == null) {
                            chart.series = null
                            chart.message = if (e is FileNotFoundException) "אין היסטוריה לפריט הזה" else "אין חיבור — נסו שוב"
                        }
                        footer(if (saved != null) "מוצג עותק שמור" else null)
                    }
                }
            }.start()
        } else footer(null)
    }

    private fun display(sr: Series?, s: Span) {
        chart.series = sr
        if (sr == null || sr.size < 2) { change.text = ""; return }
        val c = (sr.v.last() / sr.v.first() - 1) * 100
        change.text = Repo.fmtPct(c) + "  " + s.he
        change.setTextColor(if (c > 0) UP else if (c < 0) DOWN else MUTED)
        change.textDirection = View.TEXT_DIRECTION_LTR
    }

    private fun footer(extra: String?) {
        val src = if (item.cat == Cat.METAL) "Yahoo Finance · לפי חוזים עתידיים" else "Yahoo Finance"
        status.text = listOfNotNull(extra, "גררו על הגרף לערך בתאריך", src).joinToString("  ·  ")
    }

    private fun unitLabel() = when (item.cat) {
        Cat.FIAT -> "₪ ל־${item.per} ${item.code}"
        Cat.CRYPTO -> "${item.code} · $prefix"
        Cat.METAL -> (if (Repo.gram(act)) "לגרם" else "לאונקיה") + " · $prefix"
    }

    private fun icon() = TextView(act).apply {
        text = item.glyph; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        textSize = when { item.color == 0 -> 17f; item.glyph.length > 1 -> 12f; else -> 15f }
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(if (item.color == 0) Catalog.NEUTRAL else item.color)
        }
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

}
