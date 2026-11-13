package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speed limit sign on the HUD. The head unit's GPS position is matched to the nearest road in OpenStreetMap (public Overpass API, no key,
 * queried straight from the car so it works standalone) and the road's "maxspeed" tag is shown as a round sign. No tag = no sign.
 * Prefs: slOn, slX/slY (centre, % of the HUD), slSize (% of the HUD height), slPreviewUntil (settings editor shows a sample sign).
 * Same HUD display pick as [RevBar]. Owned by CamService.
 */
class SpeedLimitHud(private val ctx: Context) {
    companion object {
        @Volatile var instance: SpeedLimitHud? = null
        private val MIRRORS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")
        @Volatile private var mirrorFirst = 0   // the mirror that answered last goes first next time
        private const val RADIUS_M = 500          // roads loaded around each query point
        private const val REQUERY_M = 300.0       // ask again after moving this far from the last query point
        private const val MIN_GAP_MS = 4_000L
        private const val HOLD_MS = 10_000L       // keep the last sign this long when the lookup drops out (untagged road, GPS jump, slow answer)
        private const val MAX_SNAP_M = 40.0       // further than this from every road = off the map, show nothing
        private const val HUD_W = 1440; private const val HUD_H = 480
        private const val HUD_FALLBACK_ID = 3
        private const val CLUSTER_W = 2560; private const val CLUSTER_H = 536
    }

    private val tickRun = Runnable { tick() }
    private class Road(val limit: Int, val lat: DoubleArray, val lon: DoubleArray, val name: String = "", val raw: String = "", val oneway: Int = 0, val fwd: Int = 0, val bwd: Int = 0)   // oneway: 1 = along the drawn direction, -1 = against; fwd/bwd = maxspeed:forward/backward (0 = same as limit)

    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false

    // location (updates arrive on the main thread, read by the bg thread)
    @Volatile private var fix: Location? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { fix = l }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // roads (bg thread only)
    @Volatile private var roads: List<Road> = emptyList()
    @Volatile private var loading = false
    @Volatile private var qLat = 0.0; @Volatile private var qLon = 0.0; private var qAt = 0L; @Volatile private var haveQ = false
    @Volatile private var limit = 0       // km/h, 0 = unknown
    @Volatile private var nomFirst = true; @Volatile private var nomRoad: Road? = null
    @Volatile private var lastLoad = "none yet"; @Volatile private var lastLoadAt = 0L
    @Volatile private var dbgRoad: Road? = null; @Volatile private var dbgDist = -1.0
    private var dbgView: android.widget.TextView? = null
    private var sensor: Any? = null; private var latest: java.lang.reflect.Method? = null; private var lastTry = 0L

    private var view: SignView? = null    // main thread only
    private var hudId = -1; private var hudW = 0; private var hudH = 0; private var retryAt = 0L
    private val dm get() = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun start() {
        if (running) return
        running = true
        thread = HandlerThread("speedlimit").apply { start() }
        bg = Handler(thread!!.looper).also { it.post(tickRun) }
        instance = this
        HudLayer.raisers["lim"] = { removeView(); if (lastShow > 0) update(lastShow, lastOver) }
        main.post(::listen)
    }

    /** Run a tick now (the preview button) instead of waiting for the next second. */
    fun kick() { bg?.let { it.removeCallbacks(tickRun); it.post(tickRun) } }

    fun stop() {
        if (!running) return
        running = false
        if (instance === this) instance = null
        HudLayer.raisers.remove("lim")
        thread?.quitSafely(); thread = null; bg = null
        main.post {
            try { (ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener) } catch (_: Throwable) {}
            removeView()
        }
    }

