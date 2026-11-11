package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Small drawn icon button for the floating windows (no text, no emoji): a dark rounded chip with a stroked glyph. */
class IconButton(c: Context, private val kind: Kind, chip: Int = 0xFF2C2C2E.toInt(), private val halo: Boolean = false) : View(c) {
    enum class Kind { BACK, FORWARD, RELOAD, RESET, HIDE, GLOBE, STAR, LIST, ROTATE, FULL, UNFULL, UP, DOWN, HOME, TABS, CAM_L, CAM_R, CAM_REAR, CAM_FRONT, TRIP, EDIT, MORE, DOWNLOAD, HISTORY, DESKTOP }

    /** STAR: filled when the page is bookmarked; EDIT / LIST / TABS: lit while that mode or page is open. */
    var on = false
        set(v) { field = v; invalidate() }

    /** TABS: the number shown inside the glyph. */
    var badge = ""
        set(v) { field = v; invalidate() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F7.toInt(); textAlign = Paint.Align.CENTER; typeface = ZFont.bold
    }

    private val d = c.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F7.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F5F7.toInt(); style = Paint.Style.FILL }

    init {
        if (!halo) background = GradientDrawable().apply { setColor(chip); cornerRadius = 12 * d }   // halo: no chip, the glyph carries its own dark outline instead
        isClickable = true
    }

    override fun onDraw(cv: Canvas) {
        val main = if ((kind == Kind.EDIT || kind == Kind.LIST || kind == Kind.TABS || kind == Kind.MORE) && on) 0xFFA3E635.toInt() else 0xFFF5F5F7.toInt()
        if (halo) {
            // a fake background: the glyph drawn first in a fat dark outline, so it reads over black and white alike
            line.color = 0xE6000000.toInt(); line.strokeWidth = 2.5f * d + 4f * d
            fill.color = 0xE6000000.toInt(); fill.style = Paint.Style.FILL_AND_STROKE; fill.strokeWidth = 4f * d; fill.strokeJoin = Paint.Join.ROUND
            glyph(cv)
            fill.style = Paint.Style.FILL; fill.strokeWidth = 0f
            line.strokeWidth = 2.5f * d
        }
        line.color = main; fill.color = main
        glyph(cv)
    }

    private fun glyph(cv: Canvas) {
        val s = minOf(width, height) * 0.46f
        val cx = width / 2f; val cy = height / 2f
        val h = s / 2
        val p = Path()
        when (kind) {
            Kind.BACK -> { p.moveTo(cx + h * 0.4f, cy - h); p.lineTo(cx - h * 0.6f, cy); p.lineTo(cx + h * 0.4f, cy + h); cv.drawPath(p, line) }
            Kind.FORWARD -> { p.moveTo(cx - h * 0.4f, cy - h); p.lineTo(cx + h * 0.6f, cy); p.lineTo(cx - h * 0.4f, cy + h); cv.drawPath(p, line) }
            Kind.STAR -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) h * 1.15f else h * 0.5f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); cv.drawPath(p, if (on) fill else line)
            }
            Kind.DOWNLOAD -> { cv.drawLine(cx, cy - h, cx, cy + h * 0.45f, line); p.moveTo(cx - h * 0.6f, cy - h * 0.15f); p.lineTo(cx, cy + h * 0.5f); p.lineTo(cx + h * 0.6f, cy - h * 0.15f); cv.drawPath(p, line); cv.drawLine(cx - h, cy + h, cx + h, cy + h, line) }
            Kind.HISTORY -> { cv.drawCircle(cx, cy, h, line); cv.drawLine(cx, cy, cx, cy - h * 0.6f, line); cv.drawLine(cx, cy, cx + h * 0.45f, cy + h * 0.2f, line) }
            Kind.DESKTOP -> { cv.drawRoundRect(cx - h, cy - h * 0.8f, cx + h, cy + h * 0.35f, 3 * d, 3 * d, line); cv.drawLine(cx, cy + h * 0.35f, cx, cy + h * 0.85f, line); cv.drawLine(cx - h * 0.5f, cy + h * 0.85f, cx + h * 0.5f, cy + h * 0.85f, line) }
            Kind.MORE -> for (i in -1..1) cv.drawCircle(cx + i * h * 0.8f, cy, h * 0.17f, fill)
            Kind.LIST -> for (i in -1..1) cv.drawLine(cx - h, cy + i * h * 0.7f, cx + h, cy + i * h * 0.7f, line)
            Kind.ROTATE -> {
                // a phone with a curved arrow at each corner: turn the window
                cv.drawRoundRect(cx - h * 0.42f, cy - h * 0.72f, cx + h * 0.42f, cy + h * 0.72f, 3 * d, 3 * d, line)
                arrowArc(cv, cx, cy, h * 1.3f, -80f, 70f, h * 0.5f)
                arrowArc(cv, cx, cy, h * 1.3f, 100f, 70f, h * 0.5f)
            }
            Kind.FULL, Kind.UNFULL -> {
                // four corner brackets: pointing out = go full screen, pointing in = leave it
                val o = if (kind == Kind.FULL) 1f else -1f
                val a = h * 1.05f; val b = h * 0.45f
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                    val px = cx + sx * a; val py = cy + sy * a
                    cv.drawLine(px, py, px - sx * b * o, py, line); cv.drawLine(px, py, px, py - sy * b * o, line)
                }
            }
            Kind.UP, Kind.DOWN -> {
                // the top bar (a pill) with an arrow: up = tuck it away, down = bring it back
                val y0 = cy - h * 0.95f
                cv.drawRoundRect(cx - h * 1.25f, y0 - h * 0.3f, cx + h * 1.25f, y0 + h * 0.3f, h * 0.3f, h * 0.3f, if (kind == Kind.DOWN) fill else line)
                val dir = if (kind == Kind.UP) -1f else 1f
                val ay = cy + h * 0.55f
                cv.drawLine(cx, ay + dir * h * 0.7f, cx, ay - dir * h * 0.7f, line)
                p.moveTo(cx - h * 0.6f, ay - dir * h * 0.1f); p.lineTo(cx, ay + dir * h * 0.7f); p.lineTo(cx + h * 0.6f, ay - dir * h * 0.1f); cv.drawPath(p, line)
            }
            Kind.HOME -> {
                p.moveTo(cx - h * 1.1f, cy - h * 0.05f); p.lineTo(cx, cy - h * 1.05f); p.lineTo(cx + h * 1.1f, cy - h * 0.05f)
                p.moveTo(cx - h * 0.8f, cy - h * 0.2f); p.lineTo(cx - h * 0.8f, cy + h); p.lineTo(cx + h * 0.8f, cy + h); p.lineTo(cx + h * 0.8f, cy - h * 0.2f)
                cv.drawPath(p, line)
            }
            Kind.TABS -> {
                cv.drawRoundRect(cx - h * 1.05f, cy - h * 1.0f, cx + h * 1.05f, cy + h * 1.0f, 4 * d, 4 * d, line)
                txt.textSize = h * 1.3f
                cv.drawText(badge, cx, cy + txt.textSize * 0.35f, txt)
            }
            Kind.RELOAD -> arrowArc(cv, cx, cy, h, -50f, 280f, h * 0.55f)   // circle arrow: open ring with a chevron head
            Kind.RESET -> cv.drawRoundRect(cx - h, cy - h, cx + h, cy + h, 3 * d, 3 * d, line)
            Kind.HIDE -> cv.drawLine(cx - h, cy + h * 0.6f, cx + h, cy + h * 0.6f, line)
            Kind.EDIT -> {
                // a pencil, tip down-left
                cv.save(); cv.translate(cx, cy); cv.rotate(-45f)
                p.moveTo(-h * 1.3f, 0f); p.lineTo(-h * 0.7f, -h * 0.4f); p.lineTo(h * 1.0f, -h * 0.4f); p.lineTo(h * 1.0f, h * 0.4f); p.lineTo(-h * 0.7f, h * 0.4f); p.close()
                cv.drawPath(p, line); cv.drawLine(h * 0.5f, -h * 0.4f, h * 0.5f, h * 0.4f, line)
                cv.restore()
            }
            Kind.CAM_L, Kind.CAM_R, Kind.CAM_REAR, Kind.CAM_FRONT -> {
                // a window with an arrow the way of the camera (same glyphs as the window headers and the floating control)
                val g = h * 1.2f
                cv.drawRoundRect(cx - g, cy - g * 0.8f, cx + g, cy + g * 0.8f, 3 * d, 3 * d, line)
                if (kind == Kind.CAM_L || kind == Kind.CAM_R) {
                    val sg = if (kind == Kind.CAM_L) 1f else -1f
                    p.moveTo(cx + sg * g * 0.35f, cy - g * 0.4f); p.lineTo(cx - sg * g * 0.35f, cy); p.lineTo(cx + sg * g * 0.35f, cy + g * 0.4f)
                } else {
                    val sg = if (kind == Kind.CAM_FRONT) -1f else 1f
                    p.moveTo(cx - g * 0.4f, cy - sg * g * 0.25f); p.lineTo(cx + g * 0.4f, cy - sg * g * 0.25f); p.lineTo(cx, cy + sg * g * 0.35f)
                }
                p.close(); cv.drawPath(p, fill)
            }
            Kind.TRIP -> {
                // speedometer: open arc, a needle and a hub (the trip widget)
                val g = h * 1.2f
                cv.drawArc(RectF(cx - g, cy - g, cx + g, cy + g), 150f, 240f, false, line)
                cv.drawLine(cx, cy + g * 0.1f, cx + g * 0.55f, cy - g * 0.5f, line)
                cv.drawCircle(cx, cy + g * 0.1f, g * 0.16f, fill)
            }
            Kind.GLOBE -> {
                cv.drawCircle(cx, cy, h, line)
                cv.drawOval(RectF(cx - h * 0.45f, cy - h, cx + h * 0.45f, cy + h), line)
                cv.drawLine(cx - h, cy, cx + h, cy, line)
            }
        }
    }

    /** A stroked arc from [start] sweeping [sweep] degrees (clockwise) with an open chevron head at its end. */
    private fun arrowArc(cv: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float, head: Float) {
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, line)
        val a = Math.toRadians((start + sweep).toDouble())
        val ex = cx + r * Math.cos(a).toFloat(); val ey = cy + r * Math.sin(a).toFloat()
        val tx = -Math.sin(a).toFloat(); val ty = Math.cos(a).toFloat()      // direction of travel
        val nx = Math.cos(a).toFloat(); val ny = Math.sin(a).toFloat()       // outwards
        val tip = Path().apply {
            moveTo(ex - nx * head, ey - ny * head); lineTo(ex + tx * head * 0.9f, ey + ty * head * 0.9f); lineTo(ex + nx * head, ey + ny * head)
        }
        cv.drawPath(tip, line)
    }
}
