package com.zeekr.sentrycam

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager

/**
 * Blind-spot assist on the HUD: while the left/right indicator is on, show that side's
 * surround-camera panel on the head-up display.
 *
 * Indicator: ECARX vehicle API (com.ecarx.xui.adaptapi.car.Car, on the head unit's
 * BOOTCLASSPATH, no permission needed in the App Lab container), function
 * 0x2A091500 = 0 off / 1 left / 2 right / 3 hazard. Steady while blinking. Called by
 * reflection since the jar only exists on the car. Facts from dts88/zeekr-shortcut-car
 * platform notes (observed on a 7X, not official).
 *
 * HUD: the non-default display that is 1440x480 (7X HUD, normally id 3), else id 3 if it
 * isn't the 2560x536 cluster -- never draw on the cluster. Drawn as an overlay window
 * (SYSTEM_ALERT_WINDOW) via a window context on that display.
 *
 * Camera: a repeating preview of camera "2" (4 fisheyes stacked vertically: Front, Rear,
 * Left, Right -- same order as the dashboard's live view) rendered by CamHub, which
 * picks one panel and flattens the fisheye (same maths as the dashboard). Holds CameraCapture.halLock while open, so Sentry
 * snapshots/live view just report "camera busy" during a lane change.
 */
class BlindSpotHud(private val ctx: Context) {
    companion object {
        // Window types to try for the HUD picture (higher types may stack above the car's own HUD content; refused types fall back to overlay)
        val WIN_NAMES = listOf("Overlay (normal)", "System overlay", "System alert", "Private presentation", "Display overlay")
        val WIN_TYPES = listOf(2038, 2006, 2003, 2030, 2026)
        fun winTypeIndex(p: android.content.SharedPreferences) = p.getInt("bsWinType", 0).coerceIn(0, WIN_TYPES.size - 1)
        /** Defaults tuned on the car (left, right). */
        fun defaultFor(key: String, left: Boolean): Int = when (key) {
            "bsSize" -> 78
            "bsFov" -> if (left) 110 else 99
            "bsTilt" -> if (left) 0 else 1
            "bsYaw" -> if (left) 40 else 44
            "bsRoll" -> if (left) -39 else 17
            "bsAspect" -> 100
            else -> 0
        }
        /** Turn arrow icon names (index = pref "bsArrIcon_L/_R"). */
        val ARROW_ICONS = listOf("Block", "Chevron", "Triangle", "Double", "Slim", "Fat")
        const val ARROW_GREEN = 0xFF00E65A.toInt()
        /** Turn arrow settings per indicator side: centre x/y as % of the HUD, height as % of the HUD height, icon, colour. */
        fun arrowGet(p: android.content.SharedPreferences, key: String, left: Boolean): Int {
            val k = key + (if (left) "_L" else "_R")
            return when (key) {
                "bsArrX" -> p.getInt(k, if (left) 11 else 30)
                "bsArrY" -> p.getInt(k, 8)
                "bsArrSize" -> p.getInt(k, 13)
                "bsArrAsp" -> p.getInt(k, 133)   // width as % of height
                "bsArrIcon" -> p.getInt(k, 5)
                else -> p.getInt(k, ARROW_GREEN)
            }
        }
        val ARROW_KEYS = listOf("bsArrX", "bsArrY", "bsArrSize", "bsArrAsp", "bsArrIcon", "bsArrColor")
        /** PANEL_LEFT/PANEL_RIGHT while the HUD camera shows, else -1 (WebHud yields the left region). */
        @Volatile var activePanel = -1
        /** Latest indicator reading (0 off / 1 left / 2 right / 3 hazard), for the floating windows' arrows. */
        @Volatile var rawNow = 0
        private val sideUntil = LongArray(4)
        /** True while the indicator is on for [panel] (PANEL_LEFT=2 / PANEL_RIGHT=3), plus the hold time. Used by the floating windows. */
        fun indicating(panel: Int) = panel in 2..3 && System.currentTimeMillis() < sideUntil[panel]
        @Volatile internal var carCache: Any? = null
        private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        private const val FUNC_INDICATOR = 0x2A091500
        private const val POLL_MS = 200L
        private const val HOLD_MS = 1500L        // default: keep showing after the indicator stops
        /** How long the HUD and floating windows stay after the blinker goes off (pref "bsHoldMs", 0.5-10 s). */
        fun holdMs(p: android.content.SharedPreferences) = p.getInt("bsHoldMs", HOLD_MS.toInt()).coerceIn(500, 10_000)
        private const val HUB_KEY = "hud"
        private const val PANEL_LEFT = 2
        private const val PANEL_RIGHT = 3
        private const val HUD_W = 1440; private const val HUD_H = 480
        private const val HUD_FALLBACK_ID = 3
        private const val CLUSTER_W = 2560; private const val CLUSTER_H = 536
    }

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false
    @Volatile private var disabled = false   // set after a hard failure so we don't retry every 200ms

