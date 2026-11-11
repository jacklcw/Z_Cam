package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Rev bar on the HUD: a bar across the whole HUD (top or bottom edge) or down its right portion that fills with
 * the throttle pedal depth (sensor 0x00101400, %), green to red, with a peak marker. Overlay window like
 * BlindSpotHud (same HUD display pick). Prefs: revOn, revPos (0 top / 1 bottom / 2 right), revSize (% of the HUD
 * height for top/bottom, of its width for right), revInset (% gap to the HUD rim), revShape, revScheme, revPreviewUntil (settings screen sweeps the bar for a few seconds).
 */
class RevBar(private val ctx: Context) {
    companion object {
        @Volatile var instance: RevBar? = null
        val POS_NAMES = listOf("Top edge", "Bottom edge", "Right side")
        private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        private const val SENSOR_THROTTLE = 0x00101400
        private const val TICK_MS = 40L
        // One Car connection for the whole process: Car.create() on every start leaked a binder connection to the
        // car's service each time, and toggling the bar on and off quickly exhausted it.
        @Volatile private var sensorCache: Any? = null
        @Volatile private var latestCache: java.lang.reflect.Method? = null
        private const val HUD_W = 1440; private const val HUD_H = 480
        private const val HUD_FALLBACK_ID = 3
        private const val CLUSTER_W = 2560; private const val CLUSTER_H = 536
    }

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false

    private var sensor: Any? = null
    private var getLatest: java.lang.reflect.Method? = null
    private var lastTry = 0L
    private var level = 0f
    private var peak = 0f
    private var peakAt = 0L
    private var target = 0f

