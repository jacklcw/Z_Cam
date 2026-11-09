package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MirrorReceiver {
    private const val PORT = 8766
    private const val MAX_PACKET = 4 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var texture: TextureView? = null
    private var title: TextView? = null
    @Volatile private var session = 0
    @Volatile private var socket: Socket? = null
    private var surface: Surface? = null

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        main.post {
            appCtx = c
            if (!Settings.canDrawOverlays(c)) { say("no overlay permission"); return@post }
            ensureWindow(c)
            if (root == null) return@post
            val id = ++session
            Thread({ connectLoop(c, id) }, "mirror-net").start()
        }
    }

    fun stop() {
        main.post {
            session++
            try { socket?.close() } catch (_: Throwable) {}
            val r = root ?: return@post
            root = null
            try { appCtx?.getSystemService(WindowManager::class.java)?.removeView(r) } catch (_: Throwable) {}
        }
    }

    private fun say(msg: String) {
        val c = appCtx ?: return
        Logger.log(c, "mirror: $msg")
        main.post { Toast.makeText(c, "Mirror: $msg", Toast.LENGTH_LONG).show() }
    }

    private fun status(msg: String) {
        Logger.log(appCtx ?: return, "mirror: $msg")
        main.post { title?.text = "Mirror - $msg" }
    }

    private class Gw(val host: String, val label: String, val net: android.net.Network)

    private fun gateways(ctx: Context): List<Gw> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val nets = cm.allNetworks.sortedByDescending { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val out = LinkedHashMap<String, Gw>()
        for (n in nets) {
            val lp = cm.getLinkProperties(n) ?: continue
            val caps = cm.getNetworkCapabilities(n)
            val kind = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "wifi" else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "cell" else "other"
            val mine = lp.linkAddresses.map { it.address.hostAddress }.filter { it?.contains(':') == false }.joinToString("/")
            for (r in lp.routes) {
                val g = r.gateway
                if (g is Inet4Address && !g.isAnyLocalAddress) out.getOrPut(g.hostAddress!!) { Gw(g.hostAddress!!, "${lp.interfaceName} $kind me=$mine", n) }
            }
        }
        return out.values.toList()
    }

    private fun fetchKey(ctx: Context): ByteArray? {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val url = p.getString("url", "") ?: ""
        val tok = p.getString("token", null)
        if (url.isEmpty() || tok.isNullOrEmpty()) { status("car not linked (no relay url/token)"); return null }
        val r = Net.get("$url/cam/mirrorkey", tok)
        val k = r?.optString("key", "")
        if (!k.isNullOrEmpty()) { p.edit().putString("mirrorKey", k).apply(); return k.toByteArray() }
        status(if (r == null) "no internet to relay" else "relay replied ${r.optInt("_status")} - deploy the relay")
        return p.getString("mirrorKey", null)?.toByteArray()
    }

    private fun connectLoop(ctx: Context, id: Int) {
        var token: ByteArray? = null
        while (id == session) {
            if (token == null) {
                status("getting key")
                token = fetchKey(ctx)
                if (token == null) { Thread.sleep(3000); continue }
            }
            val hosts = gateways(ctx)
            if (hosts.isEmpty()) { status("no network gateway"); Thread.sleep(2000); continue }
            Logger.log(ctx, "mirror: gateways ${hosts.joinToString { "${it.host} (${it.label})" }}")
            var done = false
            val fails = ArrayList<String>()
            for (gw in hosts) {
                val host = gw.host
                if (id != session) break
                status("connecting to $host\n${fails.joinToString("\n")}")
                try {
                    // Bind to that gateway's own network: a plain Socket() follows the default route (the car's 4G), never the phone hotspot.
                    (gw.net.socketFactory.createSocket() ?: Socket()).use { s ->
                        socket = s
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(host, PORT), 2000)
                        s.soTimeout = 5000
                        val inp = DataInputStream(s.getInputStream())
                        val out = DataOutputStream(s.getOutputStream())
                        val nonce = ByteArray(16)
                        inp.readFully(nonce)
                        out.write(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token, "HmacSHA256")) }.doFinal(nonce))
                        out.flush()
                        if (inp.readByte().toInt() != 1) { status("key rejected"); token = null; Thread.sleep(3000); done = true; return@use }
                        s.soTimeout = 0
                        done = true
                        receive(inp, id)
                    }
                } catch (e: Throwable) {
                    fails += "$host [${gw.label}] ${if (e.message?.contains("ECONNREFUSED") == true) "refused" else e.javaClass.simpleName}"
                }
                if (done) break
            }
            if (!done && id == session) status("no phone found\n${fails.joinToString("\n")}")
            if (id == session) Thread.sleep(1500)
        }
    }

    private fun receive(inp: DataInputStream, id: Int) {
        val codecId = inp.readUnsignedByte()
        val w = inp.readInt(); val h = inp.readInt()
        if (codecId != 0 || w !in 16..4096 || h !in 16..4096) { status("bad header"); return }
        main.post { fit(w, h) }
        val st = awaitSurface(id) ?: return
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        Logger.log(appCtx!!, "mirror: decoder ${dec.name} ${w}x$h")
        dec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h), st, null, 0)
        dec.start()
        status("connected")
        val drain = Thread({
            val info = MediaCodec.BufferInfo()
            while (id == session) {
                val o = try { dec.dequeueOutputBuffer(info, 50_000) } catch (_: Throwable) { break }
                if (o >= 0) dec.releaseOutputBuffer(o, true)
            }
        }, "mirror-drain")
        drain.start()
        try {
            var frames = 0
            var t0 = System.currentTimeMillis()
            while (id == session) {
                val len = inp.readInt()
                if (len <= 0 || len > MAX_PACKET) { status("bad packet length $len"); return }
                val flags = inp.readUnsignedByte()
                val pts = inp.readLong()
                val data = ByteArray(len)
                inp.readFully(data)
                var i = -1
                while (id == session && i < 0) i = dec.dequeueInputBuffer(20_000)
                if (i < 0) return
                dec.getInputBuffer(i)!!.apply { clear(); if (len > capacity()) { status("packet too big"); return }; put(data) }
                dec.queueInputBuffer(i, 0, len, pts, flags)
                frames++
                val now = System.currentTimeMillis()
                if (now - t0 >= 5000) { Logger.log(appCtx!!, "mirror: $frames packets in 5s"); frames = 0; t0 = now }
            }
        } finally {
            try { dec.stop(); dec.release() } catch (_: Throwable) {}
            status("disconnected")
        }
    }

    private fun awaitSurface(id: Int): Surface? {
        repeat(100) {
            if (id != session) return null
            val tv = texture
            val st = tv?.surfaceTexture
            if (st != null) { surface?.release(); return Surface(st).also { surface = it } }
            Thread.sleep(50)
        }
        return null
    }

    private fun fit(w: Int, h: Int) {
        val r = root ?: return
        val p = params ?: return
        val dm = r.resources.displayMetrics
        val maxH = (dm.heightPixels * 0.8f).toInt()
        val maxW = (dm.widthPixels * 0.6f).toInt()
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        p.width = (w * scale).toInt()
        p.height = (h * scale).toInt()
        try { appCtx?.getSystemService(WindowManager::class.java)?.updateViewLayout(r, p) } catch (_: Throwable) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureWindow(ctx: Context) {
        if (root != null) return
        val wm = ctx.getSystemService(WindowManager::class.java)
        val d = ctx.resources.displayMetrics.density
        val dp = { x: Int -> (x * d).toInt() }
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(dp(360), dp(640), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = dp(40); y = dp(60) }
        val tv = TextureView(ctx)
        texture = tv
        val bar = TextView(ctx).apply {
            text = "Mirror"; setTextColor(0xFFF5F5F7.toInt()); textSize = 15f
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, 0, 0); setBackgroundColor(0x99000000.toInt())
            var dx = 0f; var dy = 0f; var ox = 0; var oy = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (ox + (e.rawX - dx).toInt()).coerceIn(0, dm.widthPixels - dp(40))
                        lp.y = (oy + (e.rawY - dy).toInt()).coerceIn(0, dm.heightPixels - dp(40))
                        try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
                    }
                }
                true
            }
        }
        title = bar
        val grip = View(ctx).apply {
            setBackgroundColor(0x99FFFFFF.toInt())
            var dx = 0f; var dy = 0f; var w0 = 0; var h0 = 0
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; w0 = lp.width; h0 = lp.height }
                    MotionEvent.ACTION_MOVE -> {
                        lp.width = (w0 + (e.rawX - dx)).toInt().coerceIn(dp(200), dm.widthPixels)
                        lp.height = (h0 + (e.rawY - dy)).toInt().coerceIn(dp(200), dm.heightPixels)
                        try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
                    }
                }
                true
            }
        }
        val box = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(tv, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP))
            addView(grip, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        }
        try { wm.addView(box, lp); root = box; params = lp }
        catch (e: Throwable) { say("addView failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }
}
