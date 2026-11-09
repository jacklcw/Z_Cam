package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager

/**
 * One round floating button that hides every floating window (cameras, web, their collapsed dock) at once and brings
 * them back with a second tap. Drag to move, pinch to resize; position and size are remembered. Owned by CamService.
 */
object FloatMaster {
    /** True while everything is hidden; the floating windows' owners check this. */
    @Volatile var hidden = false
    /** Bumped on a double-tap: every floating window collapses to the top dock. */
    @Volatile var minimizeSeq = 0
    @Volatile var restoreSeq = 0
    @Volatile var camUp: () -> Boolean = { false }   // any camera window expanded on screen
    @Volatile var webUp: () -> Boolean = { false }   // web window expanded on screen
    @Volatile var tripUp: () -> Boolean = { false }  // trip widget on screen
    /** Uptime of the last single-tap "show everything", so windows can grow out of the button. */
    @Volatile var shownAt = 0L
    /** True while a blinker-centred camera window must sit above the button and the dock. */
    @Volatile var yieldToBlink = false
    /** Bumped whenever the web window is (re)added, so a blinker camera window can climb back above it. */
    @Volatile var webAddedSeq = 0

    private val main = Handler(Looper.getMainLooper())
    private var view: Btn? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var touching = false

    private class Btn(c: Context) : View(c) {
        var off = false
        private val d = c.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
        private val zPath = android.graphics.Path()
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val c = w / 2
            // windows showing: dark button, light glyph; everything hidden: white button, dark glyph
            fill.color = if (off) 0xFFFFFFFF.toInt() else 0xE61C1C1E.toInt()
            ring.color = if (off) 0xFFE5E5EA.toInt() else 0xFF3A3A3C.toInt()
            glyph.color = if (off) 0xFF1C1C1E.toInt() else 0xFFF5F5F7.toInt()
            cv.drawCircle(c, c, c - ring.strokeWidth, fill); cv.drawCircle(c, c, c - ring.strokeWidth, ring)
            // the Z_Cam icon: "Z" plus the red recording underscore (same shapes as the launcher icon, 45 x 24 box)
            val k = w * 0.56f / 45f
            fun px(x: Float) = c + (x - 55.5f) * k
            fun py(y: Float) = c + (y - 52f) * k
            glyph.strokeWidth = 7f * k; glyph.strokeCap = Paint.Cap.ROUND
            zPath.reset(); zPath.moveTo(px(33f), py(40f)); zPath.lineTo(px(55f), py(40f)); zPath.lineTo(px(33f), py(64f)); zPath.lineTo(px(55f), py(64f))
            cv.drawPath(zPath, glyph)
            glyph.color = 0xFFFF3B30.toInt(); glyph.strokeWidth = 6f * k
            cv.drawLine(px(64f), py(64f), px(78f), py(64f), glyph)
        }
    }

    fun start(ctx: Context) {
        main.post { if (view == null) add(ctx.applicationContext) }
    }

    /** Re-adds the button so it stacks above every other overlay (overlay windows only stack by add order). Skipped mid-touch. */
    fun raise() {
        main.post {
            val v = view ?: return@post; val params = lp ?: return@post
            if (yieldToBlink) return@post
            if (touching) { main.postDelayed({ raise() }, 400); return@post }
            val wm = v.context.getSystemService(WindowManager::class.java)
            try { wm.removeView(v); wm.addView(v, params) } catch (_: Throwable) {}
        }
    }

    /** Centre of the button in screen pixels, or null if it is not up. */
    fun center(): Pair<Int, Int>? { val l = lp ?: return null; return Pair(l.x + l.width / 2, l.y + l.height / 2) }

    fun stop() {
        main.post {
            val v = view ?: return@post
            view = null
            try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
            hidden = false
            FloatQuick.close()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return
        val d = ctx.resources.displayMetrics
        fun dp(x: Int) = (x * d.density).toInt()
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val size = p.getInt("fmSize", dp(72)).coerceIn(dp(44), dp(180))
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = p.getInt("fmX", dp(16)).coerceIn(0, d.widthPixels - size); y = p.getInt("fmY", d.heightPixels / 2).coerceIn(0, d.heightPixels - size)
        }
        val btn = Btn(ctx).apply { off = hidden; alpha = 0.92f }
        val wm = ctx.getSystemService(WindowManager::class.java)
        fun apply() { try { wm.updateViewLayout(btn, params) } catch (_: Throwable) {} }
        val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                val c = params.x + params.width / 2; val m = params.y + params.height / 2
                val s = (params.width * g.scaleFactor).toInt().coerceIn(dp(44), dp(180))
                params.width = s; params.height = s
                params.x = (c - s / 2).coerceIn(0, d.widthPixels - s); params.y = (m - s / 2).coerceIn(0, d.heightPixels - s)
                apply(); return true
            }
        })
        val slop = dp(10)
        var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false; var multi = false; var longFired = false
        // press and hold (still, one finger): open the quick-toggle ring
        val longPress = Runnable {
            if (moved || multi) return@Runnable
            longFired = true
            FloatQuick.show(ctx, params.x + params.width / 2, params.y + params.height / 2, params.width / 2)
        }
        btn.setOnTouchListener { _, e ->
            // ring open after a long press: the button stays put; slide to a circle and lift to flip it
            if (longFired && FloatQuick.showing()) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> FloatQuick.hover(e.rawX, e.rawY)
                    MotionEvent.ACTION_UP -> { touching = false; FloatQuick.release(e.rawX, e.rawY) }
                    MotionEvent.ACTION_CANCEL -> touching = false
                }
                return@setOnTouchListener true
            }
            pinch.onTouchEvent(e)
            touching = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
            if (e.pointerCount > 1 || pinch.isInProgress) { multi = true; main.removeCallbacks(longPress); return@setOnTouchListener true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = params.x; oy = params.y; moved = false; multi = false; longFired = false; main.postDelayed(longPress, 550L) }
                MotionEvent.ACTION_MOVE -> if (!multi) {
                    if (Math.abs(e.rawX - dx) > slop || Math.abs(e.rawY - dy) > slop) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) {
                        params.x = (ox + (e.rawX - dx).toInt()).coerceIn(0, d.widthPixels - params.width)
                        params.y = (oy + (e.rawY - dy).toInt()).coerceIn(0, d.heightPixels - params.height)
                        apply()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    p.edit().putInt("fmX", params.x).putInt("fmY", params.y).putInt("fmSize", params.width).apply()
                    if (!moved && !multi && !longFired) {
                        // tap: minimize every floating window into the dock; tap again: bring them back
                        val minimize = camUp() || webUp() || tripUp()
                        if (minimize) minimizeSeq++ else { shownAt = android.os.SystemClock.uptimeMillis(); restoreSeq++ }
                        btn.off = minimize; btn.invalidate()
                    }
                }
            }
            true
        }
        try { wm.addView(btn, params); view = btn; lp = params } catch (e: Throwable) { Logger.log(ctx, "floatmaster: addView failed ${e.message ?: ""}") }
    }
}

/** The collapsed dock and the floating button must never be covered by a floating window: dock first, button on top of it. */
object OverlayTop {
    fun raise(ctx: Context) { if (FloatMaster.yieldToBlink) return; BubbleRow.raise(ctx); FloatMaster.raise() }
}
