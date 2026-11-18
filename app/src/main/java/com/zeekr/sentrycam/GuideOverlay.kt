package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * Distance guide lines drawn over a floating camera window. Edit mode (guide button in the window header): drag on the picture to draw
 * the first line, drag its dots to set its angle, drag any line to move the group (a copy slides the spacing), spread two fingers to
 * copy it up to 4 lines (pinch in removes one), Copies always keep the first line's angle and length.
 * Stored as fractions of the window. Pref value: "ax,ay,bx,by,ox,oy,count,len1,len2,len3"
 */
class GuideOverlay(ctx: Context, private val prefs: android.content.SharedPreferences, private val key: String) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var base: FloatArray? = null          // ax, ay, bx, by (0..1)
    private var ox = 0f; private var oy = 0.12f   // offset between neighbouring lines
    private var n = 1
    private val sc = floatArrayOf(1f, 1.18f, 1.39f, 1.64f)   // length of each line relative to the first (lower on the picture = closer = longer); each can be set on its own
    private var hidden = prefs.getBoolean(key + "Hide", false)
    private val chipP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = ZFont.bold; textSize = 14f * d }
    private val chips = arrayOf(android.graphics.RectF(), android.graphics.RectF())   // Clear, Hide/Show
    var editing = false
        set(v) { field = v; invalidate() }

    private val red = 0xFFFF2D2D.toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val main = Handler(Looper.getMainLooper())

    init {
        val v = (prefs.getString(key, "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }
        // (init: lengths are checked once loaded, below)
        if (v.size == 7 || v.size == 8 || v.size == 10) { if (v.size == 8) for (i in 1..3) sc[i] = Math.pow(v[7].coerceIn(0.4f, 2.5f).toDouble(), i.toDouble()).toFloat(); if (v.size == 10) for (i in 1..3) sc[i] = v[6 + i].coerceIn(0.2f, 6f); base = floatArrayOf(v[0], v[1], v[2], v[3]); ox = v[4]; oy = v[5]; n = v[6].toInt().coerceIn(1, 4) }
    }
    // The line further from the camera (higher on the picture) can never be longer than a closer (lower) one.
    private fun farToNear(): List<Int> = if (oy >= 0f) (0 until n).toList() else (n - 1 downTo 0).toList()
    private fun enforce() { val o = farToNear(); for (k in 1 until o.size) sc[o[k]] = maxOf(sc[o[k]], sc[o[k - 1]]) }
    private fun save() {
        enforce()
        val b = base
        prefs.edit().putString(key, if (b == null) "" else (b.toList() + listOf(ox, oy, n.toFloat(), sc[1], sc[2], sc[3])).joinToString(",") { "%.4f".format(java.util.Locale.US, it) }).apply()
    }
    // Perspective: the line lower on the picture is closer to the camera, so it is longer; each copy scales about its own centre (the angle never changes).
    init { enforce() }
    private fun line(i: Int): FloatArray {
        val b = base!!
        val k = sc[i]
        val cx = (b[0] + b[2]) / 2 + i * ox; val cy = (b[1] + b[3]) / 2 + i * oy
        val hx = (b[2] - b[0]) / 2 * k; val hy = (b[3] - b[1]) / 2 * k
        return floatArrayOf(cx - hx, cy - hy, cx + hx, cy + hy)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (editing) {
            val labels = arrayOf("Clear", if (hidden) "Show" else "Hide")
            val cw = 72f * d; val ch = 32f * d; val gap = 10f * d; val top = h - ch - 10f * d
            for (i in 0..1) {
                val l = w / 2f - cw - gap / 2 + i * (cw + gap)
                chips[i].set(l, top, l + cw, top + ch)
                fillP.color = 0xCC000000.toInt(); cv.drawRoundRect(chips[i], 10f * d, 10f * d, fillP)
                chipP.color = 0xFFF5F5F7.toInt(); cv.drawText(labels[i], chips[i].centerX(), chips[i].centerY() + chipP.textSize * 0.35f, chipP)
            }
        }
        if (base == null || hidden) return
        if (n > 1) {   // transparent red shade between the lines
            val poly = Path()
            val f0 = line(0); poly.moveTo(f0[0] * w, f0[1] * h)
            for (i in 1 until n) { val l = line(i); poly.lineTo(l[0] * w, l[1] * h) }
            for (i in n - 1 downTo 0) { val l = line(i); poly.lineTo(l[2] * w, l[3] * h) }
            poly.close()
            fillP.color = 0x26FF2D2D; cv.drawPath(poly, fillP)
        }
        for (i in 0 until n) {
            val l = line(i)
            stroke.strokeWidth = 5f * d; stroke.color = 0xB3000000.toInt(); cv.drawLine(l[0] * w, l[1] * h, l[2] * w, l[3] * h, stroke)
            stroke.strokeWidth = 2.4f * d; stroke.color = red; cv.drawLine(l[0] * w, l[1] * h, l[2] * w, l[3] * h, stroke)
        }
        if (editing) for (i in 0 until n) for (k in 0..1) {   // dots: the first line sets the angle, every other line sets its own length
            val l = line(i)
            fillP.color = 0xFF000000.toInt(); cv.drawCircle(l[k * 2] * w, l[k * 2 + 1] * h, 9f * d, fillP)
            fillP.color = red; cv.drawCircle(l[k * 2] * w, l[k * 2 + 1] * h, 7f * d, fillP)
        }
    }

    // touch state: 0 none, 1 drag a dot of the first line, 2 drag a line, 3 pinch
    private var mode = 0; private var pt = 0; private var li = 0
    private var lx = 0f; private var ly = 0f; private var stepBase = 0f
    private var drawing = false   // the first line is being drawn right now

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)
    private fun dist(x: Float, y: Float, ax: Float, ay: Float) = Math.hypot((x - ax).toDouble(), (y - ay).toDouble()).toFloat()
    private fun distSeg(x: Float, y: Float, l: FloatArray): Float {
        val ax = l[0] * width; val ay = l[1] * height; val bx = l[2] * width; val by = l[3] * height
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
        return dist(x, y, ax + t * dx, ay + t * dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing || width == 0) return false
        val w = width.toFloat(); val h = height.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y; mode = 0
                if (chips[0].contains(e.x, e.y)) { base = null; n = 1; save(); invalidate(); return true }
                if (chips[1].contains(e.x, e.y)) { hidden = !hidden; prefs.edit().putBoolean(key + "Hide", hidden).apply(); invalidate(); return true }
                if (hidden) return true
                val b = base
                if (b == null) {   // draw the first line
                    base = floatArrayOf(clamp(e.x / w), clamp(e.y / h), clamp(e.x / w), clamp(e.y / h)); n = 1; pt = 1; mode = 1; drawing = true
                } else {
                    val hit = 26f * d
                    for (i in n - 1 downTo 1) { val ll = line(i); for (k in 0..1) { if (mode != 4 && dist(e.x, e.y, ll[k * 2] * w, ll[k * 2 + 1] * h) < hit) { mode = 4; li = i } } }
                    val l0 = line(0); var best = if (mode == 4) 0f else hit
                    for (k in 0..1) { val dd = dist(e.x, e.y, l0[k * 2] * w, l0[k * 2 + 1] * h); if (dd < best) { best = dd; pt = k; mode = 1 } }
                    if (mode == 0) {
                        var bd = 20f * d
                        for (i in 0 until n) { val dd = distSeg(e.x, e.y, line(i)); if (dd < bd) { bd = dd; li = i; mode = 2 } }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && base != null) {
                mode = 3; stepBase = dist(e.getX(0), e.getY(0), e.getX(1), e.getY(1))
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                4 -> base?.let { b ->   // widen / narrow the lowest line only along its own direction
                    val ux = (b[2] - b[0]) * w; val uy = (b[3] - b[1]) * h; val ul = Math.hypot(ux.toDouble(), uy.toDouble()).toFloat()
                    if (ul > 1f) {
                        val ll = line(li); val cx = (ll[0] + ll[2]) / 2 * w; val cy = (ll[1] + ll[3]) / 2 * h
                        val half = Math.abs((e.x - cx) * ux + (e.y - cy) * uy) / ul
                        val o = farToNear(); val at = o.indexOf(li)
                        val lo = if (at > 0) sc[o[at - 1]] else 0.2f
                        val hi = if (at < o.size - 1) sc[o[at + 1]] else 6f
                        sc[li] = (half / (ul / 2)).coerceIn(0.2f, 6f).coerceIn(minOf(lo, hi), hi); invalidate()
                    }
                }
                1 -> base?.let { it[pt * 2] = clamp(e.x / w); it[pt * 2 + 1] = clamp(e.y / h); invalidate() }
                2 -> base?.let { b ->
                    val dx = (e.x - lx) / w; val dy = (e.y - ly) / h; lx = e.x; ly = e.y
                    if (li == 0) { for (k in 0..1) { b[k * 2] = clamp(b[k * 2] + dx); b[k * 2 + 1] = clamp(b[k * 2 + 1] + dy) } }
                    else { ox += dx / li; oy += dy / li }   // a copy slides the spacing, the angle stays the first line's
                    invalidate()
                }
                3 -> if (e.pointerCount >= 2) {
                    val dd = dist(e.getX(0), e.getY(0), e.getX(1), e.getY(1)); val step = 60f * d
                    while (dd - stepBase > step) { if (n < 4) n++; stepBase += step; save(); invalidate() }
                    while (stepBase - dd > step) { if (n > 1) n--; stepBase -= step; save(); invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                base?.let { b -> if (drawing && mode == 1 && n == 1 && Math.abs(b[0] - b[2]) * w < 4 * d && Math.abs(b[1] - b[3]) * h < 4 * d) base = null }   // a tap, not a line
                if (mode in 1..2 || mode == 4) { save(); invalidate() }
                mode = 0; drawing = false
            }
        }
        return true
    }
}
