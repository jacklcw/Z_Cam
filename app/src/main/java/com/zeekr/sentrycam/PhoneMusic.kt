package com.zeekr.sentrycam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * After a video or audio in our browser stops, the car's Bluetooth player has lost the car's audio for good, and pressing play on the phone
 * only gets the phone paused again by the car (the car accepts phone audio only after play was pressed on the car side). This shows a small
 * floating card with that note and a "Play phone music" button, which presses play on the car's own Bluetooth player for the user.
 * Main thread only.
 */
object PhoneMusic {
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private val autoHide = Runnable { card?.let { hide(it.context) } }

    /** Our browser's media stopped: offer the button, but only while a phone is streaming to the car over Bluetooth. */
    fun offer(ctx: Context) = main.post {
        if (!phoneConnected(ctx)) return@post
        connect(ctx)
        show(ctx.applicationContext)
    }

    fun phoneConnected(ctx: Context): Boolean = try {
        // 11 = BluetoothProfile.A2DP_SINK (the car side of phone music); hidden constant, so any failure just shows the card
        val a = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        a == null || a.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
    } catch (_: Throwable) { true }

    private fun show(ctx: Context) {
        if (card != null) { main.removeCallbacks(autoHide); main.postDelayed(autoHide, 60_000L); return }
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val wm = ctx.getSystemService(WindowManager::class.java)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { setColor(0xE61C1C1E.toInt()); cornerRadius = dp(36).toFloat(); setStroke(dp(1), 0xFF3A3A3C.toInt()) }
        }
        box.addView(TextView(ctx).apply {
            text = "The car paused your phone music for the browser.\nPlay it again here, with the phone media buttons or on the car. Play on the phone gets paused."
            setTextColor(0xFFF5F5F7.toInt()); textSize = 20f; typeface = ZFont.reg
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
        box.addView(chip(ctx, "Play phone music", 0xFFA3E635.toInt(), 0xFF1C1C1E.toInt()) { play(ctx); hide(ctx) }, LinearLayout.LayoutParams(-2, dp(72)))
        box.addView(chip(ctx, "Close", 0xFF2C2C2E.toInt(), 0xFFF5F5F7.toInt()) { hide(ctx) }, LinearLayout.LayoutParams(-2, dp(72)).apply { marginStart = dp(12) })
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(40) }
        try {
            wm.addView(box, lp); card = box
            main.postDelayed(autoHide, 60_000L)
        } catch (e: Throwable) { Logger.log(ctx, "phonemusic: card failed ${e.message ?: ""}") }
    }

    private fun chip(ctx: Context, label: String, bg: Int, fg: Int, onTap: () -> Unit) = TextView(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        text = label; setTextColor(fg); textSize = 24f; typeface = ZFont.semi; gravity = Gravity.CENTER; isSingleLine = true
        setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
        background = GradientDrawable().apply { setColor(bg); cornerRadius = 34 * d }
        setOnClickListener { onTap() }
    }

    private fun hide(ctx: Context) {
        main.removeCallbacks(autoHide)
        val c = card ?: return
        card = null
        try { ctx.getSystemService(WindowManager::class.java).removeView(c) } catch (_: Throwable) {}
    }