    private fun listen() {
        if (!running) return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            for (prov in lm.getProviders(true)) if (prov != LocationManager.PASSIVE_PROVIDER) lm.requestLocationUpdates(prov, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {} catch (_: Throwable) {}
    }

    private fun position(): Pair<Double, Double>? {
        val f = fix
        if (f != null && System.currentTimeMillis() - f.time < 30_000) return Pair(f.latitude, f.longitude)
        return Loc.current(ctx)
    }

    private fun tick() {
        val h = bg ?: return
        if (!running) return
        val preview = p.getLong("slPreviewUntil", 0L) > System.currentTimeMillis()
        val pos = position()
        if (pos != null) {
            val now = System.currentTimeMillis()
            val far = !haveQ || dist(pos.first, pos.second, qLat, qLon) > (if (roads.isEmpty()) 100.0 else REQUERY_M)
            if (far && now - qAt >= MIN_GAP_MS && !loading) {   // the network call runs on its own thread: it can take 30 s offline and must not hold up the sign (or the preview)
                qAt = now; loading = true
                val la = pos.first; val lo = pos.second
                Thread { try { load(la, lo) } finally { loading = false } }.start()
            }
            limit = nearest(pos.first, pos.second, heading(pos))
        } else limit = 0
        val tnow = System.currentTimeMillis()
        if (limit > 0) { heldLimit = limit; heldAt = tnow } else if (heldLimit > 0 && tnow - heldAt < HOLD_MS) limit = heldLimit   // brief dropouts must not blank the sign
        val show = if (preview) 60 else if (p.getBoolean("slOn", false)) limit else 0
        val bp = p.getLong("slBeepPrev", 0L)
        if (bp > beepPrevSeen) { if (bp > System.currentTimeMillis() - 5_000) beep(); beepPrevSeen = bp }   // the preview button: sound once, whatever the switch says
        val speeding = !preview && limit > 0 && speedKmh() > limit
        val over = p.getBoolean("slGlow", true) && (preview && (System.currentTimeMillis() / 1500) % 2 == 0L || speeding)
        if (speeding && p.getBoolean("slBeep", false)) {
            val now = System.currentTimeMillis()
            if (now - beepAt >= 30_000) { beepAt = now; beep() }   // once on crossing the limit, again every 30 s while still over
        } else if (!speeding) beepAt = 0L
        val info = debugText(pos)
        main.post { update(show, over); debugView(info) }
        h.postDelayed(tickRun, 1000L)
    }

    private var heldLimit = 0; private var heldAt = 0L
    private var prevLat = 0.0; private var prevLon = 0.0; private var calcBearing = Double.NaN
    /** Direction of travel in degrees (0 = north), or null when standing still: the GPS bearing, else from the last 15 m of movement. */
    private fun heading(pos: Pair<Double, Double>): Double? {
        val f = fix
        if (f != null && f.hasBearing() && f.hasSpeed() && f.speed > 2.5f && System.currentTimeMillis() - f.time < 5_000) return f.bearing.toDouble()
        if (prevLat == 0.0 && prevLon == 0.0) { prevLat = pos.first; prevLon = pos.second }
        if (dist(prevLat, prevLon, pos.first, pos.second) >= 15.0) {
            val dy = pos.first - prevLat; val dx = (pos.second - prevLon) * Math.cos(Math.toRadians(pos.first))
            calcBearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360; prevLat = pos.first; prevLon = pos.second
        }
        return if (speedKmh() > 8 && !calcBearing.isNaN()) calcBearing else null
    }

    private var beepAt = 0L
    private var beepPrevSeen = p.getLong("slBeepPrev", 0L)
    /** Two short 880 Hz beeps through the cabin speakers (media usage, like the engine sound). */
    private fun beep() {
        try {
            val rate = 22050; val n = rate * 55 / 100
            val pcm = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / rate; val inBeep = (t % 0.28) < 0.14
                val env = if (inBeep) Math.min(1.0, Math.min((t % 0.28) / 0.01, (0.14 - (t % 0.28)) / 0.02)) else 0.0
                pcm[i] = (Math.sin(2 * Math.PI * 880 * t) * env * 12000).toInt().toShort()
            }
            val tr = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
            tr.write(pcm, 0, n); tr.play()
            main.postDelayed({ try { tr.release() } catch (_: Throwable) {} }, 1500)
        } catch (_: Throwable) {}
    }

