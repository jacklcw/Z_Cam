package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Trip widget: a floating, non-touch driving-statistics window for the centre screen. The look is assets/tripwidget.html
 * (rendered in a transparent WebView, same page as docs/zcam-trip-widget-mockup.html); this class feeds it.
 *  - car sensors every 100 ms: speed, throttle, brake depth; gear, drive mode and regen state every 500 ms
 *  - relay /cam/vehicle every 20 s: HV battery %, range, 12 V battery (cloud-only values)
 * A real floating window, like the camera windows: drag it anywhere, pinch or use the corner grip to resize it, the hide button (or the
 * floating button) minimizes it into the dock, and it shoves other windows aside ([FloatPush]). Position and width are remembered.
 * Prefs (all "tw*", see [cfgJson]): twOn, twOrient, twDetail, twChart, twFont, twTheme, twUnit, twAcc, twBg, twFade, twOpacity, twPack, twReset,
 * twX/twY (place), twW_<o><d>/twH_<o><d> (widget width and height in dp, per orientation v/h and detail g/f; 0 = not set yet, the page's natural size is used), twMin (minimized), twPos + twSnapSeq ("Snap to" in settings).
 * Owned by CamService.
 */
class TripWidget(private val ctx: Context) {
    companion object {
        /** The running widget, so the camera windows' black backdrop can put it back on top of itself ([restack]). */
        @Volatile var instance: TripWidget? = null
        private const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        private const val PAD_DP = 40          // transparent margin inside the window, room for the edge fade (see tripwidget.html #root)
        private const val TICK_MS = 100L
        private const val SPEED = 0x00100100; private const val THROTTLE = 0x00101400; private const val BRAKE = 0x00101300
        private const val GEAR = 0x00200200; private const val DRIVE_MODE = 0x22010100; private const val POWER_FLOW = 0x24010100
        private const val BRAKE_FULL = 44f
        private const val BATT_PCT = 0x00404000   // HV battery %, found in another open-source Zeekr head-unit app (our own sweep stopped short of it)
        private const val RANGE_KM = 0x00100800   // ISensor ENDURANCE_MILEAGE, verified against the relay's rangeKm
        private val REGEN_FLOWS = setOf(0x24010115, 0x24010116, 0x24010117, 0x2401010a, 0x2401010b, 0x2401010c)   // same set as the driving profiles use
    }

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false

    // main thread only
    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pageReady = false
    private var cfgSent = ""
    private var natRatio = 0.9f   // natural height / width of the page for the current layout
    private var hidden = p.getBoolean("twMin", false)   // minimized into the dock (survives a restart)
    private var leaving = false
    private var minSeen = FloatMaster.minimizeSeq; private var restoreSeen = FloatMaster.restoreSeq
    private var snapSeen = p.getInt("twSnapSeq", 0)
    init { FloatMaster.tripUp = { root != null && !leaving } }

    // car link (bg thread only)
    private var sensor: Any? = null; private var latest: java.lang.reflect.Method? = null; private var event: java.lang.reflect.Method? = null
    private var function: Any? = null; private var getFn: java.lang.reflect.Method? = null
    private var lastTry = 0L; private var slowAt = 0L
    private var gear = "-"; private var mode = ""; private var regen = false

    // relay values (written by the relay thread, read by the poll thread)
    @Volatile private var batt: Double? = null; @Volatile private var range: Double? = null
    @Volatile private var aux: Double? = null; @Volatile private var auxPct: Double? = null
    @Volatile private var relayAt = 0L
    @Volatile private var relayWhy = ""   // short reason the relay values are missing ("" = fine)
    @Volatile private var relayBusy = false; private var relayTry = 0L

    private val dm get() = ctx.resources.displayMetrics
    private val wm get() = ctx.getSystemService(WindowManager::class.java)
    private fun dp(x: Int) = (x * dm.density).toInt()

    fun start() {
        if (running) return
        running = true
        instance = this
        thread = HandlerThread("tripwidget").apply { start() }
        bg = Handler(thread!!.looper).also { it.post(::tick) }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.quitSafely(); thread = null; bg = null
        FloatMaster.tripUp = { false }
        if (instance === this) instance = null
        main.post { BubbleRow.remove(ctx, "tw"); removeView() }
    }

    /** Prefs changed: re-apply width, look and (after "Snap to") place at once. */
    fun sync() {
        main.post {
            cfgSent = ""
            if (root != null && builtHdr != hdrDp() && !leaving) { removeView(); return@post }   // header height changed: rebuild so the hide button gets the new size (the next tick re-adds it)
            if (root != null) { pushCfg(); applySize(); checkSnap() } else checkSnap()
        }
    }
    private var builtHdr = 0
    private fun hdrDp() = p.getInt("flHeader", 56).coerceIn(36, 96)   // the camera windows' header height setting

    private fun want() = p.getBoolean("twOn", false) && !FloatMaster.hidden && Settings.canDrawOverlays(ctx)

    // ---- bg: poll the car, push to the page ----
    private fun tick() {
        val h = bg ?: return
        if (!running) return
        val show = want()
        main.post { state(show) }
        if (show && root != null && !hidden) {
            val json = readData()
            main.post { if (pageReady) web?.evaluateJavascript("zw.data($json)", null) }
            pollRelay()
        }
        h.postDelayed(::tick, TICK_MS)
    }

    // ---- main: show / minimize / restore, the same way the camera windows do it ----
    private fun state(want: Boolean) {
        val minimizeAll = minSeen != FloatMaster.minimizeSeq; minSeen = FloatMaster.minimizeSeq
        val restoreAll = restoreSeen != FloatMaster.restoreSeq; restoreSeen = FloatMaster.restoreSeq
        if (leaving) return
        val r = root; val l = lp
        if (!want) {
            BubbleRow.remove(ctx, "tw")
            if (r != null && l != null) {
                if (FloatMaster.hidden) { leaving = true; FloatAnim.out(wm, r, l) { leaving = false; removeView() } } else removeView()
            }
            return
        }
        if (restoreAll) setHidden(false)
        if (minimizeAll) setHidden(true)
        if (!hidden) {
            BubbleRow.remove(ctx, "tw")
            if (r == null) addView()
        } else {
            if (r != null && l != null) { leaving = true; FloatAnim.genie(wm, r, l, false) { leaving = false; removeView(); showBubble() } }
            else showBubble()
        }
    }

    private fun setHidden(v: Boolean) { if (hidden != v) { hidden = v; p.edit().putBoolean("twMin", v).apply() } }

    private fun showBubble() {
        BubbleRow.add(ctx, "tw", "Trip", IconButton.Kind.TRIP) { FloatMaster.shownAt = android.os.SystemClock.uptimeMillis(); setHidden(false); BubbleRow.remove(ctx, "tw") }
    }

    private fun connect(): Boolean {
        if (latest != null && getFn != null) return true
        val now = System.currentTimeMillis()
        if (now - lastTry < 5_000) return false
        lastTry = now
        return try {
            val car = BlindSpotHud.carCache ?: Class.forName(CAR_CLASS).getMethod("create", Context::class.java).invoke(null, ctx.applicationContext).also { BlindSpotHud.carCache = it }
            sensor = car?.javaClass?.getMethod("getSensorManager")?.invoke(car)
            latest = sensor?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
            event = sensor?.javaClass?.getMethod("getSensorEvent", Int::class.javaPrimitiveType)
            function = car?.javaClass?.getMethod("getICarFunction")?.invoke(car)
            getFn = function?.javaClass?.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
            latest != null && getFn != null
        } catch (_: Throwable) { false }
    }

    private fun num(id: Int): Float? = try {
        val f = (latest?.invoke(sensor, id) as? Number)?.toFloat()
        if (f == null || f.isNaN() || f == 255f || f == -65535f) null else f
    } catch (_: Throwable) { latest = null; null }

    private fun fn(id: Int): Int? = try {
        val v = (getFn?.invoke(function, id) as? Number)?.toInt()
        if (v == null || v == 255 || v == 254 || v == 253 || v == -1 || v == -65535) null else v
    } catch (_: Throwable) { getFn = null; null }

    private fun readData(): String {
        val o = JSONObject()
        if (connect()) {
            num(SPEED)?.let { o.put("speed", it * 3.6) }
            num(THROTTLE)?.let { o.put("thr", it.coerceIn(0f, 100f)) }
            num(BRAKE)?.let { o.put("brk", (it / BRAKE_FULL * 100f).coerceIn(0f, 100f)) }
            val now = System.currentTimeMillis()
            if (now - slowAt >= 500) {
                slowAt = now
                try {
                    val g = (event?.invoke(sensor, GEAR) as? Number)?.toInt()
                    gear = when (g) { 0x00200230 -> "P"; 0x00200240 -> "R"; 0x00200210 -> "N"; 0x00200220 -> "D"; else -> gear }
                } catch (_: Throwable) {}
                mode = when (fn(DRIVE_MODE)) { 0x22010101 -> "Comfort"; 0x22010102 -> "Standard"; 0x22010103 -> "Sport"; else -> mode }
                regen = fn(POWER_FLOW) in REGEN_FLOWS
            }
        }
        o.put("gear", gear); o.put("mode", mode); o.put("regen", regen)
        // range: the car's own sensor first (works without the relay), the relay value as fallback
        val carRange = if (latest != null) num(RANGE_KM)?.takeIf { it > 0f }?.toDouble() else null
        val carBatt = if (latest != null) num(BATT_PCT)?.takeIf { it in 0f..100f }?.toDouble() else null
        (carBatt ?: batt)?.let { o.put("batt", it) }; (carRange ?: range)?.let { o.put("range", it) }
        if (carBatt != null) o.put("battSrc", "car")
        aux?.let { o.put("aux", it) }; auxPct?.let { o.put("auxPct", it) }
        if (relayAt > 0) o.put("age", (System.currentTimeMillis() - relayAt) / 1000.0)
        if (relayWhy.isNotEmpty() && carBatt == null) o.put("why", relayWhy)   // battery from the car needs no relay: say nothing then
        return o.toString()
    }

    /** Cloud-only values (needs the relay link; stays "--" in standalone mode). Every 20 s, never overlapping. */
    private fun pollRelay() {
        val now = System.currentTimeMillis()
        if (relayBusy || now - relayTry < 20_000) return
        val base = p.getString("url", "").orEmpty().trimEnd('/'); val token = p.getString("token", "")
        if (base.isEmpty() || token.isNullOrEmpty()) { relayWhy = "not linked"; return }
        relayBusy = true; relayTry = now
        Thread {
            try {
                val r = Net.get("$base/cam/vehicle", token)
                if (r != null && r.optBoolean("ok", false)) {
                    fun d(k: String) = if (r.has(k) && !r.isNull(k)) r.optDouble(k) else null
                    batt = d("battery"); range = d("rangeKm"); aux = d("auxVoltage"); auxPct = d("auxPercent"); relayAt = System.currentTimeMillis()
                    relayWhy = if (batt == null && range == null && aux == null) "car sent no data" else ""
                } else {
                    val code = r?.optInt("_status", 0) ?: 0
                    relayWhy = when {
                        r == null -> "no internet"
                        code == 401 || code == 403 -> "relay rejected token"
                        r.optString("reason") == "no_session" -> "no Zeekr login on relay"
                        r.optString("reason") == "status_failed" -> "Zeekr login expired"
                        code in 500..599 -> "relay error $code"
                        code != 200 -> "relay http $code"
                        else -> "relay has no status"
                    }
                }
                if (relayWhy.isNotEmpty()) Logger.log(ctx, "tripwidget: relay values missing: $relayWhy")
            } catch (_: Throwable) { relayWhy = "no internet" } finally { relayBusy = false }
        }.start()
    }

    // ---- look (pushed to the page) ----
    private fun themeKey(): String {
        val t = p.getString("twTheme", "auto") ?: "auto"
        return if (t == "auto") (p.getString("theme", "dark") ?: "dark") else t
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun cfgJson(): String {
        val acc = p.getInt("twAcc", ZTheme.LIME)
        val bgc = p.getInt("twBg", 0)
        return JSONObject()
            .put("regenPill", p.getBoolean("twRegen", true))
            .put("slots", org.json.JSONArray((p.getString("twSlots", "eff,avg,dist,rng,soc,reg") ?: "eff,avg,dist,rng,soc,reg").split(",").filter { it.isNotEmpty() }))
            .put("hdr", hdrDp())   // same header height as the other floating windows
            .put("acc", hex(acc)).put("bg", if (bgc == 0) JSONObject.NULL else hex(bgc))
            .put("orient", p.getString("twOrient", "v")).put("detail", p.getString("twDetail", "glance"))
            .put("chart", org.json.JSONArray((p.getString("twChart", "load") ?: "load").split(",").filter { it.isNotEmpty() }))
            .put("font", p.getString("twFont", "Inter")).put("theme", themeKey())
            .put("unit", p.getString("twUnit", "km")).put("opacity", p.getInt("twOpacity", 90).coerceIn(50, 100))
            .put("fade", p.getInt("twFade", 0).coerceIn(0, 30)).put("pack", p.getInt("twPack", 100).coerceIn(30, 150))
            .put("resetSeq", p.getInt("twReset", 0)).toString()
    }

    private fun pushCfg() {
        val c = cfgJson()
        if (c == cfgSent || !pageReady) return
        cfgSent = c
        web?.evaluateJavascript("zw.cfg($c)", null)
    }

    // ---- main: the window ----
    // Any rectangle, like the camera windows: width and height are separate prefs per layout (orientation + detail). The page scales its
    // content to the box and stretches it to fill, so a width-only or height-only change just re-flows it.
    private fun lay(): String = (if (p.getString("twOrient", "v") == "h") "h" else "v") + (if (p.getString("twDetail", "glance") == "full") "f" else "g")
    private val minW = 180; private val minH = 100
    private fun maxWdp() = (dm.widthPixels / dm.density).toInt() - 16
    private fun maxHdp() = (dm.heightPixels / dm.density).toInt() - 16
    private fun defW() = if (lay().startsWith("h")) 720 else 380
    private fun widgetW() = p.getInt("twW_" + lay(), defW()).coerceIn(minW, maxWdp())
    private fun heightPref() = p.getInt("twH_" + lay(), 0)
    private fun widgetH() = (if (heightPref() > 0) heightPref() else (widgetW() * natRatio).toInt()).coerceIn(minH, maxHdp())
    private fun winWpx() = dp(widgetW() + PAD_DP * 2)
    private fun winHpx() = dp(widgetH() + PAD_DP * 2)

    private fun setSize(wdp: Int, hdp: Int) {
        p.edit().putInt("twW_" + lay(), wdp.coerceIn(minW, maxWdp())).putInt("twH_" + lay(), hdp.coerceIn(minH, maxHdp())).apply()
        applySize()
    }

    /** Width and height from prefs (settings changed the layout, or a resize holder moved). Place is untouched. */
    private fun applySize() {
        val v = root ?: return; val l = lp ?: return
        l.width = winWpx(); l.height = winHpx()
        l.x = l.x.coerceIn(-dp(PAD_DP), (dm.widthPixels - dp(40)).coerceAtLeast(0)); l.y = l.y.coerceIn(-dp(PAD_DP), (dm.heightPixels - dp(40)).coerceAtLeast(0))
        try { wm.updateViewLayout(v, l) } catch (_: Throwable) {}
        FloatPush.moved("tw", dm.widthPixels, dm.heightPixels)
        relayout()
    }

    /** The page re-flows to its new box. It also watches its own size, but ask directly (now and once the WebView has been laid out) so a resize shows at once. */
    private val reflow = Runnable { if (pageReady) web?.evaluateJavascript("layout()", null) }
    private fun relayout() { main.removeCallbacks(reflow); reflow.run(); main.postDelayed(reflow, 60); main.postDelayed(reflow, 250) }

    private fun savePlace() { lp?.let { p.edit().putInt("twX", it.x).putInt("twY", it.y).apply() } }

    /** "Snap to" in settings: put the widget at one of nine anchors, 12 dp from the screen edge. */
    private fun anchorXY(pos: Int): Pair<Int, Int> {
        val col = pos % 3; val row = pos / 3
        val w = winWpx(); val h = winHpx()
        val m = dp(12) - dp(PAD_DP)
        return Pair(m + (dm.widthPixels - w - 2 * m) * col / 2, m + (dm.heightPixels - h - 2 * m) * row / 2)
    }

    private fun checkSnap() {
        val s = p.getInt("twSnapSeq", 0)
        if (s == snapSeen) return
        snapSeen = s
        val (x, y) = anchorXY(p.getInt("twPos", 0).coerceIn(0, 8))
        p.edit().putInt("twX", x).putInt("twY", y).apply()
        val l = lp ?: return; val v = root ?: return
        l.x = x; l.y = y
        try { wm.updateViewLayout(v, l) } catch (_: Throwable) {}
        FloatPush.moved("tw", dm.widthPixels, dm.heightPixels)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun addView() {
        if (root != null || !Settings.canDrawOverlays(ctx)) return
        val wv = WebView(ctx.applicationContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            isClickable = false; isFocusable = false
            addJavascriptInterface(object {
                /** The page's natural height/width (per mille) for the current layout: used once as the default size of a layout the user never resized. */
                @JavascriptInterface fun nat(perMille: Int, horiz: Int) { main.post { natRatio = perMille / 1000f; if (heightPref() <= 0) { applySize() } } }
            }, "ZW")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(v: WebView, u: String?) { pageReady = true; cfgSent = ""; pushCfg() }
                override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                    Logger.log(ctx, "tripwidget: render process gone"); main.post { removeView() }; return true
                }
            }
        }
        val l = WindowManager.LayoutParams(winWpx(), winHpx(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val a = anchorXY(p.getInt("twPos", 0).coerceIn(0, 8))
            x = p.getInt("twX", a.first); y = p.getInt("twY", a.second)
        }
        // One finger anywhere drags the window, a pinch resizes it (about its centre), a tap raises it: the same gestures as the camera windows.
        val touch = View(ctx).apply {
            var pinched = false; var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false
            val slop = dp(8)
            val pinch = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
                    val cw = widgetW(); val ch = widgetH()
                    val nw = (cw * g.scaleFactor).toInt().coerceIn(minW, maxWdp()); val nh = (ch * g.scaleFactor).toInt().coerceIn(minH, maxHdp())
                    if (nw == cw && nh == ch) return true
                    val ow = l.width; val oh = l.height
                    setSize(nw, nh)
                    l.x -= (l.width - ow) / 2; l.y -= (l.height - oh) / 2; try { wm.updateViewLayout(root, l) } catch (_: Throwable) {}
                    return true
                }
                override fun onScaleEnd(g: android.view.ScaleGestureDetector) { savePlace() }
            })
            setOnTouchListener { _, e ->
                pinch.onTouchEvent(e)
                if (e.pointerCount > 1 || pinch.isInProgress) pinched = true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = l.x; oy = l.y; moved = false; pinched = false }
                    MotionEvent.ACTION_MOVE -> if (!pinched && e.pointerCount == 1) {
                        if (Math.abs(e.rawX - dx) > slop || Math.abs(e.rawY - dy) > slop) moved = true
                        if (moved) {
                            l.x = (ox + (e.rawX - dx).toInt()).coerceIn(-dp(PAD_DP), dm.widthPixels - dp(40))
                            l.y = (oy + (e.rawY - dy).toInt()).coerceIn(-dp(PAD_DP), dm.heightPixels - dp(40))
                            try { wm.updateViewLayout(root, l) } catch (_: Throwable) {}
                            FloatPush.moved("tw", dm.widthPixels, dm.heightPixels)
                        }
                    }
                    MotionEvent.ACTION_UP -> { if (moved) savePlace(); if (!pinched) raise(); pinched = false; moved = false }
                }
                true
            }
        }
        fun pill(fill: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = 10 * dm.density; setStroke(dp(1), 0xFF98989D.toInt()) }
        // Resize holders exactly like the camera windows: pills on the left, right and bottom edges change one dimension (left also moves the
        // window), the bottom-right corner changes both.
        fun holder(left: Boolean, right: Boolean, bottom: Boolean) = View(ctx).apply {
            background = pill(0x99000000.toInt())
            var dx = 0f; var dy = 0f; var w0 = 0; var h0 = 0; var px0 = 0; var x0 = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; w0 = widgetW(); h0 = widgetH(); px0 = l.width; x0 = l.x }
                    MotionEvent.ACTION_MOVE -> {
                        val sx = ((e.rawX - dx) / dm.density).toInt(); val sy = ((e.rawY - dy) / dm.density).toInt()
                        var nw = w0; var nh = h0
                        if (right) nw = w0 + sx
                        if (left) nw = w0 - sx
                        if (bottom) nh = h0 + sy
                        setSize(nw, nh)
                        if (left) { l.x = x0 + (px0 - l.width); try { wm.updateViewLayout(root, l) } catch (_: Throwable) {} }
                    }
                    MotionEvent.ACTION_UP -> savePlace()
                }
                true
            }
        }
        val edgeL = holder(true, false, false); val edgeR = holder(false, true, false); val edgeB = holder(false, false, true); val grip = holder(false, true, true)
        val hideBtn = IconButton(ctx, IconButton.Kind.HIDE, 0xFF2C2C2E.toInt()).apply { setOnClickListener { setHidden(true) } }
        val box = FrameLayout(ctx).apply {
            addView(wv, FrameLayout.LayoutParams(-1, -1))
            addView(touch, FrameLayout.LayoutParams(-1, -1))
            // the hide button sits in the widget's header, the same size and place as in the camera windows' header bar
            builtHdr = hdrDp(); val hb = dp(builtHdr) - dp(10)   // exactly the camera windows' button size: header minus a 5 dp margin all round
            addView(hideBtn, FrameLayout.LayoutParams(hb, hb, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(PAD_DP + 5), dp(PAD_DP + 8), 0) })
            addView(edgeL, FrameLayout.LayoutParams(dp(14), dp(64), Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(dp(22), 0, 0, 0) })
            addView(edgeR, FrameLayout.LayoutParams(dp(14), dp(64), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, dp(22), 0) })
            addView(edgeB, FrameLayout.LayoutParams(dp(64), dp(14), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(22)) })
            addView(grip, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
        }
        try {
            l.alpha = FloatAnim.startAlpha()
            wm.addView(box, l)
        } catch (e: Throwable) { Logger.log(ctx, "tripwidget: addView failed ${e.javaClass.simpleName} ${e.message ?: ""}"); try { wv.destroy() } catch (_: Throwable) {}; return }
        root = box; web = wv; lp = l; pageReady = false; cfgSent = ""
        FloatPush.register("tw", FloatPush.Item(l, { root }, wm) { x, y -> p.edit().putInt("twX", x).putInt("twY", y).apply() })
        wv.loadDataWithBaseURL("file:///android_asset/", ctx.assets.open("tripwidget.html").bufferedReader().use { it.readText() }, "text/html", "utf-8", null)
        if (l.alpha < 1f) FloatAnim.genie(wm, box, l, true) {}
        main.post { OverlayTop.raise(ctx) }   // the dock and the floating button always sit above every window
    }

    /** Tapping the widget while it overlaps another window brings it to the front (overlay windows only stack by add order). */
    private fun raise() {
        val r = root ?: return; val l = lp ?: return
        if (!FloatPush.overlapsOther("tw")) return
        try { wm.removeViewImmediate(r); wm.addView(r, l) } catch (_: Throwable) {}
        main.post { OverlayTop.raise(ctx) }
    }

    /** Re-add the window on top (overlay windows only stack by add order): the black backdrop behind the floating windows was added over it. */
    fun restack() {
        main.post {
            val r = root ?: return@post; val l = lp ?: return@post
            if (leaving) return@post
            try { wm.removeViewImmediate(r); wm.addView(r, l) } catch (_: Throwable) {}
        }
    }

    private fun removeView() {
        val r = root ?: return
        val v = web
        root = null; web = null; lp = null; pageReady = false; cfgSent = ""
        FloatPush.unregister("tw")
        try { wm.removeView(r) } catch (_: Throwable) {}
        try { v?.loadUrl("about:blank"); v?.destroy() } catch (_: Throwable) {}
    }
}
