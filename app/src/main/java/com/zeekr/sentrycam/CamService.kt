package com.zeekr.sentrycam

import android.app.*
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Foreground service: polls the relay for commands and captures the surround camera
 * on request (snapshots, live view); the car's own Sentry does the motion detection (or on a
 * manual Capture request). Idle = no upload.
 */
class CamService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // Live stream: a single WebSocket publisher + a continuous-capture thread.
    @Volatile private var paused = false   // battery-floor pause (alive but not capturing)
    @Volatile private var streaming = false
    @Volatile private var streamWs: WebSocket? = null
    private var streamThread: Thread? = null
    // Live-view data guards: the relay tells us how many dashboards are watching (nobody = no capture, no frames); a stream with
    // nobody watching for 60 s, or past 6 min / 120 MB, is ended and not restarted until the dashboard opens a new window.
    @Volatile private var viewers = 1
    @Volatile private var noViewersSince = 0L
    @Volatile private var suppressedUntil = 0L
    @Volatile private var streamStartMs = 0L
    @Volatile private var streamBytes = 0L
    @Volatile private var lastStreamUntil = 0L
    private val http = OkHttpClient()
    // Live-view publisher: outbound-only socket, so no read timeout (the 10 s default killed it as "socket fail timeout"); pings keep it alive.
    private val streamClient = OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).build()
    // Always-on control socket: the relay pushes "start"/"stop" instantly (no poll wait).
    @Volatile private var controlWs: WebSocket? = null
    private val controlClient = OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
    // Self-healing: last time /cam/cmd succeeded, so a long silent gap can be surfaced and
    // retried faster instead of sitting quietly on the normal 15-20s poll forever.
    @Volatile private var lastOkMs = System.currentTimeMillis()
    private var connMgr: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var usbVideoWatcher: UsbVideoWatcher? = null
    private var blindSpot: BlindSpotHud? = null
    private var webHud: WebHud? = null
    private var revBar: RevBar? = null
    private var floatCam: FloatCam? = null
    private var tripWidget: TripWidget? = null
    private val mainH = android.os.Handler(android.os.Looper.getMainLooper())
    // Wakes the poll loop's sleep early (network back, shutdown) WITHOUT Thread.interrupt(): an interrupt landing
    // mid-capture aborted the camera open and used to leave the camera held open.
    private val wakeSignal = Semaphore(0)
    private fun nap(ms: Long) { try { wakeSignal.tryAcquire(ms, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {} }
    private fun wakeLoop() { if (wakeSignal.availablePermits() == 0) wakeSignal.release() }
    @Volatile private var lastActiveMs = System.currentTimeMillis()
    private var wasSafe = false
    @Volatile private var queueLeft = 0

    override fun onBind(i: Intent?): IBinder? = null

    // Looks for a new app version about once a day while the service runs, so the app's update button is already lit when it is opened.
    private val dailyUpdate = object : Runnable {
        override fun run() {
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            if (System.currentTimeMillis() - p.getLong("updCheckedMs", 0L) > 24L * 3600_000L) Thread { try { UpdateInstaller.refresh(this@CamService) } catch (_: Throwable) {} }.start()
            mainH.postDelayed(this, 3L * 3600_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mainH.removeCallbacks(dailyUpdate); mainH.postDelayed(dailyUpdate, 60_000L)
        enterForeground()
        acquireWake()
        if (!running) {
            running = true
            // A freshly installed build starts clean: no leftover safe mode from the previous build's restarts.
            run {
                val sp = getSharedPreferences("cfg", Context.MODE_PRIVATE)
                val stamp = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
                if (sp.getLong("installStamp", 0L) != stamp) sp.edit().putLong("installStamp", stamp).remove("safeUntilMs").remove("crashTs").apply()
            }
            reportExitReason()   // before checkForUncleanRestart -- gets included in its log upload
            checkForUncleanRestart()
            try { java.io.File(cacheDir, "updates").deleteRecursively() } catch (_: Throwable) {}   // leftover update APKs
            Logger.log(this, "service start")
            DataMeter.start(this)
            worker = Thread { loop() }.apply { start() }
        }
        registerNetworkWatch()
        if (usbVideoWatcher == null) { usbVideoWatcher = UsbVideoWatcher(this).also { it.start() } }
        Park.poke()
        ServiceWatchdog.schedule(this)
        syncCameraFeatures()
        return START_STICKY
    }

    // ---- crash-loop protection ----
    // If the process keeps dying (typically a camera HAL fault that our own camera use re-triggers on every
    // restart), stop touching the camera for a while instead of restarting straight into the same crash forever.
    private fun safeMode(): Boolean = getSharedPreferences("cfg", Context.MODE_PRIVATE).getLong("safeUntilMs", 0L) > System.currentTimeMillis()

    private fun syncCameraFeatures() {
        if (safeMode()) {
            DriveProfile.sync(this, false)
            blindSpot?.stop(); blindSpot = null
            webHud?.stop(); webHud = null
            floatCam?.stop(); floatCam = null
            tripWidget?.stop(); tripWidget = null
            syncPark(); CampGuard.sync(this, true); WelcomeWatch.sync(this, true)
            return
        }
        CampGuard.sync(this, false); WelcomeWatch.sync(this, false)
        DriveProfile.sync(this, getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("pfOn", false)); syncBlindSpot(); syncRevBar(); syncWebHud(); syncFloatCam(); syncTripWidget(); syncPark()
        try { com.zeekr.sentrycam.sound.DriveSound.refresh(this) } catch (e: Throwable) { Logger.log(this, "enginesound: ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    // GPS (for the parked test) only runs while something that uses it is switched on.
    private fun syncPark() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val wanted = p.getBoolean("flLeft", false) || p.getBoolean("flRight", false) || p.getBoolean("flRear", false) || p.getBoolean("flFront", false)
        if (wanted && !safeMode()) Park.start(this) else Park.stop()
    }

    private fun noteCrash(p: android.content.SharedPreferences, ts: Long) {
        val now = System.currentTimeMillis()
        val recent = ((p.getString("crashTs", "") ?: "").split(",").mapNotNull { it.toLongOrNull() } + ts).filter { now - it < 15 * 60_000L }
        p.edit().putString("crashTs", recent.joinToString(",")).apply()
        if (recent.size >= 3) {
            p.edit().putLong("safeUntilMs", now + 30 * 60_000L).putString("crashTs", "").apply()
            Logger.log(this, "SAFE MODE: ${recent.size} crashes in 15 min -- camera features off for 30 min")
        }
    }

    // If the last run's log doesn't end with the clean-shutdown marker, the process died
    // without onDestroy running (crash, ANR kill, or someone pulling power) -- auto-upload
    // its trail now so the crash reason is on the relay without anyone going to the car.
    private fun checkForUncleanRestart() {
        try {
            val tail = Logger.read(this).trimEnd().takeLast(32)
            if (tail.isNotEmpty() && !tail.endsWith("service clean shutdown")) {
                val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
                val base = p.getString("url", "")!!.trimEnd('/')
                val token = p.getString("token", "")
                if (base.isNotEmpty() && !token.isNullOrEmpty()) {
                    Logger.log(this, "unclean previous shutdown detected, auto-uploading log")
                    uploadFullLog(base, token)
                }
            }
        } catch (_: Throwable) {}
    }

    // A native/HAL crash (e.g. the camera pipeline fault we've been chasing) kills the process
    // with no Kotlin exception and no log line -- "unclean previous shutdown" above can't tell
    // a real native crash apart from a low-memory kill or the system just stopping a background
    // service. Android keeps the real reason per-app (API 30+); report it with its exact code,
    // and for a crash/ANR, grab the trace Android captured so we're not guessing anymore.
    private fun reportExitReason() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            if (reasons.isEmpty()) return
            val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val lastTs = p.getLong("lastExitInfoTs", 0L)
            val newest = reasons.filter { it.timestamp > lastTs }.maxByOrNull { it.timestamp } ?: return
            p.edit().putLong("lastExitInfoTs", reasons.maxOf { it.timestamp }).apply()
            // Our own APK being replaced (every test install) also ends the old process with a signal: that is not a crash.
            val updated = try { packageManager.getPackageInfo(packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
            for (r in reasons.filter { it.timestamp > lastTs && !(it.reason == ApplicationExitInfo.REASON_SIGNALED && Math.abs(it.timestamp - updated) < 180_000L) }) {
                if (r.reason == ApplicationExitInfo.REASON_CRASH || r.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    r.reason == ApplicationExitInfo.REASON_ANR || r.reason == ApplicationExitInfo.REASON_SIGNALED ||
                    r.reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED) noteCrash(p, r.timestamp)
            }
            val msg = "Previous exit: ${exitReasonName(newest.reason)} (status=${newest.status}) -- ${newest.description ?: "no description"}; head unit uptime ${android.os.SystemClock.elapsedRealtime() / 1000}s"
            Logger.log(this, msg)
            val base = p.getString("url", "")!!.trimEnd('/')
            val token = p.getString("token", "")
            if (base.isNotEmpty() && !token.isNullOrEmpty()) {
                try { Net.postJson("$base/cam/log", token, JSONObject().put("msg", msg).put("ts", System.currentTimeMillis()).put("log", true)) } catch (_: Throwable) {}
            }
            if (newest.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || newest.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    newest.traceInputStream?.use { ins ->
                        Logger.log(this, "Exit trace (truncated):\n" + ins.bufferedReader().readText().take(6000))
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun exitReasonName(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (uncaught exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (HAL/native fault)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by LMK)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by signal)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN (code $code)"
    }

    // When connectivity comes back after a drop, cut the poll loop's sleep short so it
    // retries immediately instead of waiting out the rest of its backoff window.
    private fun registerNetworkWatch() {
        if (netCallback != null) return
        try {
            connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { wakeLoop() }
            }
            connMgr?.registerDefaultNetworkCallback(netCallback!!)
        } catch (_: Throwable) {}
    }

    private fun enterForeground() {
        val n = buildNotif("Sentry cam running")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // camera lets us grab frames while backgrounded/locked; specialUse keeps the
                // listener alive and allows the boot restart on Android 14.
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                startForeground(1, n, types)
            } else {
                startForeground(1, n)
            }
        } catch (_: Throwable) {
            // If the camera type is refused from the background, fall back to a plain FGS so
            // the listener still runs; on-demand captures try anyway.
            try { startForeground(1, n) } catch (_: Throwable) {}
        }
    }

    // PARTIAL_WAKE_LOCK only keeps the CPU on -- it doesn't stop the WiFi radio from
    // power-saving when idle, which is what was dropping the control WebSocket (and
    // /cam/cmd polling) once Sentry capture stopped being frequent enough to cover for
    // it. WIFI_MODE_FULL_HIGH_PERF keeps the radio awake independent of Sentry state.
    private fun acquireWake() {
        if (wake?.isHeld != true) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sentrycam:listen").apply {
                    setReferenceCounted(false); acquire()
                }
            } catch (_: Throwable) {}
        }
        if (wifiLock?.isHeld != true) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sentrycam:wifi").apply {
                    setReferenceCounted(false); acquire()
                }
            } catch (_: Throwable) {}
        }
    }
    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Throwable) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running when the user swipes the app away; re-arm a restart just in case.
        try {
            val restart = Intent(applicationContext, CamService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(restart) else startService(restart)
        } catch (_: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    // Blind-spot HUD follows the "bsHud" pref; MainActivity re-sends a start intent on toggle.
    private fun syncBlindSpot() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val on = p.getBoolean("bsHud", false) || p.getBoolean("bsSignal", true) ||
            p.getBoolean("flLeft", false) || p.getBoolean("flRight", false)   // the floating side windows follow the blinker
        if (on && blindSpot == null) blindSpot = BlindSpotHud(this).also { it.start() }
        if (!on) { blindSpot?.stop(); blindSpot = null }
    }

    // Rev bar follows the "revOn" pref; MainActivity re-sends a start intent on toggle.
    private fun syncRevBar() {
        val on = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("revOn", false)
        if (on && revBar == null) revBar = RevBar(this).also { it.start() }
        if (!on) { revBar?.stop(); revBar = null }
    }

    // Web HUD (hidden menu): only runs while unlocked and switched on; MainActivity re-sends a start intent on change.
    private fun syncWebHud() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val on = p.getBoolean("webHudOn", false) || p.getBoolean("webFloatOn", false)
        if (on && webHud == null) webHud = WebHud(this).also { it.start() }
        if (!on) { webHud?.stop(); webHud = null }
    }

    // Trip widget (floating driving statistics) follows the "twOn" pref; settings changes just re-send a start intent.
    private fun syncTripWidget() {
        val on = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("twOn", false)
        if (on && tripWidget == null) tripWidget = TripWidget(this).also { it.start() }
        if (on) tripWidget?.sync() else { tripWidget?.stop(); tripWidget = null }
    }

    // Floating left/rear camera windows follow the "flLeft"/"flRight"/"flRear" prefs.
    private fun syncFloatCam() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val on = p.getBoolean("flLeft", false) || p.getBoolean("flRight", false) || p.getBoolean("flRear", false) || p.getBoolean("flFront", false)
        if (on && floatCam == null) floatCam = FloatCam(this)
        if (on) floatCam?.sync() else { floatCam?.stop(); floatCam = null }
        // quick-hide button: while any floating window can be on
        if ((on || p.getBoolean("twOn", false) || p.getBoolean("webHudOn", false) || p.getBoolean("webFloatOn", false)) && p.getBoolean("flMaster", true)) FloatMaster.start(this) else FloatMaster.stop()
    }

    override fun onDestroy() {
        mainH.removeCallbacks(dailyUpdate); CampGuard.sync(this, true); WelcomeWatch.sync(this, true)
        running = false
        wakeLoop()
        Park.stop()
        webHud?.stop(); webHud = null
        revBar?.stop(); revBar = null
        floatCam?.stop(); floatCam = null
        tripWidget?.stop(); tripWidget = null
        FloatMaster.stop()
        try { netCallback?.let { connMgr?.unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        netCallback = null
        usbVideoWatcher?.stop(); usbVideoWatcher = null
        blindSpot?.stop(); blindSpot = null
        try { controlWs?.close(1000, "bye") } catch (_: Throwable) {}
        controlWs = null
        try { stopStreaming() } catch (_: Throwable) {}
        releaseWake()
        Logger.log(this, "service clean shutdown")
        super.onDestroy()
    }

    private fun loop() {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val base = p.getString("url", "")!!.trimEnd('/')
        val token = p.getString("token", "")
        val maxW = 2000   // >= native width -> no downscale (keep full detail)
        val quality = 92
        var lastServedReq = 0L
        var lastAutoVideoCheck = 0L
        var lastServedLogReq = 0L
        var lastServedVideoReqMs = 0L
        var lastParkSent: Boolean? = null; var lastCampSent: Boolean? = null; var lastParkAt = 0L
        var downNoted = false   // only update the notification once per outage, not every retry

        if (p.getBoolean("standalone", false)) { note("Standalone: blind-spot HUD and floating cameras active"); return }
        if (base.isEmpty() || token.isNullOrEmpty()) { note("Not configured"); return }

        while (running) {
            var sleepMs = 20000L
            try {
                ensureControlWs(base, token)   // keep the instant-trigger socket alive
                // Tell the relay whether the car is really parked (Auto Sentry waits for it): on change, and every 60 s.
                val pk = Park.parkedSignal(this); val pkNow = System.currentTimeMillis()
                ensureCampReader()
                val camNow = campRead
                if (pk != lastParkSent || camNow != lastCampSent || pkNow - lastParkAt > 60_000L) {
                    val body = JSONObject().put("parked", pk); if (camNow != null) body.put("camping", camNow)
                    if (Net.postJson("$base/cam/parked", token, body) != null) { lastParkSent = pk; lastCampSent = camNow; lastParkAt = pkNow }
                }
                val cams = (p.getString("cams", "2") ?: "2").split(",").filter { it.isNotBlank() }
                val cmd = Net.get("$base/cam/cmd", token)
                // Self-healing: a null result means the poll itself failed (timeout/DNS/refused),
                // not just an empty response. If that's been going on for a while, the control
                // socket may be a zombie too (connected but not actually reachable) -- rebuild it
                // and retry sooner than the normal idle cadence instead of silently waiting it out.
                if (cmd != null) {
                    lastOkMs = System.currentTimeMillis()
                    if (downNoted) { downNoted = false; note("Connection to the relay restored") }
                }
                else {
                    val downMs = System.currentTimeMillis() - lastOkMs
                    if (downMs > 5 * 60_000L) {
                        if (!downNoted) {
                            downNoted = true
                            try { controlWs?.close(1000, "retry") } catch (_: Throwable) {}
                            controlWs = null
                            note("No connection to the relay since " + (downMs / 60000L) + " min ago — retrying")
                        }
                        sleepMs = 10000L
                    }
                }
                // Battery floor: when the relay says kill, PAUSE (don't capture) but stay alive
                // and keep polling slowly, so we resume automatically once the battery recovers
                // or the floor is lowered — no re-link needed. (Previously we stopped the service,
                // which is why it never came back on its own.)
                if (cmd?.optBoolean("kill", false) == true) {
                    if (!paused) { paused = true; p.edit().putBoolean("suspended", true).apply()
                        releaseWake()   // let the head unit sleep
                        if (streaming) stopStreaming()
                        note("Paused — battery below floor. Resumes automatically when it recovers.") }
                    if (cmd != null) syncCampGuard(base, token, cmd)   // remote Camping on/off still works while paused (no camera involved)
                    nap(20000L)
                    continue
                } else if (paused) {
                    paused = false; p.edit().putBoolean("suspended", false).apply()
                    acquireWake(); note("Resumed — battery above floor.")
                }
                // Offline (the poll failed): keep the last armed state the relay
                // gave us; the snapshots queue on disk and go up once the connection is back.
                if (cmd != null) { lastArmed = cmd.optBoolean("armed", false); queueLeft = flushQueue(base, token) } else queueLeft = 0
                val armed = cmd?.optBoolean("armed", false) ?: lastArmed
                val reqMs = cmd?.optLong("captureReqMs", 0L) ?: 0L
                val streamUntil = cmd?.optLong("streamUntilMs", 0L) ?: 0L
                lastStreamUntil = streamUntil
                val logReqMs = cmd?.optLong("logReqMs", 0L) ?: 0L
                if (cmd != null) syncCampGuard(base, token, cmd)
                val safe = safeMode()
                if (safe) wasSafe = true else if (wasSafe) { wasSafe = false; mainH.post { syncCameraFeatures() } }   // safe window over: bring the features back

                // Wake locks only while there is work (armed, streaming, or a command just came in) plus a short
                // grace period; an always-held CPU + high-performance WiFi lock stopped the head unit from ever idling.
                val nowMs = System.currentTimeMillis()
                if (armed || streaming || streamUntil > nowMs || reqMs > lastServedReq || logReqMs > lastServedLogReq) lastActiveMs = nowMs
                if (nowMs - lastActiveMs < 3 * 60_000L) acquireWake() else releaseWake()

                // Keeps the dashboard's USB video catalog from going stale between service
                // restarts, now that there's no "Check now" button to force a refresh on demand --
                // piggybacks on this already-running poll loop instead of a separate timer.
                if (System.currentTimeMillis() - lastAutoVideoCheck > 5 * 60_000L) {
                    lastAutoVideoCheck = System.currentTimeMillis(); usbVideoWatcher?.forceCheck()
                }
                // Dashboard's "Download log": upload the whole on-device log so it can be read
                // remotely instead of needing the car's screen.
                if (logReqMs > lastServedLogReq) { lastServedLogReq = logReqMs; uploadFullLog(base, token) }
                // Dashboard's "Request" on a USB catalog row: upload that specific existing file.
                val requestedVideoReqMs = cmd?.optLong("requestedVideoReqMs", 0L) ?: 0L
                val requestedVideo = cmd?.optString("requestedVideo", "") ?: ""
                if (requestedVideoReqMs > lastServedVideoReqMs && requestedVideo.isNotEmpty()) {
                    lastServedVideoReqMs = requestedVideoReqMs; usbVideoWatcher?.uploadRequested(requestedVideo)
                } else if (requestedVideoReqMs > lastServedVideoReqMs && requestedVideo.isEmpty()) {
                    lastServedVideoReqMs = requestedVideoReqMs; usbVideoWatcher?.abortUpload = true   // dashboard cancelled
                }

                // Manual one-shot: capture every selected camera.
                if (reqMs > lastServedReq && safe) { lastServedReq = reqMs; report(base, token, "Capture skipped: safe mode after repeated crashes") }
                else if (reqMs > lastServedReq) {
                    lastServedReq = reqMs
                    var n = 0
                    val errs = ArrayList<String>()
                    if (cams.isEmpty()) errs.add("no cameras selected in the car app")
                    Park.yieldCam = true; CamHub.yieldTune(true); try { Thread.sleep(700) } catch (_: InterruptedException) {}   // floating windows let go of the camera first
                    for (cam in cams) {
                        val res = CameraCapture.captureOrError(this, cam, maxW, quality)
                        if (res.jpeg != null) { upload(base, token, cam, res.jpeg, "manual"); n++ }
                        else errs.add(res.error ?: "cam $cam failed")
                    }
                    if (!streaming) { Park.yieldCam = false; CamHub.yieldTune(false) }
                    val msg = if (n > 0) "Sent $n snapshot(s)" + (if (errs.isEmpty()) "" else "; " + errs.joinToString("; "))
                              else "Capture failed: " + errs.joinToString("; ")
                    note(msg)
                    report(base, token, msg)   // surface the reason on the dashboard
                }

                // Live stream: while the window is open, grab the surround view as fast as
                // we can (smaller/faster frame) into the relay's single live slot. Takes
                // frames are NOT saved to the gallery.
                if (safe) {
                    if (streaming) stopStreaming()
                } else if (streamUntil > System.currentTimeMillis() && cams.isNotEmpty()) {
                    // Live stream: a continuous-capture thread pushes frames over a WebSocket
                    // (no per-frame HTTP / DB). Poll a bit faster so a manual stop is caught.
                    if (!streaming && streamUntil != suppressedUntil) startStreaming(base, token, cams[0])
                    sleepMs = 3000L
                } else if (streaming) {
                    stopStreaming()
                }
            } catch (_: Throwable) {}
            nap(if (queueLeft > 0 && sleepMs > 3000L) 3000L else sleepMs)   // backlog waiting and relay reachable: drain it faster
        }
    }

    private fun upload(base: String, token: String?, cam: String, jpeg: ByteArray, trig: String) {
        val meta = JSONObject().put("trig", trig).put("cam", cam).put("ts", System.currentTimeMillis()).put("fmt", CameraCapture.encFormat())
        val loc = Loc.current(this)
        if (loc != null) { meta.put("lat", loc.first).put("lng", loc.second) }
        val body = JSONObject(meta.toString()).put("jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        val r = Net.postJson("$base/cam/snap", token, body)
        if (r == null || r.optInt("_status") !in 200..299) {
            if (r == null || r.optInt("_status") >= 500) { val n = SnapQueue.add(this, jpeg, meta); note("Offline: $n snapshot(s) waiting to upload") }
        }
    }

    // Offline queue: see SnapQueue. Sent oldest first once the relay answers again.
    @Volatile private var lastArmed = false
    // A failing queued upload is a full image (~1 MB+) each try, so after a failure wait 1, 2, 4 ... up to 30 min before the next
    // try instead of re-sending on every poll (that burned mobile data when the relay kept answering 5xx to snapshots).
    private var flushFails = 0
    private var flushNextMs = 0L
    private fun flushQueue(base: String, token: String?): Int {
        if (SnapQueue.stats(this).first == 0) return 0
        if (System.currentTimeMillis() < flushNextMs) return 0
        val (sent, left) = SnapQueue.flush(this, 5) { Net.postJson("$base/cam/snap", token, it)?.optInt("_status", 0) ?: 0 }
        if (sent > 0) { flushFails = 0; flushNextMs = 0L }
        else if (left > 0) {
            flushFails++; flushNextMs = System.currentTimeMillis() + minOf(60_000L shl minOf(flushFails - 1, 5), 1_800_000L)
            Logger.log(this, "Queued snapshot upload failed ($flushFails in a row), next try in " + (flushNextMs - System.currentTimeMillis()) / 60000 + " min")
        }
        if (sent > 0) note("Uploaded $sent queued snapshot(s)" + (if (left > 0) ", $left left" else ""))
        return if (sent > 0) left else 0   // only speed up the poll while the backlog is actually draining
    }

    // Push one live-stream frame to the relay's single live slot (not the gallery).
    private fun uploadLive(base: String, token: String?, jpeg: ByteArray) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject().put("jpeg", b64).put("ts", System.currentTimeMillis()).put("fmt", CameraCapture.encFormat())
        Net.postJson("$base/cam/live", token, body)
    }

    // ---- live stream ----
    private fun firstCam(): String? {
        val cams = (getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("cams", "2") ?: "2")
            .split(",").filter { it.isNotBlank() }
        return cams.firstOrNull()
    }
    // Maintain the control socket; the relay pushes "start"/"stop" so a stream begins
    // immediately. On (re)connect we also check once in case a stream is already pending
    // (e.g. we just woke from the Sentry nudge).
    private fun ensureControlWs(base: String, token: String?) {
        if (controlWs != null) return
        val url = base.replaceFirst("http", "ws") + "/cam/ws?role=ctrl"
        try {
            val req = Request.Builder().url(url).addHeader("Authorization", "Bearer ${token ?: ""}").build()
            controlWs = controlClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: okhttp3.Response) {
                    try {
                        val cmd = Net.get("$base/cam/cmd", token)
                        val until = cmd?.optLong("streamUntilMs", 0L) ?: 0L
                        if (until > System.currentTimeMillis()) { val c = firstCam(); if (c != null) startStreaming(base, token, c) }
                    } catch (_: Throwable) {}
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (text == "start") { suppressedUntil = 0L; val c = firstCam(); if (c != null) startStreaming(base, token, c) }
                    else if (text == "stop") { stopStreaming() }
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { controlWs = null }
                override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) { controlWs = null }
            })
        } catch (_: Throwable) { controlWs = null }
    }
    @Synchronized private fun startStreaming(base: String, token: String?, cam: String) {
        if (streaming) return
        streaming = true
        viewers = 1; noViewersSince = 0L; streamStartMs = System.currentTimeMillis(); streamBytes = 0L
        Park.yieldCam = true; CamHub.yieldTune(true)
        val wsUrl = base.replaceFirst("http", "ws") + "/cam/ws?role=pub"   // https->wss, http->ws
        report(base, token, "live: connecting")
        try {
            val req = Request.Builder().url(wsUrl).addHeader("Authorization", "Bearer ${token ?: ""}").build()
            streamWs = streamClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: okhttp3.Response) { report(base, token, "live: socket open") }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.startsWith("viewers:")) {
                        viewers = text.substring(8).toIntOrNull() ?: 1
                        noViewersSince = if (viewers == 0) System.currentTimeMillis() else 0L
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) {
                    report(base, token, "live: socket fail " + (t.message ?: t.javaClass.simpleName))
                    // Dead socket: end this stream so the next poll (window still open) starts a fresh one.
                    if (ws === streamWs) Thread { stopStreaming() }.start()
                }
            })
        } catch (e: Throwable) { streaming = false; report(base, token, "live: ws error " + (e.message ?: "")); return }
        note("Live stream started")
        val gen = CameraStream.begin()
        streamThread = Thread {
            try { Thread.sleep(700) } catch (_: InterruptedException) {}   // floating windows let go of the camera first
            // 1280 is already the composite's native width (see CameraCapture), so this never
            // downscales — quality 88 is a cheap sharpness bump over the old 80 since the per-frame
            // camera open/close, not JPEG encode time, is what caps the live frame rate.
            // True (once) when the stream must end now: nobody watching for 60 s, or past the time / size cap.
            fun mustStop(): Boolean {
                val now = System.currentTimeMillis()
                if (!((noViewersSince != 0L && now - noViewersSince > 60_000L) || now - streamStartMs > 360_000L || streamBytes > 120_000_000L)) return false
                if (suppressedUntil != lastStreamUntil) {
                    suppressedUntil = lastStreamUntil   // don't restart until the dashboard opens a new window (a new streamUntil)
                    report(base, token, "live: stopped to save data (no viewers / time or size cap)")
                    Thread { stopStreaming() }.start()
                }
                return true
            }
            CameraStream.run(gen, this, cam, 1280, 88,
                paused = { viewers == 0 || mustStop() },
                onFrame = { bytes ->
                    if (!mustStop()) {
                        streamBytes += bytes.size
                        val ws = streamWs
                        // Drop frames if the uplink is backed up, so latency can't snowball.
                        if (ws != null && ws.queueSize() < 1_500_000L) { try { ws.send(ByteString.of(*bytes)); DataMeter.sent("live-view", bytes.size.toLong()) } catch (_: Throwable) {} }
                    }
                },
                onStatus = { msg -> report(base, token, msg) })
        }.apply { start() }
    }
    @Synchronized private fun stopStreaming() {
        if (!streaming) return
        streaming = false
        Park.yieldCam = false; CamHub.yieldTune(false)
        CameraStream.stop()
        try { streamWs?.close(1000, "done") } catch (_: Throwable) {}
        streamWs = null
        try { streamThread?.join(1500) } catch (_: Throwable) {}
        streamThread = null
        note("Live stream ended")
    }

    // Camping auto-off setting, kept in step with the dashboard: a newer dashboard change is applied here; a change made
    // in this app is sent to the relay (campMs is the time of the last change, so the two never fight).
    // The car's Camping switch is read on its own thread, so a slow or stuck car API can never stall the relay poll loop.
    @Volatile private var campRead: Boolean? = null
    @Volatile private var campReader: Thread? = null
    private fun ensureCampReader() {
        if (campReader?.isAlive == true) return
        campReader = Thread {
            while (running) {
                try { campRead = CampGuard.campingState(this) } catch (_: Throwable) {}
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true; it.start() }
    }
    @Volatile private var campSetBusy = false
    private var campSetTries = 0
    private fun syncCampGuard(base: String, token: String?, cmd: JSONObject) {
        try {
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            cmd.optJSONObject("campSet")?.let { cs ->
                val seq = cs.optLong("seq", 0L)
                if (seq > p.getLong("campSetApplied", 0L) && !campSetBusy) {
                    val want = cs.optBoolean("on", false)
                    campSetBusy = true
                    Logger.log(this, "campguard: remote request: camping ${if (want) "on" else "off"} (try ${campSetTries + 1})")
                    Thread {
                        try {
                            val ok = CampGuard.setCamping(this, want)
                            campSetTries++
                            if (ok || campSetTries >= 3) {
                                p.edit().putLong("campSetApplied", seq).apply(); campSetTries = 0
                                report(base, token, "Camping " + (if (want) "on" else "off") + " from ZDash: " + (if (ok) "done" else "the car did not accept it (see log)"))
                            }
                        } catch (e: Throwable) { Logger.log(this, "campguard: remote set failed ${e.javaClass.simpleName} ${e.message ?: ""}"); campSetTries++ }
                        finally { campSetBusy = false }
                    }.start()
                }
            }
            val rMs = cmd.optLong("campMs", 0L); val rOn = cmd.optBoolean("campOff", false); val rMin = cmd.optInt("campMin", 15)
            val lOn = p.getBoolean("campOff", false); val lMin = p.getInt("campMin", 15)
            if (rMs > p.getLong("campApplied", 0L)) {
                p.edit().putBoolean("campOff", rOn).putInt("campMin", rMin).putLong("campApplied", rMs).apply()
                Logger.log(this, "campguard: setting from dashboard: ${if (rOn) "on" else "off"}, $rMin min")
                mainH.post { CampGuard.sync(this, safeMode()) }
            } else if (lOn != rOn || lMin != rMin) {
                val res = Net.postJson("$base/cam/campguard", token, JSONObject().put("on", lOn).put("min", lMin))
                val ms = res?.optLong("campMs", 0L) ?: 0L
                if (ms > 0L) p.edit().putLong("campApplied", ms).apply()
            }
        } catch (_: Throwable) {}
    }

    // Report the last capture outcome so the dashboard can show why a capture failed.
    private fun report(base: String, token: String?, msg: String) {
        Logger.log(this, "Reported to relay: $msg")
        try { Net.postJson("$base/cam/log", token, JSONObject().put("msg", msg).put("ts", System.currentTimeMillis())) } catch (_: Throwable) {}
    }

    // Dashboard's "Download log" button: upload the whole on-device log (see Logger.kt) so it
    // can be read remotely, not just on the car's own screen.
    private fun uploadFullLog(base: String, token: String?) {
        Thread {
            val text = Logger.read(this)
            val ok = Net.postText("$base/cam/log/upload", token, text)
            Logger.log(this, "Log upload to relay: " + (if (ok) "done" else "failed"))
        }.start()
    }

    // ---- notification ----
    private fun buildNotif(text: String): Notification {
        val chId = "sentrycam"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "Z_Cam", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Z_Cam").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
    }
    private fun note(text: String) {
        Logger.log(this, text)
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java).notify(1, buildNotif(text))
    }
}
