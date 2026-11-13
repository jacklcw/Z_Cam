package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * One HUD-shaped pad (1440x480, 3:1) showing every HUD item at once: both camera boxes, the web page box and both turn arrows.
 * Touch an item to select it, drag to move, pinch to resize. Items can be hidden on the pad (editor only) so stacked ones can be pulled apart.
 */
class HudPadView(c: Context, private val onSelect: (Item) -> Unit, private val onChange: (Item) -> Unit, private val onRelease: (Item) -> Unit = {}) : View(c) {
    /** kind 5 = speed limit sign (square, size = % of the HUD height). */
    /** kind 4 = free guide rectangle (default radar / speedometer area, editor only): centre x/y in %, size in HUD pixels gw x gh. */
    /** kind 0 = camera box (square), 1 = web page box (9:16), 2 = turn arrow (2:1), 3 = rev bar (a strip on the top/bottom/right edge: pos 0/1/2, size = thickness %, gap = distance from the edge %). x/y = centre in % of the HUD, size = % of the HUD height. */
    class Item(val id: String, var label: String, val kind: Int, val left: Boolean, val tint: Int) {
        var x = 16; var y = 50; var size = 50; var shown = true
        var gw = 300; var gh = 200   // kind 4 only: width/height in HUD pixels (1440 x 480)
        var aspect = 100; var icon = 0; var color = BlindSpotHud.ARROW_GREEN; var pos = 1; var gap = 0
        /** Smallest gap that keeps the rev bar inside the visible windshield area (bottom edge is cut at y 341 of 480, right edge at x 1329 of 1440). */
        fun minGap() = if (kind == 3) (when (pos) { 1 -> 29; 2 -> 8; else -> 0 }) else 0
        fun min() = when (kind) { 0 -> 40; 1 -> 20; 3 -> 2; 5 -> 10; 6 -> 8; else -> 5 }
        fun max() = when (kind) { 0 -> 100; 1 -> 200; 3 -> 30; 5 -> 80; 6 -> 60; else -> 40 }
    }
    val items = ArrayList<Item>()      // drawn in this order, so later ones sit on top
    var sel: Item? = null

    private val bg = Paint().apply { color = Color.rgb(14, 18, 22) }
    private val grid = Paint().apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private var corner: FloatArray? = null   // arrow corner resize: the fixed opposite corner (x, y) while a corner handle is dragged
    private var gx = 0f; private var gy = 0f; private var lastD = 1f; private var sizeF = 0f

