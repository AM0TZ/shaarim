package com.amotz.rateswidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0E1116")
    private val CARD = Color.parseColor("#161B22")
    private val LINE = Color.parseColor("#2A3441")
    private val TEXT = Color.parseColor("#E6EDF3")
    private val MUTED = Color.parseColor("#8B949E")
    private val UP = Color.parseColor("#3FB950")
    private val DOWN = Color.parseColor("#F85149")
    private val ACCENT = Color.parseColor("#58A6FF")

    private var cat = Cat.FIAT
    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 60_000) }   // live while the app is open
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        cat = Cat.values()[intent.getIntExtra(RatesWidget.EXTRA_CAT, 0).coerceIn(0, 2)]
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private var busy = false
    private fun refresh() {
        if (busy) return
        busy = true
        render()
        Thread {
            val err = Repo.refresh(applicationContext)
            RatesWidget.renderAll(applicationContext)
            runOnUiThread {
                busy = false
                render()
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---------------- UI ----------------
    private fun render() {
        root.removeAllViews()

        // header
        val header = hbox()
        header.addView(text("שערים בלייב", 22f, TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = if (busy) "מרענן…" else "רענון"
            isEnabled = !busy
            setTextColor(TEXT); background = round(CARD, LINE); isAllCaps = false
            setOnClickListener { refresh() }
        })
        root.addView(header)
        val upd = Repo.updatedAt(this)
        root.addView(text(if (upd == 0L) "עדיין אין נתונים" else "עודכן ב־${Repo.timeStr(upd)} · מתעדכן כל דקה כשהאפליקציה פתוחה", 12f, MUTED).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        // category tabs
        val tabs = hbox()
        Cat.values().forEach { c ->
            tabs.addView(text(c.he, 15f, if (c == cat) TEXT else MUTED, bold = c == cat).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = if (c == cat) round(LINE, LINE) else null
                setOnClickListener { cat = c; render() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        tabs.background = round(CARD, LINE)
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.addView(tabs)

        root.addView(text("סמנו מה יוצג בווידג'ט (עד ${Catalog.WIDGET_MAX} בכל קטגוריה)", 12f, MUTED).apply {
            setPadding(0, dp(12), 0, dp(6))
        })

        // rows
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, LINE) }
        val sel = Repo.selected(this).toMutableSet()
        Repo.rows(this, cat, onlySelected = false).forEachIndexed { i, r ->
            val row = hbox().apply { setPadding(dp(8), dp(10), dp(12), dp(10)) }
            row.addView(CheckBox(this).apply {
                isChecked = r.item.code in sel
                buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setOnCheckedChangeListener { cb, on ->
                    val now = Repo.selected(this@MainActivity).toMutableSet()
                    if (on) {
                        val inCat = now.count { Catalog.get(it)?.cat == cat }
                        if (inCat >= Catalog.WIDGET_MAX) {
                            Toast.makeText(this@MainActivity, "הווידג'ט מציג עד ${Catalog.WIDGET_MAX} פריטים בכל קטגוריה", Toast.LENGTH_SHORT).show()
                            cb.isChecked = false; return@setOnCheckedChangeListener
                        }
                        now.add(r.item.code)
                    } else now.remove(r.item.code)
                    Repo.setSelected(this@MainActivity, now)
                    RatesWidget.renderAll(applicationContext)
                    if (on) refresh()
                }
            })
            val name = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            name.addView(text(r.item.he, 16f, TEXT))
            name.addView(text(r.unit, 11f, MUTED))
            row.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            v.addView(text(r.price, 16f, TEXT, mono = true, bold = true).apply { textDirection = View.TEXT_DIRECTION_LTR })
            v.addView(text(Repo.fmtPct(r.change), 12f,
                when { r.change == null -> MUTED; r.change > 0 -> UP; r.change < 0 -> DOWN; else -> MUTED }, mono = true
            ).apply { textDirection = View.TEXT_DIRECTION_LTR })
            row.addView(v)
            if (i > 0) list.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            list.addView(row)
        }
        root.addView(list)

        // settings
        root.addView(text("הגדרות", 15f, TEXT, bold = true).apply { setPadding(0, dp(22), 0, dp(8)) })
        val p = Repo.prefs(this)
        root.addView(choice("מטבע להצגת קריפטו ומתכות", listOf("$ דולר" to false, "₪ שקל" to true), Repo.dispIls(this)) {
            p.edit().putBoolean("dispIls", it).apply(); afterSetting()
        })
        root.addView(choice("יחידת מתכות", listOf("אונקיה" to false, "גרם" to true), Repo.gram(this)) {
            p.edit().putBoolean("gram", it).apply(); afterSetting()
        })
        root.addView(choice("רענון אוטומטי של הווידג'ט", listOf("15 דק'" to 15, "30 דק'" to 30, "שעה" to 60, "ידני" to 0), Repo.intervalMin(this)) {
            p.edit().putInt("interval", it).apply(); RatesWidget.schedule(applicationContext); render()
        })

        // add widget
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            root.addView(Button(this).apply {
                text = "הוספת הווידג'ט למסך הבית"
                setTextColor(Color.WHITE); isAllCaps = false
                background = round(Color.parseColor("#238636"), Color.parseColor("#238636"))
                setOnClickListener { mgr.requestPinAppWidget(ComponentName(this@MainActivity, RatesWidget::class.java), null, null) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        }
        root.addView(text("בווידג'ט: הקישו על \"מטבעות / קריפטו / מתכות\" כדי להחליף קטגוריה, ועל ⟳ לרענון.", 12f, MUTED).apply {
            setPadding(0, dp(10), 0, 0)
        })

        // sources
        root.addView(text(
            "מקורות (ציבוריים, ללא מפתח):\n" +
            "• מטבעות: Coinbase exchange rates\n" +
            "• קריפטו: Coinbase Exchange (שינוי 24 שעות)\n" +
            "• מתכות: gold-api.com (מחיר ספוט)\n" +
            "שינוי במטבעות ובמתכות מחושב מול הערך האחרון של אתמול שנשמר במכשיר.",
            11f, MUTED
        ).apply { setPadding(0, dp(18), 0, 0) })
    }

    private fun afterSetting() { RatesWidget.renderAll(applicationContext); render() }

    private fun <T> choice(label: String, opts: List<Pair<String, T>>, cur: T, onPick: (T) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(10)) }
        box.addView(text(label, 13f, MUTED).apply { setPadding(0, 0, 0, dp(6)) })
        val seg = hbox().apply { background = round(CARD, LINE); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        opts.forEach { (t, v) ->
            seg.addView(text(t, 14f, if (v == cur) TEXT else MUTED, bold = v == cur).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = if (v == cur) round(LINE, LINE) else null
                setOnClickListener { if (v != cur) onPick(v) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        box.addView(seg)
        return box
    }

    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun round(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(1, stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
