package com.amotz.rateswidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import android.widget.RemoteViewsService

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

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) refreshAsync(ctx, goAsync(), showLoading = true)
        else super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.amotz.rateswidget.REFRESH"
        const val EXTRA_CAT = "cat"
        const val EXTRA_CODE = "code"

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, RatesWidget::class.java))

        /** Redraw header + reload list data on every widget. */
        fun renderAll(ctx: Context, loading: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = ids(ctx)
            ids.forEach { render(ctx, mgr, it, loading) }
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.list)
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

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int, loading: Boolean = false) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)

            val svc = Intent(ctx, RatesListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            rv.setRemoteAdapter(R.id.list, svc)
            rv.setEmptyView(R.id.list, R.id.empty)
            rv.setTextViewText(R.id.empty, "לא נבחרו פריטים — הקישו כאן או על ⚙ כדי לבחור")

            // row tap -> history popup only (transparent activity in its own task)
            val hist = Intent(ctx, HistoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            // template must be mutable so each row's fill-in intent (item code) can be merged
            rv.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getActivity(ctx, id * 10 + 1, hist, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            )
            // ⚙ -> settings screen
            val settings = PendingIntent.getActivity(
                ctx, id * 10 + 2,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.settings, settings)
            rv.setOnClickPendingIntent(R.id.empty, settings)
            rv.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    ctx, id * 10 + 3,
                    Intent(ctx, RatesWidget::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

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

/** Supplies the widget's scrolling list: all selected items, grouped by category. */
class RatesListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
        private sealed class Entry {
            class Header(val cat: Cat) : Entry()
            class Line(val row: Row) : Entry()
        }

        private var entries: List<Entry> = emptyList()

        private val UP = 0xFF3FB950.toInt()
        private val DOWN = 0xFFF85149.toInt()
        private val MUTED = 0xFF8B949E.toInt()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val out = ArrayList<Entry>()
            for (cat in Cat.values()) {
                val rows = Repo.rows(ctx, cat, onlySelected = true)
                if (rows.isEmpty()) continue
                out.add(Entry.Header(cat))
                rows.forEach { out.add(Entry.Line(it)) }
            }
            entries = out
        }

        override fun getCount() = entries.size
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val e = entries.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_header)
            return when (e) {
                is Entry.Header -> RemoteViews(ctx.packageName, R.layout.widget_header).apply {
                    setTextViewText(R.id.header, e.cat.he)
                                    }
                is Entry.Line -> RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                    val r = e.row
                    val it = r.item
                    val isFlag = it.color == 0
                    setInt(R.id.icon_bg, "setColorFilter", if (isFlag) Catalog.NEUTRAL else it.color)
                    setTextViewText(R.id.icon, it.glyph)
                    setTextViewTextSize(R.id.icon, android.util.TypedValue.COMPLEX_UNIT_SP,
                        when { isFlag -> 17f; it.glyph.length > 1 -> 12f; else -> 15f })
                    setTextViewText(R.id.name, it.he)
                    setTextViewText(R.id.price, r.price)
                    setTextViewText(R.id.chg, Repo.fmtPct(r.change))
                    setTextColor(R.id.chg, when { r.change == null -> MUTED; r.change > 0 -> UP; r.change < 0 -> DOWN; else -> MUTED })
                    setOnClickFillInIntent(R.id.row, Intent().putExtra(RatesWidget.EXTRA_CAT, it.cat.ordinal).putExtra(RatesWidget.EXTRA_CODE, it.code))
                }
            }
        }
    }
}
