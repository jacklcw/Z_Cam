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
        val POS_NAMES = listOf("Top edge", "Bottom edge", "Right side", "Custom (free)")
        private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        private const val SENSOR_THROTTLE = 0x00101400
        private const val SENSOR_BRAKE = 0x00101300
        private const val SENSOR_SPEED = 0x00100100
        private const val FUNC_POWER_FLOW = 0x24010100
        private val REGEN_FLOWS = setOf(0x24010115, 0x24010116, 0x24010117, 0x2401010a, 0x2401010b, 0x2401010c)
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
    private var regenLevel = 0f
    private var regenTarget = 0f
    private var fnMgr: Any? = null
    private var getFnVal: java.lang.reflect.Method? = null
    private var flowAt = 0L
    private val meter = RegenMeter { Logger.log(ctx, "revbar: $it") }

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
        HudLayer.raisers["rev"] = { raiseNow() }
        dm.registerDisplayListener(displayLog, main)
        thread = HandlerThread("revbar").apply { start() }
        bg = Handler(thread!!.looper).also { it.post(::tick) }
    }

    fun stop() {
        if (!running) return
        running = false; if (instance === this) instance = null
        HudLayer.raisers.remove("rev")
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

    private fun sensorNum(id: Int): Float? = try {
        val f = (getLatest?.invoke(sensor, id) as? Number)?.toFloat()
        if (f == null || f.isNaN() || f == 255f || f == -65535f) null else f
    } catch (_: Throwable) { null }

    /** Regen amount 0..100: while the car's power flow says regenerating, the larger of brake pedal depth and measured deceleration. */
    private fun readRegen(now: Long): Float {
        if (getLatest == null) return 0f
        if (now - flowAt >= 100) {
            flowAt = now
            try {
                if (getFnVal == null) {
                    val car = BlindSpotHud.carCache ?: Class.forName(CAR_CLASS).getMethod("create", Context::class.java).invoke(null, ctx.applicationContext).also { BlindSpotHud.carCache = it }
                    fnMgr = car?.javaClass?.getMethod("getICarFunction")?.invoke(car)
                    getFnVal = fnMgr?.javaClass?.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
                }
                if (((getFnVal?.invoke(fnMgr, FUNC_POWER_FLOW) as? Number)?.toInt() ?: 0) in REGEN_FLOWS) meter.flowSeen(now)
            } catch (_: Throwable) { getFnVal = null }
        }
        return meter.step(now, sensorNum(SENSOR_SPEED), sensorNum(SENSOR_BRAKE), target)
    }

    private fun tick() {
        val h = bg ?: return
        if (!running) return
        val now = System.currentTimeMillis()
        val preview = p.getLong("revPreviewUntil", 0L) > now
        target = if (preview) (0.5f + 0.5f * Math.sin(now / 450.0).toFloat()) * 100f else if (p.getString("revSrc", "0") == "1") revsPct() else readThrottle()
        val regenOn = p.getBoolean("revRegen", false)
        regenTarget = if (!regenOn) 0f else if (preview) (0.5f - 0.5f * Math.sin(now / 450.0).toFloat()) * 100f else readRegen(now)
        regenLevel = if (preview) regenLevel + (regenTarget - regenLevel) * 0.3f else regenTarget   // the meter already glides
        if (regenLevel < 0.3f) regenLevel = 0f
        // the car reports ~5 times a second: glide to each reading, quick up, slower down
        level += (target - level) * (if (target > level) 0.5f else 0.25f)
        if (level >= peak) { peak = level; peakAt = now } else if (now - peakAt > 1200) peak = maxOf(level, peak - 2.5f)
        val show = (level > 0.5f || peak > 0.5f || regenLevel > 0.5f || regenOn || (gearOn() && (com.zeekr.sentrycam.sound.DriveSound.simGear() > 0 || preview)))   // with the regen bar on, the bar stays on screen like a gauge
        main.post { update(show) }
        h.postDelayed(::tick, TICK_MS)
    }

    /** Engine revs source: the simulated engine's rpm as 0..100 between idle and the shift point. The bar is full at the rpm where the last upshift happened (5500 until the first one) and holds full for a moment as the gear changes, so it tops up at every shift instead of only at top speed. */
    private var revGear = 0; private var revLastRpm = 0f; private var shiftRpm = 5500f; private var shiftHoldUntil = 0L
    private fun revsPct(): Float {
        val r = com.zeekr.sentrycam.sound.DriveSound.simRpm(); val g = com.zeekr.sentrycam.sound.DriveSound.simGear(); val now = System.currentTimeMillis()
        if (g > revGear && revGear > 0) { shiftRpm = revLastRpm.coerceIn(3000f, 5500f); shiftHoldUntil = now + 250 }   // upshift: the rpm just before it is this gear's top
        if (g == 0) shiftRpm = 5500f
        revGear = g; revLastRpm = r
        if (now < shiftHoldUntil) return 100f
        return if (r <= 0f) 0f else ((r - 900f) / (shiftRpm - 900f) * 100f).coerceIn(0f, 100f)
    }

    private fun findHud(): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        return on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } }
    }

    private fun pos() = if (p.getBoolean("revGearOn", false)) 3 else p.getInt("revPos", 0).coerceIn(0, 3)   // the gear number needs the free (Custom) layout
    private fun thick(): Int {
        val base = if (pos() == 2) hudW else hudH
        return (base * p.getInt("revSize", 14).coerceIn(2, 30) / 100).coerceAtLeast(4)
    }
    // the HUD projection may crop its outer rim: the window stays on the edge (as before) and the bar is drawn this far in
    private fun inset() = if (pos() == 3) 0 else (if (pos() == 2) hudW else hudH) * p.getInt("revInset", 8).coerceIn(0, 40) / 100
    private fun lp(type: Int): WindowManager.LayoutParams {
        val t = thick() + inset()
        val r = pos() == 2
        val free = pos() == 3   // Custom: a window over the whole HUD, the bar and the gear number are drawn where they were placed
        return WindowManager.LayoutParams(if (free) WindowManager.LayoutParams.MATCH_PARENT else if (r) t else WindowManager.LayoutParams.MATCH_PARENT,
            if (free) WindowManager.LayoutParams.MATCH_PARENT else if (r) WindowManager.LayoutParams.MATCH_PARENT else t, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (pos()) { 0 -> Gravity.TOP; 1 -> Gravity.BOTTOM; 2 -> Gravity.RIGHT; else -> Gravity.TOP or Gravity.LEFT }
        }
    }

    private fun gearOn() = p.getBoolean("revGearOn", false)
    private val fontCache = HashMap<String, android.graphics.Typeface>()
    private fun gearFace(): android.graphics.Typeface {
        val name = p.getString("revGearFont", "Orbitron") ?: "Orbitron"
        return fontCache.getOrPut(name) {
            val file = when (name) { "JetBrains Mono" -> "jbmono_bold.ttf"; "Orbitron" -> "orbitron.ttf"; "Barlow Condensed" -> "barlow_bold.ttf"; "Rajdhani" -> "rajdhani_bold.ttf"; else -> null }
            if (file == null) ZFont.bold else try { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (_: Throwable) { ZFont.bold }
        }
    }

    private var lastShow = false
    private fun update(show: Boolean) {
        if (!running) return
        lastShow = show
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
            HudLayer.added(p, "rev")
        }
        v.visibility = View.VISIBLE
        v.pos = pos(); v.inset = inset().toFloat(); v.vInset = hudH * p.getInt("revInset", 8).coerceIn(0, 40) / 100f
        v.shape = p.getInt("revShape", 0).coerceIn(0, RevStyles.STYLE_NAMES.size - 1); v.scheme = p.getInt("revScheme", 0).coerceIn(0, RevStyles.SCHEME_NAMES.size - 1)
        v.custom = pos() == 3; v.vert = p.getBoolean("revVert", false); v.salign = (p.getString("revAlign", "0") ?: "0").toIntOrNull() ?: 0
        v.flash = p.getBoolean("revFlash", true); v.lenPct = p.getInt("revLen", 90).coerceIn(20, 100); v.thkPct = p.getInt("revSize", 14).coerceIn(2, 30)
        v.barX = p.getInt("revX", 55) / 100f; v.barY = p.getInt("revY", 65) / 100f
        var sg = if (gearOn()) com.zeekr.sentrycam.sound.DriveSound.simGear() else 0
        if (gearOn() && sg == 0 && p.getLong("revPreviewUntil", 0L) > System.currentTimeMillis()) sg = 3   // settings preview: show a sample gear while the car is not driving
        v.gearText = if (sg > 0) "D$sg" else null
        v.gearX = p.getInt("revGearX", 12) / 100f; v.gearY = p.getInt("revGearY", 65) / 100f; v.gearPct = p.getInt("revGearSize", 10).coerceIn(6, 40)
        if (gearOn()) v.gearFace = gearFace()
        v.track = p.getBoolean("revRegen", false)
        v.simGear = if (p.getString("engGearShow", "on") == "off") 0 else com.zeekr.sentrycam.sound.DriveSound.simGear()
        v.level = level; v.peak = peak; v.regen = regenLevel; v.regenColor = p.getInt("revRegenColor", Color.rgb(0, 230, 90))
        v.invalidate()
        // settings changed: re-place the window
        val w = v.layoutParams as? WindowManager.LayoutParams ?: return
        val n = lp(w.type)
        if (w.width != n.width || w.height != n.height || w.gravity != n.gravity) {
            w.width = n.width; w.height = n.height; w.gravity = n.gravity
            try { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, w) } catch (_: Throwable) {}
        }
    }

    /** Re-add the window now, on top of everything added so far (see [HudLayer]). */
    private fun raiseNow() { removeView(); if (lastShow) update(true) }

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
    var regen = 0f
    var regenColor = Color.rgb(0, 230, 90)
    var pos = 0          // 0 top / 1 bottom / 2 right
    var inset = 0f       // gap kept at the outer edge
    var vInset = 0f      // right side: gap kept at the top and bottom (the HUD crops those too)
    var shape = 0
    var scheme = 0
    var simGear = 0
    var custom = false; var vert = false; var salign = 0; var flash = true   // Custom position: free bar (see RevStyles)
    var lenPct = 90; var thkPct = 14; var barX = 0.55f; var barY = 0.65f
    var gearText: String? = null; var gearX = 0.12f; var gearY = 0.65f; var gearPct = 10
    var gearFace: android.graphics.Typeface = android.graphics.Typeface.DEFAULT_BOLD
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    var track = false    // regen bar on: draw a faint track so the bar never vanishes between rev and regen
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val funnel = android.graphics.Path()
    private val seg = android.graphics.Path()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val stops = floatArrayOf(0f, 0.6f, 1f)

    companion object {
        val SHAPE_NAMES = RevStyles.STYLE_NAMES
        val SCHEME_NAMES = RevStyles.SCHEME_NAMES
    }

    private fun colors() = RevStyles.colors(scheme)
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

    private fun regenCell(cv: Canvas) {
        fill.shader = null; fill.color = regenColor; fill.alpha = 255
        cv.drawPath(seg, fill)
        glow.color = regenColor; glow.alpha = 50; glow.style = Paint.Style.STROKE; cv.drawPath(seg, glow)
    }

    private fun newBar(cv: Canvas, x: Float, y: Float, w: Float, h: Float, vertical: Boolean) {
        val off = flash && level >= 96f && (android.os.SystemClock.uptimeMillis() / 80) % 2 == 1L   // the blink at the shift point / redline
        RevStyles.draw(cv, shape, scheme, x, y, w, h, vertical, salign, level / 100f, peak / 100f, regen / 100f, regenColor, track, off, android.os.SystemClock.uptimeMillis())
    }

    override fun onDraw(cv: Canvas) {
        if (custom) {
            // a window over the whole HUD: the bar stays inside the part of it the windshield shows (x 111-1329, y 0-341 of 1440 x 480)
            val W = width.toFloat(); val H = height.toFloat(); val k = W / 1440f
            val t = maxOf(4f, H * thkPct / 100f); val len = (if (vert) 341f else 1218f) * k * lenPct / 100f
            val bw = if (vert) t else len; val bh = if (vert) len else t
            val bx = (W * barX - bw / 2f).coerceIn(111f * k, maxOf(111f * k, 1329f * k - bw)); val by = (H * barY - bh / 2f).coerceIn(0f, maxOf(0f, 341f * k - bh))
            newBar(cv, bx, by, bw, bh, vert)
            gearText?.let { g ->
                gearPaint.typeface = gearFace; gearPaint.textSize = H * gearPct / 100f; gearPaint.setShadowLayer(6f, 0f, 0f, Color.argb(200, 0, 0, 0))
                cv.drawText(g, W * gearX, H * gearY - (gearPaint.ascent() + gearPaint.descent()) / 2f, gearPaint)
            }
            return
        }
        if (shape >= 3) {   // the newer styles on an edge position
            val wv = width.toFloat(); val hv = height.toFloat()
            when (pos) {
                0 -> newBar(cv, 0f, inset, wv, hv - inset, false)
                1 -> newBar(cv, 0f, 0f, wv, hv - inset, false)
                else -> newBar(cv, 0f, vInset, wv - inset, hv - 2 * vInset, true)
            }
            return
        }
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
                if (regen > 0f) { fill.shader = null; fill.color = regenColor; fill.alpha = 255; cv.drawRect(0f, 0f, w, h * regen / 100f, fill) }
            } else {
                // stacked slices, bottom to top
                val n = 20; val sh = h / n; val gap = sh * 0.25f
                glow.strokeWidth = gap
                for (i in 0 until n) {
                    val f = (i + 0.5f) / n
                    val y1 = h - i * sh; val y0 = y1 - sh + gap
                    seg.reset(); seg.addRect(0f, y0, w, y1, android.graphics.Path.Direction.CW)
                    cell(cv, f, f * 100f <= level, f * 100f <= peak && (i + 1f) / n * 100f > peak)
                    if ((1f - f) * 100f <= regen) regenCell(cv)
                }
            }
            cv.restore()
            if (w >= 60f) { text.textSize = w * 0.25f; cv.drawText("${level.toInt()}", w * 0.8f, h * 0.12f, text) }
        } else if (shape == 0) {
            if (track) { fill.shader = null; fill.color = Color.WHITE; fill.alpha = 35; cv.drawRect(0f, h * 0.15f, w, h * 0.85f, fill) }
            // glow underlay, then the bar
            fill.shader = null; fill.color = colorAt(level / 100f); fill.alpha = 45
            cv.drawRect(0f, 0f, w * level / 100f, h, fill)
            fill.shader = LinearGradient(0f, 0f, w, 0f, c, stops, Shader.TileMode.CLAMP)
            cv.drawRect(0f, h * 0.15f, w * level / 100f, h * 0.85f, fill)
            val px = w * peak / 100f
            cv.drawRect(px - (h * 0.2f).coerceAtLeast(3f), 0f, px, h, tick)
            if (regen > 0f) { fill.shader = null; fill.color = regenColor; fill.alpha = 255; cv.drawRect(0f, h * 0.15f, w * regen / 100f, h * 0.85f, fill) }
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
                if (f * 100f <= regen) regenCell(cv)
            }
        }
        if (simGear > 0) {
            val g = "D$simGear"
            text.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            if (vertical) { if (w >= 60f) { text.textSize = w * 0.3f; cv.drawText(g, w * 0.8f, h * 0.12f + w * 0.32f, text) } }
            else { text.textSize = h * 0.8f; text.textAlign = Paint.Align.LEFT; cv.drawText(g, h * 0.3f, h * 0.8f, text); text.textAlign = Paint.Align.CENTER }
            text.clearShadowLayer()
        }
    }
}
