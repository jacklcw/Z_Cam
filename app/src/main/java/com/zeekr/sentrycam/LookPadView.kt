package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View

/**
 * Square two-value pad: drag the puck, right = more of [xName], up = more of [yName]. Double tap puts the puck back to default.
 * Values are ints from min to max on each axis; onChange fires while dragging.
 */
class LookPadView(c: Context, private val xName: String, private val yName: String,
                  private val xMin: Int, private val xMax: Int, private val xDef: Int,
                  private val yMin: Int, private val yMax: Int, private val yDef: Int,
                  private val onChange: (Int, Int) -> Unit) : View(c) {
    var x = xDef; var y = yDef
    private val d = c.resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 120, 120, 120) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = d }
    private val puck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(4 * d, 0f, d, Color.argb(160, 0, 0, 0)) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 11 * d; textAlign = Paint.Align.CENTER; typeface = ZFont.bold }
    private val r = RectF()
    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { x = xDef; y = yDef; invalidate(); onChange(x, y); return true }
    })

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }

    private fun px(v: Int, mn: Int, mx: Int, lo: Float, hi: Float) = if (mx == mn) (lo + hi) / 2 else lo + (v - mn).toFloat() / (mx - mn) * (hi - lo)

    override fun onDraw(cv: Canvas) {
        val m = 14 * d
        r.set(m, m, width - m, height - m)
        cv.drawRoundRect(r, 14 * d, 14 * d, box)
        cv.drawRoundRect(r, 14 * d, 14 * d, line)
        // thirds, and a cross through the default so the "no change" spot is easy to find
        for (i in 1..2) { cv.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, line); cv.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, line) }
        val cx = px(x, xMin, xMax, r.left, r.right); val cy = px(y, yMin, yMax, r.bottom, r.top)
        val dx = px(xDef, xMin, xMax, r.left, r.right); val dy = px(yDef, yMin, yMax, r.bottom, r.top)
        cv.drawCircle(dx, dy, 3 * d, line)
        cv.drawCircle(cx, cy, 13 * d, puck)
        cv.drawText(xName, width / 2f, height - 2 * d, txt)
        cv.save(); cv.rotate(-90f, 12 * d, height / 2f); cv.drawText(yName, 12 * d, height / 2f, txt); cv.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            val m = 14 * d
            val fx = ((e.x - m) / (width - 2 * m)).coerceIn(0f, 1f); val fy = (1f - (e.y - m) / (height - 2 * m)).coerceIn(0f, 1f)
            x = Math.round(xMin + fx * (xMax - xMin)); y = Math.round(yMin + fy * (yMax - yMin))
            invalidate(); onChange(x, y)
        }
        return true
    }
}
