package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floating always-on-top camera windows on the centre screen: the left and right blind-spot views and the rear/front
 * views, each switched on in the app ("flLeft" / "flRight" / "flRear"). Drag the top bar to move; resize any window to any
 * rectangle with the bottom-right corner (both ways), the pills on the left/right/bottom edges (one way) or a pinch (keeps the
 * shape). Position and size are remembered, separately from the HUD picture's size.
 * Pictures come from the shared surround camera through [CamHub]. Owned by CamService.
 */
class FloatCam(private val ctx: Context) {
    private class Win(val key: String, val panel: Int, val cam: String) {
        var root: FrameLayout? = null
        var hidden = false          // collapsed to a bubble by the Hide button
        var lp: WindowManager.LayoutParams? = null
        @Volatile var renderer: PanelOut? = null
        var fadeIn = false          // a brand-new window (not a rebuild): start transparent and fade in
        var shape = 0               // corner/fade setting this window was built with
        var hdr = 0                 // header height (dp) this window was built with
        var bar: TextView? = null
        // blinker takeover (left/right): shown in front, optionally centred, then put back as it was
        var blink = false; var front = false; var preHidden = false
        var savedX = 0; var savedY = 0; var savedW = 0; var savedH = 0; var saved = false
        var ovW = 0; var ovH = 0   // size to build the window with (a blinker-enlarged window is rebuilt at that size, never resized live)
        var sizeStamp = 0L          // last flSizeStamp applied to this window
        var leaving = false         // shrinking into the floating button
        @Volatile var keepSurface = false   // re-stacking: the picture surface must survive the view being re-added
    }

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val leftW = Win("flLeft", 2, "L")
    private val rightW = Win("flRight", 3, "R")
    private val rearW = Win("flRear", 1, "B")
    private val frontW = Win("flFront", 0, "F")
    private val all = listOf(leftW, rightW, rearW, frontW)
    init { for (w in all) w.hidden = p.getBoolean(w.key + "Min", false) }   // who was minimized survives an app restart
    private val wm get() = ctx.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private fun dp(x: Int) = (x * d).toInt()

    /** Adds/removes the windows to match the prefs (any thread). */
    fun sync() { main.removeCallbacks(tick); main.post(tick) }

    fun stop() { main.removeCallbacks(tick); bgScrim?.let { try { wm.removeView(it) } catch (_: Throwable) {} }; bgScrim = null; FloatMaster.yieldToBlink = false; main.post { for (w in all) { hide(w); hideBubble(w) } } }

    // Left/right can be set to appear only while their blinker is on ("flOnly_L"/"flOnly_R"); the window (and its camera stream) is removed otherwise.
    private fun wanted(w: Win) = p.getBoolean(w.key, false) && !FloatMaster.hidden && !Park.parked(ctx) &&
        (w === rearW || w === frontW || !p.getBoolean(if (w === leftW) "flOnly_L" else "flOnly_R", false) || BlindSpotHud.indicating(w.panel))

    // Every camera is tuned in the app's Live view section (left/right share those values with the HUD).
    private fun tune(w: Win, r: PanelOut) { CamTune.apply(p, w.cam, r); r.feather = fadePct() / 100f }
    // Left/right windows that only show with their blinker use the configured animation (style + speed); everything else keeps the plain fade.
    private fun blinkOnly(w: Win) = (w === leftW || w === rightW) && p.getBoolean(if (w === leftW) "flOnly_L" else "flOnly_R", false)
    private fun animStyle(w: Win) = if (blinkOnly(w)) (p.getString("flAnim", "fade") ?: "fade") else "fade"
    private fun animMs(w: Win) = if (blinkOnly(w)) p.getInt("flAnimMs", 320).toLong().coerceIn(80, 1000) else 320L
    private fun animDir(w: Win) = if (w === rightW) 1 else -1
    private fun fadePct() = p.getInt("flFade", 0).coerceIn(0, 30)
    private fun cornerDp() = p.getInt("flCorner", 0).coerceIn(0, 40)
    private fun shapeSig() = cornerDp() * 100 + fadePct()

