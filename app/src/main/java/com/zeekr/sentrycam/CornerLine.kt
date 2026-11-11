package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/**
 * A fine line wrapped around one corner of a floating window, following the window's corner radius; dragging it resizes the window.
 * [onDrag] gets the raw finger offset since the touch began, [onStart] / [onEnd] bracket the drag.
 */
class CornerLine(ctx: Context, radiusDp: Int, left: Boolean, top: Boolean, onStart: () -> Unit, onDrag: (Float, Float) -> Unit, onEnd: () -> Unit) : View(ctx) {
    companion object {
        /** Touch zone (dp) for a window with this corner radius. */
        fun zoneDp(radiusDp: Int) = maxOf(radiusDp + 30, 56)
    }

    init {
        val d = ctx.resources.displayMetrics.density
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d + 2f; strokeCap = Paint.Cap.ROUND; color = 0xCCFFFFFF.toInt() }
        background = object : Drawable() {
            override fun draw(c: Canvas) {
                val z = bounds.width().toFloat(); val i = 3 * d; val rr = maxOf(radiusDp * d - i, 0f); val arm = minOf(z - i - rr, 20 * d)
                val path = Path()
                // drawn as the top-left corner, then mirrored into place
                c.save(); c.scale(if (left) 1f else -1f, if (top) 1f else -1f, z / 2, z / 2)
                path.moveTo(i + rr + arm, i); path.lineTo(i + rr, i)
                if (rr > 0f) path.arcTo(RectF(i, i, i + 2 * rr, i + 2 * rr), 270f, -90f)
                path.lineTo(i, i + rr + arm)
                c.drawPath(path, line); c.restore()
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        var dx = 0f; var dy = 0f
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; onStart() }
                MotionEvent.ACTION_MOVE -> onDrag(e.rawX - dx, e.rawY - dy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
            }
            true
        }
    }
}
