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
        fun min() = when (kind) { 0 -> 40; 1 -> 20; 3 -> 2; 5 -> 10; else -> 5 }
        fun max() = when (kind) { 0 -> 100; 1 -> 200; 3 -> 30; 5 -> 80; else -> 40 }
    }
    val items = ArrayList<Item>()      // drawn in this order, so later ones sit on top
    var sel: Item? = null

    private val bg = Paint().apply { color = Color.rgb(14, 18, 22) }
    private val grid = Paint().apply { color = Color.argb(60, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gx = 0f; private var gy = 0f; private var lastD = 1f; private var sizeF = 0f

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, w / 3)
    }

    private fun rect(i: Item): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        if (i.kind == 3) {
            val gp = if (i.pos == 2) w * i.gap / 100f else h * i.gap / 100f
            val t = if (i.pos == 2) w * i.size / 100f else h * i.size / 100f
            return when (i.pos) { 0 -> RectF(0f, gp, w, gp + t); 1 -> RectF(0f, h - gp - t, w, h - gp); else -> RectF(w - gp - t, 0f, w - gp, h) }
        }
        if (i.kind == 4) {
            val gw = w * i.gw / 1440f; val gh = h * i.gh / 480f
            val cx = w * i.x / 100f; val cy = h * i.y / 100f
            return RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2)
        }
        val hh = h * i.size / 100f
        val ww = when (i.kind) { 0 -> hh * i.aspect / 100f; 1 -> hh * 9f / 16f; 5 -> hh; else -> hh * 2f }
        val cx = w * i.x / 100f; val cy = h * i.y / 100f
        return RectF(cx - ww / 2, cy - hh / 2, cx + ww / 2, cy + hh / 2)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        cv.drawRect(0f, 0f, w, h, bg)
        cv.drawRect(1f, 1f, w - 1, h - 1, grid)
        cv.drawLine(w / 2, 0f, w / 2, h, grid); cv.drawLine(0f, h / 2, w, h / 2, grid)
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
        sel?.takeIf { it.shown }?.let {
            val r = rect(it); if (it.kind == 2) r.inset(-r.height() * 0.1f, -r.height() * 0.1f)
            line.color = ZTheme.LIME; line.strokeWidth = 5f; cv.drawRect(r, line); line.strokeWidth = 3f
        }
    }

    private fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat().coerceAtLeast(1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        val w = width.toFloat(); val h = height.toFloat()
        fun grab(px: Float, py: Float) { val s = sel ?: return; gx = w * s.x / 100f - px; gy = h * s.y / 100f - py }   // keep the finger's offset so nothing jumps
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = items.lastOrNull { it.shown && it.kind != 4 && rect(it).also { r -> if (it.kind == 2) r.inset(-r.height() * 0.1f, -r.height() * 0.1f) }.contains(e.x, e.y) }
                if (hit != null && hit !== sel) { sel = hit; onSelect(hit) }
                sel?.let { sizeF = it.size.toFloat() }; grab(e.x, e.y); invalidate()
            }
            MotionEvent.ACTION_UP -> sel?.takeIf { it.shown }?.let { onRelease(it) }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) lastD = dist(e)
            MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; if (i < e.pointerCount) grab(e.getX(i), e.getY(i)) }
            MotionEvent.ACTION_MOVE -> {
                val s = sel?.takeIf { it.shown } ?: return true
                if (e.pointerCount >= 2) {
                    val d = dist(e)
                    if (s.kind == 4) { s.gw = (s.gw * d / lastD).toInt().coerceIn(20, 1440); s.gh = (s.gh * d / lastD).toInt().coerceIn(20, 480) }
                    else { sizeF = (sizeF * d / lastD).coerceIn(s.min().toFloat(), s.max().toFloat()); s.size = sizeF.toInt() }
                    lastD = d
                } else {
                    if (s.kind == 3) {   // the bar snaps to the nearest edge
                        val dt = e.y; val db = h - e.y; val dr = w - e.x
                        s.pos = if (dt <= db && dt <= dr) 0 else if (db <= dr) 1 else 2
                    } else { s.x = ((e.x + gx) / w * 100f).toInt().coerceIn(0, 100); s.y = ((e.y + gy) / h * 100f).toInt().coerceIn(0, 100) }
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
