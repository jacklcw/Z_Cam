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

    /** Re-adds the bar above every floating window (called whenever a window opens or comes back). */
    fun raise() { val c = app ?: return; if (bar != null) { remove(c); build(c) } }

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
                gravity = Gravity.TOP or (if (sx < 0) Gravity.RIGHT else Gravity.LEFT); x = if (sx < 0) 0 else sx; y = p.getInt("flMediaY", dp(120))
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

    /**
     * Where the bar lands when let go: near the top or bottom edge it lies flat along that edge (corners too, so the bottom right corner is the
     * bottom, not the right edge); otherwise near the left / right edge it stands upright there; anywhere else it stays a row where it was let go.
     * Every edge is flush (no gap).
     */
    private fun snap(c: Context, rawX: Float) {
        val b = bar ?: return
        val dm = c.resources.displayMetrics
        val p = prefs(c)
        val loc = IntArray(2); b.getLocationOnScreen(loc)
        if (inset < 0) inset = 0
        val bottom = usableBottom(c)
        val wasVertical = p.getBoolean("flMediaV", false)
        val rowW = if (wasVertical) b.height else b.width; val rowH = if (wasVertical) b.width else b.height   // its size as a row
        val colW = rowH; val colH = rowW                                                                         // and standing upright
        val top = loc[1] - inset
        // how far each side of the bar is from that screen edge; every edge zone is the same size (a fifth of the screen)
        val zone = 0.2f * minOf(dm.widthPixels, bottom - inset)
        val dTop = top; val dBottom = bottom - (loc[1] + b.height); val dLeft = loc[0]; val dRight = dm.widthPixels - (loc[0] + b.width)
        var vertical = false
        var x: Int; var y: Int
        when {
            dBottom < zone && dBottom <= dTop -> { x = loc[0].coerceIn(0, (dm.widthPixels - rowW).coerceAtLeast(0)); y = (bottom - inset - rowH).coerceAtLeast(0) }   // bottom (corners included)
            dTop < zone -> { x = loc[0].coerceIn(0, (dm.widthPixels - rowW).coerceAtLeast(0)); y = 0 }   // top (corners included)
            dLeft < zone && dLeft <= dRight -> { vertical = true; x = 0; y = top.coerceIn(0, (bottom - inset - colH).coerceAtLeast(0)) }
            dRight < zone -> { vertical = true; x = -1; y = top.coerceIn(0, (bottom - inset - colH).coerceAtLeast(0)) }   // right edge (gravity right)
            else -> { x = loc[0].coerceIn(0, (dm.widthPixels - b.width).coerceAtLeast(0)); y = top.coerceIn(0, (bottom - inset - b.height).coerceAtLeast(0)) }
        }
        Logger.log(c, "mediadock: let go at ${loc[0]},${loc[1]}, usable bottom $bottom, top inset $inset, bar ${b.width}x${b.height}, placed $x,$y ${if (vertical) "upright" else "row"}")
        p.edit().putInt("flMediaX", x).putInt("flMediaY", y).putBoolean("flMediaV", vertical).apply()
        if (vertical != wasVertical || x < 0) { remove(c); build(c) } else place(c, x, y)
    }

    /** Screen y where the usable area ends (top of the bottom bar). displayMetrics already leaves both bars out, so it can't be used with the top inset. */
    private fun usableBottom(c: Context): Int {
        if (android.os.Build.VERSION.SDK_INT >= 30) try {
            val wm = c.getSystemService(WindowManager::class.java).currentWindowMetrics
            val ins = wm.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            return wm.bounds.height() - ins.bottom
        } catch (_: Throwable) {}
        return c.resources.displayMetrics.heightPixels + (if (inset < 0) 0 else inset)
    }

    private fun place(c: Context, x: Int, y: Int) {
        val b = bar ?: return
        val lp = b.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x; lp.y = y
        try { c.getSystemService(WindowManager::class.java).updateViewLayout(b, lp) } catch (_: Throwable) {}
    }
}
