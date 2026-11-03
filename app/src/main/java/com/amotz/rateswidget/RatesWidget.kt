package com.amotz.rateswidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

class RatesWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAll(ctx)
        schedule(ctx)
        refreshAsync(ctx, goAsync())
    }

    override fun onEnabled(ctx: Context) = schedule(ctx)

    override fun onDisabled(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = Repo.prefs(ctx).edit()
        ids.forEach { e.remove("tab_$it") }
        e.apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TAB -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val cat = intent.getIntExtra(EXTRA_CAT, 0)
                Repo.prefs(ctx).edit().putInt("tab_$id", cat).apply()
                render(ctx, AppWidgetManager.getInstance(ctx), id)
                if (System.currentTimeMillis() - Repo.updatedAt(ctx) > 5 * 60_000) refreshAsync(ctx, goAsync())
            }
            ACTION_REFRESH -> refreshAsync(ctx, goAsync(), showLoading = true)
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_TAB = "com.amotz.rateswidget.TAB"
        const val ACTION_REFRESH = "com.amotz.rateswidget.REFRESH"
        const val EXTRA_CAT = "cat"

        private val TABS = intArrayOf(R.id.tab0, R.id.tab1, R.id.tab2)
        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5, R.id.name6)
        private val PRICES = intArrayOf(R.id.price0, R.id.price1, R.id.price2, R.id.price3, R.id.price4, R.id.price5, R.id.price6)
        private val CHGS = intArrayOf(R.id.chg0, R.id.chg1, R.id.chg2, R.id.chg3, R.id.chg4, R.id.chg5, R.id.chg6)

        private const val UP = 0xFF3FB950.toInt()
        private const val DOWN = 0xFFF85149.toInt()
        private const val MUTED = 0xFF8B949E.toInt()
        private const val TEXT = 0xFFE6EDF3.toInt()

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, RatesWidget::class.java))

        fun renderAll(ctx: Context, loading: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            ids(ctx).forEach { render(ctx, mgr, it, loading) }
        }

        fun refreshAsync(ctx: Context, pending: android.content.BroadcastReceiver.PendingResult?, showLoading: Boolean = false) {
            val app = ctx.applicationContext
            if (showLoading) renderAll(app, loading = true)
            Thread {
                try { Repo.refresh(app) } finally {
                    renderAll(app)
                    pending?.finish()
                }
            }.start()
        }

        private fun alarmIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 999,
                Intent(ctx, RatesWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /** Periodic refresh while widgets exist (inexact, battery friendly). */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(ctx)
            am.cancel(pi)
            val min = Repo.intervalMin(ctx)
            if (min <= 0 || ids(ctx).isEmpty()) return
            val ms = min * 60_000L
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ms, ms, pi)
        }

        private fun pi(ctx: Context, action: String, id: Int, cat: Int, req: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, req,
                Intent(ctx, RatesWidget::class.java).setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_CAT, cat),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int, loading: Boolean = false) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val tab = Repo.prefs(ctx).getInt("tab_$id", 0).coerceIn(0, 2)
            val cat = Cat.values()[tab]

            Cat.values().forEachIndexed { i, c ->
                rv.setTextViewText(TABS[i], c.he)
                rv.setTextColor(TABS[i], if (i == tab) TEXT else MUTED)
                rv.setInt(TABS[i], "setBackgroundResource", if (i == tab) R.drawable.tab_sel else 0)
                rv.setOnClickPendingIntent(TABS[i], pi(ctx, ACTION_TAB, id, i, id * 10 + i))
            }
            rv.setOnClickPendingIntent(R.id.refresh, pi(ctx, ACTION_REFRESH, id, 0, id * 10 + 5))
            rv.setOnClickPendingIntent(
                R.id.body,
                PendingIntent.getActivity(
                    ctx, id * 10 + 6,
                    Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CAT, tab)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val rows = Repo.rows(ctx, cat, onlySelected = true).take(Catalog.WIDGET_MAX)
            for (i in ROWS.indices) {
                if (i < rows.size) {
                    val r = rows[i]
                    rv.setViewVisibility(ROWS[i], View.VISIBLE)
                    rv.setTextViewText(NAMES[i], r.item.he)
                    rv.setTextViewText(PRICES[i], r.price)
                    rv.setTextViewText(CHGS[i], Repo.fmtPct(r.change))
                    rv.setTextColor(CHGS[i], when { r.change == null -> MUTED; r.change > 0 -> UP; r.change < 0 -> DOWN; else -> MUTED })
                } else rv.setViewVisibility(ROWS[i], View.GONE)
            }
            rv.setViewVisibility(R.id.empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)

            val err = Repo.lastError(ctx)
            rv.setTextViewText(
                R.id.updated,
                when {
                    loading -> "מרענן…"
                    err != null && Repo.updatedAt(ctx) == 0L -> "אין חיבור"
                    else -> Repo.timeStr(Repo.updatedAt(ctx))
                }
            )
            mgr.updateAppWidget(id, rv)
        }
    }
}