    private var retryAt = 0L
    private var view: RevView? = null   // main thread only
    private var hudW = 0; private var hudH = 0
    private var hudId = -1
    private val dm get() = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayLog = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { Logger.log(ctx, "revbar: display $id added") }
        override fun onDisplayRemoved(id: Int) { Logger.log(ctx, "revbar: display $id removed") }
        override fun onDisplayChanged(id: Int) { if (id == hudId) Logger.log(ctx, "revbar: HUD display $id state ${dm.getDisplay(id)?.state}") }
    }

    fun start() {
        if (running) return
        running = true; instance = this
        dm.registerDisplayListener(displayLog, main)
        thread = HandlerThread("revbar").apply { start() }
        bg = Handler(thread!!.looper).also { it.post(::tick) }
    }

    fun stop() {
        if (!running) return
        running = false; if (instance === this) instance = null
        dm.unregisterDisplayListener(displayLog)
        thread?.quitSafely(); thread = null; bg = null
        main.post(::removeView)
    }

    private fun readThrottle(): Float {
        if (getLatest == null) {
            val now = System.currentTimeMillis()
            if (now - lastTry < 5_000) return target
            lastTry = now
            synchronized(RevBar::class.java) {
                if (latestCache == null) try {
                    val car = Class.forName(CAR_CLASS).getMethod("create", Context::class.java).invoke(null, ctx.applicationContext)
                    sensorCache = car?.javaClass?.getMethod("getSensorManager")?.invoke(car)
                    latestCache = sensorCache?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
                } catch (_: Throwable) { return 0f }
                sensor = sensorCache; getLatest = latestCache
            }
        }
        return try {
            val f = (getLatest!!.invoke(sensor, SENSOR_THROTTLE) as? Number)?.toFloat() ?: 0f
            if (f.isNaN() || f == 255f || f == -65535f) 0f else f.coerceIn(0f, 100f)
        } catch (_: Throwable) { getLatest = null; latestCache = null; sensorCache = null; 0f }
    }

    private fun tick() {
        val h = bg ?: return
        if (!running) return
        val now = System.currentTimeMillis()
        val preview = p.getLong("revPreviewUntil", 0L) > now
        target = if (preview) (0.5f + 0.5f * Math.sin(now / 450.0).toFloat()) * 100f else readThrottle()
        // the car reports ~5 times a second: glide to each reading, quick up, slower down
        level += (target - level) * (if (target > level) 0.5f else 0.25f)
        if (level >= peak) { peak = level; peakAt = now } else if (now - peakAt > 1200) peak = maxOf(level, peak - 2.5f)
        val show = (level > 0.5f || peak > 0.5f)
        main.post { update(show) }
        h.postDelayed(::tick, TICK_MS)
    }

    private fun findHud(): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        return on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } }
    }

    private fun pos() = p.getInt("revPos", 0).coerceIn(0, 2)
    private fun thick(): Int {
        val base = if (pos() == 2) hudW else hudH
        return (base * p.getInt("revSize", 14).coerceIn(2, 30) / 100).coerceAtLeast(4)
    }
    // the HUD projection may crop its outer rim: the window stays on the edge (as before) and the bar is drawn this far in
    private fun inset() = (if (pos() == 2) hudW else hudH) * p.getInt("revInset", 8).coerceIn(0, 20) / 100
    private fun lp(type: Int): WindowManager.LayoutParams {
        val t = thick() + inset()
        val r = pos() == 2
        return WindowManager.LayoutParams(if (r) t else WindowManager.LayoutParams.MATCH_PARENT, if (r) WindowManager.LayoutParams.MATCH_PARENT else t, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (pos()) { 0 -> Gravity.TOP; 1 -> Gravity.BOTTOM; else -> Gravity.RIGHT }
        }
    }

    private fun update(show: Boolean) {
        if (!running) return
        var v = view
        if (!show) { v?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }; return }
        if (v != null) {
            // the HUD display can go off/on (e.g. at P to D): the old window is then dead, so drop it and make a fresh one
            val d = dm.getDisplay(hudId)
            if (!v.isAttachedToWindow || d == null || d.state != Display.STATE_ON) {
                Logger.log(ctx, "revbar: window stale (attached=${v.isAttachedToWindow} display state=${d?.state}), recreating")
                removeView(); v = null
            }
        }
        if (v == null) {
            if (System.currentTimeMillis() < retryAt || Build.VERSION.SDK_INT < 30 || !Settings.canDrawOverlays(ctx)) return
            val hud = findHud() ?: return
            val sz = Point().also { hud.getRealSize(it) }
            hudW = sz.x; hudH = sz.y; hudId = hud.displayId
            val dctx = ctx.createDisplayContext(hud)
            val ovl = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            var type = BlindSpotHud.WIN_TYPES[BlindSpotHud.winTypeIndex(p)]
            var wctx = try { dctx.createWindowContext(type, null) } catch (_: Throwable) { type = ovl; try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx } }
            // a refused window type leaves its view half-registered, so the overlay fallback needs a fresh view
            var nv = RevView(wctx)
            try { wctx.getSystemService(WindowManager::class.java).addView(nv, lp(type)) }
            catch (e: Throwable) {
                Logger.log(ctx, "revbar: window type $type refused (${e.javaClass.simpleName} ${e.message ?: ""}), using overlay")
                nv = RevView(dctx)
                try {
                    wctx = try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx }
                    nv = RevView(wctx)
                    wctx.getSystemService(WindowManager::class.java).addView(nv, lp(ovl))
                } catch (e2: Throwable) {
                    Logger.log(ctx, "revbar: addView failed: ${e2.javaClass.simpleName} ${e2.message ?: ""}")
                    retryAt = System.currentTimeMillis() + 10_000
                    return
                }
            }
            view = nv; v = nv
            Logger.log(ctx, "revbar: on (display ${hud.displayId})")
        }
        v.visibility = View.VISIBLE
        v.pos = pos(); v.inset = inset().toFloat(); v.vInset = hudH * p.getInt("revInset", 8).coerceIn(0, 20) / 100f; v.shape = p.getInt("revShape", 0).coerceIn(0, 2); v.scheme = p.getInt("revScheme", 0).coerceIn(0, 3)
        v.level = level; v.peak = peak
        v.invalidate()
        // settings changed: re-place the window
        val w = v.layoutParams as? WindowManager.LayoutParams ?: return
        val n = lp(w.type)
        if (w.width != n.width || w.height != n.height || w.gravity != n.gravity) {
            w.width = n.width; w.height = n.height; w.gravity = n.gravity
            try { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, w) } catch (_: Throwable) {}
        }
    }

    /** Drop the window so the next update re-adds it above windows created since (e.g. the web page on the HUD). */
    fun raise() { main.post { removeView() } }

    private fun removeView() {
        val v = view ?: return
        view = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }
}

/** The bar itself: fills to [level] %, marks [peak] %. [shape] 0 smooth / 1 segments / 2 slanted; [scheme] picks the colours. Right side is a funnel. */
class RevView(c: Context) : View(c) {
    var level = 0f
    var peak = 0f
    var pos = 0          // 0 top / 1 bottom / 2 right
    var inset = 0f       // gap kept at the outer edge
    var vInset = 0f      // right side: gap kept at the top and bottom (the HUD crops those too)
    var shape = 0
    var scheme = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val funnel = android.graphics.Path()
    private val seg = android.graphics.Path()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val stops = floatArrayOf(0f, 0.6f, 1f)