    /** Car speed sensor (m/s, same id as the trip widget), else the GPS speed; km/h, 0 if unknown. */
    private fun speedKmh(): Double {
        try {
            if (latest == null && System.currentTimeMillis() - lastTry > 5_000) {
                lastTry = System.currentTimeMillis()
                val car = BlindSpotHud.carCache ?: Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, ctx.applicationContext).also { BlindSpotHud.carCache = it }
                sensor = car?.javaClass?.getMethod("getSensorManager")?.invoke(car)
                latest = sensor?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
            }
            val f = (latest?.invoke(sensor, 0x00100100) as? Number)?.toFloat()
            if (f != null && !f.isNaN() && f != 255f && f != -65535f) return f * 3.6
        } catch (_: Throwable) { latest = null }
        val g = fix
        return if (g != null && g.hasSpeed() && System.currentTimeMillis() - g.time < 5_000) g.speed * 3.6 else 0.0
    }

    private fun load(lat: Double, lon: Double) {
        // fast driving: main roads only (a much smaller answer, so the public servers cope far better)
        val classes = if (speedKmh() > 50) "motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link" else "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
        val q = "[out:json][timeout:8];way(around:$RADIUS_M,$lat,$lon)[highway~\"^($classes)\$\"];out tags geom;"
        if (nomFirst && nominatim(lat, lon)) return   // Nominatim first: one small road answer, reliable in the car (Overpass often times out there, so it is only the fallback)
        for (k in MIRRORS.indices) {
            val mi = (mirrorFirst + k) % MIRRORS.size; val base = MIRRORS[mi]
            try {
                val c = URL(base).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 9000
                c.setRequestProperty("User-Agent", "ZCam-speedlimit/1.0")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + java.net.URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode !in 200..299) { c.errorStream?.close(); lastLoad = "FAILED HTTP ${c.responseCode}"; lastLoadAt = System.currentTimeMillis(); continue }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                DataMeter.request(base, q.length.toLong(), txt.length.toLong())
                val els = JSONObject(txt).optJSONArray("elements") ?: continue
                val out = parseRoads(els)
                roads = out; qLat = lat; qLon = lon; haveQ = true
                mirrorFirst = mi; lastLoad = "ok, ${out.size} roads, " + base.substringAfter("//").substringBefore("/"); lastLoadAt = System.currentTimeMillis()
                return
            } catch (e: Throwable) { lastLoad = "FAILED " + e.javaClass.simpleName + " " + (e.message ?: "").take(40); lastLoadAt = System.currentTimeMillis() }
        }
        if (!nomFirst) { nomFirst = nominatim(lat, lon) }
    }

    /** Fallback source: Nominatim reverse geocoding returns just the one road under the point, with its maxspeed tag (extratags). Tiny answer, other host. */
    private fun nominatim(lat: Double, lon: Double): Boolean {
        try {
            val u = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&extratags=1&lat=$lat&lon=$lon"
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "ZCam-speedlimit/1.0")
            if (c.responseCode !in 200..299) { c.errorStream?.close(); lastLoad += " | nominatim HTTP ${c.responseCode}"; return false }
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            DataMeter.request("nominatim.openstreetmap.org", u.length.toLong(), txt.length.toLong())
            val j = JSONObject(txt)
            if (j.has("error")) { lastLoad += " | nominatim: no road here"; nomRoad = null; roads = emptyList(); qLat = lat; qLon = lon; haveQ = true; return true }
            val raw = j.optJSONObject("extratags")?.optString("maxspeed", "") ?: ""
            val nm = listOf(j.optString("name", ""), j.optJSONObject("extratags")?.optString("ref", "") ?: "", j.optString("type", "")).filter { it.isNotEmpty() }.joinToString(" | ")
            nomRoad = Road(parseLimit(raw), doubleArrayOf(lat, lat), doubleArrayOf(lon, lon), nm, raw)
            roads = emptyList(); qLat = lat; qLon = lon; haveQ = true
            lastLoad = "ok, via nominatim"; lastLoadAt = System.currentTimeMillis()
            return true
        } catch (e: Throwable) { lastLoad += " | nominatim FAILED " + e.javaClass.simpleName; lastLoadAt = System.currentTimeMillis(); return false }
    }

    private fun parseRoads(els: org.json.JSONArray): List<Road> {
        val out = ArrayList<Road>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            val la = DoubleArray(g.length()); val lo = DoubleArray(g.length())
            for (j in 0 until g.length()) { val pt = g.getJSONObject(j); la[j] = pt.getDouble("lat"); lo[j] = pt.getDouble("lon") }
            val tg = e.optJSONObject("tags"); val raw = tg?.optString("maxspeed", "") ?: ""
            val ow = when (tg?.optString("oneway", "") ?: "") { "yes", "1", "true" -> 1; "-1", "reverse" -> -1; else -> 0 }
            out += Road(parseLimit(raw), la, lo, listOf(tg?.optString("name", "") ?: "", tg?.optString("ref", "") ?: "", tg?.optString("highway", "") ?: "").filter { it.isNotEmpty() }.joinToString(" | "), raw,
                ow, parseLimit(tg?.optString("maxspeed:forward", "") ?: ""), parseLimit(tg?.optString("maxspeed:backward", "") ?: ""))
        }
        return out
    }

    /** "60" -> 60, "40 mph" -> 64, "none"/"signals"/"walk" -> 0 (unknown). */
    private fun parseLimit(s: String): Int {
        val m = Regex("^\\s*(\\d{1,3})\\s*(mph)?\\s*$").find(s) ?: return 0
        val v = m.groupValues[1].toInt()
        return if (m.groupValues[2].isNotEmpty()) Math.round(v * 1.609).toInt() else v
    }

    private fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dy = (la2 - la1) * 111_320.0; val dx = (lo2 - lo1) * 111_320.0 * Math.cos(Math.toRadians(la1))
        return Math.hypot(dx, dy)
    }

    /**
     * Limit of the road the car is on: the closest road, but a road running the other way (or a one-way road against the direction of travel)
     * is pushed back, so parallel roads, slip roads and crossing roads lose to the one being driven. Uses maxspeed:forward/backward when tagged.
     * 0 if nothing within [MAX_SNAP_M] or no usable maxspeed.
     */
    private fun nearest(lat: Double, lon: Double, hdg: Double?): Int {
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat)); val ky = 111_320.0
        var best = Double.MAX_VALUE; var bestD = Double.MAX_VALUE; var lim = 0; var bestRoad: Road? = null
        for (r in roads) for (i in 0 until r.lat.size - 1) {
            val ax = (r.lon[i] - lon) * kx; val ay = (r.lat[i] - lat) * ky
            val bx = (r.lon[i + 1] - lon) * kx; val by = (r.lat[i + 1] - lat) * ky
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            val d = Math.hypot(ax + t * dx, ay + t * dy)
            var score = d; var with = true
            if (hdg != null && l2 > 1.0) {
                val seg = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360
                var diff = Math.abs(seg - hdg); if (diff > 180) diff = 360 - diff
                with = diff < 90
                val along = if (with) diff else 180 - diff           // angle between the road line and the heading, 0..90
                if (along > 50) score += 40.0                          // crossing road
                if ((r.oneway == 1 && !with) || (r.oneway == -1 && with)) score += 80.0   // one-way the wrong way
            }
            if (score < best) {
                best = score; bestD = d; bestRoad = r
                val dirLim = if (with) (if (r.oneway == -1) r.bwd else r.fwd) else (if (r.oneway == -1) r.fwd else r.bwd)
                lim = if (dirLim > 0) dirLim else r.limit
            }
        }
        val nr = nomRoad
        if (roads.isEmpty() && nr != null) { val dn = dist(lat, lon, nr.lat[0], nr.lon[0]); dbgRoad = nr; dbgDist = dn; return if (dn <= 250.0) nr.limit else 0 }
        dbgRoad = bestRoad; dbgDist = if (bestRoad == null) -1.0 else bestD
        return if (bestRoad != null && bestD <= MAX_SNAP_M) lim else 0
    }

    // ---- HUD window (same display pick and window handling as the rev bar) ----
    private fun findHud(): Display? {
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        return on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } }
    }

    private fun side() = (hudH * p.getInt("slSize", 25).coerceIn(10, 80) / 100).coerceAtLeast(8)
    private fun lp(type: Int): WindowManager.LayoutParams {
        val s = side()
        return WindowManager.LayoutParams(s, s, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (hudW * p.getInt("slX", 88) / 100f - s / 2f).toInt(); y = (hudH * p.getInt("slY", 50) / 100f - s / 2f).toInt()
        }
    }

    private var lastShow = 0; private var lastOver = false
    private fun update(show: Int, over: Boolean) {
        if (!running) return
        lastShow = show; lastOver = over
        var v = view
        if (show <= 0) { v?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }; return }
        if (v != null) {
            val d = dm.getDisplay(hudId)
            if (!v.isAttachedToWindow || d == null || d.state != Display.STATE_ON) { removeView(); v = null }   // the HUD display went off/on: the old window is dead
        }
        if (v == null) {
            if (System.currentTimeMillis() < retryAt || Build.VERSION.SDK_INT < 30 || !Settings.canDrawOverlays(ctx)) return
            val hud = findHud() ?: return
            val sz = Point().also { hud.getRealSize(it) }
            hudW = sz.x; hudH = sz.y; hudId = hud.displayId
            val dctx = ctx.createDisplayContext(hud)
            val ovl = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val type = BlindSpotHud.WIN_TYPES[BlindSpotHud.winTypeIndex(p)]
            var wctx = try { dctx.createWindowContext(type, null) } catch (_: Throwable) { try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx } }
            var nv = SignView(wctx)
            try { wctx.getSystemService(WindowManager::class.java).addView(nv, lp(type)) }
            catch (_: Throwable) {   // a refused window type leaves its view half-registered: retry with the plain overlay and a fresh view
                try {
                    wctx = try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx }
                    nv = SignView(wctx)
                    wctx.getSystemService(WindowManager::class.java).addView(nv, lp(ovl))
                } catch (e2: Throwable) {
                    Logger.log(ctx, "speedlimit: addView failed: ${e2.javaClass.simpleName} ${e2.message ?: ""}")
                    retryAt = System.currentTimeMillis() + 10_000
                    return
                }
            }
            view = nv; v = nv
            Logger.log(ctx, "speedlimit: on (display ${hud.displayId})")
            HudLayer.added(p, "lim")
        }
        v.visibility = View.VISIBLE
        v.value = show; v.setGlow(over)
        // the editor moved or resized it: re-place the window
        val w = v.layoutParams as? WindowManager.LayoutParams ?: return
        val n = lp(w.type)
        if (w.width != n.width || w.x != n.x || w.y != n.y) {
            w.width = n.width; w.height = n.height; w.x = n.x; w.y = n.y
            try { v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, w) } catch (_: Throwable) {}
        }
    }

    /** Plain-text diagnostics of what the car knows about the road (the "Road info text" switch in the floating ring, HUD group). */
    private fun debugText(pos: Pair<Double, Double>?): String? {
        if (!p.getBoolean("slDebug", false)) return null
        val f = fix; val r = dbgRoad
        val road = if (r == null) "no road loaded" else (r.name.ifEmpty { "(unnamed)" } + (if (dbgDist > MAX_SNAP_M) "  [too far, " + dbgDist.toInt() + " m]" else "  " + dbgDist.toInt() + " m"))
        val gps = if (pos == null) "NO POSITION" else String.format(java.util.Locale.US, "%.5f, %.5f", pos.first, pos.second) + if (f != null) "  age " + (System.currentTimeMillis() - f.time) / 1000 + "s  +-" + f.accuracy.toInt() + "m" else "  (no live fix)"
        val ago = if (lastLoadAt == 0L) "" else "  " + (System.currentTimeMillis() - lastLoadAt) / 1000 + "s ago"
        return "ROAD  $road\nHEADING  " + (heading0()?.let { it.toInt().toString() + " deg" } ?: "unknown") + "\nOSM maxspeed  " + (if (r == null) "-" else if (r.raw.isEmpty()) "NOT TAGGED" else r.raw) + "   shown: " + (if (limit > 0) "$limit" else "none") +
            "\nSPEED  " + speedKmh().toInt() + " km/h\nGPS  $gps\nLOOKUP  $lastLoad$ago"
    }

    private fun heading0(): Double? = fix?.takeIf { it.hasBearing() && it.speed > 2.5f }?.bearing?.toDouble() ?: calcBearing.takeIf { !it.isNaN() }

    private fun debugView(text: String?) {
        if (text == null) { dbgView?.let { try { it.context.getSystemService(WindowManager::class.java).removeView(it) } catch (_: Throwable) {} }; dbgView = null; return }
        val v = dbgView ?: android.widget.TextView(ctx).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0xB0000000.toInt()); setPadding(24, 16, 24, 16); textSize = 18f; typeface = android.graphics.Typeface.MONOSPACE
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 40; y = 120 }
            try { ctx.getSystemService(WindowManager::class.java).addView(this, lp) } catch (_: Throwable) { return }
            dbgView = this
        }
        v.text = text
    }

    private fun removeView() {
        debugView(null)
        val v = view ?: return
        view = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }
}