    // ECARX (bg thread only)
    private var function: Any? = null
    private var getFunctionValue: java.lang.reflect.Method? = null
    private var lastRaw = -1
    private var offSince = 0L

    // overlay (main thread only)
    @Volatile private var view: TextureView? = null
    @Volatile private var shownPanel = -1
    private var hudH = 0
    private var hudW = 0
    private val arrows = arrayOfNulls<SignalArrow>(2)   // slot 1 only used for hazard (both sides)
    @Volatile private var lastArrowRaw = 0
    private var arrowH = 0
    private var arrowW = 0

    // camera (bg thread only; the camera itself is shared through CamHub)
    @Volatile private var renderer: PanelOut? = null

    // Left camera always sits at the HUD's left; the right camera does too unless "bsRightPos" puts it at the right edge.
    private fun hGrav(panel: Int) = if (panel == PANEL_RIGHT && p.getBoolean("bsRightPos", false)) Gravity.RIGHT else Gravity.LEFT

    // Per-side settings ("bsSize_L"/"bsSize_R" ...), falling back to the old shared key.
    private fun gi(base: String, panel: Int) =
        p.getInt(base + (if (panel == PANEL_LEFT) "_L" else "_R"), defaultFor(base, panel == PANEL_LEFT))
    private fun gb(base: String, panel: Int) =
        p.getBoolean(base + (if (panel == PANEL_LEFT) "_L" else "_R"), true)

    // Raw panels look straight out of the mirror; the rear is image-left on the left panel and image-right on
    // the right one, and the mirror option flips the picture.
    private fun rearYaw(): Float {
        val a = gi("bsYaw", shownPanel).coerceIn(0, 60)
        val sign = (if (shownPanel == PANEL_LEFT) -1 else 1) * (if (gb("bsMirror", shownPanel)) -1 else 1)
        return (a * sign).toFloat()
    }

    private fun err(what: String, e: Throwable) {
        val top = e.stackTrace.firstOrNull()?.let { " @${it.fileName}:${it.lineNumber}" } ?: ""
        Logger.log(ctx, "blindspot: $what: ${e.javaClass.simpleName} ${e.message ?: ""}$top")
    }

    fun start() {
        if (running) return
        running = true
        HudLayer.raisers["cam"] = { if (view != null) { removeViewNow(); showImpl(shownPanel) } }   // rare: the picture must sit in front of something added after it
        HudLayer.raisers["arr"] = { if (arrows[0] != null) { removeArrow(); updateArrow(lastArrowRaw) } }
        thread = HandlerThread("blindspot").apply { start() }
        bg = Handler(thread!!.looper).also { it.post(::poll) }
        Logger.log(ctx, "blindspot: started")
    }

    fun stop() {
        if (!running) return
        running = false
        HudLayer.raisers.remove("cam"); HudLayer.raisers.remove("arr")
        bg?.post { closeCamera(); thread?.quitSafely() }
        main.post { removeArrow(); removeViewNow() }
        bg = null
    }

    private fun poll() {
        try { pollImpl() } catch (e: Throwable) { err("poll", e); bg?.postDelayed(::poll, 5_000) }
    }

