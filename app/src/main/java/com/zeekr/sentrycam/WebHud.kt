package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A web page (e.g. YouTube) in the left blind-spot region of the HUD. Same overlay recipe and
 * HUD-display lookup as [BlindSpotHud]; the window is a square on the left with its own size/offset (webSize/webX/webY), so a
 * full-screen video only ever covers that region. Hidden while the left blind-spot camera shows.
 * The HUD window isn't touchable: the app's touchpad forwards touches to the WebView ([touch]).
 * Owned by CamService; the unlock + on switch live in MainActivity (hidden menu).
 */
class WebHud(private val ctx: Context) {
    companion object {
        private const val HOME = "https://www.google.com"   // the page the web view opens when no address was ever loaded
        @Volatile var instance: WebHud? = null
        /** True while Z_Cam's own screen is in front (it has the in-app pad, so no floating window then). */
        @Volatile var appVisible = false
        private const val HUD_W = 1440; private const val HUD_H = 480
        private const val HUD_FALLBACK_ID = 3
        private const val CLUSTER_W = 2560; private const val CLUSTER_H = 536
        private const val TICK_MS = 300L
        private const val FLOAT_FRAME_MS = 20L   // floating picture refresh (about 50 fps)
        private const val SS = 2   // the page renders SS times larger than the HUD box (scaled down there) so the floating copy is sharp
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val WEB_DEF_DP = 340   // default floating picture width (height is 16:9 portrait)

        fun normalize(raw: String): String {
            val u = raw.trim()
            return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        }
    }

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null      // the HUD window (portrait box)
    private var web: WebView? = null
    private var inner: FrameLayout? = null     // SS x sized holder of the page; what the floating window copies
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var hudH = 0
    private var hudW = 0
    private var loadedUrl: String? = null
    private var running = false
    // Floating-window-only mode (the HUD page is off): the page is a real view inside the floating window, drawn and composited by the
    // system itself, so video and sound stay together. (It used to live in an invisible HUD-display window and be copied frame by frame.)
    private var direct = false
    private var parkWin: FrameLayout? = null   // 1 px window that keeps the page alive (and playing) while the floating window is minimized
    private var failedUntil = 0L      // back off after a failed addView
    private var screenOffSince = 0L
    private var curUrl: String? = null         // what the page is showing now (follows links)
    private var curTitle = ""
    private var loadPct = 0
    private var vidStats = ""   // temporary: resolution / dropped frames of the playing video, shown in the floating title
    private var navAt = 0L   // when the current page load started: the floating picture keeps the old page until the new one has painted
    private var navUi: (() -> Unit)? = null    // refreshes the floating window's address / star / progress