/** Round sign: white disc, red ring, black number; [setGlow] adds a pulsing red halo (drawn in the margin, the sign itself is 80% of the window). */
class SignView(c: Context) : View(c) {
    var value = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    private var glow = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun setGlow(on: Boolean) { if (on != glow) { glow = on; invalidate() } }
    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat(); val c = s / 2f; val r = c * 0.8f
        paint.style = Paint.Style.FILL
        if (glow) {
            val k = 0.6f + 0.4f * Math.sin(System.currentTimeMillis() / 160.0).toFloat()
            paint.shader = android.graphics.RadialGradient(c, c, c, intArrayOf(Color.argb((255 * k).toInt(), 255, 40, 40), Color.argb((120 * k).toInt(), 255, 40, 40), Color.TRANSPARENT), floatArrayOf(r / c * 0.9f, r / c + (1f - r / c) * 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP)
            cv.drawCircle(c, c, c, paint); paint.shader = null
            postInvalidateOnAnimation()
        }
        paint.color = Color.RED; cv.drawCircle(c, c, r, paint)
        paint.color = Color.WHITE; cv.drawCircle(c, c, r * 0.72f, paint)
        paint.color = Color.BLACK; paint.textAlign = Paint.Align.CENTER; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val t = value.toString()
        paint.textSize = r * 2f * (if (t.length >= 3) 0.34f else 0.46f)
        cv.drawText(t, c, c - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}
