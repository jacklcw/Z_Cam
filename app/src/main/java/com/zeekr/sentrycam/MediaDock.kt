package com.zeekr.sentrycam

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Phone media buttons: a small floating bar of its own (previous / play-pause / next) that controls the phone's music through the car's
 * Bluetooth player. Playing from here, not on the phone, is what gets the car to hand its speakers back to the phone after a car app (our
 * browser) used them. Separate from the minimize dock; drags like it and snaps to the side edges (then stacks vertically); the place is kept.
 * Shown while "flMedia" is on and a phone is connected over Bluetooth. Main thread only.
 */
object MediaDock {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var bar: BubbleRow.DragBar? = null
    private var playBtn: IconButton? = null
    private var inset = -1
    private val poll = object : Runnable {
        override fun run() { val c = app ?: return; refresh(c); main.postDelayed(this, 3000L) }
    }

    fun start(ctx: Context) {
        if (app != null) { refresh(app!!); return }
        app = ctx   // the service, like the minimize dock (its window and screen size)
        PhoneMusic.onState = { on -> playBtn?.on = on }
        PhoneMusic.watch(ctx)
        main.post(poll)
    }

    fun stop() {
        main.removeCallbacks(poll)
        val c = app ?: return
        app = null
        PhoneMusic.onState = null
        PhoneMusic.unwatch()
        PhoneMusic.disconnect()
        remove(c)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun refresh(c: Context) {
        val want = prefs(c).getBoolean("flMedia", false) && Settings.canDrawOverlays(c) && !FloatMaster.hidden && PhoneMusic.phoneConnected(c)
        if (want) { PhoneMusic.connect(c); if (bar == null) build(c) } else remove(c)
    }

    private fun remove(c: Context) {
        val b = bar ?: return
        bar = null; playBtn = null
        try { c.getSystemService(WindowManager::class.java).removeView(b) } catch (_: Throwable) {}
    }

    private fun build(c: Context) {
        val p = prefs(c)
        val d = c.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val vertical = p.getBoolean("flMediaV", false)
        val b = BubbleRow.DragBar(c, { dx, dy -> move(c, dx, dy) }, { rx -> snap(c, rx) }).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(0xE61C1C1E.toInt()); cornerRadius = dp(44).toFloat(); setStroke(dp(1), 0xFF3A3A3C.toInt()) }
        }
        val play = IconButton(c, IconButton.Kind.PLAY).apply {
            on = PhoneMusic.playing(c)
            setOnClickListener { PhoneMusic.toggle(c) }   // the icon follows the phone's real state (PhoneMusic.onState), not the tap
        }
        val buttons = listOf(
            IconButton(c, IconButton.Kind.PREV).apply { setOnClickListener { PhoneMusic.prev(c) } },
            play,
            IconButton(c, IconButton.Kind.NEXT).apply { setOnClickListener { PhoneMusic.next(c) } })
        for ((i, ib) in buttons.withIndex()) {
            ib.background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(34).toFloat() }
            b.addView(ib, LinearLayout.LayoutParams(dp(96), dp(72)).apply { if (i > 0) { if (vertical) topMargin = dp(10) else marginStart = dp(10) } })
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
                val sx = p.getInt("flMediaX", dp(10))
                gravity = Gravity.TOP or (if (sx < 0) Gravity.RIGHT else Gravity.LEFT); x = if (sx < 0) dp(10) else sx; y = p.getInt("flMediaY", dp(120))
            }
        try {
            c.getSystemService(WindowManager::class.java).addView(b, lp); bar = b; playBtn = play
            FloatMaster.raise()   // the floating button stays on top
        } catch (e: Throwable) { Logger.log(c, "mediadock: failed ${e.message ?: ""}") }
    }

    private fun move(c: Context, dx: Float, dy: Float) {
        val b = bar ?: return
        val lp = b.layoutParams as? WindowManager.LayoutParams ?: return
        if (inset < 0) { val l = IntArray(2); b.getLocationOnScreen(l); inset = (l[1] - lp.y).coerceAtLeast(0) }
        if (lp.gravity != (Gravity.TOP or Gravity.LEFT)) {   // stuck to the right edge: switch to absolute left / top first
            val l = IntArray(2); b.getLocationOnScreen(l)
            lp.gravity = Gravity.TOP or Gravity.LEFT; lp.x = l[0]; lp.y = l[1] - inset
        }
        lp.x += dx.toInt(); lp.y += dy.toInt()
        try { c.getSystemService(WindowManager::class.java).updateViewLayout(b, lp) } catch (_: Throwable) {}
    }

    /** Same placement as the minimize dock ([BubbleRow]): near the left / right edge it sticks there upright; near the top it sticks to the top edge. */
    private fun snap(c: Context, rawX: Float) {
        val b = bar ?: return
        val dm = c.resources.displayMetrics
        val m = (10 * dm.density).toInt()
        val p = prefs(c)
        val loc = IntArray(2); b.getLocationOnScreen(loc)
        if (inset < 0) inset = 0
        val y = (loc[1] - inset).coerceIn(0, (dm.heightPixels - inset - b.height).coerceAtLeast(0))
        val wasVertical = p.getBoolean("flMediaV", false)
        var vertical = false
        val x: Int
        when {
            rawX < dm.widthPixels * 0.2f || loc[0] < 3 * m -> { vertical = true; x = m }
            rawX > dm.widthPixels * 0.8f || loc[0] + b.width > dm.widthPixels - 3 * m -> { vertical = true; x = -1 }   // right edge: placed once its upright size is known
            else -> x = loc[0].coerceIn(0, (dm.widthPixels - b.width).coerceAtLeast(0))
        }
        val yy = if (y < 60 * dm.density && !vertical) m else y
        p.edit().putInt("flMediaX", x).putInt("flMediaY", yy).putBoolean("flMediaV", vertical).apply()
        if (vertical != wasVertical || x < 0) { remove(c); build(c) } else place(c, x, yy)
    }

    private fun place(c: Context, x: Int, y: Int) {
        val b = bar ?: return
        val lp = b.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x; lp.y = y
        try { c.getSystemService(WindowManager::class.java).updateViewLayout(b, lp) } catch (_: Throwable) {}
    }
}