    // Pause playing media when the car goes to sleep (screen off) or a phone drops off Bluetooth.
    private val leaveReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            val why = when (i.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> "screen off"
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = i.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val major = try { dev?.bluetoothClass?.majorDeviceClass } catch (_: Throwable) { null }
                    if (major != android.bluetooth.BluetoothClass.Device.Major.PHONE) return   // only a confirmed phone (other / unknown devices dropping off must not stop the video)
                    "phone bluetooth disconnected"
                }
                else -> return
            }
            Logger.log(ctx, "webhud: pausing media ($why)")
            pauseMedia()
        }
    }

    fun start() {
        running = true; instance = this; main.post(tick)
        HudLayer.raisers["web"] = {   // same view, re-added on top (the page keeps running)
            val r = root
            if (r != null && !direct && r.isAttachedToWindow) {
                val wm = r.context.getSystemService(WindowManager::class.java); val l = r.layoutParams
                try { wm.removeViewImmediate(r); wm.addView(r, l) } catch (e: Throwable) { Logger.log(ctx, "webhud: restack failed ${e.javaClass.simpleName}") }
            }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try { ctx.registerReceiver(leaveReceiver, f) } catch (e: Throwable) { Logger.log(ctx, "webhud: receiver failed ${e.message ?: ""}") }
    }

    fun stop() {
        running = false; instance = null
        HudLayer.raisers.remove("web")
        try { ctx.unregisterReceiver(leaveReceiver) } catch (_: Throwable) {}
        main.removeCallbacks(tick)
        main.post(::destroy)
    }

    private fun hudOn() = p.getBoolean("webHudOn", false) || p.getLong("webPreviewUntil", 0L) > System.currentTimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                // Screen off for 2+ min: free the whole browser (a Chromium renderer is the heaviest thing we own); it reloads on wake.
                val now = System.currentTimeMillis()
                if (!(ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) {
                    if (screenOffSince == 0L) screenOffSince = now
                    if (now - screenOffSince > 120_000L) { destroy(); main.postDelayed(this, TICK_MS * 4); return }
                } else screenOffSince = 0L
                if (now < failedUntil) { main.postDelayed(this, TICK_MS); return }
                val on = hudOn()   // also true while the HUD pad previews the page (webPreviewUntil)
                val fl = p.getBoolean("webFloatOn", false)   // the floating window has its own switch: hiding the HUD page leaves it up
                val floatOnly = fl && !on
                val covered = BlindSpotHud.activePanel == 2 || (BlindSpotHud.activePanel == 3 && !p.getBoolean("bsRightPos", false))   // a right camera on the right edge leaves the web box alone
                // "webFloatAlways": keep the page (and its floating window) alive while the blind-spot camera covers the HUD region.
                val keep = on && covered && p.getBoolean("webFloatAlways", false)
                if (!on && !fl) { if (root != null || web != null || floatRoot != null) destroy(); main.postDelayed(this, TICK_MS); return }   // both switches off: stop the browser entirely
                if (root != null && direct != floatOnly && !(!direct && floatOnly)) destroy()   // HUD page -> floating-only keeps the page (HUD copy just goes invisible, no restart); only floating-only -> HUD page has to rebuild
                if (on && !covered) show() else if (keep || floatOnly) { show(); if (!direct) root?.alpha = 0f } else hide()
                if (minSeen != FloatMaster.minimizeSeq) { minSeen = FloatMaster.minimizeSeq; if (on || fl) { floatMin = true
                    val fr = floatRoot; val fp = floatLp
                    if (fr != null && fp != null && !leaving) { leaving = true; FloatAnim.genie(ctx.getSystemService(WindowManager::class.java), fr, fp, false) { leaving = false; hideFloat() } } else if (!leaving) hideFloat() } }   // tap on the floating button
                if (restoreSeen != FloatMaster.restoreSeq) { restoreSeen = FloatMaster.restoreSeq; if (floatMin) { floatMin = false; hideBubble() } }
                if (p.getBoolean("webMin", false) != floatMin) p.edit().putBoolean("webMin", floatMin).apply()
                if (floatRoot != null && hdrBuilt != headerDp()) hideFloat()   // header height slider changed: rebuild
                if (fl && (on && !covered || keep || floatOnly) && !FloatMaster.hidden) showFloat()
                else if (FloatMaster.hidden && floatRoot != null && floatLp != null) {
                    // shrink into the floating button, then remove (a minimized web window stays minimized for when everything comes back)
                    if (!leaving) { leaving = true; hideBubble(); FloatAnim.out(ctx.getSystemService(WindowManager::class.java), floatRoot!!, floatLp!!) { leaving = false; hideFloat() } }
                } else if (!leaving) hideFloat()
            } catch (e: Throwable) { Logger.log(ctx, "webhud: ${e.javaClass.simpleName} ${e.message ?: ""}"); destroy() }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun findHud(): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        return on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } }
    }

    private var land = false                    // landscape (16:9) instead of portrait (9:16)
    // Fixed: full HUD height in portrait, 24% of the HUD height in from the left edge. Portrait is centred vertically and never
    // wider than the right camera's HUD picture; landscape sticks to the top, 73% wide and 14% down (tuned on the car).
    private fun ratio() = if (land) 16f / 9 else 9f / 16
    private fun portH() = minOf(hudH, (hudH * CamTune.get(p, "bsSize", "R") / 100 / 0.5625f).toInt())
    private fun landW() = minOf(hudH * 73 / 100, hudH * 16 / 9)
    // Free placement from the app's HUD layout pad (webPosX/webPosY = centre as % of the HUD, webSize = box height as % of the HUD height); else the fixed legacy spot above.
    private fun placed() = hudW > 0
    private fun freeH(): Int { var h = hudH * p.getInt("webSize", 122).coerceIn(20, 200) / 100; if (land) h = minOf(h, hudW * 9 / 16); return h }
    // Full screen: the box takes the centre screen's aspect at full HUD height, so the page lays out like it would on that screen.
    private var fullScreen = false
    private fun fullW(): Int {
        if (fullAr > 0f) return minOf(hudW, (hudH * fullAr).toInt())
        val sz = Point().also { try { ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) } catch (_: Throwable) {} }
        return if (sz.x > 0 && sz.y > 0) minOf(hudW, hudH * sz.x / sz.y) else hudH * 16 / 10
    }
    private fun hudWpx() = if (placed()) (if (land) freeH() * 16 / 9 else freeH() * 9 / 16) else if (land) landW() else portH() * 9 / 16
    private fun hudHpx() = if (placed()) freeH() else if (land) landW() * 9 / 16 else portH()
    private fun offX() = if (placed()) (hudW * p.getInt("webPosX", 19) / 100 - hudWpx() / 2).coerceIn(minOf(0, hudW - hudWpx()), maxOf(0, hudW - hudWpx())) else hudH * 24 / 100
    private fun offY() = if (placed()) (hudH * p.getInt("webPosY", 61) / 100 - hudHpx() / 2).coerceIn(minOf(0, hudH - hudHpx()), maxOf(0, hudH - hudHpx())) else if (land) hudH * 14 / 100 else 0
    private fun hudGrav() = if (placed()) (Gravity.TOP or Gravity.LEFT) else (if (land) Gravity.TOP else Gravity.CENTER_VERTICAL) or Gravity.LEFT

    // ---- tabs: one WebView per tab, only the active one is attached (and so drawn); the others are paused ----
    private class Tab(var wv: WebView?, var url: String, var title: String = "", var desktop: Boolean = false) { var thumb: android.graphics.Bitmap? = null; var thumbUrl = "" }

    // Tab thumbnails (the tab list shows a small picture of each page, like a phone browser): a snapshot of the top of the page, kept per address
    // in memory and as a small JPEG so it is there after a restart too.
    private val TW = 168; private val TH = 112
    private fun thumbFile(url: String) = java.io.File(ctx.filesDir, "webthumbs").apply { mkdirs() }.let { java.io.File(it, Integer.toHexString(url.hashCode()) + ".jpg") }
    private fun captureTab(t: Tab) {
        try {
            val v: View = (if (fsWeb != null && tabs.getOrNull(curTab) === t) fsWeb else t.wv) ?: return
            if (v.width <= 0 || v.height <= 0 || t.url.isEmpty() || t.url == HOME) return
            val bmp = android.graphics.Bitmap.createBitmap(TW, TH, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
            val sc = TW.toFloat() / v.width; c.scale(sc, sc)
            v.draw(c)
            t.thumb = bmp; t.thumbUrl = t.url
            Thread { try { thumbFile(t.url).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) }
                java.io.File(ctx.filesDir, "webthumbs").listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() } } catch (_: Throwable) {} }.start()
        } catch (_: Throwable) {}
    }
    private fun thumbOf(t: Tab): android.graphics.Bitmap? {
        if (t.thumb != null && t.thumbUrl == t.url) return t.thumb
        return try { thumbFile(t.url).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }?.also { t.thumb = it; t.thumbUrl = t.url } } catch (_: Throwable) { null }
    }
    private val tabs = ArrayList<Tab>()
    private var curTab = 0
    private var bigRef: FrameLayout? = null
    private var webCtx: Context? = null
    private val MAX_TABS = 4
    private var tabsUi: (() -> Unit)? = null   // refreshes the dock's tab count (full screen)

    private fun restoreTabs() {
        try {
            val a = org.json.JSONArray(p.getString("webTabs", "[]"))
            val dk = try { org.json.JSONArray(p.getString("webTabsDesk", "[]")) } catch (_: Throwable) { org.json.JSONArray() }
            for (i in 0 until minOf(a.length(), MAX_TABS)) tabs.add(Tab(null, a.getString(i), desktop = dk.optBoolean(i, false)))
        } catch (_: Throwable) {}
        if (tabs.isEmpty()) tabs.add(Tab(null, p.getString("webUrl", HOME) ?: HOME))
        curTab = p.getInt("webTabIdx", 0).coerceIn(0, tabs.size - 1)
    }
    private fun saveTabs() {
        p.edit().putString("webTabs", org.json.JSONArray(tabs.map { it.url }).toString()).putString("webTabsDesk", org.json.JSONArray(tabs.map { it.desktop }).toString()).putInt("webTabIdx", curTab).apply()
    }
    private fun tabName(t: Tab) = t.title.ifEmpty { t.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') }.ifEmpty { "New tab" }
    fun tabCount() = tabs.size

    private fun tabsChanged() { navUi?.invoke(); tabsUi?.invoke() }

    /** Shows tab [i]: the old page stops its media and is detached (paused); the new one is attached and resumed. */
    private fun switchTab(i: Int) {
        if (fsWeb != null) {   // full screen: the tab's address opens in the full-screen page, the HUD / window page stays as it is
            if (i in tabs.indices) { curTab = i; val u = tabs[i].url; if (u.isNotEmpty()) fsWeb?.loadUrl(u); loadedUrl = u; curUrl = u.ifEmpty { null }; saveTabs(); tabsChanged() }
            return
        }
        val big = bigRef ?: return; val wc = webCtx ?: return
        if (i !in tabs.indices) return
        val old = web
        if (old != null && tabs[i].wv === old) return
        customCb?.onCustomViewHidden()
        tabs.firstOrNull { it.wv === old }?.let { captureTab(it) }
        if (old != null) {
            // a tab that is playing media keeps playing in the background (hidden, not paused); any other tab is paused and detached as before
            old.visibility = View.INVISIBLE
            old.evaluateJavascript("(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)if(!m[i].paused&&!m[i].ended)return 1;return 0})()") { r ->
                if (r != "1" && web !== old) { try { big.removeView(old); old.onPause() } catch (_: Throwable) {} }
            }
        }
        curTab = i
        val t = tabs[i]
        val wv = t.wv ?: makeWebView(wc, big, t).also { t.wv = it; if (t.url.isNotEmpty()) it.loadUrl(t.url) }
        if (wv.parent == null) big.addView(wv, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.visibility = View.VISIBLE
        wv.onResume()
        web = wv; curUrl = t.url.ifEmpty { null }; curTitle = t.title; loadPct = 100; loadedUrl = t.url
        p.edit().putString("webUrl", t.url).apply(); saveTabs()
        applyVolume(); tabsChanged()
    }

    private fun newTab() {
        if (tabs.size >= MAX_TABS) return
        tabs.add(Tab(null, HOME)); switchTab(tabs.size - 1)
    }

    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        if (tabs.size == 1) { t.url = HOME; t.title = ""; web?.loadUrl(HOME); tabsChanged(); return }   // the last tab just goes home
        if (t.wv === web) {
            switchTab(if (i + 1 < tabs.size) i + 1 else i - 1)
            // switchTab changed curTab to the neighbour's index: fix it after the removal below
        }
        val keep = tabs[curTab]
        tabs.removeAt(i)
        curTab = tabs.indexOf(keep)
        try { t.wv?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } } catch (_: Throwable) {}
        saveTabs(); tabsChanged()
    }

    private fun moveTab(from: Int, to: Int) {
        val keep = tabs[curTab]
        tabs.add(to, tabs.removeAt(from))
        curTab = tabs.indexOf(keep)
        saveTabs(); tabsChanged()
    }

    /** The tab list: tap a tab to show it, x to close it, "New tab" to open one ([onDone] gets true after a new tab). */
    private fun tabPanel(heightPx: Int, onDone: (Boolean) -> Unit): View {
        val d = ZScale.density(ctx)
        fun dp(x: Int) = (x * d).toInt()
        fun chip(fill: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = r * d }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tabs.getOrNull(curTab)?.let { captureTab(it) }
        fun fill() {
            col.removeAllViews()
            for ((i, t) in tabs.withIndex()) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = chip(if (i == curTab) 0xFF3A3A3C.toInt() else 0xFF2C2C2E.toInt(), 12)
                    setPadding(dp(14), 0, dp(6), 0)
                    setOnClickListener { switchTab(i); onDone(false) }
                    // long press, then drag up or down to a new place in the list
                    var dragging = false; var downY = 0f
                    setOnLongClickListener { dragging = true; alpha = 0.8f; scroll.requestDisallowInterceptTouchEvent(true); true }
                    setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { downY = e.rawY; false }
                            MotionEvent.ACTION_MOVE -> if (dragging) { v.translationY = e.rawY - downY; true } else false
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                                dragging = false
                                val to = (i + Math.round((e.rawY - downY) / dp(80))).coerceIn(0, tabs.size - 1)
                                if (to != i) moveTab(i, to)
                                fill(); true
                            } else false
                            else -> false
                        }
                    }
                }
                row.addView(android.widget.ImageView(ctx).apply {
                    val th = thumbOf(t)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    if (th != null) setImageBitmap(th) else setBackgroundColor(0xFF48484A.toInt())
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 8 * d) } }
                    clipToOutline = true
                    if (i == curTab) foreground = android.graphics.drawable.GradientDrawable().apply { setColor(0); setStroke(dp(2), 0xFFA3E635.toInt()); cornerRadius = 8 * d }
                    layoutParams = LinearLayout.LayoutParams(dp(78), dp(52)).apply { marginEnd = dp(14) }
                })
                row.addView(TextView(ctx).apply {
                    text = tabName(t); setTextColor(0xFFF5F5F7.toInt()); textSize = 24f; typeface = ZFont.reg; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(ctx).apply {
                    text = "X"; gravity = Gravity.CENTER; textSize = 24f; typeface = ZFont.reg; setTextColor(0xFFF5F5F7.toInt())
                    setOnClickListener { closeTab(i); fill() }
                }, LinearLayout.LayoutParams(dp(72), dp(72)))
                col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
            }
        }
        fill()
        val add = TextView(ctx).apply {
            text = if (tabs.size >= MAX_TABS) "Tab limit ($MAX_TABS)" else "New tab"; gravity = Gravity.CENTER; textSize = 24f
            typeface = ZFont.reg; setTextColor(0xFF1C1C1E.toInt())
            background = chip(if (tabs.size >= MAX_TABS) 0xFF636366.toInt() else 0xFFA3E635.toInt(), 12)
            setOnClickListener { if (tabs.size < MAX_TABS) { newTab(); onDone(true) } }
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx - dp(80)))
        root.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(8) })
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(wctx: Context, big: FrameLayout, tab: Tab): WebView = WebView(wctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (defaultUa == null) defaultUa = settings.userAgentString
            settings.userAgentString = uaFor(tab)
            settings.mediaPlaybackRequiresUserGesture = false
            setInitialScale(SS * 100)
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun edit() { main.post { keysOpen?.invoke() } }
                @android.webkit.JavascriptInterface fun idle() { com.zeekr.sentrycam.sound.AudioWake.webIdle() }   // a video/audio on the page ended or paused
                @android.webkit.JavascriptInterface fun q(t: String) { main.post { vidStats = t } }   // temporary video diagnostics (shown in the title bar)
            }, "ZCam")
            addJavascriptInterface(WebDownload.Bridge(ctx), "ZDl")
            setDownloadListener { u, ua, cd, mime, _ -> WebDownload.start(ctx, this, u, ua, cd, mime, this.url) }
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(v: WebView, u: String?, isReload: Boolean) {
                    if (u == null) return
                    tabs.firstOrNull { it.wv === v }?.url = u
                    if (v !== web) return
                    curUrl = u; saveTabs()
                    if (!isReload) WebStore.addVisit(p, u, "")   // title arrives a moment later
                    navUi?.invoke()
                }
                override fun onReceivedError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                    if (r.isForMainFrame) Logger.log(ctx, "webhud: page error ${e.errorCode} ${e.description} ${r.url}")
                }
                override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                    Logger.log(ctx, "webhud: render process gone (crashed=${d.didCrash()})"); return false
                }
                override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) {
                    if (v === web) navAt = System.currentTimeMillis()
                    // Ask sites for H.264 only: VP9 / AV1 / VP8 are decoded in software on the car and cannot keep up, so the picture fell behind the sound.
                    if (p.getBoolean("webDebug", false)) v.evaluateJavascript("(function(){if(window.__vq)return;window.__vq=1;var raf=0,vf=0,t0=0,c0=0,tf0=0,hv=null;function r(){raf++;requestAnimationFrame(r)}requestAnimationFrame(r);setInterval(function(){var v=document.querySelector('video');if(!v||v.paused||!v.videoWidth){ZCam.q('');return}if(v!==hv){hv=v;if(v.requestVideoFrameCallback){(function f(){vf++;v.requestVideoFrameCallback(f)})()}}var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():{};var n=performance.now(),c=v.currentTime;var rate=t0?((c-c0)/((n-t0)/1000)).toFixed(2):'-';var dt=t0?(n-t0)/1000:1;var tf=q.totalVideoFrames||0;ZCam.q('page '+Math.round(raf/dt)+' fps|shown '+Math.round(vf/dt)+' fps|decoded '+Math.round((tf-tf0)/dt)+' fps|dropped '+(q.droppedVideoFrames||0)+' of '+tf+'|speed x'+rate+'|'+v.videoHeight+'p');tf0=tf;raf=0;vf=0;t0=n;c0=c},1000)})()", null)
                    v.evaluateJavascript("(function(){if(window.__zi)return;window.__zi=1;function any(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)if(!m[i].paused&&!m[i].ended)return 1;return 0}function chk(){setTimeout(function(){if(!any())ZCam.idle()},150)}document.addEventListener('ended',chk,true);document.addEventListener('pause',chk,true)})()", null)
                    v.evaluateJavascript("(function(){if(window.__h264)return;window.__h264=1;var bad=/vp0?[89]|av0?1/i;var ms=window.MediaSource;if(ms&&ms.isTypeSupported){var o=ms.isTypeSupported.bind(ms);ms.isTypeSupported=function(t){return bad.test(t)?false:o(t)}}var cp=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(t){return bad.test(t)?'':cp.call(this,t)}})()", null)
                }
                override fun onPageFinished(v: WebView, u: String?) {
                    v.evaluateJavascript(WebDownload.HOOK_JS, null)
                    applyZoom(); applyVolume()
                    main.postDelayed({ tabs.firstOrNull { it.wv === v }?.let { captureTab(it) } }, 1500)   // thumbnail for the tab list once the page has painted
                    // tapping a text field on the floating window pops the typing bar (focus, and a tap on a field that is already focused)
                    v.evaluateJavascript("(function(){if(window.__zk)return;window.__zk=1;function ok(t){return t&&(t.isContentEditable||t.tagName=='TEXTAREA'||(t.tagName=='INPUT'&&!/^(checkbox|radio|button|submit|reset|range|color|file|image|hidden)$/i.test(t.type)));}function f(e){if(ok(e.target))ZCam.edit();}document.addEventListener('focusin',f,true);document.addEventListener('click',f,true);})()", null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, n: Int) { if (v === web) { loadPct = n; navUi?.invoke() } }
                override fun onReceivedTitle(v: WebView, t: String?) {
                    tabs.firstOrNull { it.wv === v }?.title = t ?: ""
                    if (v !== web) return
                    curTitle = t ?: ""
                    v.url?.let { WebStore.setTitle(p, it, curTitle) }
                    navUi?.invoke()
                }
                override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                    customView?.let { big.removeView(it) }
                    customView = view; customCb = cb
                    BubbleRow.setDim(true)
                    big.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    customView?.let { big.removeView(it) }
                    customView = null; BubbleRow.setDim(false)
                    customCb?.onCustomViewHidden(); customCb = null
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun show() {
        val r = root
        if (r != null) {
            r.visibility = View.VISIBLE; r.alpha = 1f
            if (direct) fitBig()
            val lp = r.layoutParams as? WindowManager.LayoutParams
            if (lp != null && hudH > 0 && (lp.height != hudHpx() || lp.width != hudWpx() || lp.x != offX() || lp.y != offY() || lp.gravity != hudGrav())) {
                lp.width = hudWpx(); lp.height = hudHpx(); lp.x = offX(); lp.y = offY(); lp.gravity = hudGrav()
                inner?.let { it.layoutParams = FrameLayout.LayoutParams(lp.width * SS, lp.height * SS) }
                try { r.context.getSystemService(WindowManager::class.java).updateViewLayout(r, lp) } catch (_: Throwable) {}
            }
            val url = p.getString("webUrl", HOME) ?: HOME
            if (url.isNotEmpty() && url != loadedUrl) { loadedUrl = url; web?.loadUrl(url) }
            return
        }
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        val hud = findHud() ?: return
        val dctx = ctx.createDisplayContext(hud)
        val wctx = try { dctx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) } catch (_: Throwable) { dctx }
        Point().also { hud.getRealSize(it) }.let { hudH = it.y; hudW = it.x }
        direct = p.getBoolean("webFloatOn", false) && !hudOn()
        if (direct) {
            val big = FrameLayout(ctx).apply { pivotX = 0f; pivotY = 0f }
            bigRef = big; webCtx = ctx
            if (tabs.isEmpty()) restoreTabs()
            val cur = tabs[curTab]
            val wv = cur.wv ?: makeWebView(ctx, big, cur).also { cur.wv = it }
            big.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root = big; web = wv; inner = null
            fitBig(); parkBig()
            val url = cur.url; curUrl = url.ifEmpty { null }
            if (url.isNotEmpty()) { loadedUrl = url; wv.loadUrl(url) }
            Logger.log(ctx, "webhud: on (inside the floating window)")
            return
        }
        val container = FrameLayout(wctx).apply { setBackgroundColor(Color.BLACK) }
        val big = FrameLayout(wctx).apply { pivotX = 0f; pivotY = 0f; scaleX = 1f / SS; scaleY = 1f / SS }
        bigRef = big; webCtx = wctx
        if (tabs.isEmpty()) restoreTabs()
        val cur = tabs[curTab]
        val wv = cur.wv ?: makeWebView(wctx, big, cur).also { cur.wv = it }
        big.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(big, FrameLayout.LayoutParams(hudWpx() * SS, hudHpx() * SS))
        val lp = WindowManager.LayoutParams(hudWpx(), hudHpx(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT
        ).apply { gravity = hudGrav(); x = offX(); y = offY() }
        try { wctx.getSystemService(WindowManager::class.java).addView(container, lp) }
        catch (e: Throwable) {
            // Not on screen: drop the browser view now, or every 300ms tick would build another one.
            try { wv.loadUrl("about:blank"); wv.destroy() } catch (_: Throwable) {}
            cur.wv = null
            Logger.log(ctx, "webhud: addView failed ${e.javaClass.simpleName} ${e.message ?: ""}")
            failedUntil = System.currentTimeMillis() + 30_000L
            return
        }
        root = container; web = wv; inner = big
        HudLayer.added(p, "web")   // whatever is meant to be in front of the page goes back on top
        // copy to the floating picture as soon as the page has painted a new frame (video included), instead of only on a timer
        big.viewTreeObserver.addOnDrawListener {
            if (floatRoot != null && android.os.SystemClock.uptimeMillis() - lastDrawAt >= 8L) main.post(floatDraw)
        }
        val url = cur.url; curUrl = url.ifEmpty { null }
        if (url.isNotEmpty()) { loadedUrl = url; wv.loadUrl(url) }
        Logger.log(ctx, "webhud: on (display ${hud.displayId})")
    }

    /** Direct mode: the page at its HUD-box size (times SS), scaled to fill the floating picture. */
    private fun fitBig() {
        val b = bigRef ?: return
        val w = hudWpx() * SS; val h = hudHpx() * SS
        if (w <= 0 || h <= 0) return
        val cur = b.layoutParams
        if (cur == null || cur.width != w || cur.height != h) b.layoutParams = FrameLayout.LayoutParams(w, h)
        b.pivotX = 0f; b.pivotY = 0f
        if (padW > 0 && padH > 0 && b.parent === floatPadFrame) { b.scaleX = padW / w.toFloat() * vz; b.scaleY = padH / h.toFloat() * vz; b.translationX = tx * padW; b.translationY = ty * padH } else { b.scaleX = 1f; b.scaleY = 1f; b.translationX = 0f; b.translationY = 0f }
    }
    private var floatPadFrame: FrameLayout? = null

    /** Direct mode: park the page in a 1 px window so it keeps running while the floating window is gone / minimized. */
    private fun parkBig() {
        val b = bigRef ?: return
        (b.parent as? ViewGroup)?.removeView(b)
        b.scaleX = 1f; b.scaleY = 1f; b.translationX = 0f; b.translationY = 0f
        val w = hudWpx() * SS; val h = hudHpx() * SS
        if (parkWin == null) {
            val h1 = FrameLayout(ctx)
            val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
            try { ctx.getSystemService(WindowManager::class.java).addView(h1, lp); parkWin = h1 } catch (e: Throwable) { Logger.log(ctx, "webhud: park window failed ${e.javaClass.simpleName}") }
        }
        parkWin?.addView(b, FrameLayout.LayoutParams(w, h))
    }
    private fun unparkBig() { bigRef?.let { (it.parent as? ViewGroup)?.removeView(it) } }

    // Blind-spot camera took the region: hide only; the page (and a playing video) stays loaded.
    private fun hide() { root?.visibility = View.GONE }

    private fun destroy() {
        hideFloat()
        val r = root ?: return
        root = null
        saveTabs()
        unparkBig()
        parkWin?.let { try { ctx.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Throwable) {} }; parkWin = null
        for (t in tabs) { try { t.wv?.destroy() } catch (_: Throwable) {}; t.wv = null }
        web = null; inner = null; customView = null; customCb = null; loadedUrl = null; BubbleRow.setDim(false); direct = false
        try { r.context.getSystemService(WindowManager::class.java).removeView(r) } catch (_: Throwable) {}
        Logger.log(ctx, "webhud: off")
    }

    /** Draws the whole HUD box scaled into [c] (w x h) for the live mirrors (main thread). */
    fun drawTo(c: android.graphics.Canvas, w: Int, h: Int): Boolean {
        val r = inner ?: return false
        if (r.width <= 0 || r.height <= 0) return false
        c.drawColor(Color.BLACK)
        c.translate(tx * w, ty * h)
        c.scale(vz * w / r.width, vz * h / r.height)
        r.draw(c)
        return true
    }

    // Pinch zoom: pages like YouTube block native pinch, so zoom with CSS on the document instead
    // (reapplied after each page load). Reset by the Mini button.
    private var zoom = 1f
    private fun applyZoom() {
        web?.evaluateJavascript("document.documentElement.style.zoom='" + (if (zoom == 1f) "" else zoom.toString()) + "'", null)
    }
    private var defaultUa: String? = null
    private fun uaFor(t: Tab): String? = if (t.desktop) DESKTOP_UA else defaultUa
    /** Browser type of the current tab only (like a normal browser): this tab switches and reloads, the others keep theirs. */
    private fun toggleDesktop() { val t = tabs.getOrNull(curTab) ?: return; t.desktop = !t.desktop; t.wv?.let { it.settings.userAgentString = uaFor(t); it.reload() }; saveTabs() }

    /** Web page volume (0-100 %), a multiplier on top of the car's media volume; independent of the engine sound volume. */
    fun applyVolume() {
        val v = p.getInt("webVol", 100).coerceIn(0, 100) / 100f
        web?.evaluateJavascript("(function(v){window.__zv=v;function a(){document.querySelectorAll('video,audio').forEach(function(m){if(m.volume!==window.__zv)m.volume=window.__zv})}a();if(!window.__zvi){window.__zvi=setInterval(a,1000);document.addEventListener('play',a,true)}})($v)", null)
    }
    fun resetZoom() { zoom = 1f; vz = 1f; tx = 0f; ty = 0f; applyZoom(); applyView() }
    // Pinch out beyond the page size: the whole picture is magnified past the HUD box (cropped there) and two fingers pan it.
    // vz = magnification (1..4), tx / ty = where the page's top-left sits, as a fraction of the box (0 .. 1 - vz). Pinch in below 1 shrinks the page itself (CSS zoom 0.4..1).
    private var vz = 1f; private var tx = 0f; private var ty = 0f
    private var padVw = 1f; private var padVh = 1f; private var pfx = 0f; private var pfy = 0f
    private fun applyView() {
        if (direct) fitBig()
        else inner?.let { it.scaleX = vz / SS; it.scaleY = vz / SS; it.translationX = tx * hudWpx(); it.translationY = ty * hudHpx() }
        main.post(floatDraw)
    }
    private val pinch = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean { pfx = d.focusX / padVw; pfy = d.focusY / padVh; return true }
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val f = d.scaleFactor; val fx = d.focusX / padVw; val fy = d.focusY / padVh
            if (vz == 1f && (f < 1f || zoom < 1f)) { zoom = (zoom * f).coerceIn(0.4f, 1f); applyZoom(); pfx = fx; pfy = fy; return true }
            val p0x = (pfx - tx) / vz; val p0y = (pfy - ty) / vz
            vz = (vz * f).coerceIn(1f, 4f)
            tx = (fx - p0x * vz).coerceIn(1f - vz, 0f); ty = (fy - p0y * vz).coerceIn(1f - vz, 0f)
            pfx = fx; pfy = fy; applyView(); return true
        }
    })
    private var pinching = false
    private var lastNx = 0f; private var lastNy = 0f

    /** Touch from a mirror pad [v]: one finger scrolls/clicks the page, two fingers pinch-zoom it. */
    fun padTouch(e: MotionEvent, v: View) {
        padVw = v.width.coerceAtLeast(1).toFloat(); padVh = v.height.coerceAtLeast(1).toFloat()
        pinch.onTouchEvent(e)
        // a touch on the (possibly magnified) picture lands on this spot of the page
        val nx = ((e.x / v.width - tx) / vz).coerceIn(0f, 1f); val ny = ((e.y / v.height - ty) / vz).coerceIn(0f, 1f)
        if (e.pointerCount > 1 || pinch.isInProgress) {
            if (!pinching) { pinching = true; send(MotionEvent.ACTION_CANCEL, e.downTime, e.eventTime, lastNx, lastNy) }
            return
        }
        if (pinching) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false; return }
        lastNx = nx; lastNy = ny
        send(e.actionMasked, e.downTime, e.eventTime, nx, ny)
    }

    // ---- floating control window on the centre display (main thread) ----

    private var floatRoot: ViewGroup? = null
    private var floatLp: WindowManager.LayoutParams? = null
    private var leaving = false
    private var floatSurface: Surface? = null
    private var floatPad: TextureView? = null
    private var floatMin = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("webMin", false)
    private var minSeen = FloatMaster.minimizeSeq
    private var restoreSeen = FloatMaster.restoreSeq
    init { FloatMaster.webUp = { floatRoot != null } }
    private var hdrBuilt = 0                      // header height (dp) the floating window was built with
    private var closeInput: (() -> Unit)? = null   // closes the typing window (address / keyboard) if one is open
    private var drawFails = 0                      // consecutive failed frames to the floating picture
    private var keysOpen: (() -> Unit)? = null   // page focused a text field: show the keyboard row
    private var padW = 0; private var padH = 0     // floating picture size in px (portrait 1:2, like the HUD box)
    private var winX = 0; private var winY = 0; private var winSet = false     // expanded window position
    private var lastDrawAt = 0L
    private val floatDraw = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (direct) return
            val t0 = android.os.SystemClock.uptimeMillis()
            lastDrawAt = t0
            val sf = floatSurface; val pad = floatPad
            val loading = loadPct in 0..24 && System.currentTimeMillis() - navAt < 4000L
            if (sf != null && pad != null && pad.visibility == View.VISIBLE && pad.width > 0 && !loading) {
                try {
                    val c = sf.lockHardwareCanvas()
                    try { drawTo(c, pad.width, pad.height) } finally { sf.unlockCanvasAndPost(c) }
                    drawFails = 0
                } catch (_: Throwable) {
                    // the picture went grey and stays so: rebuild the window instead of leaving it dead
                    if (++drawFails == 45) { drawFails = 0; Logger.log(ctx, "webhud: picture stuck, rebuilding"); main.post { if (floatRoot != null) { hideFloat(); showFloat() } } }
                }
            }
            // fixed cadence: the wait used to come on top of the time a frame took to draw, so the picture ran well below 30 fps and fell behind the sound
            if (floatRoot != null) main.postDelayed(this, (FLOAT_FRAME_MS - (android.os.SystemClock.uptimeMillis() - t0)).coerceAtLeast(4L))
        }
    }

    private fun headerDp() = p.getInt("flHeader", 56).coerceIn(36, 96)

    private fun maxPadW(dm: android.util.DisplayMetrics) = minOf(dm.widthPixels - (40 * dm.density).toInt(), (dm.heightPixels - (260 * dm.density).toInt()) * (if (land) 16 else 9) / (if (land) 9 else 16))
    private fun padHFor(w: Int) = if (land) w * 9 / 16 else w * 16 / 9

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloat() {
        if (floatMin) { showBubble(); return }
        if (floatRoot != null || root == null) return
        if (fullScreen) { showFull(); return }
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val hdr = headerDp(); hdrBuilt = hdr
        val btn = dp(hdr) - dp(10)
        if (!winSet) {
            val dm = ctx.resources.displayMetrics
            land = p.getBoolean("webLand", false)
            padW = p.getInt("webFloatW", dp(if (land) 480 else WEB_DEF_DP)).coerceIn(dp(200), maxPadW(dm)); padH = padHFor(padW)
            winX = p.getInt("webFloatX", dp(24)).coerceIn(0, dm.widthPixels - dp(60)); winY = p.getInt("webFloatY", dp(120)).coerceIn(0, dm.heightPixels - dp(60))
            winSet = true
        }
        fun rounded(fill: Int, r: Int, stroke: Int? = null) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = r * d; if (stroke != null) setStroke(dp(1), stroke)
        }
        fun icon(k: IconButton.Kind, onClick: () -> Unit) = IconButton(ctx, k).apply {
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(hdr), 1f)
        }
        val tsz = (hdr * 0.42f).coerceIn(15f, 26f)   // one text size for the title and the address bar
        fun gap() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        val size = padW + dp(16)
        val lp = WindowManager.LayoutParams(size, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = winX; y = winY }
        var setModeRef: (Int) -> Unit = {}   // setMode, for the callbacks defined before it
        var mode = 0   // 0 none, 1 keys, 2 url, 3 bookmarks / history
        var padDownRef: () -> Unit = {}
        val tex = TextureView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(padW, padH)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var mine: Surface? = null   // this view's own surface: a rebuilt window's old view is destroyed AFTER the new one is created, so it must not release the new surface
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { mine = Surface(st); floatSurface = mine }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { if (raising) return false; mine?.release(); if (floatSurface === mine) floatSurface = null; mine = null; return true }
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
            setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) padDownRef(); padTouch(e, v); true }
        }
        val pad: View = if (!direct) tex else FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(padW, padH)
            floatPadFrame = this
            unparkBig()
            bigRef?.let { addView(it) }
            fitBig()
            // transparent layer on top: touches are forwarded to the page like before (one finger scrolls / taps, two pinch-zoom)
            addView(View(ctx).apply { setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) padDownRef(); padTouch(e, v); true } },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val title = TextView(ctx).apply {
            text = "Web"; setTextColor(0xFFF5F5F7.toInt()); textSize = tsz   // the page title; tapping it turns it into the address bar
            typeface = ZFont.bold; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val handle = FrameLayout(ctx).apply {
            addView(title)
            layoutParams = LinearLayout.LayoutParams(0, dp(hdr), 1f)
            var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var hMoved = false
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y; hMoved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - dx) > dp(8) || Math.abs(e.rawY - dy) > dp(8)) hMoved = true
                        if (!hMoved) return@setOnTouchListener true
                        lp.x = ox + (e.rawX - dx).toInt(); lp.y = oy + (e.rawY - dy).toInt(); winX = lp.x; winY = lp.y
                        try { ctx.getSystemService(WindowManager::class.java).updateViewLayout(floatRoot, lp) } catch (_: Throwable) {}
                        FloatPush.moved("web", ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                    }
                    MotionEvent.ACTION_UP -> { if (hMoved) p.edit().putInt("webFloatX", lp.x).putInt("webFloatY", lp.y).apply() else setModeRef(if (mode == 2) 0 else 2) }
                }
                true
            }
        }
        fun applySize() {
            lp.width = padW + dp(16)
            pad.layoutParams = pad.layoutParams.apply { width = padW; height = padH }
            if (direct) fitBig()
            try { ctx.getSystemService(WindowManager::class.java).updateViewLayout(floatRoot, lp) } catch (_: Throwable) {}
            ctx.resources.displayMetrics.let { FloatPush.moved("web", it.widthPixels, it.heightPixels) }
        }
        // Keyboard / address bar: the window is normally not focusable (so it never steals the car's input). Keys or URL makes it
        // focusable and pops the system keyboard. Keys forwards typing into the page's focused field; URL loads an address.
        var suppress = false
        var fwdBtn: IconButton? = null
        var moreBtn: IconButton? = null
        var menuBox: LinearLayout? = null
        var menuToggle: () -> Unit = {}
        var listBtn: IconButton? = null
        // bookmarks / history list: drawn over the picture (not below the controls, where a tall portrait window pushed it off screen)
        val padFrame = FrameLayout(ctx)
        val listBox = FrameLayout(ctx).apply {
            visibility = View.GONE; setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true   // clickable: taps on it must not reach the page below
            background = rounded(0xFF1C1C1E.toInt(), 14)
        }
        fun field(hintText: String, action: Int) = android.widget.EditText(ctx).apply {
            visibility = View.GONE; hint = hintText; setTextColor(0xFFF5F5F7.toInt()); setHintTextColor(0xFF98989D.toInt())
            textSize = tsz; typeface = ZFont.reg; isSingleLine = true
            imeOptions = action; background = rounded(0xFF2C2C2E.toInt(), 12)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) }
        }
        val keyEdit = field("Type into the page", android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        val urlEdit = field("Address or search", android.view.inputmethod.EditorInfo.IME_ACTION_GO).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        // Typing happens in its own window at the top of the screen, so the web window itself never changes (it used to turn grey when
        // it became focusable) and the keyboard, which comes up at the bottom, never hides what is being typed.
        val wm = ctx.getSystemService(WindowManager::class.java)
        var inputBox: LinearLayout? = null
        var savedY = -1
        fun closeInputWin() {
            val b = inputBox ?: return
            inputBox = null
            (keyEdit.parent as? ViewGroup)?.removeView(keyEdit); (urlEdit.parent as? ViewGroup)?.removeView(urlEdit)
            keyEdit.visibility = View.GONE; urlEdit.visibility = View.GONE
            try { wm.removeView(b) } catch (_: Throwable) {}
            if (savedY >= 0) { lp.y = savedY; savedY = -1; try { wm.updateViewLayout(floatRoot, lp) } catch (_: Throwable) {} }
        }
        fun openInputWin(edit: android.widget.EditText) {
            closeInputWin()
            val dm = ctx.resources.displayMetrics
            if (edit === urlEdit) {
                // the address bar takes the title bar's place (never over the tab / minimize buttons on its right)
                edit.visibility = View.VISIBLE; edit.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(hdr) - dp(8))
                edit.background = rounded(0xCC000000.toInt(), 12, 0x44FFFFFF); edit.setPadding(dp(12), 0, dp(12), 0)
                val ub = LinearLayout(ctx).apply { addView(edit) }
                val iw = maxOf(handle.width, dp(160))
                val ulp = WindowManager.LayoutParams(iw, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                }
                if (savedY < 0) savedY = lp.y
                lp.y = dp(12)   // park the window near the top so the keyboard at the bottom covers little of the page
                ulp.x = lp.x + dp(8); ulp.y = lp.y + dp(8) + dp(4)
                try { wm.addView(ub, ulp); inputBox = ub } catch (e: Throwable) { Logger.log(ctx, "webhud: input window failed ${e.javaClass.simpleName} ${e.message ?: ""}"); return }
                try { wm.updateViewLayout(floatRoot, lp) } catch (_: Throwable) {}
                edit.requestFocus()
                main.postDelayed({ ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 250)
                return
            }
            edit.visibility = View.VISIBLE; edit.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
            val done = TextView(ctx).apply {
                text = "Done"; gravity = Gravity.CENTER; textSize = 16f; typeface = ZFont.reg; setTextColor(0xFF1C1C1E.toInt())
                background = rounded(0xFFA3E635.toInt(), 12); layoutParams = LinearLayout.LayoutParams(dp(88), dp(56)).apply { marginStart = dp(10) }
                setOnClickListener { setModeRef(0) }
            }
            val b = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xFF1C1C1E.toInt(), 18, 0xFF2C2C2E.toInt()); addView(edit); addView(done)
            }
            val ilp = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(32), dp(900)), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(12)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            }
            try { wm.addView(b, ilp); inputBox = b } catch (e: Throwable) { Logger.log(ctx, "webhud: input window failed ${e.javaClass.simpleName} ${e.message ?: ""}"); return }
            // park the web window right under the input, so the keyboard at the bottom covers as little of the page as possible
            if (savedY < 0) savedY = lp.y
            lp.y = dp(12 + 86)
            try { wm.updateViewLayout(floatRoot, lp) } catch (_: Throwable) {}
            edit.requestFocus()
            main.postDelayed({ ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 250)
        }
        var panelTab = 0   // which tab of the bookmarks panel opens: 0 bookmarks, 1 history, 2 downloads
        var modeUi: () -> Unit = {}   // lights the bookmarks / tabs buttons while their page is open
        fun setMode(m: Int) {
            mode = m
            listBox.removeAllViews()
            if (m == 4) {
                padFrame.minimumHeight = 0   // the picture area keeps its size: the panel scrolls inside it
                listBox.addView(tabPanel(padH - dp(16)) { isNew -> setModeRef(if (isNew) 2 else 0) })
                listBox.visibility = View.VISIBLE
            } else if (m == 3) {
                padFrame.minimumHeight = 0   // the picture area keeps its size: the list scrolls inside it
                listBox.addView(WebStore.panel(ctx, p, (padH - dp(16 + 72 + 8)).coerceAtLeast(dp(60)), false, panelTab) { u -> p.edit().putString("webUrl", u).apply(); load(u); setModeRef(0) })
                listBox.visibility = View.VISIBLE
            } else { listBox.visibility = View.GONE; padFrame.minimumHeight = 0 }
            modeUi()
            suppress = true; keyEdit.setText(""); suppress = false
            if (m == 1 || m == 2) {
                if (m == 2) { urlEdit.setText(curUrl ?: loadedUrl ?: p.getString("webUrl", HOME) ?: HOME); urlEdit.selectAll() }
                openInputWin(if (m == 1) keyEdit else urlEdit)
            } else {
                inputBox?.let { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
                closeInputWin()
            }
        }
        setModeRef = ::setMode
        padDownRef = { if (mode == 2) setMode(0) }
        closeInput = { closeInputWin() }
        keyEdit.apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {}
                override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                    if (suppress || s == null) return
                    repeat(before) { deleteChar() }
                    if (count > 0) typeText(s.substring(st, st + count))
                }
            })
            setOnEditorActionListener { v, _, _ -> pressEnter(); suppress = true; (v as android.widget.EditText).setText(""); suppress = false; true }
            setOnKeyListener { v, code, ev ->
                if (code == android.view.KeyEvent.KEYCODE_DEL && ev.action == android.view.KeyEvent.ACTION_DOWN && (v as android.widget.EditText).text.isEmpty()) { deleteChar(); true } else false
            }
        }
        urlEdit.setOnEditorActionListener { v, _, _ ->
            val u = toUrl((v as android.widget.EditText).text.toString())
            if (u != null) { p.edit().putString("webUrl", u).apply(); load(u); setMode(0) }
            true
        }
        keysOpen = { if (mode == 0) setMode(1) }
        // Browser chrome: bookmark star, current address (tap to edit) and a load-progress line.
        val star = icon(IconButton.Kind.STAR) {
            val u = curUrl
            if (u != null) { WebStore.toggleBookmark(p, u, curTitle); navUi?.invoke() }
        }
        val progFill = View(ctx).apply { setBackgroundColor(0xFFA3E635.toInt()); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f) }
        val prog = LinearLayout(ctx).apply {
            weightSum = 100f; addView(progFill)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(4) }
        }
        val tabBtn = IconButton(ctx, IconButton.Kind.TABS).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(hdr), 1f)
            setOnClickListener { setMode(if (mode == 4) 0 else 4) }
        }
        val statsTv = TextView(ctx).apply {   // temporary video diagnostics, over the bottom-left of the picture
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); setShadowLayer(6f * d, 0f, 0f, 0xFF000000.toInt()); visibility = View.GONE
        }
        val tuneBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()); setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(statsTv)
        }
        navUi = {
            statsTv.visibility = View.GONE   // diagnostics overlay removed
            tabBtn.badge = tabs.size.toString()
            val canFwd = web?.canGoForward() == true
            fwdBtn?.let { it.alpha = if (canFwd) 1f else 0.3f; it.isClickable = canFwd }   // nothing to go forward to: look and act disabled
            star.on = curUrl?.let { WebStore.isBookmarked(p, it) } == true
            title.text = curTitle.ifBlank { (curUrl ?: loadedUrl ?: "").removePrefix("https://").removePrefix("http://") }.ifEmpty { "Tap to enter an address" }
            (progFill.layoutParams as LinearLayout.LayoutParams).weight = if (loadPct in 1..99) loadPct.toFloat() else 0f
            progFill.requestLayout()
        }
        modeUi = { tabBtn.on = mode == 4 }
        navUi?.invoke()
        // A fine line around each corner (the window's 20 dp radius); drag one to resize from that corner (same picture shape, just bigger).
        val cr = p.getInt("flCorner", 0).coerceIn(0, 40); val cz = CornerLine.zoneDp(cr)   // the radius set for all floating windows
        fun corner(left: Boolean, right: Boolean, top: Boolean, bottom: Boolean): View {
            val dm = ctx.resources.displayMetrics
            var w0 = 0; var h0 = 0; var x0 = 0; var y0 = 0
            return CornerLine(ctx, cr, left, top, { w0 = padW; h0 = padH; x0 = lp.x; y0 = lp.y }, { ddx, ddy ->
                val ex = if (right) ddx else -ddx; val ey = if (bottom) ddy else -ddy
                val nw = (w0 + (ex + ey * (if (land) 16f / 9 else 9f / 16)) / 2).toInt().coerceIn(dp(200), maxPadW(dm))
                val nx = if (left) x0 + w0 - nw else x0; val ny = if (top) y0 + h0 - padHFor(nw) else y0
                val grow = padHFor(nw) - padH
                // bumping into another window, or off the screen: stop growing (height is wrap-content, so add the picture's growth)
                if (nx >= 0 && ny >= 0) {   // windows may overlap now, so only the screen edge stops the growth
                    padW = nw; padH = padHFor(padW); lp.x = nx; lp.y = ny; winX = nx; winY = ny; applySize()
                }
            }, { p.edit().putInt("webFloatW", padW).putInt("webFloatX", lp.x).putInt("webFloatY", lp.y).apply() })
        }
        val cTL = corner(true, false, true, false); val cTR = corner(false, true, true, false); val cBL = corner(true, false, false, true); val cBR = corner(false, true, false, true)
        listOf(cTL, cTR, cBL, cBR).forEach { it.visibility = View.GONE }   // only shown while the pencil is on
        // Row 1 (same as the camera windows): title/drag handle + Hide. Row 2: page controls as icons.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(handle)
                addView(FrameLayout(ctx).also { grp ->
                    grp.layoutParams = LinearLayout.LayoutParams(2 * btn + dp(10), dp(hdr))
                    grp.addView(IconButton(ctx, IconButton.Kind.EDIT, halo = true).apply {
                        setOnClickListener {
                            on = !on; val v = if (on) View.VISIBLE else View.GONE; listOf(cTL, cTR, cBL, cBR).forEach { it.visibility = v }
                            grp.layoutParams = LinearLayout.LayoutParams(2 * btn + dp(10) + (if (on) dp(cz) else 0), dp(hdr))   // buttons clear of the top-right corner while resizing
                        }
                    }, FrameLayout.LayoutParams(btn, btn, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(3) })
                    grp.addView(IconButton(ctx, IconButton.Kind.HIDE, halo = true).apply { setOnClickListener { floatMin = true; hideFloat(); showBubble() } },
                        FrameLayout.LayoutParams(btn, btn, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(5) + btn })
                })
            })
            addView(prog)
        }
        // "More" menu at the bottom right, drawn over the picture: the less used buttons live here to keep the control row short
        fun menuItem(kind: IconButton.Kind, label: () -> String, onClick: () -> Unit): LinearLayout {
            val tv = TextView(ctx).apply { text = label(); textSize = 22f; typeface = ZFont.reg; setTextColor(0xFFF5F5F7.toInt()); gravity = Gravity.CENTER_VERTICAL }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = label
                setPadding(dp(8), 0, dp(18), 0); background = rounded(0xFF2C2C2E.toInt(), 12)
                addView(IconButton(ctx, kind, chip = 0x00000000).apply { isClickable = false }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { onClick(); tv.text = label(); (menuBox?.parent as? View)?.visibility = View.GONE; moreBtn?.on = false }
            }
        }
        val rowH = dp(56)
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(0xF01C1C1E.toInt(), 16, 0xFF3A3A3C.toInt())
            minimumWidth = dp(300)
            val rowLp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH).apply { topMargin = dp(3); bottomMargin = dp(3) } }   // every row the same size
            addView(menuItem(IconButton.Kind.FULL, { "Full screen" }) { setFullScreen(true) }, rowLp())
            addView(menuItem(IconButton.Kind.DESKTOP, { "This tab: " + (if (tabs.getOrNull(curTab)?.desktop == true) "Desktop site" else "Mobile site") }) { toggleDesktop() }, rowLp())
            addView(menuItem(IconButton.Kind.STAR, { "Bookmarks" }) { panelTab = 0; setMode(3) }, rowLp())
            addView(menuItem(IconButton.Kind.HISTORY, { "History" }) { panelTab = 1; setMode(3) }, rowLp())
            addView(menuItem(IconButton.Kind.DOWNLOAD, { "Downloads" }) { panelTab = 2; setMode(3) }, rowLp())
            addView(menuItem(IconButton.Kind.DESKTOP, { "Debug stats: " + (if (p.getBoolean("webDebug", false)) "On" else "Off") }) { p.edit().putBoolean("webDebug", !p.getBoolean("webDebug", false)).apply(); web?.reload() }, rowLp())
        }
        val host = android.widget.ScrollView(ctx).apply { visibility = View.GONE; addView(menu) }
        menuBox = menu
        menuToggle = {
            val on = host.visibility != View.VISIBLE
            if (on) {
                for (i in 0 until menu.childCount) (menu.getChildAt(i) as? LinearLayout)?.let { row -> @Suppress("UNCHECKED_CAST") (row.tag as? (() -> String))?.let { (row.getChildAt(1) as? TextView)?.text = it() } }
                host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply { height = minOf(6 * (rowH + dp(6)) + dp(16), (padH - dp(16)).coerceAtLeast(dp(120))) }
            }
            host.visibility = if (on) View.VISIBLE else View.GONE; moreBtn?.on = on
        }
        val padDown0 = padDownRef
        padDownRef = { padDown0(); if (host.visibility == View.VISIBLE) { host.visibility = View.GONE; moreBtn?.on = false } }
        // Browser controls sit below the picture.
        val ctrl = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(icon(IconButton.Kind.BACK) { back() }); addView(gap())
                addView(icon(IconButton.Kind.FORWARD) { forward() }.also { fwdBtn = it }); addView(gap())
                addView(icon(IconButton.Kind.RELOAD) { reload() }); addView(gap())
                addView(star); addView(gap())
                addView(tabBtn); addView(gap())
                addView(icon(IconButton.Kind.ROTATE) {
                    // portrait <-> landscape: the picture's short and long sides swap, on the HUD box too
                    val dm = ctx.resources.displayMetrics
                    land = !land
                    padW = padH.coerceIn(dp(200), maxPadW(dm)); padH = padHFor(padW)
                    lp.x = lp.x.coerceIn(0, (dm.widthPixels - padW - dp(16)).coerceAtLeast(0)); winX = lp.x
                    p.edit().putBoolean("webLand", land).putInt("webFloatW", padW).putInt("webFloatX", lp.x).apply()
                    hideFloat()   // rebuilt once the HUD box has resized, or the copy stays grey
                    main.postDelayed({ if (running) showFloat() }, 500)
                    main.postDelayed({ web?.reload() }, 600)   // re-lay the page out for the new shape
                }); addView(gap())
                addView(icon(IconButton.Kind.MORE) { menuToggle() }.also { moreBtn = it })
            })
        }
        // nothing loaded yet: open the address bar straight away
        if (loadedUrl == null && (p.getString("webUrl", HOME) ?: HOME).isEmpty()) main.postDelayed({ if (floatRoot != null && mode == 0) setMode(2) }, 400)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(0xB3000000.toInt(), cr)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; clipToOutline = true
            addView(bar)
            addView(padFrame.apply { addView(pad); addView(listBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); addView(tuneBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)); addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(8)) }) })
            addView(ctrl)
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) main.post { raiseFloat() }; true }
        }
        // the corner lines wrap the real window corners, so they sit in a shell around the box
        val shell = FrameLayout(ctx).apply {
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(cTL, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.TOP or Gravity.START)); addView(cTR, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.TOP or Gravity.END))
            addView(cBL, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.BOTTOM or Gravity.START)); addView(cBR, FrameLayout.LayoutParams(dp(cz), dp(cz), Gravity.BOTTOM or Gravity.END))
        }
        try {
            lp.alpha = FloatAnim.startAlpha()
            ctx.getSystemService(WindowManager::class.java).addView(shell, lp)
            floatRoot = shell; floatPad = pad as? TextureView; floatLp = lp
            if (lp.alpha < 1f) FloatAnim.genie(ctx.getSystemService(WindowManager::class.java), shell, lp, true) {}
            main.post { OverlayTop.raise(ctx) }   // dock + floating button stay above this window
            FloatPush.register("web", FloatPush.Item(lp, { floatRoot }, ctx.getSystemService(WindowManager::class.java)) { x, y ->
                winX = x; winY = y; p.edit().putInt("webFloatX", x).putInt("webFloatY", y).apply() })
            FloatMaster.webAddedSeq++
            if (!direct) main.post(floatDraw)
        } catch (e: Throwable) { Logger.log(ctx, "webhud: float failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    /**
     * Full-screen mode: a real activity ([FullWebActivity]) shows the page, so the car's own panels (aircon, seats, opened from its bottom
     * bar) can draw over it, which an overlay window would hide. A 1px window stands in as floatRoot so the tick logic is unchanged.
     * The dock (Back, Refresh ... Hide bar) stays an overlay above the activity.
     */
    private var fullAct: android.app.Activity? = null
    private var fullAr = 0f   // width / height of the activity's picture area, once known

    // The full-screen page is its own WebView inside the activity (laid out for the centre screen); the HUD / window page is left alone.
    private var fsWeb: WebView? = null
    private var fsSavedWeb: WebView? = null
    fun attachFull(a: android.app.Activity, frame: FrameLayout) {
        fullAct = a
        val t0 = tabs.getOrNull(curTab)
        val url = curUrl ?: t0?.url ?: HOME
        val ft = Tab(null, url, desktop = t0?.desktop == true)
        val wv = makeWebView(a, frame, ft)
        wv.setInitialScale(0)   // normal scale: the HUD page is drawn at a multiple of its size, this one is not
        ft.wv = wv
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fsSavedWeb = web
        web?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();})", null)   // the page behind stops playing
        fsWeb = wv; web = wv
        curUrl = url; loadedUrl = url
        wv.loadUrl(url)
    }
    /** System back in full screen: first back through the page, then leave. */
    fun fullBack(): Boolean { val w = fsWeb ?: return false; return if (w.canGoBack()) { w.goBack(); true } else false }
    private fun endFull() {
        val w = fsWeb ?: return
        fsWeb = null
        val u = w.url
        (w.parent as? ViewGroup)?.removeView(w)
        try { w.destroy() } catch (_: Throwable) {}
        web = fsSavedWeb; fsSavedWeb = null
        val t = tabs.getOrNull(curTab)
        if (t != null && !u.isNullOrEmpty() && u != t.url) { t.url = u; t.wv?.loadUrl(u); curUrl = u; loadedUrl = u; saveTabs() }
        if (t != null && t.wv !== web) switchTab(curTab)   // the tab was changed while in full screen
    }
    fun fullGone(a: android.app.Activity) {
        if (fullAct !== a) return
        fullAct = null
        if (fullScreen) main.post { setFullScreen(false) }   // closed with the system back button
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFull() {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
        val box = LinearLayout(ctx)
        try {
            wm.addView(box, lp)
            floatRoot = box; floatLp = lp
            ctx.startActivity(android.content.Intent(ctx, FullWebActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // Back / Exit live in the top-centre dock, next to the minimized camera windows (which collapse into it now)
            BubbleRow.add(ctx, "fsBack", "Back", IconButton.Kind.BACK) { back() }
            BubbleRow.add(ctx, "fsReload", "Refresh", IconButton.Kind.RELOAD) { reload() }
            BubbleRow.add(ctx, "fsHome", "Home", IconButton.Kind.HOME) { closeFsWins(); p.edit().putString("webUrl", HOME).apply(); load(HOME) }
            BubbleRow.add(ctx, "fsUrl", "Address", IconButton.Kind.GLOBE) { if (fsWin != null && fsKind == 1) closeFsWins() else openFsUrl() }
            BubbleRow.add(ctx, "fsList", "Bookmarks", IconButton.Kind.LIST) { if (fsWin != null && fsKind == 2) closeFsWins() else openFsList() }
            fun markChip() {
                BubbleRow.remove(ctx, "fsMark")
                val saved = curUrl?.let { WebStore.isBookmarked(p, it) } == true
                BubbleRow.add(ctx, "fsMark", "Bookmark", IconButton.Kind.STAR, saved) {
                    curUrl?.let { WebStore.toggleBookmark(p, it, curTitle) }; markChip()
                }
            }
            markChip()
            fun tabChip() {
                BubbleRow.remove(ctx, "fsTabs")
                BubbleRow.add(ctx, "fsTabs", "Tabs", IconButton.Kind.TABS, false, tabs.size.toString()) { if (fsWin != null && fsKind == 3) closeFsWins() else openFsTabs() }
            }
            tabChip(); tabsUi = { tabChip() }
            BubbleRow.add(ctx, "fsExit", "Exit full screen", IconButton.Kind.UNFULL) { setFullScreen(false) }
            fun hideChip() {
                BubbleRow.remove(ctx, "fsHide")
                BubbleRow.add(ctx, "fsHide", "Hide bar", if (barHidden) IconButton.Kind.DOWN else IconButton.Kind.UP) { barHidden = !barHidden; BubbleRow.setCompact(ctx, barHidden); hideChip() }
            }
            hideChip()
            main.post { OverlayTop.raise(ctx) }   // dock + floating button stay above
            closeInput = { closeFsWins(); fullAct?.finish(); fullAct = null }
            FloatMaster.webAddedSeq++
            main.post(floatDraw)
        } catch (e: Throwable) { Logger.log(ctx, "webhud: full screen failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    // Full screen address bar / bookmark list: a window under the dock (fsKind 1 = address, 2 = bookmarks)
    private var fsWin: View? = null
    private var fsKind = 0
    private fun closeFsWins() {
        val v = fsWin ?: return
        fsWin = null; fsKind = 0
        try { ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) } catch (_: Throwable) {}
        try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }
    private fun openFsUrl() {
        closeFsWins()
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        fun rounded(fill: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = r * d }
        val edit = android.widget.EditText(ctx).apply {
            hint = "Address or search"; setTextColor(0xFFF5F5F7.toInt()); setHintTextColor(0xFF98989D.toInt())
            textSize = 18f; typeface = ZFont.reg; isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO; background = rounded(0xFF2C2C2E.toInt(), 12)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), 0, dp(14), 0)
            setText(curUrl ?: loadedUrl ?: p.getString("webUrl", HOME) ?: HOME); selectAll()
            setOnEditorActionListener { v, _, _ ->
                val u = toUrl((v as android.widget.EditText).text.toString())
                if (u != null) { p.edit().putString("webUrl", u).apply(); load(u); closeFsWins() }
                true
            }
        }
        val done = TextView(ctx).apply {
            text = "Done"; gravity = Gravity.CENTER; textSize = 16f; typeface = ZFont.reg; setTextColor(0xFF1C1C1E.toInt())
            background = rounded(0xFFA3E635.toInt(), 12); setOnClickListener { closeFsWins() }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF1C1C1E.toInt(), 18)
            addView(edit, LinearLayout.LayoutParams(0, dp(56), 1f)); addView(done, LinearLayout.LayoutParams(dp(88), dp(56)).apply { marginStart = dp(10) })
        }
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(32), dp(900)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(112)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        try { ctx.getSystemService(WindowManager::class.java).addView(box, lp); fsWin = box; fsKind = 1 } catch (e: Throwable) { Logger.log(ctx, "webhud: address window failed ${e.message ?: ""}"); return }
        edit.requestFocus()
        main.postDelayed({ ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 250)
        main.post { OverlayTop.raise(ctx) }
    }
    private fun openFsTabs() {
        closeFsWins()
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val dm = ctx.resources.displayMetrics
        val h = minOf(dm.heightPixels - dp(260), dp(560))
        val box = FrameLayout(ctx).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius = 18 * d }
            addView(tabPanel(h - dp(16)) { isNew -> closeFsWins(); if (isNew) openFsUrl() })
        }
        val lp = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(32), dp(620)), h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(112)
        }
        try { ctx.getSystemService(WindowManager::class.java).addView(box, lp); fsWin = box; fsKind = 3 } catch (e: Throwable) { Logger.log(ctx, "webhud: tabs window failed ${e.message ?: ""}"); return }
        main.post { OverlayTop.raise(ctx) }
    }
    private fun openFsList() {
        closeFsWins()
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val dm = ctx.resources.displayMetrics
        val h = minOf(dm.heightPixels - dp(260), dp(620))
        val box = FrameLayout(ctx).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius = 18 * d }
            addView(WebStore.panel(ctx, p, h - dp(8 + 64 + 8 + 8), false) { u -> p.edit().putString("webUrl", u).apply(); load(u); closeFsWins() })
        }
        val lp = WindowManager.LayoutParams(minOf(dm.widthPixels - dp(32), dp(760)), h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(112)
        }
        try { ctx.getSystemService(WindowManager::class.java).addView(box, lp); fsWin = box; fsKind = 2 } catch (e: Throwable) { Logger.log(ctx, "webhud: bookmarks window failed ${e.message ?: ""}"); return }
        main.post { OverlayTop.raise(ctx) }
    }

    private var barHidden = false   // full screen: the dock is collapsed to its Show bar chip

    private fun setFullScreen(on: Boolean) {
        if (on && fullScreen) return
        fullScreen = on
        if (on) { FloatMaster.minimizeSeq++; minSeen = FloatMaster.minimizeSeq }   // other floating windows collapse to the dock; this one stays
        else endFull()
        hideFloat()   // rebuilt by the tick (the window again, or the full-screen stand-in)
    }

    // Collapsed state: a bubble in the shared top-centre row (see BubbleRow); a tap brings the control window back.
    private fun showBubble(): Unit = BubbleRow.add(ctx, "web", "Web", IconButton.Kind.GLOBE) { floatMin = false; FloatMaster.shownAt = android.os.SystemClock.uptimeMillis(); hideBubble(); showFloat() }

    private fun hideBubble(): Unit = BubbleRow.remove(ctx, "web")

    // Tapping the window while it overlaps another brings it to the front (re-added on top).
    fun restack() { if (floatRoot != null && !fullScreen) { hideFloat(); showFloat() } }
    // Re-add the same window on top (overlay windows stack by add order); its SurfaceTexture is kept, so no rebuild / fade-in blink.
    private var raising = false
    private fun raiseFloat() {
        val r = floatRoot ?: return
        if (!FloatPush.overlapsOther("web")) return
        val l = r.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = ctx.getSystemService(WindowManager::class.java)
        raising = true
        try { l.alpha = 1f; wm.removeViewImmediate(r); wm.addView(r, l) } catch (_: Throwable) {} finally { raising = false }
    }

    /** Address-bar text to a URL: a bare word or anything with spaces is searched, the rest gets https:// if missing. */
    private fun toUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return if (t.contains(' ') || !t.contains('.')) "https://www.google.com/search?q=" + android.net.Uri.encode(t) else normalize(t)
    }

    private fun hideFloat() {
        hideBubble(); BubbleRow.remove(ctx, "fsBack"); BubbleRow.remove(ctx, "fsReload"); BubbleRow.remove(ctx, "fsHome"); BubbleRow.remove(ctx, "fsUrl"); BubbleRow.remove(ctx, "fsList"); BubbleRow.remove(ctx, "fsMark"); BubbleRow.remove(ctx, "fsTabs"); tabsUi = null; BubbleRow.remove(ctx, "fsExit"); BubbleRow.remove(ctx, "fsHide"); barHidden = false; BubbleRow.setCompact(ctx, false)
        val r = floatRoot ?: return
        closeInput?.invoke(); closeInput = null
        floatRoot = null; floatPad = null; keysOpen = null; navUi = null
        if (direct) { floatPadFrame = null; parkBig() }   // the page keeps playing in the park window
        FloatPush.unregister("web")
        main.removeCallbacks(floatDraw)
        try { ctx.getSystemService(WindowManager::class.java).removeView(r) } catch (_: Throwable) {}
    }

    // ---- controls (main thread, called from MainActivity) ----

    /** Loads [url] now (also saved by the caller). */
    fun load(url: String) { loadedUrl = url; web?.loadUrl(url) }
    fun back() { val w = web ?: return; if (customView != null) customCb?.onCustomViewHidden() else if (w.canGoBack()) w.goBack() }
    fun reload() { web?.reload() }
    fun forward() { val w = web ?: return; if (w.canGoForward()) w.goForward() }
    fun pauseMedia() {
        main.post { web?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();})", null) }
    }

    // Typing: the HUD window never takes focus/IME, so insert into the page's focused element by script.
    fun typeText(t: String) {
        val q = org.json.JSONObject.quote(t)
        web?.evaluateJavascript("(function(t){var e=document.activeElement;if(!e)return;" +
            "if(!document.execCommand('insertText',false,t)&&'value' in e){" +
            "var pr=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "Object.getOwnPropertyDescriptor(pr,'value').set.call(e,e.value+t);" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));}})($q)", null)
    }
    fun deleteChar() {
        web?.evaluateJavascript("(function(){var e=document.activeElement;if(!e)return;" +
            "if(!document.execCommand('delete')&&'value' in e){" +
            "var pr=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "Object.getOwnPropertyDescriptor(pr,'value').set.call(e,e.value.slice(0,-1));" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));}})()", null)
    }
    fun pressEnter() {
        web?.evaluateJavascript("(function(){var e=document.activeElement;if(!e)return;" +
            "['keydown','keypress','keyup'].forEach(function(n){e.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});" +
            "if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}})()", null)
    }

    /** Forwards one touch to the page ([nx], [ny] are 0..1 of the pad). */
    private fun send(action: Int, downTime: Long, eventTime: Long, nx: Float, ny: Float) {
        val w = web ?: return
        val target = customView ?: w
        val ev = MotionEvent.obtain(downTime, eventTime, action, nx * target.width, ny * target.height, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }
}