    private fun pollImpl() {
        if (!running) return
        val h = bg ?: return
        if (getFunctionValue == null && !connect()) { h.postDelayed(::poll, 10_000); return }
        val raw = try {
            (getFunctionValue!!.invoke(function, FUNC_INDICATOR) as? Number)?.toInt() ?: -1
        } catch (_: Throwable) {
            getFunctionValue = null
            if (++failStreak >= 5) { failStreak = 0; releaseCar() }   // connection looks dead: rebuild it, but not on every blip
            -1
        }
        if (raw != -1) failStreak = 0
        if (raw != lastRaw) { Logger.log(ctx, "blindspot: indicator $raw"); lastRaw = raw }
        val now = System.currentTimeMillis()
        if (raw != 0 && raw != -1) Park.poke()
        // The car moving also counts as "not parked", straight away: after a long stop the GPS needs a while for a fix, and the camera views should be ready by the first blinker, not opened by it
        if (now - speedAt > 1000) { speedAt = now; if ((readSpeed() ?: 0f) > 0.5f || inDriveOrReverse()) Park.poke() }
        if (raw == 1) sideUntil[PANEL_LEFT] = now + holdMs(p)
        if (raw == 2) sideUntil[PANEL_RIGHT] = now + holdMs(p)
        val hudOn = p.getBoolean("bsHud", false)   // the poller also runs just for the floating windows
        // While a settings slider is being dragged the app stamps bsPreviewUntil: show the chosen
        // side even with the indicator off, so the new angle/size/tilt can be judged live.
        val preview = p.getLong("bsPreviewUntil", 0L) > now
        val panel = if (!hudOn) -1 else when (raw) { 1 -> PANEL_LEFT; 2 -> PANEL_RIGHT; else -> if (preview) (if (p.getInt("bsPreviewSide", PANEL_RIGHT) == PANEL_LEFT) PANEL_LEFT else PANEL_RIGHT) else -1 }
        // Everything lives in the left HUD region: the arrow only shows when the camera picture isn't covering it.
        rawNow = raw
        // The in-app arrow editor stamps bsArrPrevUntil: show that side's arrow (blinking) even with the indicator off.
        val prevArrow = p.getLong("bsArrPrevUntil", 0L) > now
        val arrowRaw = if (raw in 1..3) raw else if (prevArrow) (if (p.getInt("bsArrPrevSide", 1) == 1) 1 else 2) else 0
        lastArrowRaw = arrowRaw
        if (p.getBoolean("bsSignal", true) || prevArrow) main.post { updateArrow(arrowRaw) } else main.post(::removeArrow)
        if (panel >= 0) {
            offSince = 0
            main.post { show(panel) }
        } else {
            if (offSince == 0L) offSince = now
            // the in-app "Preview exit" button skips the hold so the animation starts right away
            val hold = if (now < p.getLong("bsNoHoldUntil", 0L) + 5000) 0L else holdMs(p).toLong()
            if (now - offSince >= hold) main.post(::removeView)
        }
        h.postDelayed(::poll, POLL_MS)
    }