    // A connection to the car's Bluetooth player kept open ahead of time, so a button press goes out at once (connecting on the press took seconds).
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var connecting = false
    /** Told when the phone's play state changes (true = playing). */
    var onState: ((Boolean) -> Unit)? = null
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) { onState?.invoke(state?.state == android.media.session.PlaybackState.STATE_PLAYING) }
        override fun onSessionDestroyed() { drop() }
    }

    /** Our own browser is playing a video/audio right now (told by the page). */
    @Volatile var webPlaying = false

    /**
     * Is the phone playing? From the car's Bluetooth player when connected; the car's one here never answers the connection, so otherwise
     * from the car's running audio players: a started media player that is neither our engine sound nor our browser is the phone.
     */
    fun playing(ctx: Context): Boolean {
        controller?.playbackState?.let { return it.state == android.media.session.PlaybackState.STATE_PLAYING }
        return try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val started = am.activePlaybackConfigurations.count { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA && it.toString().contains("state:started") }
            started - com.zeekr.sentrycam.sound.AudioWake.ourMediaTracks() - (if (webPlaying) 1 else 0) > 0
        } catch (_: Throwable) { false }
    }

    private var players: android.media.AudioManager.AudioPlaybackCallback? = null
    private var stateCtx: Context? = null
    /** Re-reads the state and tells [onState] (players starting / stopping, the page starting / stopping). */
    fun recheck() = main.post { val c = stateCtx ?: return@post; onState?.invoke(playing(c)) }

    /** Follows the phone's play state while the media buttons are shown. */
    fun watch(ctx: Context) = main.post {
        if (players != null) return@post
        val app = ctx.applicationContext
        stateCtx = app
        players = object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) { onState?.invoke(playing(app)) }
        }.also { try { app.getSystemService(android.media.AudioManager::class.java).registerAudioPlaybackCallback(it, main) } catch (_: Throwable) {} }
        onState?.invoke(playing(app))
    }

    fun unwatch() = main.post {
        val c = stateCtx; val cb = players
        players = null; stateCtx = null
        if (c != null && cb != null) try { c.getSystemService(android.media.AudioManager::class.java).unregisterAudioPlaybackCallback(cb) } catch (_: Throwable) {}
    }
    private var listed = false

    /** Opens the connection if it isn't open (cheap to call often). */
    fun connect(ctx: Context) = main.post {
        if (controller != null || connecting) return@post
        val app = ctx.applicationContext
        if (!listed) {   // which media services the car has, for the log (the one picked never answered on the 7X)
            listed = true
            try { Logger.log(app, "phonemusic: media services " + app.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).joinToString { it.serviceInfo.packageName + "/" + it.serviceInfo.name }) } catch (_: Throwable) {}
        }
        val svc = btBrowserService(app) ?: return@post
        connecting = true
        var b: MediaBrowser? = null
        b = MediaBrowser(app, svc, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                connecting = false
                try {
                    controller = MediaController(app, b!!.sessionToken).also { it.registerCallback(cb, main) }
                    Logger.log(app, "phonemusic: connected to ${svc.flattenToShortString()}")
                    onState?.invoke(playing(app))
                } catch (e: Throwable) { Logger.log(app, "phonemusic: controller failed ${e.message ?: ""}") }
            }
            override fun onConnectionFailed() { connecting = false; browser = null; Logger.log(app, "phonemusic: Bluetooth media service refused") }
            override fun onConnectionSuspended() { drop() }
        }, null)
        browser = b
        try { b.connect() } catch (e: Throwable) { connecting = false; browser = null; Logger.log(app, "phonemusic: connect failed ${e.message ?: ""}") }
    }

    private fun drop() {
        try { controller?.unregisterCallback(cb) } catch (_: Throwable) {}
        controller = null; connecting = false
        try { browser?.disconnect() } catch (_: Throwable) {}
        browser = null
    }

    /** Closes the connection (the media buttons were switched off). */
    fun disconnect() = main.post { drop() }

    fun play(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) { it.play() }
    /** Play / pause: through the connection by its state; as a key it is the toggle key, so it is right whatever the button shows. */
    fun toggle(ctx: Context) { val c = controller; if (c != null) send(ctx, 0) { if (playing(ctx)) it.pause() else it.play() } else send(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {} }
    fun pause(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
    fun next(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) { it.skipToNext() }
    fun prev(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) { it.skipToPrevious() }

    /** Through the open connection when there is one, else a media key right away (never waits), and connects for next time. */
    private fun send(ctx: Context, code: Int, act: (MediaController.TransportControls) -> Unit) {
        val c = controller
        if (c != null) {
            try { act(c.transportControls); return } catch (e: Throwable) { Logger.log(ctx, "phonemusic: command failed ${e.message ?: ""}"); drop() }
        }
        key(ctx, code)
        connect(ctx)
    }

    /** The car's Bluetooth music browser service (com.android.bluetooth on AOSP, maybe a vendor package here). */
    private fun btBrowserService(ctx: Context): ComponentName? = try {
        ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { if (it.packageName == "com.android.bluetooth") 0 else 1 }
            .firstOrNull { it.packageName.contains("bluetooth", true) || it.className.contains("bluetooth", true) }
    } catch (_: Throwable) { null }

    private fun key(ctx: Context, code: Int) {
        Logger.log(ctx, "phonemusic: not connected, sending media key $code")
        try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Throwable) { Logger.log(ctx, "phonemusic: key failed ${e.message ?: ""}") }
    }
}