    companion object {
        val SHAPE_NAMES = listOf("Smooth", "Segments", "Slanted")
        val SCHEME_NAMES = listOf("Traffic", "Neon", "Ice", "Sport")
        private val SCHEMES = arrayOf(
            intArrayOf(Color.rgb(0, 230, 90), Color.rgb(255, 214, 0), Color.rgb(255, 40, 40)),
            intArrayOf(Color.rgb(0, 229, 255), Color.rgb(90, 80, 255), Color.rgb(255, 0, 200)),
            intArrayOf(Color.rgb(170, 230, 255), Color.rgb(0, 190, 255), Color.rgb(255, 255, 255)),
            intArrayOf(Color.rgb(255, 150, 0), Color.rgb(255, 70, 0), Color.rgb(255, 0, 60)))
    }

    private fun colors() = SCHEMES[scheme.coerceIn(0, SCHEMES.size - 1)]
    private fun colorAt(f: Float): Int {
        val c = colors(); val x = f.coerceIn(0f, 1f)
        val (a, b, t) = if (x < 0.6f) Triple(c[0], c[1], x / 0.6f) else Triple(c[1], c[2], (x - 0.6f) / 0.4f)
        return android.animation.ArgbEvaluator().evaluate(t, a, b) as Int
    }

    private fun cell(cv: Canvas, f: Float, lit: Boolean, isPeak: Boolean) {
        fill.shader = null
        fill.color = if (isPeak) Color.WHITE else colorAt(f)
        fill.alpha = if (lit || isPeak) 255 else 45
        cv.drawPath(seg, fill)
        if (lit) { glow.color = colorAt(f); glow.alpha = 50; glow.style = Paint.Style.STROKE; cv.drawPath(seg, glow) }
    }

    override fun onDraw(cv: Canvas) {
        val vertical = pos == 2
        val w = width.toFloat() - (if (vertical) inset else 0f); val h = height.toFloat() - (if (vertical) 2 * vInset else inset)
        if (w <= 0f || h <= 0f) return
        if (pos == 0) cv.translate(0f, inset) else if (vertical) cv.translate(0f, vInset)
        val c = colors()
        if (vertical) {
            // funnel: wide at the top, narrow at the bottom, right-aligned; fills from the bottom
            funnel.reset()
            funnel.moveTo(0f, 0f); funnel.lineTo(w, 0f); funnel.lineTo(w, h); funnel.lineTo(w * 0.75f, h); funnel.close()
            outline.color = Color.argb(90, 255, 255, 255)
            outline.strokeWidth = (w * 0.02f).coerceAtLeast(2f)
            cv.drawPath(funnel, outline)
            cv.save()
            cv.clipPath(funnel)
            if (shape == 0) {
                fill.shader = LinearGradient(0f, h, 0f, 0f, c, stops, Shader.TileMode.CLAMP)
                cv.drawRect(0f, h * (1f - level / 100f), w, h, fill)
                val py = h * (1f - peak / 100f)
                cv.drawRect(0f, py, w, py + (w * 0.04f).coerceAtLeast(3f), tick)
            } else {
                // stacked slices, bottom to top
                val n = 20; val sh = h / n; val gap = sh * 0.25f
                glow.strokeWidth = gap
                for (i in 0 until n) {
                    val f = (i + 0.5f) / n
                    val y1 = h - i * sh; val y0 = y1 - sh + gap
                    seg.reset(); seg.addRect(0f, y0, w, y1, android.graphics.Path.Direction.CW)
                    cell(cv, f, f * 100f <= level, f * 100f <= peak && (i + 1f) / n * 100f > peak)
                }
            }
            cv.restore()
            if (w >= 60f) { text.textSize = w * 0.25f; cv.drawText("${level.toInt()}", w * 0.8f, h * 0.12f, text) }
        } else if (shape == 0) {
            // glow underlay, then the bar
            fill.shader = null; fill.color = colorAt(level / 100f); fill.alpha = 45
            cv.drawRect(0f, 0f, w * level / 100f, h, fill)
            fill.shader = LinearGradient(0f, 0f, w, 0f, c, stops, Shader.TileMode.CLAMP)
            cv.drawRect(0f, h * 0.15f, w * level / 100f, h * 0.85f, fill)
            val px = w * peak / 100f
            cv.drawRect(px - (h * 0.2f).coerceAtLeast(3f), 0f, px, h, tick)
        } else {
            val n = if (shape == 1) 40 else 32
            val sw = w / n; val gap = sw * 0.22f
            val skew = if (shape == 2) h * 0.6f else 0f
            glow.strokeWidth = (h * 0.12f).coerceAtLeast(2f)
            for (i in 0 until n) {
                val f = (i + 0.5f) / n
                val x0 = i * sw
                seg.reset()
                seg.moveTo(x0 + skew, 0f); seg.lineTo(x0 + sw - gap + skew, 0f); seg.lineTo(x0 + sw - gap, h); seg.lineTo(x0, h); seg.close()
                cell(cv, f, f * 100f <= level, f * 100f <= peak && (i + 1f) / n * 100f > peak)
            }
        }
    }
}