    private var failStreak = 0
    private var speedAt = 0L
    private var speedFn: java.lang.reflect.Method? = null; private var speedMgr: Any? = null
    /** Car speed in m/s from the sensor manager, or null. */
    private fun readSpeed(): Float? = try {
        if (speedFn == null) { val c = carCache; speedMgr = c?.javaClass?.getMethod("getSensorManager")?.invoke(c); speedFn = speedMgr?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType) }
        (speedFn?.invoke(speedMgr, 0x00100100) as? Number)?.toFloat()?.takeIf { !it.isNaN() && it != 255f && it != -65535f }
    } catch (_: Throwable) { speedFn = null; null }
    private var gearFn: java.lang.reflect.Method? = null
    /** In D or R: the car is in use even when stopped (red light, queue), so the camera views must not count it as parked. */
    private fun inDriveOrReverse(): Boolean = try {
        if (gearFn == null) { val c = carCache; speedMgr = speedMgr ?: c?.javaClass?.getMethod("getSensorManager")?.invoke(c); gearFn = speedMgr?.javaClass?.getMethod("getSensorEvent", Int::class.javaPrimitiveType) }
        val g = (gearFn?.invoke(speedMgr, 0x00200200) as? Number)?.toInt()
        g == 0x00200220 || g == 0x00200240
    } catch (_: Throwable) { gearFn = null; false }
    private fun releaseCar() {
        val c = carCache ?: return
        carCache = null; speedFn = null; gearFn = null; speedMgr = null
        for (m in arrayOf("release", "disconnect", "destroy")) {
            try { c.javaClass.getMethod(m).invoke(c); break } catch (_: Throwable) {}
        }
    }

    private fun connect(): Boolean = try {
        // One Car connection for the whole process: calling create() again on every reconnect leaked a binder
        // connection to the car's service each time.
        val car = carCache ?: Class.forName(CAR_CLASS).getMethod("create", Context::class.java).invoke(null, ctx.applicationContext).also { carCache = it }
        function = car?.javaClass?.getMethod("getICarFunction")?.invoke(car)
        getFunctionValue = function?.javaClass?.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
        Logger.log(ctx, "blindspot: car api ${if (getFunctionValue != null) "connected" else "has no function manager"}")
        getFunctionValue != null
    } catch (e: Throwable) {
        Logger.log(ctx, "blindspot: car api unavailable (${e.javaClass.simpleName} ${e.message ?: ""})")
        false
    }

    // ---- HUD overlay (main thread) ----

    private fun findHud(): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        return on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } }
    }

    private fun show(panel: Int) {
        try { showImpl(panel) } catch (e: Throwable) { err("show failed", e); disabled = true; removeViewNow() }
    }

    private fun chosenType() = WIN_TYPES[winTypeIndex(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE))]

    private fun showImpl(panel: Int) {
        if (!running || disabled) return
        val v = view
        if (v != null) {
            leaving?.let { it.cancel(); leaving = null; ExitAnim.reset(v) }   // indicator came back mid-animation
            if (panel != shownPanel) { shownPanel = panel; activePanel = panel; relayout(v); renderer?.panel = panel }
            applyLive(v)
            return
        }
        if (Build.VERSION.SDK_INT < 30) return
        if (!Settings.canDrawOverlays(ctx)) { Logger.log(ctx, "blindspot: no overlay permission"); return }
        val hud = findHud() ?: run { Logger.log(ctx, "blindspot: HUD display not found/off"); return }
        val dctx = ctx.createDisplayContext(hud)
        val ovl = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var wtype = chosenType()
        var wctx = try { dctx.createWindowContext(wtype, null) }
            catch (e: Throwable) { err("createWindowContext type $wtype (using overlay)", e); wtype = ovl; try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx } }
        fun newTv(c: Context): TextureView = TextureView(c).also { tv ->   // no background: TextureView throws on one
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { bg?.post { openCamera(st, w, h) } }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { renderer?.let { it.boxW = w; it.boxH = h } }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // GL must let go of the surface before it dies (main thread); the camera + halLock
                // belong to the bg thread (ReentrantLock: only its owner can unlock), so close there.
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        } }
        var tv = newTv(wctx)
        shownPanel = panel; activePanel = panel
        try {
            try { wctx.getSystemService(WindowManager::class.java).addView(tv, params(hud, wtype)) }
            catch (e: Throwable) {
                if (wtype == ovl) throw e
                Logger.log(ctx, "blindspot: window type $wtype refused (${e.javaClass.simpleName} ${e.message ?: ""}), using overlay")
                try { wctx.getSystemService(WindowManager::class.java).removeViewImmediate(tv) } catch (_: Throwable) {}   // a refused addView can leave the view registered
                wtype = ovl; wctx = try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx }
                tv = newTv(wctx)   // the refused view can stay registered with the window manager, so the fallback gets a fresh one
                wctx.getSystemService(WindowManager::class.java).addView(tv, params(hud, ovl))
            }
            Logger.log(ctx, "blindspot: window type $wtype")
            view = tv
            HudLayer.added(p, "cam")   // whatever is meant to be in front of the picture goes back on top
            Logger.log(ctx, "blindspot: HUD on (display ${hud.displayId}, ${if (panel == PANEL_LEFT) "left" else "right"})")
        } catch (e: Throwable) {
            Logger.log(ctx, "blindspot: HUD addView failed: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    // Box (default square, each fisheye panel is ~1280x1285): "bsSize" % of the HUD height, width = height * "bsAspect" % (default 100),
    // vertically centred against the outer edge so it stays clear of the car's own centre graphics.
    private fun params(hud: Display, type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY): WindowManager.LayoutParams {
        val hs = Point().also { hud.getRealSize(it) }
        hudH = hs.y; hudW = hs.x
        return WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).also { fillCam(it) }
    }

    /** Size and place the camera box: free position when set in the app's HUD layout editor (bsPosX/bsPosY_L/_R, centre as % of the HUD), else the old edge-aligned default. */
    private fun fillCam(lp: WindowManager.LayoutParams) {
        val s = hudH * gi("bsSize", shownPanel).coerceIn(40, 100) / 100
        val w = if (hudW > 0) (s * gi("bsAspect", shownPanel).coerceIn(40, 250) / 100).coerceAtMost(hudW) else s
        lp.width = w; lp.height = s
        val sfx = if (shownPanel == PANEL_LEFT) "_L" else "_R"
        if (hudW > 0) {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = (hudW * p.getInt("bsPosX$sfx", 20) / 100 - w / 2).coerceIn(0, (hudW - w).coerceAtLeast(0))
            lp.y = (hudH * p.getInt("bsPosY$sfx", 36) / 100 - s / 2).coerceIn(0, (hudH - s).coerceAtLeast(0))
        } else { lp.gravity = Gravity.CENTER_VERTICAL or hGrav(shownPanel); lp.x = 0; lp.y = 0 }
    }

    // Re-read the settings every tick so slider changes show up on the HUD immediately.
    private fun applyLive(v: TextureView) {
        renderer?.let {
            it.fovDeg = gi("bsFov", shownPanel).coerceIn(50, 120).toFloat()
            it.tiltDeg = gi("bsTilt", shownPanel).coerceIn(0, 60).toFloat()
            it.yawDeg = rearYaw()
            it.rollDeg = gi("bsRoll", shownPanel).coerceIn(-45, 45).toFloat()
            it.mirror = gb("bsMirror", shownPanel)
            CamTune.applyLook(p, if (shownPanel == PANEL_LEFT) "L" else "R", it)
        }
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val ow = lp.width; val oh = lp.height; val og = lp.gravity; val ox = lp.x; val oy = lp.y
        fillCam(lp)
        if (lp.width > 0 && (lp.width != ow || lp.height != oh || lp.gravity != og || lp.x != ox || lp.y != oy)) {
            try { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    private fun relayout(v: TextureView) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        fillCam(lp)
        try { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    /** Blinking green arrow at the bottom of the camera picture (left region of the HUD), pointing the way of the blinker that is on. */
    private fun updateArrow(raw: Int) {
        if (!running || raw !in 1..3) { removeArrow(); return }
        if (raw == 3) { arrowOne(0, true); arrowOne(1, false) }   // hazard: both blinkers
        else { removeArrow(1); arrowOne(0, raw == 1) }
    }

    private fun arrowOne(slot: Int, left: Boolean) {
        var a = arrows[slot]
        if (a == null) {
            if (Build.VERSION.SDK_INT < 30 || !Settings.canDrawOverlays(ctx)) return
            val hud = findHud() ?: return
            val dctx = ctx.createDisplayContext(hud)
            val ovl = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            var at = chosenType()
            var c = try { dctx.createWindowContext(at, null) } catch (_: Throwable) { at = ovl; try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx } }
            val hs = Point().also { hud.getRealSize(it) }
            val h = hs.y
            var v = SignalArrow(c)
            try { c.getSystemService(WindowManager::class.java).addView(v, arrowParams(h, at)) }
            catch (e: Throwable) {
                if (at == ovl) { err("arrow addView", e); return }
                try { c.getSystemService(WindowManager::class.java).removeViewImmediate(v) } catch (_: Throwable) {}
                c = try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx }
                v = SignalArrow(c)
                try { c.getSystemService(WindowManager::class.java).addView(v, arrowParams(h, ovl)) } catch (e2: Throwable) { err("arrow addView", e2); return }
            }
            arrows[slot] = v; arrowH = h; arrowW = hs.x; a = v
            Logger.log(ctx, "blindspot: arrow on (${if (left) "left" else "right"})")
            HudLayer.added(p, "arr")
        }
        a.left = left
        a.icon = arrowGet(p, "bsArrIcon", left).coerceIn(0, ARROW_ICONS.size - 1)
        a.color = arrowGet(p, "bsArrColor", left)
        a.lit = (System.currentTimeMillis() / 400) % 2 == 0L
        a.invalidate()
        val lp = a.layoutParams as? WindowManager.LayoutParams ?: return
        // placement chosen in the app: centre at x/y % of the HUD, 2:1 box
        val hh = (arrowH * arrowGet(p, "bsArrSize", left).coerceIn(5, 40) / 100).coerceAtLeast(8)
        val ww = hh * arrowGet(p, "bsArrAsp", left).coerceIn(50, 500) / 100
        val x = (arrowW * arrowGet(p, "bsArrX", left).coerceIn(0, 100) / 100 - ww / 2).coerceIn(0, (arrowW - ww).coerceAtLeast(0))
        val y = (arrowH * arrowGet(p, "bsArrY", left).coerceIn(0, 100) / 100 - hh / 2).coerceIn(0, (arrowH - hh).coerceAtLeast(0))
        if (lp.x != x || lp.y != y || lp.width != ww || lp.height != hh || lp.gravity != (Gravity.TOP or Gravity.LEFT)) {
            lp.x = x; lp.y = y; lp.width = ww; lp.height = hh; lp.gravity = Gravity.TOP or Gravity.LEFT
            try { a.context.getSystemService(WindowManager::class.java).updateViewLayout(a, lp) } catch (_: Throwable) {}
        }
    }

    private fun arrowParams(hudH: Int, type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) = WindowManager.LayoutParams(hudH * 30 / 100, hudH * 15 / 100,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
    }

    private fun removeArrow(from: Int = 0) {
        for (i in from until arrows.size) removeArrowSlot(i)
    }

    private fun removeArrowSlot(i: Int) {
        val a = arrows[i] ?: return
        arrows[i] = null
        try { a.context.getSystemService(WindowManager::class.java).removeView(a) } catch (_: Throwable) {}
    }

    private var leaving: android.animation.ValueAnimator? = null

    /** Takes the HUD picture away with the exit animation chosen in the app (Cut = at once). */
    private fun removeView() {
        val v = view ?: return
        if (leaving != null) return
        val kind = ExitAnim.kind(p)
        if (kind == 0 || v.width == 0) { removeViewNow(); return }
        leaving = ExitAnim.run(v, kind, ExitAnim.ms(p)) { leaving = null; removeViewNow() }
    }

    private fun removeViewNow() {
        leaving?.cancel(); leaving = null
        val v = view ?: return
        view = null; shownPanel = -1; activePanel = -1
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
        Logger.log(ctx, "blindspot: HUD off")
    }

    // ---- camera (shared via CamHub) ----

    private fun openCamera(st: SurfaceTexture, w: Int, h: Int) {
        if (!running || renderer != null) return
        val r = PanelOut(st); r.boxW = w; r.boxH = h
        r.panel = shownPanel.coerceAtLeast(0)
        r.fovDeg = gi("bsFov", shownPanel).coerceIn(50, 120).toFloat()
        r.tiltDeg = gi("bsTilt", shownPanel).coerceIn(0, 60).toFloat()
        r.yawDeg = rearYaw()
        r.rollDeg = gi("bsRoll", shownPanel).coerceIn(-45, 45).toFloat()
        r.mirror = gb("bsMirror", shownPanel)
        CamTune.applyLook(p, if (shownPanel == PANEL_LEFT) "L" else "R", r)
        renderer = r
        CamHub.add(ctx, HUB_KEY, r)
    }

    // Unhook from the shared GL/camera before the surface dies.
    private fun closeCamera() {
        val r = renderer ?: return
        renderer = null
        CamHub.remove(HUB_KEY, r)
    }
}

/** Turn-signal arrow (blinks: the owner toggles [lit]). Shown on the HUD and, via [draw], in the app's placement editor. */
class SignalArrow(c: Context) : View(c) {
    var left = true
    var lit = true
    var icon = 0
    var color = BlindSpotHud.ARROW_GREEN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        if (!lit) return
        draw(cv, width.toFloat(), height.toFloat(), left, icon, color, paint)
    }
    companion object {
        /** Draws icon [icon] into a w x h box (meant to be 2:1), pointing right and mirrored for [left]. */
        fun draw(cv: Canvas, w: Float, h: Float, left: Boolean, icon: Int, color: Int, paint: Paint) {
            paint.color = color; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
            val m = h * 0.08f; val cx = w / 2; val cy = h / 2
            if (left) { cv.save(); cv.scale(-1f, 1f, cx, cy) }
            fun chevron(ox: Float) {
                val p = Path()
                p.moveTo(cx + ox - h * 0.2f, m + h * 0.1f); p.lineTo(cx + ox + h * 0.3f, cy); p.lineTo(cx + ox - h * 0.2f, h - m - h * 0.1f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = h * 0.2f
                cv.drawPath(p, paint)
            }
            when (icon) {
                1 -> chevron(0f)
                2 -> {
                    val p = Path(); p.moveTo(cx - h * 0.4f, m); p.lineTo(cx + h * 0.45f, cy); p.lineTo(cx - h * 0.4f, h - m); p.close()
                    paint.style = Paint.Style.FILL; cv.drawPath(p, paint)
                }
                3 -> { chevron(-h * 0.25f); chevron(h * 0.25f) }
                4 -> {
                    val x0 = m; val x1 = w - m; val hd = h * 0.4f
                    val p = Path(); p.moveTo(x0, cy); p.lineTo(x1, cy); p.moveTo(x1 - hd, cy - hd); p.lineTo(x1, cy); p.lineTo(x1 - hd, cy + hd)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = h * 0.14f
                    cv.drawPath(p, paint)
                }
                5 -> {   // fat arrow: thick shaft + full-height head
                    val x0 = m; val x1 = w - m; val hx = x1 - (x1 - x0) * 0.5f; val sy = h * 0.6f   // head = half of the icon width
                    val p = Path()
                    p.moveTo(x0, cy - sy * 0.5f); p.lineTo(hx, cy - sy * 0.5f); p.lineTo(hx, m)
                    p.lineTo(x1, cy); p.lineTo(hx, h - m); p.lineTo(hx, cy + sy * 0.5f); p.lineTo(x0, cy + sy * 0.5f); p.close()
                    paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = h * 0.06f; cv.drawPath(p, paint)
                }
                else -> {   // block arrow (shaft + head)
                    val head = h - 2 * m; val sy = h * 0.32f
                    val x0 = m; val x1 = w - m; val hx = x1 - head * 0.8f
                    val p = Path()
                    p.moveTo(x0, cy - sy * 0.5f); p.lineTo(hx, cy - sy * 0.5f); p.lineTo(hx, m)
                    p.lineTo(x1, cy); p.lineTo(hx, h - m); p.lineTo(hx, cy + sy * 0.5f); p.lineTo(x0, cy + sy * 0.5f); p.close()
                    paint.style = Paint.Style.FILL; cv.drawPath(p, paint)
                }
            }
            if (left) cv.restore()
        }
    }
}