    @SuppressLint("ClickableViewAccessibility")
    private fun show(w: Win) {
        if (w.root != null) return
        if (!Settings.canDrawOverlays(ctx)) { Logger.log(ctx, "floatcam: no overlay permission"); return }
        val dm = ctx.resources.displayMetrics
        val maxSide = minOf(dm.widthPixels, dm.heightPixels) - dp(20)
        val hdr = headerDp(); w.hdr = hdr; w.shape = shapeSig()
        val btn = dp(hdr) - dp(10)   // buttons sit inside the header with a 5dp margin all round
        val maxW = dm.widthPixels - dp(20); val maxH = dm.heightPixels - dp(20)
        var side = p.getInt("${w.key}Size", dp(320)).coerceIn(dp(160), maxSide)
        // Rear/front default to a wide strip, like a real mirror.
        // Left/right start from their old square size; any window can be any rectangle (the picture keeps its proportions, a wide window crops top/bottom).
        val wide = w === rearW || w === frontW
        var winW = (if (w.ovW > 0) w.ovW else p.getInt("${w.key}W", if (wide) dp(520) else side)).coerceIn(dp(160), maxW)
        var winH = (if (w.ovH > 0) w.ovH else p.getInt("${w.key}H", if (wide) dp(200) else side)).coerceIn(dp(160), maxH)
        val lp = WindowManager.LayoutParams(winW, winH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = p.getInt("${w.key}X", dp(24) + (if (w === leftW) 0 else if (w === rightW) 1 else if (w === rearW) 2 else 3) * (side + dp(12))); y = p.getInt("${w.key}Y", dp(120))
        }
        fun rounded(fill: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
            setColor(fill); cornerRadius = r * d; if (stroke != null) setStroke(dp(1), stroke)
        }
        val tv = TextureView(ctx)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, tw: Int, th: Int) { attach(w, st, tw, th) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, tw: Int, th: Int) { w.renderer?.let { it.boxW = tw; it.boxH = th } }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (w.keepSurface) return false   // just being brought to the front: keep the same surface, nothing to rebuild
                if (w.renderer?.st === st) detach(w)   // not when a rebuilt window already has a newer surface (its old one dies late)
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // Top bar: drag to move.
        val bar = TextView(ctx).apply {
            w.bar = this
            text = label(w); setTextColor(0xFFF5F5F7.toInt()); textSize = (hdr * 0.38f).coerceIn(13f, 24f)
            typeface = ZFont.bold; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, 0, 0)
            // the same window glyph the Z Cam floating control shows for this camera, in front of the name (sized from the header, header itself unchanged)
            val isz = (dp(hdr) * 0.5f).toInt()
            setCompoundDrawablesRelative(CamGlyph(if (w === leftW) "flLeft" else if (w === rightW) "flRight" else if (w === rearW) "flRear" else "flFront", d).also { it.setBounds(0, 0, isz, isz) }, null, null, null)
            compoundDrawablePadding = dp(8)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000))   // black at the top fading out into the picture
            setShadowLayer(5f * d, 0f, 0f, 0xFF000000.toInt())
            var dx = 0f; var dy = 0f; var ox = 0; var oy = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (ox + (e.rawX - dx).toInt()).coerceIn(0, dm.widthPixels - dp(40))
                        lp.y = (oy + (e.rawY - dy).toInt()).coerceIn(0, dm.heightPixels - dp(40))
                        try { wm.updateViewLayout(w.root, lp) } catch (_: Throwable) {}
                        FloatPush.moved(w.key, dm.widthPixels, dm.heightPixels)
                    }
                    MotionEvent.ACTION_UP -> { p.edit().putInt("${w.key}X", lp.x).putInt("${w.key}Y", lp.y).apply() }
                }
                true
            }
        }
        // Fine line around each corner (following the corner radius set in settings): drag it to resize from that corner.
        val cr = cornerDp()
        val cz = CornerLine.zoneDp(cr)
        fun corner(l: Boolean, r: Boolean, t: Boolean, bt: Boolean): View {
            var w0 = 0; var h0 = 0; var x0 = 0; var y0 = 0
            return CornerLine(ctx, cr, l, t, { w0 = winW; h0 = winH; x0 = lp.x; y0 = lp.y }, { ddx, ddy ->
                var nw = if (r) w0 + ddx.toInt() else w0 - ddx.toInt()
                var nh = if (bt) h0 + ddy.toInt() else h0 - ddy.toInt()
                nw = nw.coerceIn(dp(160), maxW); nh = nh.coerceIn(dp(160), maxH)
                var nx = if (l) x0 + w0 - nw else x0; var ny = if (t) y0 + h0 - nh else y0
                if (nx < 0) { nw += nx; nx = 0 }; if (ny < 0) { nh += ny; ny = 0 }
                if (!FloatPush.hits(w.key, nx, ny, nw, nh)) {   // bumping into another window: stop growing
                    winW = nw; winH = nh; lp.width = nw; lp.height = nh; lp.x = nx; lp.y = ny
                    try { wm.updateViewLayout(w.root, lp) } catch (_: Throwable) {}
                }
            }, { p.edit().putInt("${w.key}X", lp.x).putInt("${w.key}Y", lp.y).putInt("${w.key}W", winW).putInt("${w.key}H", winH).apply() })
        }
        val cTL = corner(true, false, true, false); val cTR = corner(false, true, true, false); val cBL = corner(true, false, false, true); val cBR = corner(false, true, false, true)
        listOf(cTL, cTR, cBL, cBR).forEach { it.visibility = View.GONE }   // only shown while the pencil is on
        var spaceButtons: (Boolean) -> Unit = {}
        val editBtn = IconButton(ctx, IconButton.Kind.EDIT, halo = true).apply {
            setOnClickListener { on = !on; val v = if (on) View.VISIBLE else View.GONE; listOf(cTL, cTR, cBL, cBR).forEach { it.visibility = v }; spaceButtons(on) }
        }
        val hideBtn = IconButton(ctx, IconButton.Kind.HIDE, halo = true).apply {
            setOnClickListener { w.hidden = true; main.removeCallbacks(tick); main.post(tick) }
        }
        val box = FrameLayout(ctx).apply {
            if (fadePct() > 0) setBackgroundColor(0)   // soft edge: no frame, the picture itself fades out
            else { background = rounded(0xFF000000.toInt(), cornerDp()); outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true }
            // Pinch anywhere on the picture to resize (about its centre); a plain tap still raises it.
            var pinched = false
            val pinch = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
                    val f = g.scaleFactor
                    val nw = (winW * f).toInt().coerceIn(dp(160), maxW)
                    val nh = (winH * f).toInt().coerceIn(dp(160), maxH)
                    val nx = (lp.x - (nw - winW) / 2).coerceIn(0, (dm.widthPixels - nw).coerceAtLeast(0))
                    val ny = (lp.y - (nh - winH) / 2).coerceIn(0, (dm.heightPixels - nh).coerceAtLeast(0))
                    if (nw > winW || nh > winH) if (FloatPush.hits(w.key, nx, ny, nw, nh)) return true   // bumping into another window: no bigger
                    lp.x = nx; lp.y = ny
                    winW = nw; winH = nh; lp.width = nw; lp.height = nh
                    try { wm.updateViewLayout(w.root, lp) } catch (_: Throwable) {}
                    return true
                }
                override fun onScaleEnd(g: android.view.ScaleGestureDetector) {
                    p.edit().putInt("${w.key}X", lp.x).putInt("${w.key}Y", lp.y).putInt("${w.key}W", winW).putInt("${w.key}H", winH).apply()
                }
            })
            // One finger anywhere on the picture drags the window; a tap raises it.
            var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false
            val slop = dp(8)
            setOnTouchListener { _, e ->
                pinch.onTouchEvent(e)
                if (e.pointerCount > 1 || pinch.isInProgress) pinched = true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y; moved = false; pinched = false }
                    MotionEvent.ACTION_MOVE -> if (!pinched && e.pointerCount == 1) {
                        if (Math.abs(e.rawX - dx) > slop || Math.abs(e.rawY - dy) > slop) moved = true
                        if (moved) {
                            lp.x = (ox + (e.rawX - dx).toInt()).coerceIn(0, dm.widthPixels - dp(40))
                            lp.y = (oy + (e.rawY - dy).toInt()).coerceIn(0, dm.heightPixels - dp(40))
                            try { wm.updateViewLayout(w.root, lp) } catch (_: Throwable) {}
                            FloatPush.moved(w.key, dm.widthPixels, dm.heightPixels)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) p.edit().putInt("${w.key}X", lp.x).putInt("${w.key}Y", lp.y).apply()
                        if (!pinched && !moved && FloatPush.overlapsOther(w.key)) raise(w, true)   // rebuilt, not re-added: a re-added window came back transparent
                        pinched = false; moved = false
                    }
                }
                true
            }
            addView(tv, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, dp(hdr), Gravity.TOP))
            addView(hideBtn, FrameLayout.LayoutParams(btn, btn, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(8), 0) })
            addView(editBtn, FrameLayout.LayoutParams(btn, btn, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(8) + btn + dp(2), 0) })
            spaceButtons = { on -> val m = if (on) dp(cz) + dp(2) else dp(8); (hideBtn.layoutParams as FrameLayout.LayoutParams).marginEnd = m; (editBtn.layoutParams as FrameLayout.LayoutParams).marginEnd = m + btn + dp(2); hideBtn.requestLayout(); editBtn.requestLayout() }
            addView(cTL, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.TOP or Gravity.START)); addView(cTR, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.TOP or Gravity.END))
            addView(cBL, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.BOTTOM or Gravity.START)); addView(cBR, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.BOTTOM or Gravity.END))
        }
        try {
            lp.alpha = if (w.fadeIn) 0f else FloatAnim.startAlpha()
            wm.addView(box, lp)
            w.root = box; w.lp = lp
            main.post { OverlayTop.raise(ctx) }   // the dock and the floating button always sit above every window
            FloatPush.register(w.key, FloatPush.Item(lp, { w.root }, wm) { x, y -> p.edit().putInt("${w.key}X", x).putInt("${w.key}Y", y).apply() })
            Logger.log(ctx, "floatcam: ${w.key} on")
        } catch (e: Throwable) { Logger.log(ctx, "floatcam: ${w.key} addView failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    private fun attach(w: Win, st: SurfaceTexture, tw: Int, th: Int) {
        if (w.renderer != null) return
        val r = PanelOut(st).also { w.renderer = it; it.boxW = tw; it.boxH = th }
        tune(w, r)
        CamHub.add(ctx, w.key, r)
    }

    // Unhook from the shared GL/camera before the surface dies.
    private fun detach(w: Win) {
        val r = w.renderer ?: return
        w.renderer = null
        CamHub.remove(w.key, r)
    }

    // Shows/hides windows and picks up tuning slider changes.
    private var minSeen = FloatMaster.minimizeSeq
    private var webSeen = FloatMaster.webAddedSeq
    private var restoreSeen = FloatMaster.restoreSeq
    init { FloatMaster.camUp = { all.any { it.root != null && !it.leaving } } }
    private var lastPark: String? = null
    private val tick = object : Runnable {
        override fun run() {
            // a failure in one pass is logged and retried, it must not take the whole app down
            try { step() } catch (e: Throwable) { Logger.log(ctx, "floatcam: tick failed ${e.javaClass.simpleName} ${e.message ?: ""} @ ${e.stackTrace.firstOrNull() ?: ""}"); main.postDelayed(this, 500) }
        }
        private fun step() {
            val pr = Park.reason(ctx)
            if (pr != lastPark) {
                lastPark = pr
                if (all.any { p.getBoolean(it.key, false) }) Logger.log(ctx, if (pr != null) "floatcam: windows released the camera (" + pr + ")" else "floatcam: windows back on the camera")
            }
            val minimizeAll = minSeen != FloatMaster.minimizeSeq
            minSeen = FloatMaster.minimizeSeq
            val restoreAll = restoreSeen != FloatMaster.restoreSeq
            restoreSeen = FloatMaster.restoreSeq
            val webAdded = webSeen != FloatMaster.webAddedSeq
            webSeen = FloatMaster.webAddedSeq
            val stampNow = p.getLong("flSizeStamp", 0L)
            for (w in all) {
                if (w.leaving) continue
                val want = wanted(w)
                val r0 = w.root; val l0 = w.lp
                if (!want && FloatMaster.hidden && r0 != null && l0 != null) {
                    w.leaving = true; hideBubble(w)
                    FloatAnim.out(wm, r0, l0) { w.leaving = false; hide(w) }
                    continue
                }
                if (restoreAll && want) w.hidden = false
                if (minimizeAll && want) w.hidden = true   // double-tap on the floating button: collapse everything to the top dock
                if (!want && !FloatMaster.hidden && !Park.parked(ctx)) w.hidden = false   // blinker off / switched off: next time it comes back expanded (hide-all on the floating button and a parked / just-started app keep who was minimized)
                // Blinker on: this side's window comes to the front (expanded, optionally centred); when it stops it goes back where it was.
                val ind = want && (w === leftW || w === rightW) && BlindSpotHud.indicating(w.panel)
                if (ind && !w.blink) { w.blink = true; w.front = true; w.preHidden = w.hidden }
                if (ind) w.hidden = false
                if (!ind && w.blink) {
                    w.blink = false; w.front = false
                    var rebuild = false
                    w.lp?.let { l -> if (w.root != null) { l.x = w.savedX; l.y = w.savedY; if (w.saved && w.savedW > 0 && (l.width != w.savedW || l.height != w.savedH)) { l.width = w.savedW; l.height = w.savedH; rebuild = true }; if (!rebuild) try { wm.updateViewLayout(w.root, l) } catch (_: Throwable) {} } }
                    w.saved = false; w.savedW = 0
                    // shrinking back: a fresh window at the small size (the camera surface is re-made) instead of resizing the live one in one jump
                    if (rebuild && w.root != null) raise(w, true)
                    w.hidden = w.preHidden
                }
                if (want && !w.hidden) {
                    hideBubble(w)
                    val had = w.root != null
                    w.fadeIn = !had
                    show(w)
                    w.fadeIn = false
                    if (w.blink && (!had || webAdded)) w.front = true   // the blinker side always ends up on top (windows re-added by the floating button, or the web window added after)
                    val r1 = w.root; val l1 = w.lp
                    if (!had && r1 != null && l1 != null && l1.alpha < 1f) { if (FloatAnim.revealing() && !w.blink) FloatAnim.genie(wm, r1, l1, true) {} else FloatAnim.run(wm, r1, l1, animStyle(w), animMs(w), true, animDir(w)) {} }
                } else {
                    val rr = w.root; val ll = w.lp
                    if (rr != null && ll != null && !want && blinkOnly(w) && !FloatMaster.hidden) {
                        w.leaving = true; hideBubble(w)   // blinker went off: animate out, then remove
                        FloatAnim.genie(wm, rr, ll, false) { w.leaving = false; hide(w) }   // same exit as minimize (flMinAnim / flMinMs)
                        continue
                    }
                    if (rr != null && ll != null && want && w.hidden && !w.blink) {
                        w.leaving = true   // minimized: shrink into the dock, then the dock chip takes over
                        FloatAnim.genie(wm, rr, ll, false) { w.leaving = false; hide(w); showBubble(w) }
                        continue
                    }
                    hide(w); if (want) showBubble(w) else hideBubble(w) }
                if (w.root != null && (w.hdr != headerDp() || w.shape != shapeSig())) { hide(w); show(w) }   // header height slider changed
                // size changed from the app's adjust pop-up (it stamps flSizeStamp): resize the open window to the saved width and height
                if (w.root != null && w.sizeStamp != p.getLong("flSizeStamp", 0L)) w.lp?.let { l ->
                    w.sizeStamp = p.getLong("flSizeStamp", 0L)
                    val dm = ctx.resources.displayMetrics
                    val nw = p.getInt("${w.key}W", l.width).coerceIn(dp(160), dm.widthPixels - dp(20)); val nh = p.getInt("${w.key}H", l.height).coerceIn(dp(160), dm.heightPixels - dp(20))
                    if (nw != l.width || nh != l.height) { l.width = nw; l.height = nh; try { wm.updateViewLayout(w.root, l) } catch (_: Throwable) {} }
                }
                if (w.blink && w.front && w.root != null) {
                    w.lp?.let { l ->
                        if (!w.saved) { w.saved = true; w.savedX = l.x; w.savedY = l.y; w.savedW = l.width; w.savedH = l.height }   // once per blinker, not again when the window is re-fronted
                        val pct = p.getInt("flBlinkSize", 0)
                        if (pct > 0) {
                            // small window while idle, big while the blinker is on: same shape, grown about its own centre
                            val dm = ctx.resources.displayMetrics
                            val ratio = l.width.toFloat() / l.height.coerceAtLeast(1)
                            var nh = (dm.heightPixels * pct / 100f).toInt(); var nw = (nh * ratio).toInt()
                            if (nw > dm.widthPixels - dp(20)) { nw = dm.widthPixels - dp(20); nh = (nw / ratio).toInt() }
                            if (nw > l.width) {
                                val cx = l.x + l.width / 2; val cy = l.y + l.height / 2
                                l.width = nw; l.height = nh
                                l.x = (cx - nw / 2).coerceIn(0, (dm.widthPixels - nw).coerceAtLeast(0)); l.y = (cy - nh / 2).coerceIn(0, (dm.heightPixels - nh).coerceAtLeast(0))   // applied by the rebuild below
                            }
                        }
                        if (p.getBoolean("flBlinkCenter", false)) {
                            val dm = ctx.resources.displayMetrics
                            l.x = ((dm.widthPixels - l.width) / 2).coerceAtLeast(0); l.y = ((dm.heightPixels - l.height) / 2).coerceAtLeast(0)
                            if (p.getInt("flBlinkSize", 0) <= 0) try { wm.updateViewLayout(w.root, l) } catch (_: Throwable) {}
                            showScrim()   // before raise(): the window is re-added on top of it
                        }
                    }
                    raise(w, true); w.front = false
                }
                w.renderer?.let { tune(w, it) }
                if (!w.blink && p.getBoolean(w.key + "Min", false) != w.hidden) p.edit().putBoolean(w.key + "Min", w.hidden).apply()
                // blinker on: the name turns green in the header (left/right windows); the camera icon already says which side
                w.bar?.let { b ->
                    val on = (w === leftW || w === rightW) && BlindSpotHud.indicating(w.panel)
                    val c = if (on) 0xFFA3E635.toInt() else 0xFFF5F5F7.toInt()
                    if (b.currentTextColor != c) b.setTextColor(c)
                }
            }
            syncBgScrim()
            // a blinker window centred on screen outranks the floating button and the dock
            val yieldNow = p.getBoolean("flBlinkCenter", false) && all.any { it.blink && it.root != null }
            if (!yieldNow) hideScrim()
            if (yieldNow != FloatMaster.yieldToBlink) { FloatMaster.yieldToBlink = yieldNow; if (!yieldNow) OverlayTop.raise(ctx) }
            if (all.any { p.getBoolean(it.key, false) }) main.postDelayed(this, 250)
        }
    }

    // Centred blinker window: a translucent black layer over the whole screen, behind the window, so the eye goes to the picture.
    private var scrim: View? = null
    private fun showScrim() {
        if (scrim != null) return
        val sl = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
        try {
            scrim = View(ctx).apply { setBackgroundColor(0xE6000000.toInt()) }
            wm.addView(scrim, sl)
        } catch (_: Throwable) { scrim = null }
    }
    // Optional near-black backdrop (99%) behind every floating window so nothing behind them distracts.
    private var bgScrim: View? = null
    private var bgScrimColor = 0
    private var emptySince = 0L
    private fun syncBgScrim() {
        val open = all.filter { it.root != null }
        if (open.isNotEmpty()) emptySince = 0L else if (emptySince == 0L) emptySince = System.currentTimeMillis()
        // a window gone for a moment (camera hand-over, rebuild) must not drop and re-add the backdrop: that cycles the web window above it
        // a capture lets the windows go for ~6 s: keep the backdrop through it, or it is re-added after and every window (cams, web, trip) re-stacks
        if (!p.getBoolean("flBlackBg", false) || (open.isEmpty() && (FloatMaster.hidden || all.any { it.hidden } || System.currentTimeMillis() - emptySince > (if (Park.yieldCam) 15_000L else 3000L)))) {
            bgScrim?.let { try { wm.removeView(it) } catch (_: Throwable) {} }; bgScrim = null; return
        }
        val c = 0xFC000000.toInt()   // 99% black
        if (open.isEmpty()) return
        bgScrim?.let { if (c != bgScrimColor) { bgScrimColor = c; it.setBackgroundColor(c) }; return }
        val sl = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
        try {
            bgScrimColor = c
            val v = View(ctx).apply { setBackgroundColor(c) }
            wm.addView(v, sl); bgScrim = v
            // windows already open sit below it: re-add them on top (surfaces are kept, pictures keep running)
            for (w in open) { val r = w.root ?: continue; val lp = w.lp ?: continue
                w.keepSurface = true
                try { wm.removeViewImmediate(r); wm.addView(r, lp) } catch (_: Throwable) {}
                main.post { w.keepSurface = false } }
            WebHud.instance?.restack()   // the web window sits above the backdrop too
            TripWidget.instance?.restack()   // and the trip widget
            main.post { OverlayTop.raise(ctx) }
        } catch (_: Throwable) { bgScrim = null }
    }
    private fun hideScrim() {
        val v = scrim ?: return
        scrim = null
        try { wm.removeView(v) } catch (_: Throwable) {}
    }

    private fun headerDp() = p.getInt("flHeader", 56).coerceIn(36, 96)

    private fun label(w: Win) = if (w === leftW) "Left" else if (w === rightW) "Right" else if (w === rearW) "Rear" else "Front"

    private fun showBubble(w: Win): Unit =
        BubbleRow.add(ctx, w.key, label(w), if (w === leftW) IconButton.Kind.CAM_L else if (w === rightW) IconButton.Kind.CAM_R else if (w === rearW) IconButton.Kind.CAM_REAR else IconButton.Kind.CAM_FRONT) { FloatMaster.shownAt = android.os.SystemClock.uptimeMillis(); w.hidden = false; main.removeCallbacks(tick); main.post(tick) }

    private fun hideBubble(w: Win): Unit = BubbleRow.remove(ctx, w.key)

    // Tapping a window that overlaps another brings it to the front: overlay windows only stack by add order, so re-add the same
    // view. Its SurfaceTexture is kept, so the camera picture keeps running (rebuilding the window made it go black).
    private fun raise(w: Win, force: Boolean = false) {
        val r = w.root ?: return; val lp = w.lp ?: return
        if (!force && !FloatPush.overlapsOther(w.key)) return
        if (force) {
            // Blinker took a window that was already on screen: re-adding the same view left its picture black (a window shown fresh,
            // e.g. after being minimized, works). So rebuild it from scratch, keeping the position it was just given, and leave the scrim alone.
            val x = lp.x; val y = lp.y; val ww = lp.width; val hh = lp.height   // size too: a blinker-enlarged window must stay enlarged
            w.root = null; w.bar = null
            FloatPush.unregister(w.key)
            try { wm.removeView(r) } catch (_: Throwable) {}
            detach(w)
            w.ovW = ww; w.ovH = hh
            show(w)
            w.ovW = 0; w.ovH = 0
            w.lp?.let { l -> l.x = x; l.y = y; l.width = ww; l.height = hh; l.alpha = 1f; try { wm.updateViewLayout(w.root, l) } catch (_: Throwable) {} }
            Logger.log(ctx, "floatcam: ${w.key} rebuilt for the blinker")
            main.post { OverlayTop.raise(ctx) }
            return
        }
        w.keepSurface = true
        try { wm.removeViewImmediate(r); wm.addView(r, lp); Logger.log(ctx, "floatcam: ${w.key} raised") }
        catch (e: Throwable) { Logger.log(ctx, "floatcam: raise failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
        main.post { w.keepSurface = false; OverlayTop.raise(ctx) }
    }

    private fun hide(w: Win) {
        val r = w.root ?: return
        w.root = null; w.bar = null
        FloatPush.unregister(w.key)
        if (w.blink) hideScrim()
        try { wm.removeView(r) } catch (_: Throwable) {}
        detach(w)
    }
}

/** The floating control's camera glyphs (a window with an arrow: left / right / rear down / front up), drawn white for the window header. */
private class CamGlyph(private val kind: String, private val d: Float) : android.graphics.drawable.Drawable() {
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = 0xFFF5F5F7.toInt() }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL; color = 0xFFF5F5F7.toInt() }
    override fun draw(cv: android.graphics.Canvas) {
        val b = bounds; val h = b.width() / 2f; val cx = b.exactCenterX(); val cy = b.exactCenterY()
        line.strokeWidth = (b.width() * 0.09f).coerceAtLeast(1.5f * d)
        cv.drawRoundRect(cx - h, cy - h * 0.8f, cx + h, cy + h * 0.8f, 3 * d, 3 * d, line)
        val q = android.graphics.Path()
        if (kind == "flLeft" || kind == "flRight") {
            val s = if (kind == "flLeft") 1f else -1f   // tip points the way of the camera (left window = arrow to the left)
            q.moveTo(cx + s * h * 0.35f, cy - h * 0.4f); q.lineTo(cx - s * h * 0.35f, cy); q.lineTo(cx + s * h * 0.35f, cy + h * 0.4f); q.close()
        } else {
            val s = if (kind == "flFront") -1f else 1f
            q.moveTo(cx - h * 0.4f, cy - s * h * 0.25f); q.lineTo(cx + h * 0.4f, cy - s * h * 0.25f); q.lineTo(cx, cy + s * h * 0.35f); q.close()
        }
        cv.drawPath(q, fill)
    }
    override fun setAlpha(a: Int) { line.alpha = a; fill.alpha = a }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { line.colorFilter = cf; fill.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
