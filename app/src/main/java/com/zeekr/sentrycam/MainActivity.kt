package com.zeekr.sentrycam

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.URL
import java.util.UUID

private data class Quad(val id: Int, val key: String, val names: List<String>, val label: String)

class MainActivity : AppCompatActivity() {
    // No relay URL is built into this app -- it only ever talks to whatever Worker the user
    // deploys for themselves (see the phone app's Account -> Automation flow). Pasted here once.

    private lateinit var p: android.content.SharedPreferences
    private lateinit var status: TextView
    private lateinit var svcStatus: TextView   // service / capture messages, shown in the Sentry card
    private fun svc(t: String) { svcStatus.text = t; svcStatus.visibility = View.VISIBLE }
    private val ui = Handler(Looper.getMainLooper())
    private var mode = ZTheme.Mode.DARK
    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
    private var rightScroll: ScrollView? = null   // the display column on wide screens (the only thing that scrolls once linked)
    private var bsSide = "R"   // which side the HUD sliders edit and preview
    @Volatile private var polling = false
    private var mirrorLoop: Runnable? = null
    private var mirrorRunning = false
    // "Offline snapshots" line: only shown while some are waiting; refreshed every 3s while the app is in front.
    private var queueLoopOn = false
    private val queueLoop = object : Runnable {
        override fun run() {
            if (!WebHud.appVisible) { queueLoopOn = false; return }
            val tv = findViewById<TextView>(R.id.queueInfo)
            Thread {
                val (n, bytes) = SnapQueue.stats(this@MainActivity)
                ui.post {
                    tv.visibility = if (n > 0) View.VISIBLE else View.GONE
                    if (n > 0) tv.text = "Offline snapshots waiting to upload: $n (" + String.format(java.util.Locale.US, "%.1f", bytes / 1048576.0) + " MB). Sent automatically when the relay is reachable."
                }
            }.start()
            ui.postDelayed(this, 3000)
        }
    }
    private fun startQueueLoop() { if (queueLoopOn) return; queueLoopOn = true; ui.post(queueLoop) }
    private fun startMirror() { val m = mirrorLoop ?: return; if (mirrorRunning) return; mirrorRunning = true; ui.post(m) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ZScale.pin(this)
        setContentView(R.layout.activity_main)
        p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        // first ever opening (no saved settings at all): offer the default settings once; upgrades and later openings are never asked
        val askDefaults = !p.contains("defaultsAsked") && p.all.isEmpty()
        if (!p.contains("defaultsAsked")) p.edit().putBoolean("defaultsAsked", true).apply()
        // record every change of screen size / density / font scale (the screen sometimes shrinks on its own; this shows whether the system did it)
        fun uiInfo() = resources.displayMetrics.let { m -> "${m.widthPixels}x${m.heightPixels} density=${m.density} pinned=${ZScale.density(this)} dpi=${m.densityDpi} fontScale=${resources.configuration.fontScale} sw=${resources.configuration.smallestScreenWidthDp}dp" }
        Logger.log(this, "ui: onCreate ${uiInfo()}")
        var lastUi = ""
        findViewById<View>(R.id.scroll).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val u = uiInfo(); if (u != lastUi) { if (lastUi.isNotEmpty()) Logger.log(this, "ui: changed $lastUi -> $u"); lastUi = u }
        }
        status = findViewById(R.id.status)
        svcStatus = findViewById(R.id.svcStatus)
        mode = ZTheme.Mode.of(p.getString("theme", null))
        findViewById<TextView>(R.id.ver).text = "v" + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }
        mapOf(R.id.themeDark to ZTheme.Mode.DARK, R.id.themeBlack to ZTheme.Mode.BLACK, R.id.themeLight to ZTheme.Mode.LIGHT)
            .forEach { (id, m) -> findViewById<Button>(id).setOnClickListener { mode = m; p.edit().putString("theme", m.key).apply(); applyTheme() } }
        // Two columns on a wide screen (the car), stacked on a narrow one.
        if (resources.configuration.screenWidthDp >= 900) {
            // wide: the left (service) column stays put and only the right (display) column scrolls; see fixColumns()
            val op = findViewById<LinearLayout>(R.id.opBox); val right = findViewById<LinearLayout>(R.id.colLeft)
            val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            val leftCol = findViewById<LinearLayout>(R.id.colRight); val sv2 = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
            op.removeView(leftCol); op.removeView(right)
            leftCol.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            sv2.addView(leftCol); op.addView(sv2, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 28f))
            right.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            sv.addView(right)
            op.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 72f).apply { marginStart = (20 * ZScale.density(this@MainActivity)).toInt() })
            rightScroll = sv
        }
        if (resources.configuration.screenWidthDp < 900) {
            findViewById<LinearLayout>(R.id.opBox).orientation = LinearLayout.VERTICAL
            for (id in intArrayOf(R.id.colLeft, R.id.colRight)) findViewById<View>(id).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        findViewById<EditText>(R.id.relayUrl).setText(p.getString("url", "") ?: "")
        findViewById<Button>(R.id.relaySave).setOnClickListener {
            val url = findViewById<EditText>(R.id.relayUrl).text?.toString()?.trim()?.trimEnd('/') ?: ""
            if (url.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) {
                Toast.makeText(this, "Paste a full URL, e.g. https://your-worker.workers.dev", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            p.edit().putString("url", url).apply()
            showState()
        }

        findViewById<Button>(R.id.standaloneBtn).setOnClickListener {
            p.edit().putBoolean("standalone", true).apply(); showState()
        }
        findViewById<Button>(R.id.setupQrNew).setOnClickListener { stopSetupListen(); startSetupListen() }
        findViewById<CompoundButton>(R.id.setupQrOn).apply {
            isChecked = p.getBoolean("setupQrOn", true)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("setupQrOn", on).apply()
                stopSetupListen()
                if (on) startSetupListen() else { showSetupQr(false); findViewById<View>(R.id.setupQrNew).visibility = View.GONE }
            }
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { buildPreviewList() }
        findViewById<Button>(R.id.toCode).setOnClickListener { showCodeStage() }
        findViewById<Button>(R.id.backToId).setOnClickListener { showIdentifyStage() }
        findViewById<Button>(R.id.start).setOnClickListener {
            if (!ensurePerms()) return@setOnClickListener
            p.edit().putBoolean("suspended", false).apply()   // manual start clears a relay-suspend
            requestNoBatteryOpt()
            val i = Intent(this, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            svc("Service started — keeps listening while parked. (Allow 'unrestricted battery' if asked.)")
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, CamService::class.java)); svc("Service stopped.")
        }
        findViewById<Button>(R.id.test).setOnClickListener { testCapture() }
        findViewById<Button>(R.id.viewLog).setOnClickListener { showLogDialog() }
        findViewById<Button>(R.id.viewLog).setOnLongClickListener {
            Thread {
                CarProbe.run(this)
                ChargeTest.run(this)
                val p =getSharedPreferences("cfg", Context.MODE_PRIVATE)
                val base = p.getString("url", "").orEmpty().trimEnd('/'); val token = p.getString("token", "")
                if (base.isNotEmpty() && !token.isNullOrEmpty()) Net.postText("$base/cam/log/upload", token, Logger.read(this))
            }.start()
            Toast.makeText(this, "Car probe running", Toast.LENGTH_SHORT).show(); true
        }
        findViewById<Button>(R.id.applyDefaults).setOnClickListener {
            ZDialog.show(this, "Apply the default settings?",
                "This sets up the windows, camera tuning, HUD, web window, engine sound, driving profiles, trip widget and theme the way the app's author uses them. Your current settings of those are replaced. Your relay link, bookmarks and history are kept.", "Apply") { applyDefaultSettings() }
        }
        findViewById<Button>(R.id.restartApp).setOnClickListener {
            ZDialog.show(this, "Close the app?", "This stops the floating windows, HUD and engine sound until you open Z Cam again.", "Close app") { forceCloseApp() }
        }
        findViewById<Button>(R.id.credits).setOnClickListener { showCreditsDialog() }
        findViewById<Button>(R.id.whatsNew).setOnClickListener { showChangelogDialog() }
        findViewById<Button>(R.id.resetAll).setOnClickListener {
            ZDialog.show(this, "Reset all settings?",
                "Windows, camera tuning, HUD, web, engine sound, profiles and trip widget settings, bookmarks and history go back to default, then the app restarts. Your relay link stays. The relay backup is not touched.", "Reset") {
                val e = p.edit(); for (k in p.all.keys.filter { backupKey(it) || it.startsWith("fm") }) e.remove(k); e.commit()
                Logger.log(this, "all settings reset to default")
                restartApp()
            }
        }
        findViewById<Button>(R.id.unlink).setOnClickListener {
            // Standalone: the button becomes "Link to Z app" and reopens the relay setup.
            fun go() {
                if (standalone()) p.edit().putBoolean("standalone", false).apply() else p.edit().remove("token").apply()
                stopService(Intent(this, CamService::class.java)); showState()
            }
            if (standalone()) go() else ZDialog.show(this, "Unlink ZDash?",
                "Remote snapshots, live view and clips stop until you link this car again.", "Unlink") { go() }
        }
        setupBackup()
        findViewById<Button>(R.id.pickSentryFolder).setOnClickListener { pickSentryFolder() }
        findViewById<CompoundButton>(R.id.bsSignal).apply {
            isChecked = p.getBoolean("bsSignal", true)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("bsSignal", on).apply()
                if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
                }
                val i = Intent(this@MainActivity, CamService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        }
        fun slPrevLabel() { findViewById<Button>(R.id.slPreview).text = if (p.getLong("slPreviewUntil", 0L) > System.currentTimeMillis()) "Hide preview" else "Show preview of sign and alert" }
        findViewById<Button>(R.id.slPreview).setOnClickListener {
            if (!p.getBoolean("slOn", false)) { Toast.makeText(this, "Turn on the speed limit sign first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val now = System.currentTimeMillis()
            if (p.getLong("slPreviewUntil", 0L) > now) p.edit().putLong("slPreviewUntil", 0L).apply()   // shown: hide it
            else p.edit().putLong("slPreviewUntil", now + 600_000).putLong("slBeepPrev", now).apply()   // hidden: show it (stays until pressed again) and beep once
            SpeedLimitHud.instance?.kick()
            slPrevLabel()
        }
        slPrevLabel()
        fun slSeek(id: Int, lab: Int, key: String, def: Int, min: Int, name: String) {
            val l = findViewById<TextView>(lab)
            findViewById<SeekBar>(id).apply {
                progress = p.getInt(key, def).coerceIn(min, min + max) - min
                l.text = "$name: ${progress + min}%"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        l.text = "$name: ${v + min}%"
                        if (fromUser) { p.edit().putInt(key, v + min).apply(); SpeedLimitHud.instance?.kick() }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
        }
        slSeek(R.id.slRing, R.id.slRingLabel, "slRing", 28, 8, "Red border thickness")
        slSeek(R.id.slNum, R.id.slNumLabel, "slNum", 100, 50, "Number size")
        findViewById<CompoundButton>(R.id.slBeep).apply {
            isChecked = p.getBoolean("slBeep", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("slBeep", on).apply() }
        }
        findViewById<CompoundButton>(R.id.slGlow).apply {
            isChecked = p.getBoolean("slGlow", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("slGlow", on).apply() }
        }
        findViewById<CompoundButton>(R.id.slOn).apply {
            isChecked = p.getBoolean("slOn", false)
            fun apply(on: Boolean) {
                p.edit().putBoolean("slOn", on).putLong("slPreviewUntil", if (on) System.currentTimeMillis() + 6000 else 0L).apply()
                if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    catch (_: Throwable) { Toast.makeText(this@MainActivity, "Allow 'Display over other apps' for this app", Toast.LENGTH_LONG).show() }
                }
                val i = Intent(this@MainActivity, CamService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
            setOnCheckedChangeListener { btn, on ->
                if (!on) { apply(false); return@setOnCheckedChangeListener }
                ZDialog.show(this@MainActivity, "Speed limit: for reference only",
                    "Where the limit comes from: OpenStreetMap, a volunteer-edited map, looked up online from the car's GPS position (public servers: Overpass, or Nominatim as a fallback). Z_Cam does not read real road signs.\n\n" +
                    "How reliable: speed limits can be missing, outdated or wrong, temporary and variable limits (roadworks, smart motorways, school zones) are not known, and GPS drift can pick a neighbouring road. The sign can disappear when the road has no limit in the map or the lookup is slow or offline.\n\n" +
                    "Use it only as a hint. The posted road signs and the law always come first, and you stay responsible for your speed.",
                    "I understand", "Cancel", cancelable = false, onNegative = { btn.isChecked = false }) { apply(true) }
            }
        }
        findViewById<CompoundButton>(R.id.bsHud).apply {
            isChecked = p.getBoolean("bsHud", false)
            setOnCheckedChangeListener { _, on ->
                p.edit().putBoolean("bsHud", on).apply()
                if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: Throwable) {
                        Toast.makeText(this@MainActivity, "Allow 'Display over other apps' for this app", Toast.LENGTH_LONG).show()
                    }
                }
                // Running service picks the pref up from a fresh start intent.
                val i = Intent(this@MainActivity, CamService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            }
        }
        fun syncService() {
            val i = Intent(this, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        fun needOverlay() {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Throwable) { Toast.makeText(this, "Allow 'Display over other apps' for this app", Toast.LENGTH_LONG).show() }
            }
        }
        findViewById<CompoundButton>(R.id.parkCut).apply {
            isChecked = p.getBoolean("parkCut", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("parkCut", on).apply(); Park.poke(); syncService() }
        }
        for ((id, key) in listOf(R.id.flOnlyL to "flOnly_L", R.id.flOnlyR to "flOnly_R", R.id.flBlinkCenter to "flBlinkCenter", R.id.flBlackBg to "flBlackBg", R.id.webFloatAlways to "webFloatAlways"))
            findViewById<CompoundButton>(id).apply {
                isChecked = p.getBoolean(key, false)
                setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); syncService() }
            }
        findViewById<CompoundButton>(R.id.campOff).apply {
            isChecked = p.getBoolean("campOff", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("campOff", on).apply(); syncService() }
        }
        findViewById<CompoundButton>(R.id.campLock).apply {
            isChecked = p.getBoolean("campLock", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("campLock", on).apply() }
        }
        run {
            val lbl = findViewById<TextView>(R.id.campMinLabel); val sb = findViewById<SeekBar>(R.id.campMin)
            val cur = p.getInt("campMin", 15).coerceIn(5, 60)
            sb.progress = cur - 5; lbl.text = "Wait: $cur min"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { lbl.text = "Wait: ${v + 5} min"; if (fromUser) p.edit().putInt("campMin", v + 5).apply() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        findViewById<CompoundButton>(R.id.engOn).apply {
            isChecked = p.getBoolean("engOn", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("engOn", on).apply(); syncService() }
        }
        buildEngineCard { syncService() }
        findViewById<CompoundButton>(R.id.pfOn).apply {
            isChecked = p.getBoolean("pfOn", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("pfOn", on).apply(); syncService() }
        }
        buildProfileCard()
        buildTripCard { syncService() }
        buildMascotCard { syncService() }
        findViewById<SeekBar>(R.id.flHdr).apply {
            val lab = this@MainActivity.findViewById<TextView>(R.id.flHdrLabel)
            progress = p.getInt("flHeader", 56).coerceIn(36, 96) - 36
            lab.text = "Window header height: ${progress + 36}"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    lab.text = "Window header height: ${v + 36}"
                    if (fromUser) p.edit().putInt("flHeader", v + 36).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        for ((sid, lid, key, def, fmt) in listOf(
            Quint(R.id.flCorner, R.id.flCornerLabel, "flCorner", 0, "Window corner radius: %d (0 = sharp)"),
            Quint(R.id.flFade, R.id.flFadeLabel, "flFade", 0, "Window edge fade: %d%% (0 = off, hides the frame)")))
            findViewById<SeekBar>(sid).apply {
                val lab = this@MainActivity.findViewById<TextView>(lid)
                progress = p.getInt(key, def).coerceIn(0, max)
                lab.text = String.format(fmt, progress)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = String.format(fmt, v); if (fromUser) p.edit().putInt(key, v).apply() }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) { syncService() }
                })
            }
        findViewById<Button>(R.id.flAnimBtn).apply {
            val keys = listOf("none", "fade", "slide", "zoom"); val names = listOf("None", "Fade", "Slide in from the edge", "Zoom")
            fun paint() { text = "Blinker window animation: " + names[keys.indexOf(p.getString("flAnim", "fade")).coerceAtLeast(0)] + " (tap to change)" }
            paint()
            setOnClickListener { val i = (keys.indexOf(p.getString("flAnim", "fade")).coerceAtLeast(0) + 1) % keys.size; p.edit().putString("flAnim", keys[i]).apply(); paint() }
        }
        findViewById<SeekBar>(R.id.flBlinkSize).apply {
            // 0 = keep the window's own size; 1..15 = grow to 30..100 % of the screen height while the blinker is on (same shape), then back
            val lab = this@MainActivity.findViewById<TextView>(R.id.flBlinkSizeLabel)
            fun txt(v: Int) = "Left / Right: enlarge while the blinker is on: " + (if (v == 0) "off" else "${30 + (v - 1) * 5}% of screen height")
            progress = p.getInt("flBlinkSize", 0).let { if (it <= 0) 0 else ((it - 30) / 5 + 1).coerceIn(1, 15) }
            lab.text = txt(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = txt(v); if (fromUser) p.edit().putInt("flBlinkSize", if (v == 0) 0 else 30 + (v - 1) * 5).apply() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        findViewById<SeekBar>(R.id.flAnimMs).apply {
            val lab = this@MainActivity.findViewById<TextView>(R.id.flAnimMsLabel)
            progress = (p.getInt("flAnimMs", 320).coerceIn(80, 1000) - 80) / 10
            lab.text = "Blinker animation speed: ${progress * 10 + 80} ms"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = "Blinker animation speed: ${v * 10 + 80} ms"; if (fromUser) p.edit().putInt("flAnimMs", v * 10 + 80).apply() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        // Minimize / restore animation of the floating windows (same choices as the HUD exit animation)
        findViewById<Button>(R.id.flMinAnim).apply {
            fun paint() { text = "Minimize animation: " + ExitAnim.NAMES[p.getInt("flMinAnim", 1).coerceIn(0, ExitAnim.NAMES.size - 1)] }
            paint()
            setOnClickListener { ZDialog.choose(this@MainActivity, "Minimize animation", ExitAnim.NAMES, p.getInt("flMinAnim", 1).coerceIn(0, ExitAnim.NAMES.size - 1)) { i -> p.edit().putInt("flMinAnim", i).apply(); paint() } }
        }
        findViewById<SeekBar>(R.id.flMinMs).apply {
            val lab = this@MainActivity.findViewById<TextView>(R.id.flMinMsLabel)
            progress = (p.getInt("flMinMs", 200).coerceIn(100, 1000) - 100) / 10
            lab.text = "Minimize speed: ${progress * 10 + 100} ms"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = "Minimize speed: ${v * 10 + 100} ms"; if (fromUser) p.edit().putInt("flMinMs", v * 10 + 100).apply() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        findViewById<CompoundButton>(R.id.flMaster).apply {
            isChecked = p.getBoolean("flMaster", true)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("flMaster", on).apply(); if (on) needOverlay(); syncService() }
        }
        for ((id, key) in listOf(R.id.flLeft to "flLeft", R.id.flRight to "flRight", R.id.flRear to "flRear", R.id.flFront to "flFront"))
            findViewById<CompoundButton>(id).apply {
                isChecked = p.getBoolean(key, false)
                setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); if (on) needOverlay(); syncService() }
            }
        // Each camera is tuned separately (prefs "<key>_L/_R/_B/_F"). The Live view draws the selected one through the shared CamHub,
        // exactly as the HUD and the floating windows do. Gestures write the same prefs the sliders use.
        val mirror = findViewById<CompoundButton>(R.id.bsMirror)
        val flip = findViewById<CompoundButton>(R.id.bsFlip)
        data class Slider(val id: Int, val labelId: Int, val key: String, val name: String, val unit: String)
        val sliders = listOf(
            Slider(R.id.bsSize, R.id.bsSizeLabel, "bsSize", "HUD picture height", "%"),
            Slider(R.id.bsAspect, R.id.bsAspectLabel, "bsAspect", "HUD picture width", "%"),
            Slider(R.id.bsFov, R.id.bsFovLabel, "bsFov", "View angle", ""),
            Slider(R.id.bsTilt, R.id.bsTiltLabel, "bsTilt", "View tilt up", ""),
            Slider(R.id.bsYaw, R.id.bsYawLabel, "bsYaw", "View turn", ""),
            Slider(R.id.bsRoll, R.id.bsRollLabel, "bsRoll", "View rotate", ""))
        var loading = false
        val sideBtns = listOf(R.id.bsSideL to "L", R.id.bsSideR to "R", R.id.bsSideB to "B", R.id.bsSideF to "F",
            R.id.bsSideXLs to "XLs", R.id.bsSideXLr to "XLr", R.id.bsSideXRs to "XRs", R.id.bsSideXRr to "XRr")
        var popRefresh: (() -> Unit)? = null
        fun loadSide() {
            loading = true
            val side = CamTune.side(bsSide) && !CamTune.isX(bsSide)
            val stitchTab = CamTune.isX(bsSide)
            findViewById<View>(R.id.bsStitchBox).visibility = if (stitchTab) View.VISIBLE else View.GONE
            if (stitchTab) {
                val bl = bsSide.substring(1, 2)
                findViewById<SeekBar>(R.id.stSeam).progress = CamTune.stSeam(p, bl) - 30
                findViewById<TextView>(R.id.stSeamLabel).text = "Seam position: ${CamTune.stSeam(p, bl)}%"
                findViewById<SeekBar>(R.id.stBlend).progress = CamTune.stBlend(p, bl)
                findViewById<TextView>(R.id.stBlendLabel).text = "Seam blend: ${CamTune.stBlend(p, bl)}%"
                findViewById<Button>(R.id.stCopy).text = "Copy both halves from the " + (if (bl == "L") "right" else "left") + " stitch"
            }
            // rear/front are wide pictures: a horizontal live view, like their windows
            findViewById<View>(R.id.bsLive).let { lv ->
                val d = ZScale.density(this)
                lv.layoutParams = (lv.layoutParams as LinearLayout.LayoutParams).apply { width = ((if (side) 340 else 560) * d).toInt(); height = ((if (side) 340 else if (stitchTab) 260 else 210) * d).toInt() }
            }
            findViewById<View>(R.id.bsSizeBox).visibility = if (side) View.VISIBLE else View.GONE
            flip.visibility = if (side) View.GONE else View.VISIBLE
            for (sl in sliders) {
                val mn = CamTune.min(sl.key, bsSide)
                val sb = findViewById<SeekBar>(sl.id)
                sb.max = CamTune.max(sl.key, bsSide) - mn
                val v = CamTune.get(p, sl.key, bsSide)
                sb.progress = v - mn
                findViewById<TextView>(sl.labelId).text = "${sl.name}: $v${sl.unit}"
            }
            mirror.isChecked = CamTune.mirror(p, bsSide); flip.isChecked = CamTune.flip(p, bsSide)
            for ((id, c) in sideBtns) findViewById<Button>(id).isSelected = bsSide == c
            loading = false
            popRefresh?.invoke()
        }
        for ((sid, lid, key, nm, off) in listOf(Tuple5(R.id.stSeam, R.id.stSeamLabel, "stSeam", "Seam position", 30), Tuple5(R.id.stBlend, R.id.stBlendLabel, "stBlend", "Seam blend", 0)))
            findViewById<SeekBar>(sid).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    findViewById<TextView>(lid).text = "$nm: ${v + off}%"
                    if (fromUser && CamTune.isX(bsSide)) { p.edit().putInt("${key}_${bsSide.substring(1, 2)}", v + off).apply(); bsPreview() }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        findViewById<Button>(R.id.stCopy).setOnClickListener {
            if (!CamTune.isX(bsSide)) return@setOnClickListener
            val to = bsSide.substring(1, 2); val from = if (to == "L") "R" else "L"
            val e = p.edit()
            for (h in listOf("s", "r")) for (k in listOf("bsFov", "bsTilt", "bsYaw", "bsRoll")) e.putInt("${k}_X$to$h", CamTune.get(p, k, "X$from$h"))
            e.putInt("stSeam_$to", CamTune.stSeam(p, from)).putInt("stBlend_$to", CamTune.stBlend(p, from)).apply()
            loadSide(); bsPreview()
            Toast.makeText(this, "Copied from the ${if (from == "L") "left" else "right"} stitch", Toast.LENGTH_SHORT).show()
        }
        for (sl in sliders) findViewById<SeekBar>(sl.id).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                val value = v + CamTune.min(sl.key, bsSide)
                findViewById<TextView>(sl.labelId).text = "${sl.name}: $value${sl.unit}"
                if (fromUser) { p.edit().putInt("${sl.key}_$bsSide", value).apply(); bsPreview(); if (sl.key == "bsSize" || sl.key == "bsAspect") hudPadReload?.invoke() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        // Exit animation of the HUD picture: choice, speed and previews (in the live view below / on the HUD itself).
        run {
            val pick = findViewById<Button>(R.id.bsExitAnim)
            val msSb = findViewById<SeekBar>(R.id.bsExitMs); val msLbl = findViewById<TextView>(R.id.bsExitMsLabel)
            fun showAnim() { pick.text = "Exit animation: " + ExitAnim.NAMES[ExitAnim.kind(p)] }
            showAnim(); msSb.progress = ExitAnim.ms(p) - 200; msLbl.text = "Exit speed: ${ExitAnim.ms(p)} ms"
            pick.setOnClickListener {
                ZDialog.choose(this, "Exit animation", ExitAnim.NAMES, ExitAnim.kind(p)) { i -> p.edit().putInt("bsExitAnim", i).apply(); showAnim() }
            }
            msSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    msLbl.text = "Exit speed: ${v + 200} ms"
                    if (fromUser) p.edit().putInt("bsExitMs", v + 200).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            val holdSb = findViewById<SeekBar>(R.id.bsHold); val holdLbl = findViewById<TextView>(R.id.bsHoldLabel)
            holdSb.progress = BlindSpotHud.holdMs(p) / 500 - 1
            holdLbl.text = "Blinker view stays after blinker off: ${BlindSpotHud.holdMs(p) / 1000.0} s"
            holdSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    holdLbl.text = "Blinker view stays after blinker off: ${(v + 1) / 2.0} s"
                    if (fromUser) p.edit().putInt("bsHoldMs", (v + 1) * 500).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            // HUD pad pop-up: the camera box sits where it does on the HUD and leaves with the chosen animation, over and over until closed.
            findViewById<Button>(R.id.bsExitPrevApp).setOnClickListener {
                val blinker = (if (CamTune.isX(bsSide)) bsSide.substring(1, 2) else bsSide).let { if (it == "L") "L" else "R" }
                val sfx = "_$blinker"
                val box = TextView(this).apply {
                    text = if (blinker == "L") "LEFT CAM" else "RIGHT CAM"; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
                    typeface = ZFont.reg; textSize = 14f; setBackgroundColor(0xFF2E7D5B.toInt())
                }
                // a 3:1 pad like the HUD (1440x480); the box is sized and placed from the same settings the HUD uses once the pad has its width
                val pad = object : FrameLayout(this) {
                    override fun onMeasure(w: Int, h: Int) { val pw = MeasureSpec.getSize(w); setMeasuredDimension(pw, pw / 3); measureChildren(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(pw / 3, MeasureSpec.AT_MOST)) }
                    override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) {
                        val padW = r - l; val padH = b - t
                        val s = padH * CamTune.get(p, "bsSize", blinker).coerceIn(40, 100) / 100
                        val bw = (s * CamTune.get(p, "bsAspect", blinker) * 1 / 100).coerceAtMost(padW)
                        val cx = padW * p.getInt("bsPosX$sfx", 20) / 100
                        val cy = padH * p.getInt("bsPosY$sfx", 36) / 100
                        val x = (cx - bw / 2).coerceIn(0, (padW - bw).coerceAtLeast(0)); val y = (cy - s / 2).coerceIn(0, (padH - s).coerceAtLeast(0))
                        box.measure(MeasureSpec.makeMeasureSpec(bw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
                        box.layout(x, y, x + bw, y + s)
                        box.pivotX = bw / 2f; box.pivotY = s / 2f
                    }
                }.apply { setBackgroundColor(android.graphics.Color.rgb(14, 18, 22)); addView(box) }
                val dlg = ZDialog.custom(this, pad, "Close")
                var running: android.animation.ValueAnimator? = null
                val loop = object : Runnable {
                    override fun run() {
                        if (!dlg.isShowing) return
                        ExitAnim.reset(box)
                        ui.postDelayed({
                            if (!dlg.isShowing) return@postDelayed
                            val kind = ExitAnim.kind(p)
                            if (kind == 0) { box.visibility = View.INVISIBLE; ui.postDelayed({ box.visibility = View.VISIBLE; this.run() }, 700) }
                            else running = ExitAnim.run(box, kind, ExitAnim.ms(p)) { ui.postDelayed({ this.run() }, 700) }
                        }, 800)
                    }
                }
                dlg.setOnDismissListener { running?.cancel(); ui.removeCallbacks(loop) }
                loop.run()
            }
            findViewById<Button>(R.id.bsExitPrevHud).setOnClickListener {
                if (!CamTune.side(bsSide)) return@setOnClickListener
                val now = System.currentTimeMillis()
                // show the picture for a moment, then let the HUD take it away with the chosen animation (no hold)
                p.edit().putLong("bsPreviewUntil", now + 1200).putLong("bsNoHoldUntil", now + 1200)
                    .putInt("bsPreviewSide", if (bsSide == "L") 2 else 3).apply()
                if (!p.getBoolean("bsHud", false)) Toast.makeText(this, "Turn on the HUD camera first", Toast.LENGTH_SHORT).show()
            }
        }
        mirror.setOnCheckedChangeListener { _, on -> if (!loading) { p.edit().putBoolean(CamTune.mirrorKey(bsSide), on).apply(); bsPreview() } }
        flip.setOnCheckedChangeListener { _, on -> if (!loading) { p.edit().putBoolean(CamTune.flipKey(bsSide), on).apply(); bsPreview() } }
        findViewById<Button>(R.id.bsReset).setOnClickListener {
            val e = p.edit(); for (k in CamTune.keys(bsSide)) e.remove(k); e.apply()
            loadSide(); bsPreview()
        }
        for ((id, c) in sideBtns) findViewById<Button>(id).setOnClickListener { bsSide = c; loadSide(); applyTheme(); bsPreview() }
        // Drag/pinch/twist tuning on a live picture: the page's own preview (hubKey "tune") and the pop-up (hubKey "tunePop", [popup] = two fingers also move the view)
        fun setupTuner(live: TextureView, hubKey: String, popup: Boolean) {
            var out: PanelOut? = null
            fun tuneOut() { out?.let { if (CamTune.isX(bsSide)) CamTune.applyStitch(p, bsSide.substring(1, 2), it, true) else CamTune.apply(p, bsSide, it) } }
            val loop = object : Runnable { override fun run() { if (out != null) { tuneOut(); ui.postDelayed(this, 200) } } }
            // The camera is only held while the picture is really wanted: the pop-up always, the page's own preview only while the Live view section is open and the app is in front
            var stex: android.graphics.SurfaceTexture? = null; var bw = 0; var bh = 0
            fun hold() {
                val want = popup || liveWanted()
                val t = stex
                if (want && out == null && t != null) {
                    val r = PanelOut(t); r.boxW = bw; r.boxH = bh; out = r; tuneOut(); CamHub.add(this@MainActivity, hubKey, r); ui.post(loop)
                } else if (!want) {
                    val r = out ?: return
                    out = null; CamHub.remove(hubKey, r)
                }
            }
            if (!popup) liveHolders += ::hold
            live.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { stex = st; bw = w; bh = h; hold() }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { bw = w; bh = h; out?.let { it.boxW = w; it.boxH = h } }
                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    stex = null
                    val r = out ?: return true
                    out = null; r.dead = true; CamHub.remove(hubKey, r); return true
                }
                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            }
            var lx = 0f; var ly = 0f; var lDist = 1f; var lAng = 0f
            var fov = 90f; var tilt = 0f; var yaw = 0f; var roll = 0f; var shift = 0f
            fun load() { fov = CamTune.get(p, "bsFov", bsSide).toFloat(); tilt = CamTune.get(p, "bsTilt", bsSide).toFloat(); yaw = CamTune.get(p, "bsYaw", bsSide).toFloat(); roll = CamTune.get(p, "bsRoll", bsSide).toFloat(); shift = CamTune.get(p, "bsShift", bsSide).toFloat() }
            var lMidY = 0f; var lMidX = 0f
            fun midY(v: View, e: MotionEvent): Float { val loc = IntArray(2); v.getLocationOnScreen(loc); return loc[1] + (e.getY(0) + e.getY(1)) / 2 }
            fun dist(e: MotionEvent) = Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat().coerceAtLeast(1f)
            fun ang(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
            fun put(e: android.content.SharedPreferences.Editor, key: String, v: Float) =
                e.putInt("${key}_$bsSide", v.toInt().coerceIn(CamTune.min(key, bsSide), CamTune.max(key, bsSide)))
            // Stitch view: the half under the finger (left of the seam = left camera, right of it = right camera) becomes the one being edited
            fun pickHalf(x: Float, w: Int) {
                if (!CamTune.isX(bsSide) || w <= 0) return
                val bl = bsSide.substring(1, 2)
                val leftHalf = x / w < CamTune.stSeam(p, bl) / 100f
                val sideHalf = if (bl == "L") leftHalf else !leftHalf
                val target = "X$bl" + (if (sideHalf) "s" else "r")
                if (target != bsSide) { bsSide = target; loadSide(); applyTheme() }
            }
            // dragging the picture right turns the view left, dragging down looks higher
            fun pan(dx: Float, dy: Float) {
                val mir = CamTune.mirror(p, bsSide); val fl = CamTune.flip(p, bsSide)
                val sign = (if (CamTune.side(bsSide)) (if (CamTune.base(bsSide) == "L") -1 else 1) * (if (mir) -1 else 1)
                            else (if (mir != fl) -1 else 1) * (if (fl) -1 else 1)).toFloat()
                yaw = (yaw - dx * 0.15f * sign).coerceIn(CamTune.min("bsYaw", bsSide).toFloat(), 60f)
                tilt = (tilt + dy * 0.15f * (if (fl) -1 else 1)).coerceIn(CamTune.min("bsTilt", bsSide).toFloat(), 60f)
            }
            fun commit() {
                val e = p.edit(); put(e, "bsFov", fov); put(e, "bsTilt", tilt); put(e, "bsYaw", yaw); put(e, "bsRoll", roll); if (CamTune.isX(bsSide)) put(e, "bsShift", shift); e.apply()
                tuneOut(); loadSide(); bsPreview()
            }
            live.setOnTouchListener { v, e ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pickHalf(e.x, v.width); load(); lx = e.x; ly = e.y }
                    MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { pickHalf((e.getX(0) + e.getX(1)) / 2, v.width); load(); lDist = dist(e); lAng = ang(e); lMidY = midY(v, e); lMidX = (e.getX(0) + e.getX(1)) / 2 }
                    MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; if (i < e.pointerCount) { lx = e.getX(i); ly = e.getY(i) } }
                    MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) {
                        val d = dist(e); val a = ang(e)
                        if (popup) {
                            val mx = (e.getX(0) + e.getX(1)) / 2; val my2 = (e.getY(0) + e.getY(1)) / 2
                            // stitch: two fingers moving sideways turn the view, up/down slides the whole picture of that camera (to line the rear up with the side)
                            if (CamTune.isX(bsSide)) { pan(mx - lMidX, 0f); shift = (shift - (my2 - lMidY) / v.height.coerceAtLeast(1) * 100f).coerceIn(CamTune.min("bsShift", bsSide).toFloat(), CamTune.max("bsShift", bsSide).toFloat()) }
                            else pan(mx - lMidX, my2 - lMidY)
                            lMidX = mx; lMidY = my2
                        }
                        else { val my = midY(v, e); (rightScroll ?: findViewById<ScrollView>(R.id.scroll)).scrollBy(0, (lMidY - my).toInt()); lMidY = midY(v, e) }   // two fingers moving together scroll the page (the picture holds the touch)
                        fov = (fov / (d / lDist)).coerceIn(50f, CamTune.max("bsFov", bsSide).toFloat())           // pinch out = zoom in = narrower view
                        var da = a - lAng; if (da > 180) da -= 360; if (da < -180) da += 360
                        roll = (roll + da).coerceIn(-45f, 45f)
                        lDist = d; lAng = a; commit()
                    } else {
                        pan(e.x - lx, e.y - ly)
                        lx = e.x; ly = e.y; commit()
                    }
                }
                true
            }
        }
        setupTuner(findViewById(R.id.bsLive), "tune", false)
        findViewById<Button>(R.id.bsPop).setOnClickListener {
            val d = ZScale.density(this)
            fun dp(x: Int) = (x * d).toInt()
            val tv = TextureView(this)
            val which = TextView(this).apply { tag = "accent"; typeface = ZFont.bold; setPadding(0, 0, 0, dp(2)) }
            val hint = TextView(this).apply { tag = "m"; text = "1 finger: turn / tilt. 2 fingers: pinch zoom, twist rotate, move sideways to turn, up / down to slide the picture (stitch). Stitch: the half under your fingers is edited."; textSize = 11f; setPadding(0, 0, 0, dp(4)) }
            setupTuner(tv, "tunePop", true)
            // the floating window this picture belongs to: its size is also adjustable here
            val ck = if (CamTune.isX(bsSide)) bsSide.substring(1, 2) else bsSide
            val fkey = when (ck) { "L" -> "flLeft"; "R" -> "flRight"; "B" -> "flRear"; else -> "flFront" }
            fun winPx(which: String): Int {
                val dm = resources.displayMetrics; val wide = ck == "B" || ck == "F"
                val sq = p.getInt("${fkey}Size", dp(320)).coerceIn(dp(160), minOf(dm.widthPixels, dm.heightPixels) - dp(20))
                return if (which == "W") p.getInt("${fkey}W", if (wide) dp(520) else sq).coerceIn(dp(160), dm.widthPixels - dp(20))
                       else p.getInt("${fkey}H", if (wide) dp(200) else sq).coerceIn(dp(160), dm.heightPixels - dp(20))
            }
            // the live view has the proportions of the floating window it tunes (letterboxed in the free area)
            val frame = object : FrameLayout(this) {
                override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) {
                    val aw = r - l; val ah = b - t
                    val a = winPx("W").toFloat() / winPx("H")
                    var w = aw; var h = (aw / a).toInt()
                    if (h > ah) { h = ah; w = (ah * a).toInt() }
                    val x = (aw - w) / 2; val y = (ah - h) / 2
                    tv.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                    tv.layout(x, y, x + w, y + h)
                }
            }.apply { addView(tv) }
            // the same sliders as the page, for precise values; they follow the half being edited and stay in step with the page
            val updates = ArrayList<() -> Unit>()
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val seeks = ArrayList<SeekBar>(); val labs = ArrayList<TextView>()
            fun addSlider(name: String, minF: () -> Int, maxF: () -> Int, getF: () -> Int, setF: (Int) -> Unit) {
                val lab = TextView(this).apply { tag = "m"; textSize = 11f }
                val sb = SeekBar(this).apply { tag = "seek" }
                fun refresh() { val mn = minF(); sb.max = maxF() - mn; sb.progress = getF() - mn; lab.text = "$name: ${getF()}" }
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { lab.text = "$name: ${v + minF()}"; if (fromUser) setF(v + minF()) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
                refresh(); updates += ::refresh; col.addView(lab); col.addView(sb); seeks += sb; labs += lab
            }
            if (CamTune.isX(bsSide)) {
                val bl = bsSide.substring(1, 2)
                addSlider("Seam position (%)", { 30 }, { 70 }, { CamTune.stSeam(p, bl) }) { v -> p.edit().putInt("stSeam_$bl", v).apply(); loadSide(); bsPreview() }
                addSlider("Seam blend (%)", { 0 }, { 40 }, { CamTune.stBlend(p, bl) }) { v -> p.edit().putInt("stBlend_$bl", v).apply(); loadSide(); bsPreview() }
            }
            for ((k, nm) in listOf("bsFov" to "View angle", "bsTilt" to "View tilt up", "bsYaw" to "View turn", "bsRoll" to "View rotate", "bsShift" to "Move up / down").filter { it.first != "bsShift" || CamTune.isX(bsSide) })
                addSlider(nm, { CamTune.min(k, bsSide) }, { CamTune.max(k, bsSide) }, { CamTune.get(p, k, bsSide) }) { v -> p.edit().putInt("${k}_$bsSide", v).apply(); loadSide(); bsPreview() }
            addSlider("Window width (dp)", { 160 }, { ((resources.displayMetrics.widthPixels - dp(20)) / d).toInt() }, { (winPx("W") / d).toInt() }) { v ->
                p.edit().putInt("${fkey}W", dp(v)).putLong("flSizeStamp", System.currentTimeMillis()).apply(); frame.requestLayout(); syncService() }
            addSlider("Window height (dp)", { 160 }, { ((resources.displayMetrics.heightPixels - dp(20)) / d).toInt() }, { (winPx("H") / d).toInt() }) { v ->
                p.edit().putInt("${fkey}H", dp(v)).putLong("flSizeStamp", System.currentTimeMillis()).apply(); frame.requestLayout(); syncService() }
            // iOS-photo-style look: two square pads (drag the puck, double tap resets). Pad 1 = contrast across / brightness up, pad 2 = colour across / opacity up
            val lookCam = { bsSide }
            fun lk(k: String) = CamTune.lookGet(p, k, lookCam())
            fun lkPut(a: String, av: Int, b: String, bv: Int) { p.edit().putInt("${a}_${CamTune.base(lookCam())}", av).putInt("${b}_${CamTune.base(lookCam())}", bv).apply() }
            val pad1 = LookPadView(this, "Contrast", "Brightness", -100, 100, 0, -100, 100, 0) { cx, by -> lkPut("lkCon", cx, "lkBri", by) }
            val pad2 = LookPadView(this, "Colour", "Opacity", -100, 100, 0, 20, 100, 100) { sx, oy -> lkPut("lkSat", sx, "lkOpa", oy) }
            val pad3 = LookPadView(this, "Warmth", "Night boost", -100, 100, 0, 0, 100, 0) { wx, ny -> lkPut("lkWrm", wx, "lkNit", ny) }
            val pad4 = LookPadView(this, "", "Sharpness", 0, 0, 0, 0, 100, 50) { _, sy -> p.edit().putInt("lkShp_${CamTune.base(lookCam())}", sy).apply() }
            fun syncPads() { pad1.x = lk("lkCon"); pad1.y = lk("lkBri"); pad2.x = lk("lkSat"); pad2.y = lk("lkOpa"); pad3.x = lk("lkWrm"); pad3.y = lk("lkNit"); pad4.y = lk("lkShp"); for (v in listOf(pad1, pad2, pad3, pad4)) v.invalidate() }
            updates += ::syncPads; syncPads()
            val padRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pad1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pad2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val padRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pad3, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pad4, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(padRow2, 0); col.addView(padRow, 0)
            fun refreshAll() {
                which.text = if (CamTune.isX(bsSide)) "${if (bsSide[1] == 'L') "LEFT" else "RIGHT"} STITCH, ${if (bsSide.endsWith("s")) "SIDE" else "REAR"} CAMERA" else "${CamTune.name(bsSide).uppercase()} CAMERA"
                updates.forEach { it() }
            }
            popRefresh = ::refreshAll; refreshAll()
            val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val done = Button(this).apply { tag = "btn"; text = "Done"; setOnClickListener { dlg.dismiss() } }
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(which); addView(hint)
                addView(ScrollView(this@MainActivity).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            val root = LinearLayout(this).apply {
                tag = "root"; orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f).apply { marginStart = dp(14) })
            }
            ZTheme.style(root, ZTheme.palette(mode))
            // slimmer rows than the page's, so everything fits next to a big live view (the knob stays the app-wide size)
            for (sb in seeks) { sb.minimumHeight = dp(44); sb.setPadding(dp(20), 0, dp(20), 0) }
            for (l in labs) l.setPadding(0, dp(2), 0, 0)
            done.minHeight = dp(52); done.minimumHeight = dp(52)
            dlg.setContentView(root)
            dlg.setOnDismissListener { popRefresh = null }
            dlg.show()
            dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setupArrowEditor()
        setupCollapsible()
        setupWebHud()
        loadSide()
        showState()
        updateSentryFolderStatus()
        markUpdate(p.getString("updAvail", ""))   // from the daily check or the last open
        checkForUpdate()
        findViewById<Button>(R.id.checkUpdate).setOnClickListener { checkForUpdate(true) }
        applyTheme()
        if (askDefaults) window.decorView.post {
            ZDialog.show(this, "Apply the default settings?",
                "First time here? Start with the setup the author uses. You can apply it any time later with the Apply default settings button.", "Apply", "Not now") { applyDefaultSettings() }
        }
    }

    // Hidden: tap the "HUD" title 10 times to show/hide the web-on-HUD section.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onPause() { WebHud.appVisible = false; resumed = false; updateLiveHold(); super.onPause() }

    // Nothing may outlive the screen: the old Activity would otherwise stay pinned in memory by the
    // redraw loop and the pairing poller every time the app is opened and closed.
    override fun onDestroy() {
        updPulse?.cancel()
        polling = false
        stopSetupListen()
        ui.removeCallbacksAndMessages(null)
        mirrorLoop = null; mirrorRunning = false; queueLoopOn = false
        super.onDestroy()
    }

    // Driving profiles card: per drive mode an ambient colour (swatches) and an engine-sound style (picker), plus an optional start mode.
    private val pfRefresh = ArrayList<() -> Unit>()
    private var feelListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null   // prefs only hold it weakly
    /** Mascot card: the pixel pet that roams the screen (see Mascot). Every control writes an "mc*" pref and re-sends the start intent. */
    private fun buildMascotCard(sync: () -> Unit) {
        val body = findViewById<LinearLayout>(R.id.mcBody)
        val d = ZScale.density(this)
        fun dp(x: Int) = (x * d).toInt()
        val mode = ZTheme.Mode.of(p.getString("theme", null))
        fun add(v: View, top: Int = 8) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        fun sw(key: String, text: String, def: Boolean, onChange: () -> Unit = {}) = androidx.appcompat.widget.SwitchCompat(this).apply {
            tag = "sw"; this.text = text; isChecked = p.getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); onChange(); sync() }
        }
        fun seek(key: String, text: String, min: Int, max: Int, def: Int, fmt: (Int) -> String): View {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val lab = TextView(this).apply { tag = "m"; textSize = 13f }
            box.addView(lab)
            box.addView(SeekBar(this).apply {
                tag = "seek"; this.max = max - min; progress = p.getInt(key, def).coerceIn(min, max) - min
                lab.text = text + ": " + fmt(progress + min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = text + ": " + fmt(v + min); if (fromUser) p.edit().putInt(key, v + min).apply() }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) { sync() }
                })
            }, LinearLayout.LayoutParams(-1, -2))
            return box
        }
        body.addView(TextView(this).apply { tag = "m"; text = "Zed, a little pixel creature that lives on the screen: it walks, hops onto the floating windows, crawls up the walls and over the ceiling, naps and plays with a ball or a bug. It rests at night, goes hyper in the evening, and sometimes reacts when the car goes very fast." })
        add(sw("mcOn", "Show Zed", true) { if (p.getBoolean("mcOn", true) && !android.provider.Settings.canDrawOverlays(this@MainActivity)) try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) {} })
        // colour: preset swatches, a custom colour and a rainbow mode
        val swatches = listOf(0xFFB6FF3B.toInt(), 0xFFE07B53.toInt(), 0xFF3BD9FF.toInt(), 0xFFFF6FA5.toInt(), 0xFFA78BFA.toInt(), 0xFFFFD43B.toInt(), 0xFFFF5A5A.toInt(), 0xFFF2F2F7.toInt())
        val dots = ArrayList<View>()
        fun mark() { val c = p.getInt("mcColor", swatches[0]); dots.forEachIndexed { i, v -> v.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(swatches[i]); setStroke(dp(if (swatches[i] == c) 3 else 1), if (swatches[i] == c) 0xFFFFFFFF.toInt() else 0x66888888) } } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        swatches.forEach { col ->
            val v = View(this).apply { setOnClickListener { p.edit().putInt("mcColor", col).putBoolean("mcRainbow", false).apply(); mark(); sync() } }
            dots.add(v); row.addView(v, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        }
        mark()
        add(TextView(this).apply { tag = "m"; text = "Colour"; textSize = 13f }, 12)
        add(row, 4)
        add(Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Custom colour"
            setOnClickListener { ColorPicker.show(this@MainActivity, ZTheme.palette(mode), "Zed colour", p.getInt("mcColor", swatches[0]), onLive = {}, onDone = { c -> if (c != 0) { p.edit().putInt("mcColor", c).putBoolean("mcRainbow", false).apply(); mark(); sync() } }) } })
        add(sw("mcRainbow", "Rainbow (the colour keeps changing)", false))
        add(Button(this).apply { tag = "seg"; isAllCaps = false
            val names = listOf("Screen (two LED eyes)", "Lenses (two round eyes)", "Visor (one light bar)")
            fun show() { text = "Face: " + names[p.getInt("mcFace", 2).coerceIn(0, 2)] }
            show()
            setOnClickListener { ZDialog.choose(this@MainActivity, "Face", names, p.getInt("mcFace", 2).coerceIn(0, 2)) { i -> p.edit().putInt("mcFace", i).apply(); show(); sync() } } })
        add(seek("mcSize", "Size", 3, 12, 5) { "$it" }, 12)
        add(seek("mcSpeed", "Pace", 40, 220, 100) { "$it%" }, 8)
        add(TextView(this).apply { tag = "m"; text = "What it does"; textSize = 13f }, 12)
        add(sw("mcJump", "Hop onto the floating windows", true), 4)
        add(sw("mcClimb", "Crawl up the walls and over the ceiling", true), 0)
        add(sw("mcToys", "Play with a ball and chase a bug", true), 0)
        add(sw("mcNap", "Take naps", true), 0)
        add(sw("mcLife", "Daily life: eat, shower, toilet, sports, charging", true), 0)
        add(sw("mcReact", "Sometimes react when the car is very fast", true), 0)
        add(Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Play now"; setOnClickListener { sync(); Mascot.cheer() } }, 12)
        applyTheme()
    }

    /** Trip widget card: a floating driving-statistics window (see TripWidget). Every control writes a "tw*" pref and re-sends the start intent. */
    private fun buildTripCard(sync: () -> Unit) {
        val body = findViewById<LinearLayout>(R.id.twBody)
        val d = ZScale.density(this)
        fun dp(x: Int) = (x * d).toInt()
        fun label(t: String) = TextView(this).apply { tag = "m"; text = t; textSize = 13f; setPadding(0, dp(10), 0, dp(4)) }
        fun picker(key: String, title: String, values: List<String>, names: List<String>): Button = Button(this).apply {
            tag = "seg"; isAllCaps = false
            fun show() { text = title + ": " + names[values.indexOf(p.getString(key, values[0])).coerceAtLeast(0)] }
            show()
            setOnClickListener { ZDialog.choose(this@MainActivity, title, names, values.indexOf(p.getString(key, values[0])).coerceAtLeast(0)) { i -> p.edit().putString(key, values[i]).apply(); show(); sync() } }
        }
        fun add(v: View, top: Int = 8) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        /** Two controls side by side (the card used to be one tall stack). */
        fun pair(l: View, r: View?, top: Int = 8) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            if (r != null) row.addView(r, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) }) else row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
            add(row, top)
        }
        fun seekBox(key: String, text: String, min: Int, max: Int, def: Int, fmt: (Int) -> String): View {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val lab = TextView(this).apply { tag = "m"; textSize = 13f; setPadding(0, 0, 0, dp(2)) }
            box.addView(lab)
            box.addView(SeekBar(this).apply {
                tag = "seek"; this.max = max - min; progress = p.getInt(key, def).coerceIn(min, max) - min
                lab.text = text + ": " + fmt(progress + min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { lab.text = text + ": " + fmt(v + min); if (fromUser) p.edit().putInt(key, v + min).apply() }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) { sync() }
                })
            }, LinearLayout.LayoutParams(-1, -2))
            return box
        }
        body.addView(TextView(this).apply { tag = "m"; text = "Speed, pedals, battery and trip statistics in a floating window like the cameras: drag it anywhere, resize with the edge holders, hide it into the dock, or hide it with the floating button." })
        pair(androidx.appcompat.widget.SwitchCompat(this).apply {
            tag = "sw"; text = "Show trip widget"; isChecked = p.getBoolean("twOn", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("twOn", on).apply(); if (on) { if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) {} }; sync() }
        }, Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Reset trip"; setOnClickListener { p.edit().putInt("twReset", p.getInt("twReset", 0) + 1).apply(); sync() } })
        pair(picker("twOrient", "Layout", listOf("v", "h", "auto"), listOf("Vertical", "Horizontal", "Auto")),
            picker("twDetail", "Detail", listOf("glance", "full"), listOf("Glance", "Full")))
        pair(picker("twFont", "Font", listOf("Inter", "JetBrains Mono", "Orbitron", "Barlow Condensed", "Rajdhani"), listOf("Inter", "JetBrains Mono", "Orbitron", "Condensed", "Rajdhani")),
            picker("twTheme", "Theme", listOf("auto", "dark", "black", "light"), listOf("Follow app", "Dark", "Black", "Light")))
        val posNames = listOf("Top left", "Top centre", "Top right", "Middle left", "Centre", "Middle right", "Bottom left", "Bottom centre", "Bottom right")
        pair(picker("twUnit", "Units", listOf("km", "mi"), listOf("km", "miles")),
            Button(this).apply {
                tag = "seg"; isAllCaps = false; text = "Snap to a place"
                setOnClickListener { ZDialog.choose(this@MainActivity, "Snap widget to", posNames, p.getInt("twPos", 0).coerceIn(0, 8)) { i -> p.edit().putInt("twPos", i).putInt("twSnapSeq", p.getInt("twSnapSeq", 0) + 1).apply(); sync() } }
            })
        body.addView(label("Chart lines (pick any)"))
        val chartKeys = listOf("load", "speed", "accel", "batt"); val chartNames = listOf("Load", "Speed", "G force", "Battery")
        val chartSw = chartKeys.mapIndexed { i, k ->
            androidx.appcompat.widget.SwitchCompat(this).apply {
                tag = "sw"; text = chartNames[i]
                isChecked = (p.getString("twChart", "load") ?: "load").split(",").contains(k)
                setOnCheckedChangeListener { _, on ->
                    val cur = (p.getString("twChart", "load") ?: "load").split(",").filter { it.isNotEmpty() }.toMutableList()
                    if (on && k !in cur) cur += k; if (!on) cur.remove(k)
                    if (cur.isEmpty()) { cur += "load"; if (k == "load") isChecked = true }   // at least one line
                    p.edit().putString("twChart", chartKeys.filter { it in cur }.joinToString(",")).apply(); sync()
                }
            }
        }
        pair(chartSw[0], chartSw[1], 0); pair(chartSw[2], chartSw[3], 0)
        pair(seekBox("twFade", "Edge fade", 0, 30, 0) { if (it == 0) "off" else "$it%" }, seekBox("twOpacity", "Opacity", 50, 100, 90) { "$it%" }, 12)
        pair(seekBox("twHdrText", "Header text size (clock, battery, gear)", 60, 200, 100) { "$it%" }, null, 12)
        pair(seekBox("twPack", "Battery size", 30, 150, 100) { "$it kWh" },
            androidx.appcompat.widget.SwitchCompat(this).apply {
                tag = "sw"; text = "Regen pill"; isChecked = p.getBoolean("twRegen", true)
                setOnCheckedChangeListener { _, on -> p.edit().putBoolean("twRegen", on).apply(); sync() }
            }, 8)
        // The six tiles of the Glance (compact) grid: each slot picks any metric
        body.addView(label("Glance tiles (the 3 x 2 grid)"))
        // alphabetical by name
        val metricIds = listOf("p0100", "p1000", "v12", "avg", "dist", "used", "soc", "pa", "pb", "rng", "reg", "stp", "top", "eff")
        val metricNames = listOf("0-100 time", "100-0 distance", "12V battery", "Average speed", "Distance", "Energy used", "HV battery", "Peak accel", "Peak brake", "Range", "Regen recovered", "Stopped time", "Top speed", "Wh/km")
        val defSlots = listOf("eff", "avg", "dist", "rng", "soc", "reg")
        fun slots() = (p.getString("twSlots", null) ?: defSlots.joinToString(",")).split(",").filter { it in metricIds }.let { l -> (l + defSlots.drop(l.size)).take(6) }
        fun slotBtn(i: Int) = Button(this).apply {
            tag = "seg"; isAllCaps = false
            fun show() { text = "Tile ${i + 1}: " + metricNames[metricIds.indexOf(slots()[i]).coerceAtLeast(0)] }
            show()
            setOnClickListener {
                ZDialog.choose(this@MainActivity, "Tile ${i + 1}", metricNames, metricIds.indexOf(slots()[i]).coerceAtLeast(0)) { k ->
                    val cur = slots().toMutableList(); cur[i] = metricIds[k]
                    p.edit().putString("twSlots", cur.joinToString(",")).apply(); show(); sync()
                }
            }
        }
        pair(slotBtn(0), slotBtn(1), 0); pair(slotBtn(2), slotBtn(3), 8); pair(slotBtn(4), slotBtn(5), 8)
        fun colourBox(title: String, key: String, def: Int, auto: Boolean): View {
            fun cur() = p.getInt(key, def).let { if (it == 0) def else it }
            val dot = View(this); val hexT = TextView(this).apply { tag = "m"; gravity = Gravity.CENTER_VERTICAL; textSize = 13f }
            fun mark() {
                val c = p.getInt(key, def)
                dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (c == 0) 0xFF444444.toInt() else c); setStroke(dp(1), 0x66888888) }
                hexT.text = title + "\n" + (if (c == 0) "Auto" else String.format("#%06X", c and 0xFFFFFF))
            }
            val pick = Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Choose"
                setOnClickListener { ColorPicker.show(this@MainActivity, ZTheme.palette(mode), title, cur(), onLive = {}, onDone = { c -> if (c != 0) { p.edit().putInt(key, c).apply(); mark(); sync() } }) } }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
            row.addView(hexT, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(pick)
            if (auto) row.addView(Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Auto"; setOnClickListener { p.edit().putInt(key, 0).apply(); mark(); sync() } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            mark(); return row
        }
        pair(colourBox("Accent", "twAcc", ZTheme.LIME, false), colourBox("Background", "twBg", 0, true), 12)
        body.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            tag = "sw"; text = "Accent follows the drive mode's ambient light colour (modes set to Keep use the accent above)"; isChecked = p.getBoolean("twAccMode", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("twAccMode", on).apply(); sync() }
        })
        applyTheme()
    }

    private fun buildProfileCard() {
        val body = findViewById<LinearLayout>(R.id.pfBody)
        val d = ZScale.density(this)
        fun dp(x: Int) = (x * d).toInt()
        fun label(t: String, top: Int) = TextView(this).apply { tag = "m"; text = t; textSize = 13f; setPadding(0, dp(top), 0, dp(4)) }
        // a button that shows the current choice and opens a single-choice list
        fun picker(key: String, title: String, values: List<String>, names: List<String>, def: String = values[0], onChange: (() -> Unit)? = null): Button = Button(this).apply {
            tag = "seg"; isAllCaps = false
            fun show() { text = names[values.indexOf(p.getString(key, def)).coerceAtLeast(0)] }
            show()
            setOnClickListener {
                ZDialog.choose(this@MainActivity, title, names, values.indexOf(p.getString(key, def)).coerceAtLeast(0)) { i -> p.edit().putString(key, values[i]).apply(); show(); onChange?.invoke() }
            }
        }
        val styleKeys = listOf("", "off", "typer", "american", "prototype", "turbo4")
        val styleNames = listOf("Keep current", "Silent (no engine sound)", "Civic Type R", "American V8", "Prototype V8", "Turbo four")
        // one tab per drive mode, so only one mode's settings are on screen at a time
        val panels = ArrayList<LinearLayout>()
        val tabBtns = ArrayList<Button>()
        fun showTab(i: Int) {
            p.edit().putInt("pfTab", i).apply()
            panels.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabBtns.forEachIndexed { k, b -> b.isSelected = k == i }
            applyTheme()
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        DriveProfile.MODES.forEachIndexed { i, mo ->
            tabRow.addView(Button(this).apply { tag = "seg"; text = mo.second; isAllCaps = false; setOnClickListener { showTab(i) } ; tabBtns += this },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        body.addView(tabRow)
        // rev bar controls live in the HUD card: a drive-mode tab row, then that mode's panel
        val revBody = findViewById<LinearLayout>(R.id.revBody)
        val revPanels = ArrayList<LinearLayout>()
        val revTabs = ArrayList<Button>()
        fun showRevTab(i: Int) {
            p.edit().putInt("revTab", i).apply()
            revPanels.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            revTabs.forEachIndexed { k, b -> b.isSelected = k == i }
            hudPadReload?.invoke()
            applyTheme()
        }
        revBody.addView(TextView(this).apply { tag = "m"; text = "Per drive mode. Position and thickness: drag and pinch on the pad above."; setPadding(0, 0, 0, 0) })
        val revTabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        DriveProfile.MODES.forEachIndexed { i, mo ->
            revTabRow.addView(Button(this).apply { tag = "seg"; text = mo.second; isAllCaps = false; setOnClickListener { showRevTab(i) }; revTabs += this },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        revBody.addView(revTabRow)
        for ((code, name) in DriveProfile.MODES) {
            val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            panels += host; body.addView(host)
            val h = DriveProfile.hex(code)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, 0) }
            head.addView(TextView(this).apply { tag = "m"; text = name; textSize = 17f; typeface = ZFont.bold }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(Button(this).apply { tag = "btn2"; text = "Test"; isAllCaps = false; setOnClickListener { DriveProfile.test(this@MainActivity, code) } })
            host.addView(head)
            fun colourRow(key: String, dlgTitle: String, keepText: String, def: Int = 0, into: LinearLayout = host, onChange: (() -> Unit)? = null) {
                fun cur() = DriveProfile.colorOf(p.getString(key, def.toString())).let { if (it == 0 && def != 0) def else it }
                val dot = View(this); val hexT = TextView(this).apply { tag = "m"; gravity = Gravity.CENTER_VERTICAL }
                val pickBtn = Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Choose colour" }
                fun mark() {
                    val c = cur(); val pal = ZTheme.palette(mode)
                    dot.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (c != 0) c else android.graphics.Color.TRANSPARENT); setStroke(dp(if (c != 0) 2 else 1), pal.line)
                    }
                    hexT.text = if (c != 0) String.format("#%06X", c and 0xFFFFFF) else keepText
                }
                pickBtn.setOnClickListener {
                    // every colour already set in any drive mode (ambient, slow, fast, regen), except this one, to copy from
                    val used = ArrayList<Pair<String, Int>>()
                    for ((mc, mn) in DriveProfile.MODES) {
                        val mh = DriveProfile.hex(mc)
                        for ((k, lab, def) in listOf(Triple("pfColor_$mh", "ambient", 0), Triple("pfSlow_$mh", "slow", 0xFF2040FF.toInt()), Triple("pfFastC_$mh", "fast", 0xFFFF2020.toInt()), Triple("pfRegenColor_$mh", "regen", 0xFFFF2020.toInt()))) {
                            if (k == key) continue
                            val c = DriveProfile.colorOf(p.getString(k, def.toString())).let { if (it == 0) def else it }
                            if (c != 0 && used.none { it.second == c && it.first.startsWith(mn) }) used += "$mn $lab" to c
                        }
                    }
                    ColorPicker.show(this, ZTheme.palette(mode), dlgTitle, cur(),
                        onLive = { c -> if (into === host) DriveProfile.previewColor(this, c) },
                        onDone = { c -> p.edit().putString(key, c.toString()).apply(); mark(); onChange?.invoke() }, swatches = used)
                }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(dot, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
                row.addView(hexT, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(pickBtn)
                mark(); pfRefresh += ::mark
                into.addView(row)
            }
            host.addView(label("Ambient light", 4))
            colourRow("pfColor_$h", "$name: ambient light", "Keep as is")
            val spdViews = ArrayList<Pair<View, Boolean>>()   // (view, only for colour mode)
            fun spdVis() { val m = p.getString("pfSpd_$h", "0"); spdViews.forEach { (v, colourOnly) -> v.visibility = if (m == "0" || (colourOnly && m != "1")) View.GONE else View.VISIBLE } }
            host.addView(label("Follow the speed", 12))
            host.addView(picker("pfSpd_$h", "$name: follow the speed", listOf("0", "1", "2"), listOf("Off", "Colour: slow colour to fast colour", "Brightness: same colour, brighter when faster")) { spdVis(); applyTheme() })
            var at = host.childCount
            host.addView(label("Slow colour", 8))
            colourRow("pfSlow_$h", "$name: slow colour", "Blue", 0xFF2040FF.toInt())
            host.addView(label("Fast colour", 8))
            colourRow("pfFastC_$h", "$name: fast colour", "Red", 0xFFFF2020.toInt())
            while (at < host.childCount) spdViews += host.getChildAt(at++) to true
            run {
                val sk = "pfSpeed_$h"
                val lab = TextView(this).apply { tag = "m"; setPadding(0, dp(8), 0, 0) }
                val sb = SeekBar(this).apply { tag = "seek"; max = 140 }   // 60..200 km/h
                fun show(v: Int) { lab.text = "Fully fast / brightest at $v km/h (stopped = the ambient light above, dim in Brightness)" }
                val start = p.getInt(sk, 120).coerceIn(60, 200)
                sb.progress = start - 60; show(start)
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { show(v + 60); if (fromUser) p.edit().putInt(sk, v + 60).apply() }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
                host.addView(lab); host.addView(sb)
                spdViews += lab to false; spdViews += sb to false
            }
            spdVis()
            // ambient colour while the car regenerates (slowing down), so everyone sees it is braking; back to the mode's colour afterwards
            host.addView(label("Regen braking", 12))
            val regSw = androidx.appcompat.widget.SwitchCompat(this).apply { tag = "sw"; text = "Change the ambient colour while regen braking"; isChecked = p.getBoolean("pfRegenCol_$h", false) }
            host.addView(regSw, LinearLayout.LayoutParams(-1, -2))
            val regAt = host.childCount
            host.addView(label("Braking colour", 4))
            colourRow("pfRegenColor_$h", "$name: braking colour", "Red", 0xFFFF2020.toInt())
            val regViews = (regAt until host.childCount).map { host.getChildAt(it) }
            fun regVis() { regViews.forEach { it.visibility = if (regSw.isChecked) View.VISIBLE else View.GONE } }
            regSw.setOnCheckedChangeListener { _, on -> p.edit().putBoolean("pfRegenCol_$h", on).apply(); regVis() }
            regVis()
            // three small pickers in one row: regen, steering, engine sound
            fun cell(title: String, btn: Button) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 0))
                btn.maxLines = 1; btn.ellipsize = android.text.TextUtils.TruncateAt.END; btn.textSize = 13f
                addView(btn, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val trio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            fun addCell(c: View, first: Boolean = false) = trio.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) marginStart = dp(8) })
            addCell(cell("Regen strength", picker("pfRegen_$h", "$name: regen strength", listOf("0") + DriveProfile.REGEN.map { it.first.toString() }, listOf("Keep") + DriveProfile.REGEN.map { it.second })), true)
            addCell(cell("Steering", picker("pfSteer_$h", "$name: steering", listOf("0") + DriveProfile.STEER.map { it.first.toString() }, listOf("Keep") + DriveProfile.STEER.map { it.second })))
            addCell(cell("Engine sound", picker("pfSound_$h", "$name: engine sound", styleKeys, styleNames) { DriveProfile.previewSound(this, code) }))
            host.addView(trio)
            host.addView(label("Rev bar on the HUD: set in the HUD section.", 12))
            // rev bar: this mode's own switch and look, shown in the HUD section (one mode at a time), copied into the rev bar settings when the mode applies
            val rh = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            revPanels += rh; revBody.addView(rh)
            val revViews = ArrayList<View>()
            var afterRevVis: () -> Unit = {}
            fun revVis() { revViews.forEach { it.visibility = if (p.getString("pfRev_$h", DriveProfile.revDef(code, "pfRev")) == "2") View.VISIBLE else View.GONE }; afterRevVis() }
            rh.addView(label("Rev bar: $name", 12))
            rh.addView(picker("pfRev_$h", "$name: rev bar", listOf("0", "1", "2"), listOf("Keep as is", "Off", "On"), DriveProfile.revDef(code, "pfRev")) { revVis(); applyTheme(); DriveProfile.revEdited(this, code) })
            val revRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            for ((i, q) in listOf(Triple("pfRevShape_$h", RevView.SHAPE_NAMES, "Style"), Triple("pfRevScheme_$h", RevView.SCHEME_NAMES, "Colours")).withIndex())
                revRow.addView(cell(q.third, picker(q.first, "$name: ${q.third.lowercase()}", q.second.indices.map { it.toString() }, q.second, DriveProfile.revDef(code, q.first.substringBefore('_'))) { DriveProfile.revEdited(this, code) }),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
            rh.addView(revRow); revViews += revRow
            val rbSw = androidx.appcompat.widget.SwitchCompat(this).apply { tag = "sw"; text = "Regen bar (grows from the other end while regenerating)"; isChecked = p.getBoolean("pfRevRegen_$h", false) }
            val regCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun regVis2() { regCol.visibility = if (rbSw.isChecked && p.getString("pfRev_$h", DriveProfile.revDef(code, "pfRev")) == "2") View.VISIBLE else View.GONE }
            afterRevVis = ::regVis2
            rbSw.setOnCheckedChangeListener { _, on -> p.edit().putBoolean("pfRevRegen_$h", on).apply(); regVis2(); DriveProfile.revEdited(this, code) }
            rh.addView(rbSw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); revViews += rbSw
            colourRow("pfRevRegenColor_$h", "$name: regen bar colour", "Green", DriveProfile.REGEN_BAR_DEF, regCol) { DriveProfile.revEdited(this, code) }
            rh.addView(regCol); regVis2()
            // bar size and gap are set on the HUD pad (drag the bar, drag a corner dot)
            revVis()
        }
        run { val t = p.getInt("revTab", 0).coerceIn(0, revPanels.size - 1); revPanels.forEachIndexed { k, v -> v.visibility = if (k == t) View.VISIBLE else View.GONE }; revTabs.forEachIndexed { k, b -> b.isSelected = k == t } }
        run { val t = p.getInt("pfTab", 0).coerceIn(0, panels.size - 1); panels.forEachIndexed { k, v -> v.visibility = if (k == t) View.VISIBLE else View.GONE }; tabBtns.forEachIndexed { k, b -> b.isSelected = k == t } }
        body.addView(TextView(this).apply { tag = "m"; text = "When the car starts"; textSize = 17f; typeface = ZFont.bold; setPadding(0, dp(22), 0, dp(6)) })
        body.addView(picker("pfStart", "Switch to drive mode", listOf("0") + DriveProfile.MODES.map { it.first.toString() }, listOf("Leave it as it is") + DriveProfile.MODES.map { it.second }))
        body.addView(TextView(this).apply { tag = "m"; text = "Keep = leave unchanged. Engine sound also changes the Engine sound style setting; Silent mutes the engine sound in that mode (it comes back in the next mode that has a sound). The drive mode is switched once when the ignition comes on."; textSize = 13f; setPadding(0, dp(8), 0, 0) })
    }

    // Engine sound card (hidden menu): grouped chip rows and two volume sliders, built in code.
    private fun buildEngineCard(syncService: () -> Unit) {
        val body = findViewById<LinearLayout>(R.id.engBody)
        val d = ZScale.density(this)
        fun dp(x: Int) = (x * d).toInt()
        var built = false   // the first theme pass happens at the end of onCreate
        fun heading(t: String) = body.addView(TextView(this).apply { tag = "m"; text = t.uppercase(); textSize = 12f; letterSpacing = 0.1f; setPadding(0, dp(16), 0, dp(6)) })
        fun hint(t: String) = body.addView(TextView(this).apply { tag = "m"; text = t; textSize = 13f; setPadding(0, dp(6), 0, 0) })
        // One group of chips (single choice), [perRow] to a row; the chosen one is highlighted.
        fun chips(key: String, values: List<String>, names: List<String>, perRow: Int) {
            val btns = ArrayList<Button>()
            fun cur() = values.indexOf(p.getString(key, values[0])).coerceAtLeast(0)
            fun mark() { btns.forEachIndexed { i, b -> b.isSelected = i == cur() }; if (built) applyTheme() }
            for (r in 0 until (values.size + perRow - 1) / perRow) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, if (r == 0) 0 else dp(8), 0, 0) }
                for (c in 0 until perRow) {
                    val i = r * perRow + c
                    if (i >= values.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }); continue }
                    row.addView(Button(this).apply {
                        tag = "seg"; text = names[i]; isAllCaps = false
                        setOnClickListener { p.edit().putString(key, values[i]).apply(); mark(); syncService() }
                        btns += this
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (c > 0) marginStart = dp(8) })
                }
                body.addView(row)
            }
            mark()
        }
        fun slider(label: String, key: String, min: Int, max: Int, def: Int) {
            val lab = TextView(this).apply { tag = "m"; setPadding(0, dp(10), 0, 0) }
            val sb = SeekBar(this).apply { tag = "seek"; this.max = max - min }
            fun show(v: Int) { lab.text = "$label: ${if (v > 0) "+" else ""}$v dB" }
            val start = p.getInt(key, def).coerceIn(min, max)
            sb.progress = start - min; show(start)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { show(v + min); if (fromUser) p.edit().putInt(key, v + min).apply() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            body.addView(lab); body.addView(sb)
        }
        heading("Speakers")
        chips("engOut", listOf("cabin", "outside"), listOf("Cabin", "Outside"), 2)
        hint("The engine sound style is chosen per drive mode under Driving profiles. Other music may pause for a moment when the engine sound starts.")
        heading("Welcome sound (outside speaker)")
        chips("welcomeOn", listOf("off", "on"), listOf("Off", "Play on unlock"), 2)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        run {
            val keys = listOf("typer", "american", "prototype", "turbo4")
            val names = listOf("Civic Type R", "American V8", "Prototype V8", "Turbo four")
            body.addView(Button(this).apply {
                tag = "btn2"; isAllCaps = false
                fun cur() = keys.indexOf(p.getString("welcomeStyle", "typer")).coerceAtLeast(0)
                fun show() { text = "Sound: " + names[cur()] }
                show()
                setOnClickListener { ZDialog.choose(this@MainActivity, "Welcome sound", names, cur()) { i -> p.edit().putString("welcomeStyle", keys[i]).apply(); show() } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.addView(Button(this).apply {
            tag = "btn2"; text = "Play welcome sound"; isAllCaps = false
            setOnClickListener { com.zeekr.sentrycam.sound.WelcomeSound.play(this@MainActivity) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        hint("Engine start, one rev and shut down on the outside speaker when the car is unlocked. Uses the outside volume below.")
        heading("Lock sound (outside speaker)")
        chips("lockOn", listOf("off", "on"), listOf("Off", "Chirp on lock"), 2)
        run {
            val keys = listOf("chirp", "single", "triple", "fob", "chime", "horn", "sweep")
            val names = listOf("Double chirp", "Single beep", "Triple beep", "Key fob (low-high)", "Soft chime", "Horn honk", "Rising sweep")
            body.addView(Button(this).apply {
                tag = "btn2"; isAllCaps = false
                fun cur() = keys.indexOf(p.getString("lockStyle", "chirp")).coerceAtLeast(0)
                fun show() { text = "Sound: " + names[cur()] }
                show()
                setOnClickListener { ZDialog.choose(this@MainActivity, "Lock sound", names, cur()) { i -> p.edit().putString("lockStyle", keys[i]).apply(); show(); com.zeekr.sentrycam.sound.LockSound.play(this@MainActivity) } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        body.addView(Button(this).apply {
            tag = "btn2"; text = "Play lock sound"; isAllCaps = false
            setOnClickListener { com.zeekr.sentrycam.sound.LockSound.play(this@MainActivity) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        hint("Sound played when the car locks, so you know it is locked as you walk away. Uses the outside volume below.")
        heading("Feel")
        // buttons that only matter for some sound styles are hidden unless the current style, or a driving profile's style, uses them
        val gated = ArrayList<Pair<List<View>, Set<String>>>()
        fun gate(styles: Set<String>, add: () -> Unit) { val at = body.childCount; add(); gated += (at until body.childCount).map { body.getChildAt(it) } to styles }
        fun feelStyles(): Set<String> = HashSet<String>().apply {
            add(p.getString("engStyle", "typer") ?: "typer")
            if (p.getBoolean("pfOn", false)) DriveProfile.MODES.forEach { add(p.getString("pfSound_" + DriveProfile.hex(it.first), "") ?: "") }
        }
        fun updateFeel() { val now = feelStyles(); gated.forEach { (views, styles) -> views.forEach { it.visibility = if (styles.any { st -> st in now }) View.VISIBLE else View.GONE } } }
        gate(setOf("american", "prototype")) {
            chips("engGearbox", listOf("auto8", "manual6"), listOf("8-speed auto", "6-speed manual"), 2)
            body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        }
        chips("engRpm", listOf("continuous", "shifts"), listOf("Smooth revs", "Gear shifts"), 2)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        gate(setOf("american")) {
            chips("engView", listOf("cabin", "outside"), listOf("Inside the car", "Outside the car"), 2)
            body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        }
        chips("engGearShow", listOf("on", "off"), listOf("Show gear on rev bar and widget", "Hide gear"), 2)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        chips("engBlip", listOf("on", "off"), listOf("Rev when shifting into D/R", "No rev blip"), 2)
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        chips("engCrackle", listOf("normal", "low", "off"), listOf("Crackle", "Low crackle", "No crackle"), 3)
        gate(setOf("typer")) {
            body.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
            chips("engPop", listOf("off", "low", "high"), listOf("No exhaust pops", "Light pops (high-rev shifts)", "Strong pops (high-rev shifts)"), 3)
        }
        updateFeel()
        feelListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "engStyle" || k == "pfOn" || k?.startsWith("pfSound_") == true) runOnUiThread { updateFeel() } }
        p.registerOnSharedPreferenceChangeListener(feelListener)
        heading("Volume")
        run {   // the car's own media volume (the engine sound plays on the same stream, so it moves with this)
            val am = getSystemService(android.media.AudioManager::class.java)
            val top = try { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Throwable) { 1 }.coerceAtLeast(1)
            val lab = TextView(this).apply { tag = "m"; setPadding(0, dp(10), 0, 0) }
            val sb = SeekBar(this).apply { tag = "seek"; max = top }
            fun show(v: Int) { lab.text = "Car media volume: $v / $top" }
            val cur = try { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Throwable) { 0 }
            sb.progress = cur; show(cur)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { show(v); if (fromUser) try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) } catch (_: Throwable) {} }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            body.addView(lab); body.addView(sb)
        }
        slider("Cabin volume", "engVol", -30, 12, 0)
        hint("Above 0 dB the engine is pushed into the limiter: louder than phone music, but denser.")
        slider("Outside volume (max loud)", "engExtVol", 0, 36, 18)
        hint("Outside above about +24 dB is pushed hard into the limiter and can distort the small outside speaker.")

        built = true
    }

    // Every card collapses on its own (any number can be open in either column); the open ones are remembered.
    private val cards = ArrayList<Triple<LinearLayout, TextView, String>>()
    private fun openSet(): MutableSet<String> = (p.getString("openSections", null) ?: "Driving profiles").split("|").filter { it.isNotEmpty() }.toMutableSet()
    private val SECTION_ICONS = mapOf("Relay" to R.drawable.ic_sec_relay, "Cameras" to R.drawable.ic_sec_camera, "Backup" to R.drawable.ic_sec_backup,
        "Driving profiles" to R.drawable.ic_sec_profile, "Engine sound" to R.drawable.ic_sec_engine, "HUD" to R.drawable.ic_sec_blind, "Camping mode" to R.drawable.ic_sec_camp,
        "360° live view" to R.drawable.ic_sec_live, "Web view" to R.drawable.ic_sec_web, "Floating windows" to R.drawable.ic_sec_float, "Trip widget" to R.drawable.ic_sec_trip, "Zed" to R.drawable.ic_sec_mascot, "Snapshot (works with ZDash)" to R.drawable.ic_sec_sentry)
    private val SIDE_BUTTONS = mapOf(R.id.checkUpdate to R.drawable.ic_btn_update, R.id.applyDefaults to R.drawable.ic_btn_reset, R.id.backupCard to R.drawable.ic_sec_backup, R.id.dataUsage to R.drawable.ic_btn_data,
        R.id.viewLog to R.drawable.ic_btn_log, R.id.whatsNew to R.drawable.ic_btn_whatsnew, R.id.unlink to R.drawable.ic_btn_unlink, R.id.restartApp to R.drawable.ic_btn_restart, R.id.credits to R.drawable.ic_btn_credits, R.id.resetAll to R.drawable.ic_btn_reset)
    private fun addSectionIcons(v: View) {
        if (v.tag == "h" && v is TextView) SECTION_ICONS[v.text.toString()]?.let {
            v.setCompoundDrawablesRelativeWithIntrinsicBounds(it, 0, 0, 0); v.compoundDrawablePadding = (10 * ZScale.density(this)).toInt()
        } else if (v is ViewGroup) for (i in 0 until v.childCount) addSectionIcons(v.getChildAt(i))
    }
    private fun setupCollapsible() {
        addSectionIcons(findViewById(R.id.scroll))
        for ((id, res) in SIDE_BUTTONS) findViewById<Button>(id).apply {
            gravity = Gravity.CENTER; includeFontPadding = false
            val ic = resources.getDrawable(res, theme); ic.setBounds(0, 0, ic.intrinsicWidth, ic.intrinsicHeight)
            setCompoundDrawablesRelative(ic, null, null, null); compoundDrawablePadding = (6 * ZScale.density(this@MainActivity)).toInt() }
        fun walk(v: View) {
            if (v.tag == "card" && v is LinearLayout && v.childCount > 1) {
                val h = v.getChildAt(0) as? TextView
                if (h != null && h.tag == "h") cards += Triple(v, h, h.text.toString())
            } else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(findViewById(R.id.opBox))
        for ((_, h, title) in cards) h.setOnClickListener {
            val s = openSet(); if (!s.remove(title)) s.add(title)
            p.edit().putString("openSections", s.joinToString("|")).apply(); showOpen()
        }
        showOpen()
    }
    private val liveHolders = ArrayList<() -> Unit>()
    private var resumed = false
    private fun liveWanted() = resumed && "360° live view" in openSet()
    private fun updateLiveHold() { liveHolders.forEach { it() } }
    // One short line after each section name, so you know what is inside before opening it
    private val SECTION_DESC = mapOf(
        "Relay" to "link to your ZDash relay",
        "Cameras" to "choose cameras, previews",
        "Driving profiles" to "sound, light, regen per drive mode",
        "Camping mode" to "auto-off when the car is empty",
        "Engine sound" to "simulated engine, volume, style",
        "HUD" to "rev bar, side camera, turn arrow",
        "360\u00b0 live view" to "tune the four camera pictures",
        "Web view" to "browser on the HUD and in a window",
        "Floating windows" to "windows, button, animations",
        "Trip widget" to "driving stats window",
        "Zed" to "your pixel pet that roams the screen",
        "Snapshot (works with ZDash)" to "remote snapshots, live view, clips")
    private fun headerText(title: String): CharSequence {
        val d = SECTION_DESC[title] ?: return title
        val sp = android.text.SpannableString("$title   $d")
        val mut = ZTheme.palette(ZTheme.Mode.of(p.getString("theme", null))).mut
        sp.setSpan(android.text.style.RelativeSizeSpan(0.62f), title.length, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(mut), title.length, sp.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }
    private fun showOpen() {
        val open = openSet()
        updateLiveHold()
        for ((card, h, title) in cards) {
            val closed = title !in open
            h.text = headerText(title); h.maxLines = 1; h.ellipsize = android.text.TextUtils.TruncateAt.END
            h.isSelected = closed
            h.setPadding(0, 0, 0, if (closed) 0 else (6 * ZScale.density(this)).toInt())
            for (i in 1 until card.childCount) card.getChildAt(i).let { if (it.id != R.id.stitchPick) it.visibility = if (closed) View.GONE else View.VISIBLE }
        }
    }

    // Kill the whole process (floating windows, HUD, camera hub) and have the system bring the service and the app back.
    // Stops the service and kills the whole process (floating windows, HUD, camera hub). Nothing brings it back; open the app again to start it.
    // The built-in preset (assets/default_settings.json): replaces every backed-up setting except the personal web data (bookmarks, history, tabs), then reloads.
    private val STRING_PREFS = listOf("pfRev", "pfRevShape", "pfRevScheme", "pfRevPos", "pfSpd", "pfRegen", "pfRegenColor", "pfSteer", "pfSound", "pfColor", "pfSlow", "pfFastC", "pfStart", "pfVoice")
    /** One pref from a preset or backup; the profile pickers read their keys as strings, so a number there would crash the settings screen. Anything unexpected is skipped, never thrown. */
    private fun putPref(e: android.content.SharedPreferences.Editor, k: String, v: Any?): Boolean {
        try {
            val asText = STRING_PREFS.any { k == it || k.startsWith(it + "_") }
            when (v) {
                is Boolean -> if (asText) e.putString(k, v.toString()) else e.putBoolean(k, v)
                is Int -> if (asText) e.putString(k, v.toString()) else e.putInt(k, v)
                is Long -> if (asText) e.putString(k, v.toString()) else e.putLong(k, v)
                is String -> e.putString(k, v)
                else -> return false
            }
            return true
        } catch (_: Throwable) { return false }
    }
    private fun applyDefaultSettings() {
        val cfg = try { JSONObject(assets.open("default_settings.json").bufferedReader().use { it.readText() }) } catch (e: Throwable) { Toast.makeText(this, "Default settings missing", Toast.LENGTH_LONG).show(); return }
        val keep = setOf("webBookmarks", "webHistory", "webTabs", "webTabsDesk", "webTabIdx", "webUrl")
        val e = p.edit()
        for (k in p.all.keys.filter { (backupKey(it) || it.startsWith("fm")) && it !in keep }) e.remove(k)
        var n = 0
        for (k in cfg.keys()) {
            if (k in keep) continue
            putPref(e, k, cfg.get(k)).let { if (it) n++ }
        }
        e.putLong("flSizeStamp", System.currentTimeMillis())   // windows re-read their saved sizes
        e.commit()
        Logger.log(this, "default settings applied ($n values)")
        recreate()
        val i = Intent(this, CamService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun forceCloseApp() {
        Logger.log(this, "force close requested from the app")
        p.edit().remove("safeUntilMs").remove("crashTs").commit()   // a deliberate close must never count towards the crash-loop safe mode
        try { stopService(Intent(this, CamService::class.java)) } catch (_: Throwable) {}
        finishAffinity()
        ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()); System.exit(0) }, 400)
    }

    private fun restartApp() {
        // Kill the whole process (floating windows, HUD, camera hub) and have the system bring the service and the app back.
        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
        val at = System.currentTimeMillis() + 1200
        val svc = Intent(this, CamService::class.java)
        alarm.set(android.app.AlarmManager.RTC_WAKEUP, at, if (Build.VERSION.SDK_INT >= 26) android.app.PendingIntent.getForegroundService(this, 71, svc, flags) else android.app.PendingIntent.getService(this, 71, svc, flags))
        packageManager.getLaunchIntentForPackage(packageName)?.let { alarm.set(android.app.AlarmManager.RTC_WAKEUP, at + 300, android.app.PendingIntent.getActivity(this, 72, it, flags)) }
        Logger.log(this, "restart requested from the app")
        // A deliberate restart must never count towards the crash-loop safe mode (a kill signal would).
        p.edit().remove("safeUntilMs").remove("crashTs").commit()
        finishAffinity()
        System.exit(0)
    }

    // Settings backup to the relay (car-app token). Only user choices go up; link/credential keys never do.
    // Backup groups: what goes up to the relay, and what the long press on the Backup button can leave out.
    private val BACKUP_CATS = listOf(
        "cam" to "Camera windows, HUD and tuning", "web" to "Web window settings", "webdata" to "Web bookmarks, history and tabs",
        "eng" to "Engine sound and welcome sound", "pf" to "Driving profiles", "tw" to "Trip widget", "mc" to "Zed", "rev" to "Rev bar", "ui" to "Theme")
    private val BACKUP_SKIP = setOf("bsPreviewUntil", "bsNoHoldUntil", "bsPreviewSide", "bsArrPrevUntil", "bsArrPrevSide", "flSizeStamp", "revPreviewUntil", "slPreviewUntil", "slBeepPrev", "webPreviewUntil", "twSnapSeq", "twReset", "pfTab", "revTab")
    private fun backupCat(k: String): String? = when {
        k in BACKUP_SKIP -> null
        k.startsWith("lk") || k.startsWith("bs") || k.startsWith("sl") || k.startsWith("fl") || k.startsWith("fm") || k.startsWith("stitch") || k.startsWith("stSeam") || k.startsWith("stBlend") || k == "parkCut" || k.startsWith("mm") || k.startsWith("gd") || k == "hudZ" -> "cam"
        k == "webBookmarks" || k == "webHistory" || k == "webTabs" || k == "webTabsDesk" || k == "webTabIdx" || k == "webUrl" -> "webdata"
        k.startsWith("web") -> "web"
        k.startsWith("eng") || k.startsWith("welcome") || k.startsWith("lock") -> "eng"
        k.startsWith("pf") -> "pf"
        k.startsWith("tw") -> "tw"
        k.startsWith("mc") -> "mc"
        k.startsWith("rev") -> "rev"
        k == "theme" || k.startsWith("openSection") -> "ui"
        else -> null
    }
    private fun backupKey(k: String) = backupCat(k) != null
    private fun backupOff(): Set<String> = (p.getString("backupOff", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
    private fun backupIncluded(k: String): Boolean { val c = backupCat(k) ?: return false; return c !in backupOff() }
    // Settings never touched are absent from the prefs, so a backup would leave them out and a restore could not bring them back
    // (the window switches, camera tuning...). Defaults go in first, saved values override them.
    private fun backupDefaults(): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        for (k in listOf("flLeft", "flRight", "flRear", "flFront", "flOnly_L", "flOnly_R", "flBlinkCenter", "flBlackBg", "bsHud", "bsRightPos", "revOn", "slOn", "slBeep", "mmOn", "webHudOn", "webFloatOn", "webFloatAlways")) m[k] = false
        for (k in listOf("flMaster", "parkCut", "bsSignal", "slGlow")) m[k] = true
        m["flHeader"] = 56; m["flCorner"] = 0; m["flFade"] = 0; m["slRing"] = 28; m["slNum"] = 100
        for (c in CamTune.CAMS) {
            for (k in listOf("bsSize", "bsAspect", "bsFov", "bsTilt", "bsYaw", "bsRoll")) if ((k != "bsSize" && k != "bsAspect") || CamTune.side(c)) m["${k}_$c"] = CamTune.def(k, c)
            for (k in CamTune.LOOK) m["${k}_$c"] = CamTune.lookDef(k)
            m[CamTune.mirrorKey(c)] = CamTune.mirrorDef(c)
            if (!CamTune.side(c)) m[CamTune.flipKey(c)] = false
        }
        return m
    }
    private fun backupKeys(): Map<String, Any> {
        val out = LinkedHashMap(backupDefaults())
        for ((k, v) in p.all) if (v != null && backupKey(k) && (v is Boolean || v is Int || v is Long || v is String)) out[k] = v
        out.keys.retainAll { backupIncluded(it) }   // groups left out with the long press on Backup
        return out
    }
    private fun setupBackup() {
        fun relay(): Pair<String, String>? {
            val url = p.getString("url", "") ?: ""; val tok = p.getString("token", null)
            return if (url.isEmpty() || tok.isNullOrEmpty()) null else url to tok
        }
        fun doBackup(st: TextView) {
            val r = relay() ?: run { st.text = "Not linked"; return }
            val cfg = JSONObject(); for ((k, v) in backupKeys()) cfg.put(k, v)
            // The relay takes 32 KB: shed the oldest history first (bookmarks and settings matter more).
            var hist = WebStore.history(p)
            while (cfg.has("webHistory") && cfg.toString().length > 30000 && hist.isNotEmpty()) {
                hist = hist.take(hist.size / 2).toMutableList()
                cfg.put("webHistory", org.json.JSONArray().also { a -> hist.forEach { e -> a.put(org.json.JSONArray().put(e.url).put(e.title).put(e.t)) } }.toString())
            }
            fun upload() {
                st.text = "Backing up..."
                Thread {
                    val res = Net.postJson(r.first + "/cam/config", r.second, JSONObject().put("config", cfg))
                    ui.post { st.text = if (res?.optBoolean("ok") == true) "Backed up ${cfg.length()} settings" else "Backup failed (deploy the latest relay?)" }
                }.start()
            }
            // Never silently replace a bigger stored backup with a smaller one (that is how a full backup gets lost).
            st.text = "Checking the stored backup..."
            Thread {
                val old = Net.get(r.first + "/cam/config", r.second)?.optJSONObject("config")
                ui.post {
                    if (old != null && old.length() > cfg.length()) ZDialog.show(this, "Replace the stored backup?",
                        "The stored backup has ${old.length()} settings; this device would upload only ${cfg.length()}.", "Replace",
                        onNegative = { st.text = "Backup cancelled" }) { upload() }
                    else upload()
                }
            }.start()
        }
        fun doRestore(st: TextView) {
            val r = relay() ?: run { st.text = "Not linked"; return }
            st.text = "Restoring..."
            Thread {
                val res = Net.get(r.first + "/cam/config", r.second)
                val cfg = res?.optJSONObject("config")
                ui.post {
                    if (cfg == null) { st.text = if (res?.optBoolean("ok") == true) "No backup found" else "Restore failed (deploy the latest relay?)"; return@post }
                    val e = p.edit(); var n = 0
                    for (k in cfg.keys()) {
                        if (!backupKey(k)) continue
                        if (putPref(e, k, cfg.get(k))) n++
                    }
                    e.apply()
                    st.text = "Restored $n settings"
                    recreate()
                    val i = Intent(this, CamService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                }
            }.start()
        }
        findViewById<Button>(R.id.dataUsage).setOnClickListener { showDataUsage() }
        // one button opens a pop-up with Back up and Restore (only shown once the app is linked to the relay, see showState)
        findViewById<Button>(R.id.backupCard).setOnLongClickListener {
            // choose what a backup leaves out
            val d = ZScale.density(this)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(this).apply { tag = "m"; text = "Tick what a backup should include. Unticked groups are left out."; setPadding(0, 0, 0, (8 * d).toInt()) })
            for ((id, label) in BACKUP_CATS) box.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
                tag = "sw"; text = label; isChecked = id !in backupOff()
                setOnCheckedChangeListener { _, on -> val s = backupOff().toMutableSet(); if (on) s.remove(id) else s.add(id); p.edit().putString("backupOff", s.joinToString(",")).apply() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            ZDialog.custom(this, box, "Done")
            true
        }
        findViewById<Button>(R.id.backupCard).setOnClickListener {
            val d = ZScale.density(this)
            val st = TextView(this).apply { tag = "m"; setPadding(0, (8 * d).toInt(), 0, 0) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(this).apply { tag = "m"; text = "Save these settings to your relay, or restore them after a reinstall."; setPadding(0, 0, 0, (8 * d).toInt()) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply { tag = "btn"; text = "Back up"; setOnClickListener { doBackup(st) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply { tag = "btn2"; text = "Restore"; setOnClickListener { doRestore(st) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (12 * d).toInt() })
            box.addView(row); box.addView(st)
            ZDialog.custom(this, box, "Close")
        }
    }

    private fun showDataUsage() {
        val d = ZScale.density(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tot = TextView(this).apply { tag = "m"; setPadding(0, (8 * d).toInt(), 0, 0) }
        val holder = FrameLayout(this)
        val tabs = ArrayList<Button>()
        fun show(daily: Boolean) {
            tabs.forEachIndexed { i, b -> b.isSelected = (i == 1) == daily; ZTheme.style(b, ZTheme.palette(mode)) }   // lime = the active tab ("seg" styling is set when styled, so restyle on every switch)
            val g = DataGraph(this, daily); holder.removeAllViews()
            holder.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt()))
            tot.text = (if (daily) "Last 14 days: " else "Last 24 hours: ") + g.totals() + "  (lime = sent, grey = received)"
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { tag = "seg"; text = "Hourly"; tabs.add(this); setOnClickListener { show(false) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { tag = "seg"; text = "Daily"; tabs.add(this); setOnClickListener { show(true) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (12 * d).toInt() })
        box.addView(row); box.addView(holder); box.addView(tot)
        ZDialog.custom(this, box, "Close")
        show(false)   // after the dialog styled the buttons
    }

    private fun setupWebHud() {
        fun syncService() {
            val i = Intent(this, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        fun webManager(history: Boolean) {
            val d = ZScale.density(this)
            var dlg: AlertDialog? = null
            val panel = WebStore.panel(this, p, (520 * d).toInt(), history) { u ->
                p.edit().putString("webUrl", u).apply(); WebHud.instance?.load(u); dlg?.dismiss()
            }
            dlg = ZDialog.custom(this, panel)
        }
        findViewById<SeekBar>(R.id.webVol).apply {
            val lab = this@MainActivity.findViewById<TextView>(R.id.webVolLabel)
            progress = p.getInt("webVol", 100).coerceIn(0, 100); lab.text = "Web volume: $progress%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    lab.text = "Web volume: $v%"
                    if (fromUser) { p.edit().putInt("webVol", v).apply(); WebHud.instance?.applyVolume() }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        findViewById<Button>(R.id.webBookmarks).setOnClickListener { webManager(false) }
        findViewById<Button>(R.id.webHistory).setOnClickListener { webManager(true) }
        findViewById<CompoundButton>(R.id.webOn).apply {
            isChecked = p.getBoolean("webHudOn", false)
            var confirmed = false
            setOnCheckedChangeListener { btn, on ->
                if (on && !confirmed) {
                    // Safety notice: stays off until the driver accepts.
                    btn.isChecked = false
                    ZDialog.show(this@MainActivity, "Drive responsibly",
                        "The web view is for passengers and when parked. Use it responsibly while driving and at your own risk. Keep your eyes on the road; you are responsible for driving safely and obeying local laws.",
                        "I understand") { confirmed = true; btn.isChecked = true; confirmed = false }
                    return@setOnCheckedChangeListener
                }
                p.edit().putBoolean("webHudOn", on).apply()
                if (on && !android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Allow 'Display over other apps' for this app", Toast.LENGTH_LONG).show()
                }
                if (on && Build.VERSION.SDK_INT >= 31 &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
                syncService()
            }
        }
    }

    // Restyles the whole screen (also needed after views are added at runtime).
    private fun applyTheme() {
        for ((id, m) in listOf(R.id.themeDark to ZTheme.Mode.DARK, R.id.themeBlack to ZTheme.Mode.BLACK, R.id.themeLight to ZTheme.Mode.LIGHT))
            findViewById<Button>(id).isSelected = (m == mode)
        ZTheme.apply(this, findViewById(R.id.scroll), mode)
        pfRefresh.forEach { it() }
        // the underscore in the logo is the accent
        findViewById<TextView>(R.id.logo).text = SpannableString("Z_Cam").apply {
            setSpan(ForegroundColorSpan(ZTheme.LIME), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // Sentry's own incident clips live in dated subfolders (e.g. SentryMode/2026-10-23_16_42_44/)
    // that Android's MediaStore does not automatically index (confirmed live: 372 older
    // continuous-recording clips were indexed, zero SentryMode clips ever were). Storage Access
    // Framework's folder picker needs a DocumentsUI activity to handle
    // ACTION_OPEN_DOCUMENT_TREE, which ZeekrOS automotive doesn't ship ("No Activity found to
    // handle Intent"). So the Sentry folder is located via direct filesystem access instead:
    // scan mounted storage volumes for a top-level "SentryMode" folder. ZeekrOS also has no
    // Settings screen for ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (same missing-system-UI
    // pattern), so isExternalStorageManager() can't be fixed via that intent here -- just try
    // the scan directly and report what's actually blocking it.
    // HUD layout pad: every HUD item on one pad (both cameras, web page, both arrows); eye chips hide items on the pad so stacked ones can be pulled apart.
    private var hudPadReload: (() -> Unit)? = null   // set by the pad; the rev bar tabs call it when the drive mode changes
    private var arrSide = true   // which arrow the icon/colour rows edit (true = left)
    private fun setupArrowEditor() {
        val host = findViewById<LinearLayout>(R.id.arrHost)
        val style = findViewById<LinearLayout>(R.id.arrStyleHost)
        var loading = false
        fun sfx(left: Boolean) = if (left) "_L" else "_R"
        lateinit var pad: HudPadView
        val camL = HudPadView.Item("camL", "LEFT CAM", 0, true, 0xFF6EA8FF.toInt())
        val camR = HudPadView.Item("camR", "RIGHT CAM", 0, false, 0xFF5FD6A0.toInt())
        val web = HudPadView.Item("web", "WEB", 1, true, 0xFFFFB454.toInt())
        val arrL = HudPadView.Item("arrL", "", 2, true, 0)
        val arrR = HudPadView.Item("arrR", "", 2, false, 0)
        val rev = HudPadView.Item("rev", "REV BAR", 3, true, 0xFFFF6E6E.toInt())
        val lim = HudPadView.Item("lim", "LIMIT", 5, true, 0xFFFF4D4D.toInt())
        val mm = HudPadView.Item("mm", "DRIVE MODE", 6, true, 0xFF00E5FF.toInt())
        // guides: where the car's own radar / surround display and speedometer sit by default (measured on the car; user moves are kept in prefs "gd*")
        val radar = HudPadView.Item("radar", "RADAR", 4, true, 0xFFB48EFF.toInt()).apply { x = 62; y = 49; gw = 432; gh = 408 }
        val speed = HudPadView.Item("speed", "SPEEDO", 4, true, 0xFFFFD166.toInt()).apply { x = 38; y = 44; gw = 96; gh = 142 }
        fun revMode() = DriveProfile.MODES[p.getInt("revTab", 0).coerceIn(0, DriveProfile.MODES.size - 1)]
        fun arrow() = if (arrSide) arrL else arrR
        val icons = ArrayList<View>(); val dot = View(this); val hexT = TextView(this).apply { tag = "m"; gravity = Gravity.CENTER_VERTICAL }
        val chips = ArrayList<Pair<Button, HudPadView.Item>>()
        // the HUD settings sections: all listed, folded up until their header is tapped (or their component is picked on the pad)
        val secIds = listOf(R.id.hudSecShow, R.id.hudSecArrow, R.id.hudSecRev, R.id.hudSecLimit, R.id.hudSecExit)
        val secName = HashMap<Int, String>(); val secHead = HashMap<Int, TextView>(); val secBody = HashMap<Int, List<View>>(); val secOpen = HashMap<Int, Boolean>()
        var lastSelId: String? = null
        fun setSec(id: Int, open: Boolean) {
            secOpen[id] = open
            secHead[id]!!.text = (if (open) "v  " else ">  ") + secName[id]
            for (v in secBody[id]!!) v.visibility = if (open) View.VISIBLE else View.GONE
        }
        for (id in secIds) {
            val sec = findViewById<LinearLayout>(id)
            val kids = (0 until sec.childCount).map { sec.getChildAt(it) }
            val head = kids.first { it is TextView && it.tag == "accent" } as TextView
            secName[id] = head.text.toString(); secHead[id] = head; secBody[id] = kids.drop(kids.indexOf(head) + 1)
            head.setPadding(0, dp(14), 0, dp(10)); head.setOnClickListener { setSec(id, secOpen[id] != true) }
            setSec(id, false)
        }
        fun preview(i: HudPadView.Item) {
            val until = System.currentTimeMillis() + 6000
            val e = p.edit()
            when (i.kind) {
                0 -> e.putLong("bsPreviewUntil", until).putInt("bsPreviewSide", if (i.left) 2 else 3)
                2 -> e.putLong("bsArrPrevUntil", until).putInt("bsArrPrevSide", if (i.left) 1 else 2)
                5 -> e.putLong("slPreviewUntil", until)
                1 -> e.putLong("webPreviewUntil", until)   // show the page on the HUD while it is being placed, even with its switch off
                else -> {}
            }
            e.apply()
        }
        fun mark() {
            val pal = ZTheme.palette(mode); val a = arrow()
            // picking a component on the pad opens its section (the others fold up); nothing picked = all folded
            val sl = pad.sel?.takeIf { it.shown }
            if (sl?.id != lastSelId) {
                lastSelId = sl?.id
                val want = when { sl == null -> null; sl.kind == 2 -> R.id.hudSecArrow; sl.id == "rev" -> R.id.hudSecRev; sl.id == "lim" -> R.id.hudSecLimit; sl.kind == 0 -> R.id.hudSecExit; sl.kind == 1 -> R.id.hudSecShow; else -> null }
                if (want != null) for (sid in secIds) setSec(sid, sid == want)
            }
            dot.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(a.color); setStroke(dp(2), pal.line)
            }
            hexT.text = String.format("#%06X", a.color and 0xFFFFFF)
            for ((i, v) in icons.withIndex()) v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(android.graphics.Color.rgb(14, 18, 22)); setStroke(dp(if (i == a.icon) 3 else 1), if (i == a.icon) ZTheme.LIME else pal.line)
            }
            for ((id, l) in listOf(R.id.arrSideL to true, R.id.arrSideR to false)) findViewById<Button>(id).also { it.isSelected = arrSide == l; ZTheme.style(it, pal) }
            for ((b, it) in chips) { b.isSelected = it.shown; ZTheme.style(b, pal) }
        }
        lateinit var gdSync: () -> Unit
        fun restyleIcons() { for (v in icons) (v as ArrowIconChip).apply { left = arrSide; color = arrow().color; invalidate() } }
        fun load() {
            loading = true
            for (c in listOf(camL, camR)) {
                val l = c.left; val k = sfx(l)
                c.size = p.getInt("bsSize$k", BlindSpotHud.defaultFor("bsSize", l)).coerceIn(40, 100)
                // not placed yet: where the old edge-aligned default puts it (the HUD is 3 times as wide as high)
                c.x = p.getInt("bsPosX$k", 20)
                c.y = p.getInt("bsPosY$k", 36)
                c.aspect = p.getInt("bsAspect$k", 100).coerceIn(40, 250)
            }
            run {   // the rev bar of the drive mode picked in the rev bar tabs below
                val (code, name) = revMode(); val h = DriveProfile.hex(code)
                rev.label = "REV BAR: " + name.uppercase()
                rev.pos = (p.getString("pfRevPos_$h", DriveProfile.revDef(code, "pfRevPos"))?.toIntOrNull() ?: 1).coerceIn(0, 2)
                rev.size = p.getInt("pfRevSize_$h", DriveProfile.revSizeDef(code)).coerceIn(2, 30); rev.gap = p.getInt("pfRevInset_$h", DriveProfile.REV_GAP).coerceIn(0, 40)
                if (rev.gap < rev.minGap()) {   // saved outside the visible windshield area (can't be seen or grabbed): bring it back in
                    rev.gap = rev.minGap(); p.edit().putInt("pfRevInset_$h", rev.gap).apply(); DriveProfile.revEdited(this, code)
                }
            }
            for (g in listOf(radar, speed)) {   // guides: the saved spot and size, else the measured defaults
                if (g === radar) { g.x = 63; g.y = 35; g.gw = 412; g.gh = 276 } else { g.x = 38; g.y = 34; g.gw = 80; g.gh = 110 }   // fixed: measured on the car
            }
            mm.x = p.getInt("mmX", 36); mm.y = p.getInt("mmY", 55); mm.size = p.getInt("mmSize", 16).coerceIn(8, 60)
            lim.x = p.getInt("slX", 44); lim.y = p.getInt("slY", 12); lim.size = p.getInt("slSize", 25).coerceIn(10, 80)
            web.size = p.getInt("webSize", 122).coerceIn(20, 200); web.x = p.getInt("webPosX", 19); web.y = p.getInt("webPosY", 61)
            for (a in listOf(arrL, arrR)) {
                val l = a.left
                a.x = BlindSpotHud.arrowGet(p, "bsArrX", l); a.y = BlindSpotHud.arrowGet(p, "bsArrY", l); a.size = BlindSpotHud.arrowGet(p, "bsArrSize", l); a.aspect = BlindSpotHud.arrowGet(p, "bsArrAsp", l).coerceIn(50, 500)
                a.icon = BlindSpotHud.arrowGet(p, "bsArrIcon", l).coerceIn(0, BlindSpotHud.ARROW_ICONS.size - 1); a.color = BlindSpotHud.arrowGet(p, "bsArrColor", l)
            }
            restyleIcons(); pad.invalidate(); mark(); loading = false
        }
        pad = HudPadView(this, onSelect = { i ->
            if (i.kind == 2) { arrSide = i.left; restyleIcons() }
            mark(); preview(i); gdSync()
        }, onChange = { i ->
            if (!loading) {
                val e = p.edit(); val k = sfx(i.left)
                when (i.id) {
                    "rev" -> { val h = DriveProfile.hex(revMode().first); e.putString("pfRevPos_$h", i.pos.toString()).putInt("pfRevSize_$h", i.size).putInt("pfRevInset_$h", i.gap) }
                    "mm" -> e.putInt("mmX", i.x).putInt("mmY", i.y).putInt("mmSize", i.size)
                    "lim" -> e.putInt("slX", i.x).putInt("slY", i.y).putInt("slSize", i.size)
                    "web" -> e.putInt("webPosX", i.x).putInt("webPosY", i.y).putInt("webSize", i.size)
                    "radar", "speed" -> e.putInt("gdX_${i.id}", i.x).putInt("gdY_${i.id}", i.y).putInt("gdW_${i.id}", i.gw).putInt("gdH_${i.id}", i.gh)
                    "camL", "camR" -> e.putInt("bsPosX$k", i.x).putInt("bsPosY$k", i.y).putInt("bsSize$k", i.size)
                    else -> e.putInt("bsArrX$k", i.x).putInt("bsArrY$k", i.y).putInt("bsArrSize$k", i.size).putInt("bsArrAsp$k", i.aspect)
                }
                e.apply(); if (i.id != "rev") preview(i); gdSync()
            }
        }, onRelease = { i -> if (i.id == "rev") DriveProfile.revEdited(this, revMode().first) else if (i.id == "mm") ModeHud.show(this, "Sport", 0xFFFF2020.toInt()) })   // the bar shows on the HUD once the finger lifts
        // layering: the pad draws in the saved HUD order (back to front); the real HUD windows follow it too (HudLayer)
        val layerInfo = TextView(this).apply { tag = "m"; setPadding(0, dp(8), 0, 0) }
        fun applyZ() {
            pad.items.clear(); pad.items += listOf(radar, speed, mm)   // guides first: they sit underneath
            val ord = HudLayer.order(p)
            for (id in ord) pad.items += when (id) { "cam" -> listOf(camL, camR); "arr" -> listOf(arrL, arrR); "web" -> listOf(web); "rev" -> listOf(rev); else -> listOf(lim) }
            layerInfo.text = "Layers, back to front: " + ord.joinToString(" > ") { HudLayer.NAMES[it] ?: it } + "\nTap an item on the pad, then move it."
            pad.invalidate()
        }
        applyZ()
        hudPadReload = { load() }
        host.addView(pad, LinearLayout.LayoutParams(-1, -2))
        // eye chips: lit = shown on the pad (editor only, the HUD itself is not affected)
        host.addView(TextView(this).apply { tag = "m"; text = "Show on pad (hide items that are in the way while arranging):"; setPadding(0, dp(10), 0, 0) })
        val crow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for ((n, pair) in listOf("L cam" to camL, "R cam" to camR, "Web" to web, "Rev bar" to rev, "Limit" to lim, "Mode" to mm, "L arrow" to arrL, "R arrow" to arrR).withIndex()) {
            val b = Button(this).apply { tag = "seg"; text = pair.first; isAllCaps = false; isSelected = true }
            b.setOnClickListener {
                pair.second.shown = !pair.second.shown
                if (!pair.second.shown && pad.sel === pair.second) pad.sel = null
                pad.invalidate(); mark()
            }
            chips += b to pair.second
            crow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(8) })
        }
        host.addView(crow)
        host.addView(layerInfo, LinearLayout.LayoutParams(-1, -2))
        val zrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for ((n, step) in listOf("Send back" to -1, "Bring forward" to 1).withIndex()) {
            val b = Button(this).apply { tag = "seg"; text = step.first; isAllCaps = false }
            b.setOnClickListener {
                val s = pad.sel?.takeIf { it.kind != 4 && it.kind != 6 } ?: return@setOnClickListener
                HudLayer.move(p, HudLayer.layerOf(s.id), step.second); applyZ()
                HudLayer.restackAll(p)
            }
            zrow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(8) })
        }
        host.addView(zrow)
        // guide rectangles: show/hide, then exact width and height (the pad's pinch scales both together)
        val grow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        for ((n, pair) in listOf("Radar guide" to radar, "Speedo guide" to speed).withIndex()) {
            val b = Button(this).apply { tag = "seg"; text = pair.first; isAllCaps = false; isSelected = true }
            b.setOnClickListener {
                pair.second.shown = !pair.second.shown
                if (!pair.second.shown && pad.sel === pair.second) pad.sel = null
                pad.invalidate(); mark(); gdSync()
            }
            chips += b to pair.second
            grow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(8) })
        }
        host.addView(grow)
        val gdInfo = TextView(this).apply { tag = "m"; setPadding(0, dp(8), 0, 0) }
        val gdWLbl = TextView(this).apply { tag = "m"; setPadding(0, dp(6), 0, 0) }
        val gdHLbl = TextView(this).apply { tag = "m"; setPadding(0, dp(6), 0, 0) }
        val gdW = SeekBar(this).apply { tag = "seek"; max = 1420 }
        val gdH = SeekBar(this).apply { tag = "seek"; max = 460 }
        for (v in listOf<View>(gdInfo, gdWLbl, gdW, gdHLbl, gdH)) host.addView(v, LinearLayout.LayoutParams(-1, -2))
        fun gdSel(): HudPadView.Item? = pad.sel?.takeIf { it.kind == 4 }
        // contextual copy: whichever camera or arrow is selected on the pad gets its size copied to the other side
        val copyBtn = Button(this).apply { tag = "btn2"; isAllCaps = false; visibility = View.GONE }
        host.addView(copyBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        copyBtn.setOnClickListener {
            val src = pad.sel?.takeIf { it.kind == 0 || it.kind == 2 } ?: return@setOnClickListener
            val dst = when (src.id) { "camL" -> camR; "camR" -> camL; "arrL" -> arrR; else -> arrL }
            dst.size = src.size; dst.aspect = src.aspect
            val k = sfx(dst.left)
            if (src.kind == 0) p.edit().putInt("bsSize$k", dst.size).putInt("bsAspect$k", dst.aspect).apply()
            else p.edit().putInt("bsArrSize$k", dst.size).putInt("bsArrAsp$k", dst.aspect).apply()
            pad.invalidate(); mark(); preview(dst)
        }
        gdSync = {
            val g = gdSel()
            val cs = pad.sel?.takeIf { it.kind == 0 || it.kind == 2 }
            copyBtn.visibility = if (cs != null) View.VISIBLE else View.GONE
            if (cs != null) copyBtn.text = "Copy ${if (cs.left) "left" else "right"} ${if (cs.kind == 0) "camera" else "arrow"} size to ${if (cs.left) "right" else "left"}"
            val vis = if (g != null) View.VISIBLE else View.GONE
            for (v in listOf<View>(gdWLbl, gdW, gdHLbl, gdH)) v.visibility = vis
            if (g == null) {
                val q = pad.sel
                val cur = if (q != null && q.kind == 2) "Selected ${if (q.left) "left" else "right"} arrow: centre x=${q.x}% (${q.x * 1440 / 100} px) y=${q.y}% (${q.y * 480 / 100} px), height ${q.size}% (${q.size * 480 / 100} px), width ${q.aspect}% of height (${q.size * 480 / 100 * q.aspect / 100} px)\n" else ""
                gdInfo.text = cur + "The RADAR and SPEEDO guides mark where the car draws its own radar and speedometer. They are fixed; the chips only show or hide them."
            }
            else {
                gdInfo.text = "${g.label}: centre x=${g.x * 1440 / 100} y=${g.y * 480 / 100} px (${g.x}% / ${g.y}%), width ${g.gw} px, height ${g.gh} px"
                gdWLbl.text = "Width: ${g.gw} px"; gdHLbl.text = "Height: ${g.gh} px"
                loading = true; gdW.progress = g.gw - 20; gdH.progress = g.gh - 20; loading = false
            }
        }
        for ((sb, wide) in listOf(gdW to true, gdH to false)) sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                val g = gdSel() ?: return
                if (!fromUser || loading) return
                if (wide) g.gw = v + 20 else g.gh = v + 20
                p.edit().putInt("gdW_${g.id}", g.gw).putInt("gdH_${g.id}", g.gh).apply()
                pad.invalidate(); gdSync()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        gdSync()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in BlindSpotHud.ARROW_ICONS.indices) {
            val chip = ArrowIconChip(this, i)
            chip.setOnClickListener { val a = arrow(); a.icon = i; p.edit().putInt("bsArrIcon" + sfx(a.left), i).apply(); pad.invalidate(); mark(); preview(a) }
            icons += chip
            row.addView(chip, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        style.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val corow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pick = Button(this).apply { tag = "btn2"; isAllCaps = false; text = "Choose colour" }
        pick.setOnClickListener {
            ColorPicker.show(this, ZTheme.palette(mode), "Turn arrow colour", arrow().color, onLive = {}, onDone = { c ->
                if (c != 0) { val a = arrow(); a.color = c; p.edit().putInt("bsArrColor" + sfx(a.left), c).apply(); restyleIcons(); pad.invalidate(); mark(); preview(a) }
            })
        }
        corow.addView(dot, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        corow.addView(hexT, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        corow.addView(pick)
        style.addView(corow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val cprow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((n, fromL) in listOf(true, false).withIndex()) {
            val b = Button(this).apply { tag = "btn2"; isAllCaps = false; text = if (fromL) "Copy L size to R" else "Copy R size to L" }
            b.setOnClickListener {
                val src = if (fromL) arrL else arrR; val dst = if (fromL) arrR else arrL
                dst.size = src.size; dst.aspect = src.aspect
                p.edit().putInt("bsArrSize" + sfx(dst.left), dst.size).putInt("bsArrAsp" + sfx(dst.left), dst.aspect).apply()
                pad.invalidate(); mark(); gdSync(); preview(dst)
            }
            cprow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n > 0) marginStart = dp(8) })
        }
        style.addView(cprow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        for ((id, l) in listOf(R.id.arrSideL to true, R.id.arrSideR to false)) findViewById<Button>(id).setOnClickListener {
            arrSide = l; pad.sel = arrow(); restyleIcons(); pad.invalidate(); mark(); preview(arrow())
        }
        findViewById<Button>(R.id.arrReset).setOnClickListener {
            ZDialog.show(this, "Reset HUD layout?", "Cameras, web page and arrows (not the rev bar, which is per drive mode) go back to their default places, sizes and arrow looks.", "Reset") {
                val e = p.edit()
                for (k in listOf("_L", "_R")) for (b in BlindSpotHud.ARROW_KEYS + listOf("bsPosX", "bsPosY", "bsSize")) e.remove(b + k)
                for (b in listOf("webPosX", "webPosY", "webSize", "slX", "slY", "slSize")) e.remove(b)
                e.apply(); load()
            }
        }
        pfRefresh += ::mark
        load()
    }

    // Tells the car-side HUD to show a preview picture for the next few seconds (see BlindSpotHud).
    private fun bsPreview() {
        val bl = if (CamTune.isX(bsSide)) bsSide.substring(1, 2) else bsSide
        if (bl != "L" && bl != "R") return   // the HUD only has the two side cameras
        p.edit().putLong("bsPreviewUntil", System.currentTimeMillis() + 6000)
            .putInt("bsPreviewSide", if (bl == "L") 2 else 3).apply()
    }

    private fun pickSentryFolder() {
        val folder = findSentryModeFolder()
        if (folder != null) {
            p.edit().putString("sentryFolderPath", folder.absolutePath).apply()
            Logger.log(this, "Sentry folder selected: ${folder.absolutePath}")
            updateSentryFolderStatus()
            Toast.makeText(this, "Sentry folder saved: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }
        val roots = usbMountRoots()
        if (roots.isEmpty()) {
            Toast.makeText(this, "No USB drive mounted (check /proc/mounts).", Toast.LENGTH_LONG).show()
            return
        }
        val manager = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        val msg = if (manager) "USB drive(s) found (${roots.joinToString { it.name }}) but no \"SentryMode\" folder in them."
            else "USB drive(s) found (${roots.joinToString { it.name }}), but this app can't read them yet. Open this app's own Settings page (the one with \"Permission management\") and grant file/storage access there."
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        Logger.log(this, "Sentry folder pick failed: $msg")
    }

    // On this head unit a plugged-in USB drive only ever shows up in /proc/mounts as
    // /storage/XXXX-XXXX -- confirmed by github.com/dts88/zeekr-shortcut-car (a dashcam app for
    // the same car) hitting the identical gap: StorageManager/getExternalFilesDirs() never see
    // it, but direct file I/O against the /proc/mounts path works fine once "All files access"
    // is granted.
    private fun findSentryModeFolder(): java.io.File? {
        for (root in usbMountRoots()) {
            val hit = root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("SentryMode", ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }

    private fun usbMountRoots(): List<java.io.File> {
        val roots = mutableListOf<java.io.File>()
        try {
            java.io.File("/proc/mounts").forEachLine { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@forEachLine
                val mountPoint = parts[1]
                if (!mountPoint.matches(Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) return@forEachLine
                val dir = java.io.File(mountPoint)
                if (dir.isDirectory && dir.canRead()) roots.add(dir)
            }
        } catch (_: Throwable) {}
        return roots
    }

    private fun updateSentryFolderStatus() {
        val path = p.getString("sentryFolderPath", null)
        findViewById<TextView>(R.id.sentryFolderStatus).text =
            if (path.isNullOrEmpty()) "Sentry folder: not selected" else "Sentry folder: selected"
    }

    override fun onResume() {
        WebHud.appVisible = true
        super.onResume()
        resumed = true; updateLiveHold()
        startMirror()
        startQueueLoop()
        if (standalone() || !p.getString("token", "").isNullOrEmpty()) buildCamGrid()
        // Picks up where pickSentryFolder() left off after the user grants "All files access"
        // in Settings and returns here, without a second button tap.
        if (p.getString("sentryFolderPath", null).isNullOrEmpty() &&
            Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
            findSentryModeFolder()?.let {
                p.edit().putString("sentryFolderPath", it.absolutePath).apply()
                Logger.log(this, "Sentry folder selected: ${it.absolutePath}")
                updateSentryFolderStatus()
            }
        }
    }

    // In-app updates, same mechanism as the phone app: the zdash-updates Worker proxies the latest APK from
    // GitHub (see update-relay's /v1/cam/update*) so the user never has to plug the head unit into
    // anything or manually sideload a file in the car. Checks once at launch; the Check for
    // update button at the top runs it again and reports the result.
    private var updPulse: android.animation.ObjectAnimator? = null
    /** Lit state of the side button: a new version is waiting (lime, pulsing) or not (plain). */
    private fun markUpdate(ver: String?) {
        val btn = findViewById<Button>(R.id.checkUpdate)
        val on = !ver.isNullOrEmpty()
        btn.tag = if (on) "btn" else "btn2"
        btn.text = if (on) "Update available" else "Check for update"
        ZTheme.style(btn, ZTheme.palette(mode))
        updPulse?.cancel(); btn.alpha = 1f
        if (on) updPulse = android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.55f).apply { duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start() }
    }

    private fun checkForUpdate(manual: Boolean = false) {
        val btn = findViewById<Button>(R.id.checkUpdate)
        if (manual) { btn.isEnabled = false; btn.text = "Checking..." }
        fun done(msg: String) { if (manual) ui.post { btn.isEnabled = true; markUpdate(p.getString("updAvail", "")); Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
        Thread {
            val r = Net.get("${UpdateInstaller.BASE}/v1/cam/update", null, UpdateInstaller.HEADERS)
            if (r == null || !r.optBoolean("ok", false)) { done("Could not reach the update server. Check the car's internet."); return@Thread }
            val serverCode = r.optInt("versionCode", 0)
            val myCode = try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
            } catch (_: Throwable) { Int.MAX_VALUE }
            if (serverCode <= myCode) { p.edit().putString("updAvail", "").putLong("updCheckedMs", System.currentTimeMillis()).apply(); ui.post { markUpdate("") }; done("Up to date"); return@Thread }
            val ver = r.optString("version", "")
            p.edit().putString("updAvail", ver.ifEmpty { "new" }).putLong("updCheckedMs", System.currentTimeMillis()).apply()
            done("Update available: v$ver")
            ui.post {
                btn.isEnabled = true; markUpdate(ver.ifEmpty { "new" })   // the side button itself tells there is a new version
                findViewById<TextView>(R.id.updateMsg).text = "Update available: v$ver"
                findViewById<View>(R.id.updateBox).visibility = View.VISIBLE
                findViewById<Button>(R.id.updateNow).setOnClickListener {
                    UpdateInstaller.downloadAndInstall(this, "${UpdateInstaller.BASE}/v1/cam/update/apk") { msg -> ui.post { status.text = msg } }
                }
            }
        }.start()
    }


    private fun standalone() = p.getBoolean("standalone", false)

    // ---- relay hand-off from the phone (no typing) ----
    // The car shows a QR holding a random one-time topic on ntfy.sh and listens on it; the ZDash phone app scans the
    // QR and posts the user's relay URL to that topic. The URL is only used after the user confirms it on this screen
    // (a stranger who photographed the QR could otherwise point the car at their own relay). The QR expires after
    // 10 minutes and a rejected or used topic is dropped. ntfy.sh sees the topic, the URL and both IPs (see the note
    // on the setup page); linking itself still needs the signed-in phone to claim the pairing code on the relay.
    @Volatile private var setupGen = 0
    private var setupTopic: String? = null
    private val setupExpire = Runnable { stopSetupListen(); showSetupQr(false) }
    private fun showSetupQr(on: Boolean) {
        findViewById<View>(R.id.setupQr).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.setupQrNote).visibility = if (p.getBoolean("setupQrOn", true)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.setupQrNew).visibility = if (!on && p.getBoolean("setupQrOn", true)) View.VISIBLE else View.GONE
    }
    private fun startSetupListen() {
        if (setupTopic != null) return
        if (!p.getBoolean("setupQrOn", true)) { showSetupQr(false); findViewById<View>(R.id.setupQrNew).visibility = View.GONE; return }
        val topic = "zcam-" + ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        setupTopic = topic
        val gen = ++setupGen
        try {
            val bm = com.google.zxing.qrcode.QRCodeWriter().encode("zcam://setup?t=$topic", com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val px = IntArray(512 * 512) { if (bm.get(it % 512, it / 512)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            findViewById<ImageView>(R.id.setupQr).setImageBitmap(Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888))
        } catch (_: Throwable) {}
        showSetupQr(true)
        ui.removeCallbacks(setupExpire); ui.postDelayed(setupExpire, 10 * 60_000L)
        Thread {
            val http = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            while (gen == setupGen) {
                try {
                    val req = okhttp3.Request.Builder().url("https://ntfy.sh/$topic/json?since=all").build()
                    http.newCall(req).execute().use { resp ->
                        val src = resp.body?.source() ?: return@use
                        while (gen == setupGen) {
                            val line = src.readUtf8Line() ?: break
                            val j = try { JSONObject(line) } catch (_: Throwable) { continue }
                            if (j.optString("event") != "message") continue
                            val url = j.optString("message").trim().trimEnd('/')
                            if (!url.startsWith("https://") || url.length > 200 || url.any { it.isWhitespace() }) continue
                            setupGen++   // this topic is spent whatever the user decides
                            ui.post { confirmRelay(url) }
                            return@Thread
                        }
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }.start()
    }
    private fun confirmRelay(url: String) {
        ui.removeCallbacks(setupExpire); setupTopic = null
        ZDialog.show(this, "Use this relay?",
            "Your phone sent:\n\n$url\n\nOnly accept it if it is your own relay. Anyone else's relay would receive this car's camera pictures.",
            "Use it", "Reject", cancelable = false, onNegative = { showState(); startSetupListen() }) { p.edit().putString("url", url).apply(); showState() }
    }
    private fun stopSetupListen() { setupGen++; setupTopic = null; ui.removeCallbacks(setupExpire) }

    private fun showState() {
        if (standalone()) {
            stopSetupListen()
            findViewById<View>(R.id.relayBox).visibility = View.GONE
            findViewById<View>(R.id.identifyBox).visibility = View.GONE
            findViewById<View>(R.id.codeBox).visibility = View.GONE
            findViewById<View>(R.id.opBox).visibility = View.VISIBLE
            fixColumns(true)
            findViewById<View>(R.id.backupCard).apply { visibility = View.VISIBLE; isEnabled = false; alpha = 0.4f }   // needs a ZDash link
            findViewById<View>(R.id.dataUsage).visibility = View.GONE
            findViewById<View>(R.id.test).visibility = View.GONE
            findViewById<Button>(R.id.unlink).text = "Link to Z app"
            polling = false
            buildCamGrid(); autoStartService()
            return
        }
        findViewById<View>(R.id.backupCard).apply { visibility = View.VISIBLE; isEnabled = true; alpha = 1f }
        findViewById<View>(R.id.dataUsage).visibility = View.VISIBLE
        findViewById<View>(R.id.test).visibility = View.VISIBLE
        findViewById<Button>(R.id.unlink).text = "Unlink ZDash"
        val hasRelay = !p.getString("url", "").isNullOrEmpty()
        findViewById<View>(R.id.relayBox).visibility = if (hasRelay) View.GONE else View.VISIBLE
        if (hasRelay) stopSetupListen() else startSetupListen()
        if (!hasRelay) {
            findViewById<View>(R.id.opBox).visibility = View.GONE
            findViewById<View>(R.id.identifyBox).visibility = View.GONE
            findViewById<View>(R.id.codeBox).visibility = View.GONE
            return
        }
        val linked = !p.getString("token", "").isNullOrEmpty()
        findViewById<View>(R.id.opBox).visibility = if (linked) View.VISIBLE else View.GONE
        fixColumns(linked)
        findViewById<View>(R.id.identifyBox).visibility = View.GONE
        findViewById<View>(R.id.codeBox).visibility = View.GONE
        polling = false
        // First setup goes straight to the pairing QR; the cameras to capture are picked later in the Sentry section.
        if (linked) { buildCamGrid(); autoStartService() } else showCodeStage()
    }

    private var pinPage = false
    private var pageHooked = false
    // A ScrollView ignores its child's height, so the page only stayed screen-high while the left column was shorter than the screen; a taller left column made the whole page scroll and steal the right column's drags. Give the columns an exact height instead.
    private fun pinPageHeight() {
        if (rightScroll == null) return
        val sc = findViewById<ScrollView>(R.id.scroll); val content = sc.getChildAt(0) as? LinearLayout ?: return
        val op = findViewById<View>(R.id.opBox)
        val want = if (!pinPage) ViewGroup.LayoutParams.WRAP_CONTENT else {
            var others = 0
            for (i in 0 until content.childCount) { val c = content.getChildAt(i); if (c === op || c.visibility == View.GONE) continue; val m = c.layoutParams as? ViewGroup.MarginLayoutParams; others += c.height + (m?.topMargin ?: 0) + (m?.bottomMargin ?: 0) }
            val avail = sc.height - sc.paddingTop - sc.paddingBottom - content.paddingTop - content.paddingBottom - others
            if (avail < (200 * ZScale.density(this)).toInt()) return
            avail
        }
        if (op.layoutParams.height != want) { op.layoutParams = op.layoutParams.apply { height = want }; op.requestLayout() }
    }

    // Wide screen, linked: the page itself fills the screen without scrolling; both columns scroll inside.
    private fun fixColumns(linked: Boolean) {
        if (rightScroll == null) return
        val sc = findViewById<ScrollView>(R.id.scroll)
        pinPage = linked
        if (!pageHooked) { pageHooked = true; sc.viewTreeObserver.addOnGlobalLayoutListener { pinPageHeight() } }
        findViewById<View>(R.id.opBox).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sc.post { pinPageHeight() }
    }

    // Make background capture "just work": whenever the car is linked and has every permission
    // it needs, make sure the listener service is running without the user tapping Start.
    // ensurePerms() (not just a Camera check) matters for installs that paired before a newer
    // version added a permission (e.g. USB video storage) -- otherwise an upgrade silently
    // never asks for it, since this is the only path already-linked users go through on open.
    private fun autoStartService() {
        if (!ensurePerms()) return
        if (p.getBoolean("suspended", false)) { svc("Paused by battery floor. Tap Start to resume."); return }
        requestNoBatteryOpt()   // no-op once granted
        try {
            val i = Intent(this, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Throwable) {}
    }

    // ---- Stage 1: identify cameras with previews ----
    private fun showIdentifyStage() {
        polling = false
        findViewById<View>(R.id.identifyBox).visibility = View.VISIBLE
        findViewById<View>(R.id.codeBox).visibility = View.GONE
        buildPreviewList()
    }

    private fun buildPreviewList() {
        val list = findViewById<LinearLayout>(R.id.previewList)
        val count = findViewById<TextView>(R.id.camCount)
        if (!ensurePerms()) { count.text = "Grant Camera permission to see previews."; return }
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (_: Throwable) { emptyList() }
        count.text = if (ids.isEmpty()) "No cameras visible (run on the car head unit)." else "Cameras found: ${ids.size}"
        list.removeAllViews()
        if (ids.isEmpty()) return
        val sel = selectedCams()
        val previews = ArrayList<Triple<String, ImageView, TextView>>()
        for (id in ids) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 20)
            }
            val cb = SwitchCompat(this).apply {
                tag = "sw"; text = camLabel(cm, id); isChecked = sel.contains(id)
                setOnCheckedChangeListener { _, checked ->
                    val s = selectedCams(); if (checked) s.add(id) else s.remove(id); saveCams(s)
                }
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
                scaleType = ImageView.ScaleType.FIT_CENTER
                tag = "chip"
            }
            val hint = TextView(this).apply { tag = "m"; text = "Loading preview..."; setPadding(0, 4, 0, 0) }
            row.addView(cb); row.addView(img); row.addView(hint)
            list.addView(row)
            ZTheme.style(row, ZTheme.palette(mode))
            previews.add(Triple(id, img, hint))
        }
        // one-shot previews (we are a snapshot app; a still frame is the "live view"), one camera at a
        // time on a single thread: parallel opens just fight over the camera HAL.
        Thread {
            for ((id, img, hint) in previews) {
                val jpeg = CameraCapture.capture(this, id, 480, 50)
                ui.post {
                    if (jpeg != null) {
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) { img.setImageBitmap(bmp); hint.visibility = View.GONE }
                        else hint.text = "Preview unavailable"
                    } else hint.text = "Preview unavailable (camera busy)"
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * ZScale.density(this)).toInt()

    // ---- Stage 2: pairing code ----
    private fun showCodeStage() {
        if (selectedCams().isEmpty()) saveCams(setOf("2"))
        findViewById<View>(R.id.identifyBox).visibility = View.GONE
        findViewById<View>(R.id.codeBox).visibility = View.VISIBLE
        startPairing()
    }

    // ---- camera grid (tap to toggle which cameras are captured) ----
    private fun selectedCams(): MutableSet<String> {
        val raw = p.getString("cams", "2") ?: "2"
        return raw.split(",").filter { it.isNotBlank() }.toMutableSet()
    }
    private fun saveCams(set: Set<String>) { p.edit().putString("cams", set.joinToString(",")).apply() }

    private fun camLabel(cm: CameraManager, id: String): String {
        val facing = try {
            when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "?"
            }
        } catch (_: Throwable) { "?" }
        val hint = when (id) { "0" -> " · front cabin"; "1" -> " · rear cabin"; "2" -> " · surround"; else -> "" }
        return "Camera $id ($facing)$hint"
    }

    private fun buildCamGrid() {
        val grid = findViewById<LinearLayout>(R.id.camGrid)
        grid.removeAllViews()
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (_: Throwable) { emptyList() }
        if (ids.isEmpty()) {
            grid.addView(TextView(this).apply { tag = "m"; ZTheme.style(this, ZTheme.palette(mode)); text = "No cameras visible (grant Camera permission, or run on the car head unit)." })
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) ensurePerms()
            return
        }
        val sel = selectedCams()
        for (id in ids) {
            val cb = SwitchCompat(this).apply {
                tag = "sw"
                text = camLabel(cm, id)
                isChecked = sel.contains(id)
                setOnCheckedChangeListener { _, checked ->
                    val s = selectedCams(); if (checked) s.add(id) else s.remove(id); saveCams(s)
                }
            }
            grid.addView(cb)
            ZTheme.style(cb, ZTheme.palette(mode))
        }
    }

    private fun startPairing() {
        val deviceId = p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
        status.text = "Getting code…"
        Thread {
            val r = Net.postJson("${p.getString("url", "")}/cam/pair/start", null, JSONObject().put("deviceId", deviceId))
            val code = r?.optString("code", "") ?: ""
            ui.post {
                if (code.isEmpty()) { status.text = "Couldn't get a code (network?)"; return@post }
                findViewById<TextView>(R.id.code).text = code
                status.text = "Waiting for your phone to link…"
                loadQr(code)
            }
            pollClaim(deviceId)
        }.start()
    }

    private fun loadQr(code: String) {
        Thread {
            try {
                val c = URL("${p.getString("url", "")}/camqr?code=$code").openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                val bytes = c.getInputStream().use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ui.post { findViewById<ImageView>(R.id.qr).setImageBitmap(bmp) }
            } catch (_: Throwable) { ui.post { status.text = "QR didn't load - type the code on your phone instead" } }
        }.start()
    }

    private fun pollClaim(deviceId: String) {
        if (polling) return
        polling = true
        Thread {
            val until = System.currentTimeMillis() + 10 * 60 * 1000
            while (polling && System.currentTimeMillis() < until) {
                val r = Net.get("${p.getString("url", "")}/cam/pair/poll?deviceId=$deviceId", null)
                if (r?.optBoolean("claimed", false) == true) {
                    val tok = r.optString("token", "")
                    if (tok.isNotEmpty()) {
                        p.edit().putString("token", tok).apply()
                        ui.post { status.text = "Linked"; showState() }
                        return@Thread
                    }
                }
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }.start()
    }

    // On-device log viewer: everything the service/watcher has done (and any error it hit),
    // visible on the car's own screen without needing the dashboard or a relay connection.
    // What's new: assets/changelog.txt (newest first). Add the new version's notes there on every release.
    private fun showChangelogDialog() {
        val text = try { assets.open("changelog.txt").bufferedReader().use { it.readText() } } catch (_: Throwable) { "No changelog." }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }
        val tv = TextView(this).apply { tag = "m"; textSize = 13f; setLineSpacing(0f, 1.15f); this.text = "Installed: v$ver\n\n$text" }
        val cap = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val sv = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)) }
        }.apply { addView(tv) }
        ZDialog.custom(this, sv, "Close")
        applyTheme()
    }

    private fun showCreditsDialog() {
        val pal = ZTheme.palette(mode)
        val tv = TextView(this).apply {
            tag = "m"; textSize = 13f; setLineSpacing(0f, 1.15f)
            text = "Z Cam is its own app, with its own direction and features such as the web view and driving profiles.\n\n" +
                "Zeekr Shortcut by dts88 (github.com/dts88/zeekr-shortcut-car), GPL-3.0\n" +
                "The engine sound code is ported from this project. Platform facts for the camera layout, ECARX indicator and HUD also came from it.\n\n" +
                "CarPiano by jlunnn (github.com/jlunnn/CarPiano), MIT\n" +
                "On-car facts about playing sound to the 7X speakers. Facts only.\n\n" +
                "FerixzTanz (github.com/FerixzTanz), CAMCORDER\n" +
                "Original research on showing the side camera on the HUD.\n\n" +
                "EngineV1 (evenginev1.com)\n" +
                "Reference for the engine sound character. Facts only.\n\n" +
                "ZXing (github.com/zxing/zxing), Apache-2.0\n" +
                "Draws the setup QR.\n\n" +
                "Not affiliated with ZEEKR."
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val title = TextView(this).apply { tag = "h"; setText("Credits") }
        val close = Button(this).apply { tag = "btn"; setText("Close") }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "card"
            addView(title); addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(14) })
            addView(close)
        }
        ZTheme.style(card, pal)
        val dlg = AlertDialog.Builder(this).setView(card).create()
        close.setOnClickListener { dlg.dismiss() }
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.6f).toInt(), (resources.displayMetrics.heightPixels * 0.8f).toInt())
    }

    private fun showLogDialog() {
        // Newest first, so the latest entry is right there instead of needing a scroll to the bottom.
        val text = Logger.readAll(this).trim().split("\n").asReversed().joinToString("\n").ifEmpty { "(empty)" }
        val pal = ZTheme.palette(mode)
        val tv = TextView(this).apply {
            setText(text); tag = "t"; setPadding(0, 0, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val title = TextView(this).apply { tag = "h"; setText("Car cam log") }
        val clear = Button(this).apply { tag = "bad"; setText("Clear") }
        val close = Button(this).apply { tag = "btn"; setText("Close") }
        val smaller = Button(this).apply { tag = "btn"; setText("A-") }
        val bigger = Button(this).apply { tag = "btn"; setText("A+") }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smaller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
            addView(bigger, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f).apply { marginStart = dp(8) })
            addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
            addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "card"
            addView(title); addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(14) })
            addView(buttons)
        }
        ZTheme.style(card, pal)
        tv.textSize = getSharedPreferences("cfg", MODE_PRIVATE).getFloat("logTextSize", 22f)   // big enough to read from the driver's seat / in a photo
        smaller.setOnClickListener { tv.textSize = (tv.textSize / resources.displayMetrics.scaledDensity - 3f).coerceAtLeast(12f); getSharedPreferences("cfg", MODE_PRIVATE).edit().putFloat("logTextSize", tv.textSize / resources.displayMetrics.scaledDensity).apply() }
        bigger.setOnClickListener { tv.textSize = (tv.textSize / resources.displayMetrics.scaledDensity + 3f).coerceAtMost(48f); getSharedPreferences("cfg", MODE_PRIVATE).edit().putFloat("logTextSize", tv.textSize / resources.displayMetrics.scaledDensity).apply() }
        val dlg = AlertDialog.Builder(this).setView(card).create()
        close.setOnClickListener { dlg.dismiss() }
        clear.setOnClickListener { Logger.clear(this); dlg.dismiss() }
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dlg.show()
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), (resources.displayMetrics.heightPixels * 0.9f).toInt())
    }

    private fun testCapture() {
        if (!ensurePerms()) return
        val cams = selectedCams().toList()
        if (cams.isEmpty()) { svc("Tick at least one camera first."); return }
        svc("Capturing ${cams.size} camera(s)…")
        val token = p.getString("token", "")
        Thread {
            var ok = 0
            for (cam in cams) {
                val jpeg = CameraCapture.capture(this, cam, 2000, 92) ?: continue
                val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
                val body = JSONObject().put("jpeg", b64).put("trig", "test").put("cam", cam).put("ts", System.currentTimeMillis()).put("fmt", CameraCapture.encFormat())
                val loc = Loc.current(this)
                if (loc != null) { body.put("lat", loc.first).put("lng", loc.second) }
                val r = Net.postJson("${p.getString("url", "")}/cam/snap", token, body)
                if (r?.optBoolean("ok", false) == true) ok++
            }
            ui.post { svc("Uploaded $ok/${cams.size} snapshot(s) — check the dashboard Cam tab.") }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (standalone() || !p.getString("token", "").isNullOrEmpty()) { buildCamGrid(); autoStartService() } else buildPreviewList()
    }

    private fun requestNoBatteryOpt() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            }
        } catch (_: Throwable) {
            // Some automotive builds hide this screen; the foreground service + wake lock still apply.
        }
    }

    private fun ensurePerms(): Boolean {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)   // geo-tag snapshots
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)   // live announcement to the outside speaker
        // USB Sentry video detection (UsbVideoWatcher) -- MediaStore video query.
        val videoPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, videoPerm) != PackageManager.PERMISSION_GRANTED) need.add(videoPerm)
        if (need.isNotEmpty()) { ActivityCompat.requestPermissions(this, need.toTypedArray(), 1); return false }
        return true
    }
}

private data class Quint(val a: Int, val b: Int, val c: String, val d: Int, val e: String)