    // The pad shows only the part of the 1440 x 480 HUD that the windshield really shows (measured from the arrow edges); item values stay in % of the full HUD.
    private val VL = 111f; private val VR = 1329f; private val VT = 0f; private val VB = 341f
    private fun sc() = width / (VR - VL)
    private fun px(xPct: Float) = (xPct / 100f * 1440f - VL) * sc()
    private fun py(yPct: Float) = (yPct / 100f * 480f - VT) * sc()
    private fun xPct(x: Float) = (x / sc() + VL) / 1440f * 100f
    private fun yPct(y: Float) = (y / sc() + VT) / 480f * 100f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (w * (VB - VT) / (VR - VL)).toInt())
    }

    private fun rect(i: Item): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        if (i.kind == 3) {   // the bar sits at the real HUD edge (1440 x 480), which may be outside the visible pad
            val gp = if (i.pos == 2) 1440f * i.gap / 100f else 480f * i.gap / 100f
            val t = if (i.pos == 2) 1440f * i.size / 100f else 480f * i.size / 100f
            fun yy(v: Float) = (v - VT) * sc()
            fun xx(v: Float) = (v - VL) * sc()
            return when (i.pos) { 0 -> RectF(0f, yy(gp), w, yy(gp + t)); 1 -> RectF(0f, yy(480f - gp - t), w, yy(480f - gp)); else -> RectF(xx(1440f - gp - t), 0f, xx(1440f - gp), h) }
        }
        if (i.kind == 4) {
            val gw = i.gw * sc(); val gh = i.gh * sc()
            val cx = px(i.x.toFloat()); val cy = py(i.y.toFloat())
            return RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2)
        }
        val hh = 480f * i.size / 100f * sc()
        val ww = when (i.kind) { 0 -> hh * i.aspect / 100f; 1 -> hh * 9f / 16f; 5 -> hh; 6 -> hh * 6.5f; 2 -> hh * i.aspect / 100f; else -> hh * 2f }
        val cx = px(i.x.toFloat()); val cy = py(i.y.toFloat())
        return RectF(cx - ww / 2, cy - hh / 2, cx + ww / 2, cy + hh / 2)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawRect(0f, 0f, w, h, bg)
        cv.drawRect(1f, 1f, w - 1, h - 1, grid)
        val gcx = px(50f); val gcy = py(50f); cv.drawLine(gcx, 0f, gcx, h, grid); if (gcy in 0f..h) cv.drawLine(0f, gcy, w, gcy, grid)
        cv.save(); cv.clipRect(0f, 0f, w, h)
        for (i in items) {
            if (!i.shown) continue
            val r = rect(i)
            if (i.kind == 2) {
                cv.save(); cv.translate(r.left, r.top)
                SignalArrow.draw(cv, r.width(), r.height(), i.left, i.icon, i.color, ink)
                cv.restore()
            } else {
                fill.color = Color.argb(70, Color.red(i.tint), Color.green(i.tint), Color.blue(i.tint)); cv.drawRect(r, fill)
                line.color = i.tint; cv.drawRect(r, line)
                label.color = i.tint; label.textSize = h * 0.08f
                cv.drawText(i.label, r.centerX(), r.centerY() + label.textSize / 3, label)
            }
        }
        cv.restore()
        sel?.takeIf { it.shown }?.let {
            val r = rect(it); if (it.kind == 2) r.inset(-r.height() * 0.1f, -r.height() * 0.1f)
            line.color = ZTheme.LIME; line.strokeWidth = 5f; cv.drawRect(r, line); line.strokeWidth = 3f
            if (it.kind == 2 || it.kind == 3) { val q = rect(it); fill.color = ZTheme.LIME; val hr = resources.displayMetrics.density * 5f
                for ((cx, cy) in listOf(q.left to q.top, q.right to q.top, q.left to q.bottom, q.right to q.bottom)) cv.drawCircle(cx, cy, hr, fill) }
        }
    }

    private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat().coerceAtLeast(1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        val w = width.toFloat(); val h = height.toFloat()
        fun grab(fx: Float, fy: Float) { val s = sel ?: return; gx = px(s.x.toFloat()) - fx; gy = py(s.y.toFloat()) - fy }   // keep the finger's offset so nothing jumps
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                corner = null
                val sa = sel?.takeIf { it.shown && (it.kind == 2 || it.kind == 3) }
                if (sa != null) {   // tap a corner of the selected arrow to resize it from that corner (the opposite corner stays put)
                    val q = rect(sa); val rad = resources.displayMetrics.density * 22f
                    val c = listOf(q.left to q.top, q.right to q.top, q.left to q.bottom, q.right to q.bottom)
                        .minByOrNull { Math.hypot((it.first - e.x).toDouble(), (it.second - e.y).toDouble()) }!!
                    if (Math.hypot((c.first - e.x).toDouble(), (c.second - e.y).toDouble()) <= rad)
                        corner = floatArrayOf(if (c.first == q.left) q.right else q.left, if (c.second == q.top) q.bottom else q.top)
                }
                if (corner != null) return true
                val hit = items.lastOrNull { it.shown && it.kind != 4 && rect(it).also { r -> if (it.kind == 2) r.inset(-r.height() * 0.1f, -r.height() * 0.1f) }.contains(e.x, e.y) }
                if (hit != null && hit !== sel) { sel = hit; onSelect(hit) }
                sel?.let { sizeF = it.size.toFloat() }; grab(e.x, e.y); invalidate()
            }
            MotionEvent.ACTION_UP -> { corner = null; sel?.takeIf { it.shown }?.let { onRelease(it) } }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) lastD = dist(e)
            MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; if (i < e.pointerCount) grab(e.getX(i), e.getY(i)) }
            MotionEvent.ACTION_MOVE -> {
                val s = sel?.takeIf { it.shown } ?: return true
                val cn = corner
                if (cn != null && s.kind == 3 && e.pointerCount == 1) {   // rev bar: a corner drags its thickness; the opposite edge stays put
                    if (s.pos == 2) {
                        val l = Math.min(cn[0], e.x); val r = Math.max(cn[0], e.x)
                        s.size = ((r - l) / sc() / 1440f * 100f).toInt().coerceIn(s.min(), s.max()); s.gap = (100f - xPct(r)).toInt().coerceIn(s.minGap(), 40)
                    } else {
                        val t = Math.min(cn[1], e.y); val b = Math.max(cn[1], e.y)
                        s.size = ((b - t) / sc() / 480f * 100f).toInt().coerceIn(s.min(), s.max())
                        s.gap = (if (s.pos == 0) yPct(t) else 100f - yPct(b)).toInt().coerceIn(s.minGap(), 40)
                    }
                    invalidate(); onChange(s); return true
                }
                if (cn != null && s.kind == 4 && e.pointerCount == 1) {
                    val gwPx = Math.abs(e.x - cn[0]); val ghPx = Math.abs(e.y - cn[1])
                    s.gw = (gwPx / sc()).toInt().coerceIn(20, 1440); s.gh = (ghPx / sc()).toInt().coerceIn(20, 480)
                    val aw = s.gw * sc(); val ah = s.gh * sc()
                    val ax = if (e.x >= cn[0]) cn[0] + aw / 2 else cn[0] - aw / 2; val ay = if (e.y >= cn[1]) cn[1] + ah / 2 else cn[1] - ah / 2
                    s.x = xPct(ax).toInt().coerceIn(0, 100); s.y = yPct(ay).toInt().coerceIn(0, 100)
                    invalidate(); onChange(s); return true
                }
                if (cn != null && s.kind == 2 && e.pointerCount == 1) {
                    val hh = Math.abs(e.y - cn[1]).coerceIn(480f * s.min() / 100f * sc(), 480f * s.max() / 100f * sc())
                    val ww = Math.abs(e.x - cn[0]).coerceIn(hh * 0.5f, hh * 5f)
                    s.size = (hh / sc() / 480f * 100f).toInt().coerceIn(s.min(), s.max()); s.aspect = (ww / hh * 100f).toInt().coerceIn(50, 500)
                    val ax = if (e.x >= cn[0]) cn[0] + ww / 2 else cn[0] - ww / 2; val ay = if (e.y >= cn[1]) cn[1] + hh / 2 else cn[1] - hh / 2
                    s.x = xPct(ax).toInt().coerceIn(0, 100); s.y = yPct(ay).toInt().coerceIn(0, 100)
                    sizeF = s.size.toFloat(); invalidate(); onChange(s); return true
                }
                if (e.pointerCount >= 2) {
                    val d = dist(e)
                    if (s.kind == 4) { s.gw = (s.gw * d / lastD).toInt().coerceIn(20, 1440); s.gh = (s.gh * d / lastD).toInt().coerceIn(20, 480) }
                    else { sizeF = (sizeF * d / lastD).coerceIn(s.min().toFloat(), s.max().toFloat()); s.size = sizeF.toInt() }
                    lastD = d
                } else {
                    if (s.kind == 3) {   // the bar goes to the nearest HUD edge and follows the finger away from it (gap)
                        val hx = xPct(e.x) / 100f * 1440f; val hy = yPct(e.y) / 100f * 480f
                        val dt = hy; val db = 480f - hy; val dr = 1440f - hx
                        s.pos = if (dt <= db && dt <= dr) 0 else if (db <= dr) 1 else 2
                        s.gap = when (s.pos) {
                            0 -> (hy - 480f * s.size / 200f) / 480f * 100f
                            1 -> (480f - hy - 480f * s.size / 200f) / 480f * 100f
                            else -> (1440f - hx - 1440f * s.size / 200f) / 1440f * 100f
                        }.toInt().coerceIn(s.minGap(), 40)
                    } else { val r = rect(s)   // the HUD keeps the whole box inside its screen, so the pad does too (what you see here is what the HUD shows)
                        val hx = r.width() / 2f / sc() / 1440f * 100f; val hy = r.height() / 2f / sc() / 480f * 100f
                        s.x = xPct(e.x + gx).coerceIn(hx.coerceAtMost(50f), 100f - hx.coerceAtMost(50f)).toInt().coerceIn(0, 100)
                        s.y = yPct(e.y + gy).coerceIn(hy.coerceAtMost(50f), 100f - hy.coerceAtMost(50f)).toInt().coerceIn(0, 100) }
                }
                invalidate(); onChange(s)
            }
        }
        return true
    }
}

/** One selectable icon in the arrow style row. */
class ArrowIconChip(c: Context, private val icon: Int) : View(c) {
    var left = true; var color = BlindSpotHud.ARROW_GREEN
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val h = height * 0.5f
        cv.save(); cv.translate((width - h * 2) / 2, height * 0.25f)
        SignalArrow.draw(cv, h * 2, h, left, icon, color, ink)
        cv.restore()
    }
}
