package com.amotz.rateswidget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Line chart with soft fill, light grid, and a press-and-drag tooltip (value + date). */
class ChartView(ctx: Context) : View(ctx) {

    var series: Series? = null
        set(v) { field = v; sel = -1; invalidate() }
    var span: Span = Span.Y1
    var prefix: String = ""
    var message: String? = null
        set(v) { field = v; invalidate() }

    private val UP = 0xFF3FB950.toInt()
    private val DOWN = 0xFFF0857A.toInt()
    private val GRID = 0xFF2A3038.toInt()
    private val LABEL = 0xFF8B949E.toInt()

    private val d = resources.displayMetrics.density
    private val he = Locale("he")

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint().apply { color = GRID; strokeWidth = 1f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL; textSize = 12f * d }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B949E.toInt(); strokeWidth = 1f * d; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01C2128.toInt() }
    private val tipVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6EDF3.toInt(); textSize = 15f * d; typeface = Typeface.DEFAULT_BOLD }
    private val tipDate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL; textSize = 13f * d }

    private var sel = -1
    private val path = Path()
    private val area = Path()

    // plot rect (computed in onDraw)
    private var l = 0f; private var r = 0f; private var t = 0f; private var b = 0f
    private var lo = 0.0; private var hi = 1.0; private var stepNow = 1.0

    private fun xOf(i: Int, s: Series): Float {
        val t0 = s.t.first(); val t1 = s.t.last()
        return if (t1 == t0) l else l + (r - l) * ((s.t[i] - t0).toFloat() / (t1 - t0))
    }
    private fun yOf(v: Double) = (b - (b - t) * ((v - lo) / (hi - lo))).toFloat()

    override fun onDraw(c: Canvas) {
        val s = series
        if (s == null || s.size < 2) {
            text.textAlign = Paint.Align.CENTER
            c.drawText(message ?: "", width / 2f, height / 2f, text)
            return
        }
        // nice y range
        var mn = s.v.min(); var mx = s.v.max()
        if (mx == mn) { mx += abs(mx) * 0.01 + 1e-9; mn -= abs(mn) * 0.01 + 1e-9 }
        val step = nice((mx - mn) / 3)
        lo = floor(mn / step) * step; hi = ceil(mx / step) * step
        stepNow = step

        text.textAlign = Paint.Align.LEFT
        val labelW = maxOf(text.measureText(axis(lo)), text.measureText(axis(hi))) + 8 * d
        l = labelW; r = width - 4 * d; t = 10 * d; b = height - 24 * d

        // grid + y labels
        var g = lo
        while (g <= hi + step / 2) {
            val y = yOf(g)
            c.drawLine(l, y, r, y, grid)
            c.drawText(axis(g), 0f, y + 4 * d, text)
            g += step
        }
        // x labels (4)
        text.textAlign = Paint.Align.CENTER
        val fmt = SimpleDateFormat(axisDateFmt(), he)
        for (k in 1..3) {
            val i = (s.size - 1) * k / 4
            val x = xOf(i, s)
            c.drawLine(x, b, x, b + 4 * d, grid)
            c.drawText(fmt.format(Date(s.t[i])), x, height - 4 * d, text)
        }

        // line + fill
        val color = if (s.v.last() >= s.v.first()) UP else DOWN
        line.color = color
        path.reset(); area.reset()
        for (i in 0 until s.size) {
            val x = xOf(i, s); val y = yOf(s.v[i])
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, b); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(xOf(s.size - 1, s), b); area.close()
        fill.shader = LinearGradient(0f, t, 0f, b, (color and 0x00FFFFFF) or 0x55000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        c.drawPath(path, line)

        // tooltip
        if (sel in 0 until s.size) {
            val x = xOf(sel, s); val y = yOf(s.v[sel])
            c.drawLine(x, t, x, b, cursor)
            dot.color = color; c.drawCircle(x, y, 5 * d, dot)
            dot.color = 0xFF0E1116.toInt(); c.drawCircle(x, y, 2.2f * d, dot)

            val vs = prefix + Repo.fmt(s.v[sel])
            val ds = SimpleDateFormat(if (span.intraday) "d MMM, HH:mm" else "d MMM yyyy", he).format(Date(s.t[sel]))
            val pad = 10 * d; val gap = 10 * d
            val w = tipVal.measureText(vs) + gap + tipDate.measureText(ds) + pad * 2
            val h = 34 * d
            var tx = x - w / 2
            tx = tx.coerceIn(0f, width - w)
            val ty = if (y - h - 14 * d > t) y - h - 14 * d else minOf(y + 14 * d, b - h)
            c.drawRoundRect(RectF(tx, ty, tx + w, ty + h), 8 * d, 8 * d, tipBg)
            tipVal.textAlign = Paint.Align.LEFT; tipDate.textAlign = Paint.Align.LEFT
            c.drawText(vs, tx + pad, ty + h / 2 + 5.5f * d, tipVal)
            c.drawText(ds, tx + pad + tipVal.measureText(vs) + gap, ty + h / 2 + 5f * d, tipDate)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = series ?: return false
        if (s.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val t0 = s.t.first(); val t1 = s.t.last()
                val frac = ((e.x - l) / (r - l)).coerceIn(0f, 1f)
                val target = t0 + ((t1 - t0) * frac).toLong()
                var best = 0; var bd = Long.MAX_VALUE
                for (i in 0 until s.size) { val dd = abs(s.t[i] - target); if (dd < bd) { bd = dd; best = i } }
                if (best != sel) { sel = best; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun axisDateFmt() = when (span) {
        Span.D1 -> "HH:mm"
        Span.D5 -> "EEE"
        Span.M1, Span.M6 -> "d MMM"
        Span.Y1 -> "MMM yy"
        else -> "yyyy"
    }

    private fun axis(v: Double): String {
        val a = abs(v)
        if (a >= 1_000_000) return String.format(Locale.US, "%.1fM", v / 1_000_000)
        if (a >= 10_000 && stepNow >= 1000) return String.format(Locale.US, "%.0fK", v / 1000)
        var dec = 0
        while (dec < 6 && abs(stepNow * 10.0.pow(dec) - Math.round(stepNow * 10.0.pow(dec))) > 1e-6) dec++
        return String.format(Locale.US, "%,.${dec}f", v)
    }

    private fun nice(x: Double): Double {
        if (x <= 0) return 1.0
        val e = 10.0.pow(floor(log10(x)))
        val f = x / e
        return e * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 2.5 -> 2.5; f <= 5 -> 5.0; else -> 10.0 }
    }
}
