package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Quick toggles around the floating button: press and hold it and a ring of labelled circles opens, each one a switch
 * from the app's settings (engine sound, rev bar, web page on the HUD ...). Tap a circle to flip it (the ring stays open),
 * tap anywhere else to close. Writes the same prefs the settings screen does and nudges CamService to pick them up.
 */
object FloatQuick {
    private const val DRIVE = "@drive"   // not a switch: hovering it opens a third layer with the drive modes
    private const val APP = "@app"       // not a switch either: lifting on it opens the Z Cam app
    private const val VOL = "@vol"       // not a switch: hovering it opens arc sliders for the cabin and outside volume
    private const val DIR = "@dir"       // not a switch either: lifting on it flips the engine sound between the cabin and the outside speaker
    private const val PA = "@pa"         // not a switch: hovering it broadcasts the microphone live to the outside speaker until the finger leaves
    private const val HUD = "@hud"       // group sectors: hovering one opens a second layer with its switches
    private const val HCAM = "@hcam"
    private const val LIVE = "@live"
    private fun isAction(key: String) = key.startsWith("@")
    private class Item(val key: String, val label: String)
    private val ITEMS = listOf(
        Item("engOn", "Engine sound"), Item("mcOn", "Zed"), Item(VOL, "Volume"), Item(DIR, "Sound direction"), Item(PA, "Announce outside"), Item(HUD, "HUD"),
        Item(DRIVE, "Acceleration mode"),
        Item(LIVE, "Floating windows"),
        Item(APP, "Open Z Cam"))
    private val GROUPS = mapOf(
        HUD to listOf(Item("revOn", "Rev bar"), Item("webHudOn", "Web on HUD"), Item("bsHud", "HUD side cam"), Item("bsSignal", "Turn arrow on HUD"), Item("slOn", "Speed limit on HUD"), Item("slBeep", "Speed limit sound"), Item("slDebug", "Road info text"), Item("mmOn", "Drive mode on HUD")),
        LIVE to listOf(Item("flLeft", "Left window"), Item("flRight", "Right window"), Item("flRear", "Rear window"), Item("flFront", "Front window"), Item("webFloatOn", "Web window"), Item("twOn", "Trip widget")))
    private const val IDLE_MS = 7_000L

    private val main = Handler(Looper.getMainLooper())
    private var view: Ring? = null
    private var appCtx: Context? = null
    private val closer = Runnable { close() }
    // Flips in quick succession are coalesced into one service nudge.
    private val nudge = Runnable {
        val c = appCtx ?: return@Runnable
        val i = Intent(c, CamService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
    }
    private fun syncSoon() { main.removeCallbacks(nudge); main.postDelayed(nudge, 500L) }

    /** Finger still down after the long press: highlight the circle under it. */
    fun hover(x: Float, y: Float) {
        val v = view ?: return
        main.removeCallbacks(closer); main.postDelayed(closer, IDLE_MS)   // still busy with the ring: do not close it under the finger
        v.hoverAt(x, y)
    }
    /** Finger lifted at ([x], [y]): flip the circle under it, if any and close the ring. */
    fun release(x: Float, y: Float) { if (view?.releaseAt(x, y) != true) close() }

    fun showing() = view != null

    fun close() {
        val v = view ?: return
        view = null
        LivePa.stop()
        main.removeCallbacks(closer)
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    /** Opens the ring around the button centred at ([cx], [cy]) screen pixels, [r] its radius. */
    fun show(ctx: Context, cx: Int, cy: Int, r: Int) {
        if (view != null) return
        appCtx = ctx.applicationContext
        val v = Ring(ctx, cx.toFloat(), cy.toFloat(), r.toFloat())
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)   // covers the whole screen, bars included, so no sector is cut off
        try { ctx.getSystemService(WindowManager::class.java).addView(v, lp); view = v } catch (e: Throwable) { Logger.log(ctx, "floatquick: addView failed ${e.message ?: ""}"); return }
        main.postDelayed(closer, IDLE_MS)
    }

    /**
     * The ring as a layered pie around the button: the first layer is one sector per switch (drawn icon, lime when on), centred
     * on the button and spread over whatever part of the screen is free. Touching the Drive mode sector fans out a second,
     * outer layer of drive-mode sectors right beyond it. Slide to a sector, lift to choose; the name shows at the top.
     */
    @SuppressLint("ViewConstructor")
    private class Ring(c: Context, val bx: Float, val by: Float, val br: Float) : View(c) {
        private val d = c.resources.displayMetrics.density * 1.2f   // the whole ring is 20% bigger than the base size
        private val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        private val pal = ZTheme.palette(ZTheme.Mode.of(p.getString("theme", null)))
        private val dim = Paint().apply { color = if (pal.light) 0x88FFFFFF.toInt() else 0xAA000000.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; typeface = ZFont.reg }
        private val arcTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; isFakeBoldText = true; typeface = ZFont.reg }
        private val edge = if (pal.light) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt()   // white outlines (grey on the light theme, where white would vanish)
        private val loc = IntArray(2)
        private val n = ITEMS.size
        private val driveIdx = ITEMS.indexOfFirst { it.key == DRIVE }
        private fun flag(key: String) = p.getBoolean(key, key == "mcOn")   // Zed is on unless switched off
        private fun grp(i: Int): List<Item>? = if (i in 0 until n) GROUPS[ITEMS[i].key] else null
        private var gopen = -1                      // first-layer index of the group sector whose switches are showing
        private val g0 = DoubleArray(n); private val gSweep = DoubleArray(n) { 30.0 }
        private val volIdx = ITEMS.indexOfFirst { it.key == VOL }
        private val paIdx = ITEMS.indexOfFirst { it.key == PA }
        private var looping = false
        private fun redrawLoop() { if (LivePa.live() || hover == paIdx) { invalidate(); main.postDelayed({ redrawLoop() }, 80L) } else looping = false }
        private val modes = DriveProfile.MODES.reversed()   // the ring runs clockwise, so this puts Comfort on the left and Sport on the right
        private val curMode: Int? = try { DriveProfile.currentMode(c) } catch (_: Throwable) { null }
        private var hover = -1                      // 0 until n = first layer, n + j = drive mode j
        private var lockBand = -1                   // the volume arc the finger is dragging: it stays on that arc (and the ring stays still) until lift
        private var vopen = false                   // the volume arcs are showing (VOL sector touched)
        private var open = false                    // second layer stays open once the Drive mode sector was touched, until another first-layer sector is hovered

        private val r0 = br + 10f * d
        private val r1 = r0 + 76f * d               // first layer outer edge
        private val r2a = r1 + 4f * d
        private val r2 = r2a + 76f * d              // second layer outer edge
        private val grow = 8f * d
        private val a0 = FloatArray(n); private var sweep1 = 0f      // first layer sector starts / sweep (degrees, canvas convention)
        private val b0 = FloatArray(modes.size); private var sweep2 = 0f
        private var laid = false
        private var vlo = 0.0; private var vspan = 120.0   // volume arcs: start angle and span (degrees)
        // three arcs, inner to outer: engine sound in the cabin, the web browser, engine sound outside
        private val bandThick = 46f * d
        private val bandIn = FloatArray(3) { r2a + it * (bandThick + 4f * d) }; private val bandOut = FloatArray(3) { bandIn[it] + bandThick }
        private val bandKey = arrayOf("engVol", "webVol", "engExtVol"); private val bandMin = intArrayOf(-30, 0, 0); private val bandMax = intArrayOf(12, 100, 36); private val bandDef = intArrayOf(0, 100, 18)
        private val bandName = arrayOf("Engine sound, cabin", "Web browser volume", "Engine sound, outside")
        private fun bandVal(b: Int): Int = p.getInt(bandKey[b], bandDef[b])
        private fun bandText(b: Int): String { val v = bandVal(b); return if (b == 1) "$v%" else (if (v > 0) "+" else "") + v + " dB" }
        private val bandShort = arrayOf("CABIN", "WEB", "EXTERIOR")   // drawn along each arc so its channel reads at a glance
        // Which arcs show, inner to outer. Web always; the engine arc follows the sound direction:
        // cabin plays the rev sound inside -> show only the cabin arc; outside -> show only the exterior arc.
        private fun visBands(): IntArray = if (outside()) intArrayOf(2, 1) else intArrayOf(0, 1)

        private fun norm(a: Double): Double { var x = a % 360.0; if (x < 0) x += 360.0; return x }

        // usable angles (degrees) at radius [rad]: the point is on screen with a margin
        private fun usable(rad: Float): BooleanArray {
            val dm = resources.displayMetrics; val m = 8f * d
            return BooleanArray(360) { i ->
                val a = Math.toRadians(i.toDouble())
                val x = bx + rad * Math.cos(a); val y = by + rad * Math.sin(a)
                x >= m && x <= dm.widthPixels - m && y >= m && y <= dm.heightPixels - m
            }
        }

        // the contiguous usable run around [near] degrees (or the longest one): returns start and length, or null when all 360 are usable
        private fun run(ok: BooleanArray, near: Double?): Pair<Double, Double>? {
            if (ok.all { it }) return null
            if (ok.none { it }) return Pair(near ?: 0.0, 0.0)
            var best: Pair<Double, Double>? = null; var bestScore = Double.MAX_VALUE
            // start scanning just after an unusable angle so runs never wrap mid-scan
            val first = (0 until 360).first { !ok[it] }
            var i = 1
            while (i <= 360) {
                val idx = (first + i) % 360
                if (ok[idx]) {
                    var len = 0
                    while (len < 360 && ok[(idx + len) % 360]) len++
                    val score = if (near == null) -len.toDouble() else {
                        val mid = norm(idx + len / 2.0); val dd = Math.abs(norm(mid - near + 180.0) - 180.0)
                        if (len < 60) 1000.0 + dd else dd
                    }
                    if (score < bestScore) { bestScore = score; best = Pair(idx.toDouble(), len.toDouble()) }
                    i += len
                } else i++
            }
            return best
        }

        private fun layout() {
            if (laid) return
            laid = true
            val dm = resources.displayMetrics
            val face = Math.toDegrees(Math.atan2((dm.heightPixels / 2f - by).toDouble(), (dm.widthPixels / 2f - bx).toDouble()))
            // first layer
            val full = run(usable(r1 + grow), face)
            val start: Double; val total: Double
            if (full == null) { start = -90.0; total = 360.0 } else { start = full.first; total = Math.max(full.second, 1.0) }
            sweep1 = if (full == null) 360f / n else Math.min(60.0, total / n).toFloat()
            val used = sweep1 * n
            val from = if (full == null) start else start + (total - used) / 2
            for (i in 0 until n) a0[i] = (from + sweep1 * i).toFloat()
            // second layer: centred on the Drive mode sector, kept inside the usable angles at its outer radius
            val mid = a0[driveIdx] + sweep1 / 2.0
            val r = run(usable(r2 + grow), mid)
            var w = 30.0
            var lo = mid - w * modes.size / 2
            if (r != null) {
                val inRun = norm(mid - r.first) <= r.second
                w = Math.min(w, Math.max(14.0, r.second / modes.size)).coerceAtLeast(14.0)
                val span = w * modes.size
                val off = if (inRun) norm(mid - r.first) else 0.0
                lo = r.first + (off - span / 2).coerceIn(0.0, Math.max(0.0, r.second - span))
                if (!inRun) lo = r.first
            }
            sweep2 = w.toFloat()
            for (j in modes.indices) b0[j] = (lo + w * j).toFloat()
            // group switches: second layer centred on the group's sector, same rules as the drive modes
            for (gi in 0 until n) {
                val gl = grp(gi) ?: continue
                val hm = a0[gi] + sweep1 / 2.0
                val hr = run(usable(r2 + grow), hm)
                gSweep[gi] = 30.0; g0[gi] = hm - gSweep[gi] * gl.size / 2
                if (hr != null) {
                    val inRun = norm(hm - hr.first) <= hr.second
                    gSweep[gi] = Math.min(30.0, Math.max(14.0, hr.second / gl.size)).coerceAtLeast(14.0)
                    val span = gSweep[gi] * gl.size
                    g0[gi] = if (inRun) hr.first + (norm(hm - hr.first) - span / 2).coerceIn(0.0, Math.max(0.0, hr.second - span)) else hr.first
                }
            }
            // volume arcs: up to 150 degrees, centred on the Volume sector and kept on screen
            val vm = a0[volIdx] + sweep1 / 2.0
            val vr = run(usable(bandOut[visBands().size - 1] + grow), vm)
            vspan = 150.0; vlo = vm - vspan / 2
            if (vr != null) {
                val inRun = norm(vm - vr.first) <= vr.second
                vspan = Math.min(150.0, Math.max(60.0, vr.second))
                vlo = if (inRun) vr.first + (norm(vm - vr.first) - vspan / 2).coerceIn(0.0, Math.max(0.0, vr.second - vspan)) else vr.first
            }
            Logger.log(context, "floatquick: volume arcs lo=${vlo.toInt()} span=${vspan.toInt()} usableRun=${vr?.second?.toInt()}")
        }

        private fun inSector(ang: Double, start: Float, sweep: Float) = norm(ang - start) < sweep

        private fun at(x: Float, y: Float): Int {
            layout()
            val dx = (x - bx).toDouble(); val dy = (y - by).toDouble()
            val dist = Math.hypot(dx, dy); val ang = Math.toDegrees(Math.atan2(dy, dx))
            if (gopen >= 0 && dist in (r2a - 2f * d)..(r2 + grow)) for (k in grp(gopen)!!.indices) if (inSector(ang, (g0[gopen] + gSweep[gopen] * k).toFloat(), gSweep[gopen].toFloat())) return 2000 + k
            if (vopen) { val vb = visBands(); for (s in vb.indices) if (dist in (bandIn[s] - 2f * d)..(bandOut[s] + grow)) {
                val na = norm(ang - vlo)
                if (na <= vspan + 15.0 || na >= 345.0) return 1000 + vb[s]
            } }
            if (open && dist in (r2a - 2f * d)..(r2 + grow)) for (j in modes.indices) if (inSector(ang, b0[j], sweep2)) return n + j
            if (dist in (r0 - 4f * d)..(r1 + grow)) for (i in 0 until n) if (inSector(ang, a0[i], sweep1)) return i
            return -1
        }

        // short click as hover feedback: higher for a switch that is on (or a mode), lower for one that is off
        private fun tone(hz: Double): AudioTrack {
            val rate = 44_100; val n = rate * 30 / 1000
            val pcm = ShortArray(n) { i -> (Math.sin(2 * Math.PI * hz * i / rate) * Math.exp(-i * 7.0 / n) * 9000).toInt().toShort() }
            return AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build().also { it.write(pcm, 0, n) }
        }
        private fun outside() = p.getString("engOut", "cabin") == "outside"

        // futuristic click for a drive mode switch: a rising chirp, then a short ring-modulated high blip
        private fun futureClick() = ModeClick.play()
        private var tickOn: AudioTrack? = null
        private var tickOff: AudioTrack? = null
        private fun tick(off: Boolean) {
            try {
                val t = if (off) tickOff ?: tone(900.0).also { tickOff = it } else tickOn ?: tone(1700.0).also { tickOn = it }
                t.stop(); t.reloadStaticData(); t.play()
            } catch (_: Throwable) {}
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            try { tickOn?.release(); tickOff?.release() } catch (_: Throwable) {}
            tickOn = null; tickOff = null
        }

        fun hoverAt(x: Float, y: Float) {
            if (lockBand >= 0) { setVol(lockBand, x, y); invalidate(); return }
            val h = at(x, y)
            if (h == paIdx) { if (!LivePa.live()) LivePa.start(context); if (!looping) { looping = true; main.post { redrawLoop() } } } else LivePa.stop()
            val was = open
            val wasV = vopen
            val wasG = gopen
            if (h == driveIdx) { open = true; vopen = false; gopen = -1 } else if (h == volIdx) { vopen = true; open = false; gopen = -1 } else if (grp(h) != null) { gopen = h; open = false; vopen = false } else if (h in 0 until n) { open = false; vopen = false; gopen = -1 }
            if (h in 1000 until 2000) { lockBand = h - 1000; setVol(lockBand, x, y) }
            if (h != hover && h >= 0) tick(if (h >= 2000) !p.getBoolean(grp(gopen)!![h - 2000].key, false) else h < n && !isAction(ITEMS[h].key) && !flag(ITEMS[h].key))
            if (h != hover || was != open || wasV != vopen || wasG != gopen || h >= 1000) { hover = h; invalidate() }
        }

        /** Lift/tap at the point: true = keep the ring open (the Drive mode sector only opens its modes). */
        fun releaseAt(x: Float, y: Float): Boolean {
            LivePa.stop()
            if (lockBand >= 0) { setVol(lockBand, x, y); lockBand = -1; invalidate(); return true }
            val h = at(x, y)
            if (grp(h) != null) { gopen = h; open = false; vopen = false; hover = h; invalidate(); return true }
            if (h >= 2000) { val k = grp(gopen)!![h - 2000].key; p.edit().putBoolean(k, !p.getBoolean(k, false)).apply(); syncSoon(); invalidate(); return true }
            if (h == driveIdx) { open = true; vopen = false; gopen = -1; hover = driveIdx; invalidate(); return true }
            if (h == volIdx) { vopen = true; open = false; gopen = -1; hover = volIdx; invalidate(); return true }
            if (h in 1000 until 2000) { setVol(h - 1000, x, y); return true }
            toggleAt(x, y); return false
        }

        /** Finger at ([x], [y]) over volume arc [b]: the angle along the arc sets the value (the arc ends clamp). */
        private fun setVol(b: Int, x: Float, y: Float) {
            val ang = Math.toDegrees(Math.atan2((y - by).toDouble(), (x - bx).toDouble()))
            val na = norm(ang - vlo)
            val t = if (na <= vspan) na / vspan else if (na - vspan < 360.0 - na) 1.0 else 0.0
            val v = Math.round(bandMin[b] + t * (bandMax[b] - bandMin[b])).toInt()
            if (bandVal(b) != v) { p.edit().putInt(bandKey[b], v).apply(); if (b == 1) WebHud.instance?.applyVolume() }
        }

        fun toggleAt(x: Float, y: Float) {
            val i = at(x, y)
            if (i < 0 || i == paIdx || i == driveIdx || i == volIdx || grp(i) != null || i >= 1000) return
            if (i >= n) { DriveProfile.switchMode(context, modes[i - n].first); futureClick(); return }
            if (ITEMS[i].key == DIR) { p.edit().putString("engOut", if (outside()) "cabin" else "outside").apply(); syncSoon(); return }
            if (ITEMS[i].key == APP) {
                try { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (e: Throwable) { Logger.log(context, "floatquick: open app failed ${e.message ?: ""}") }
                return
            }
            p.edit().putBoolean(ITEMS[i].key, !flag(ITEMS[i].key)).apply()
            syncSoon()
        }

        private fun sector(start: Float, sweep: Float, ri: Float, ro: Float): Path {
            val gap = 1.2f
            val st = start + gap; val sw = Math.max(1f, sweep - 2 * gap)
            return Path().apply {
                arcTo(RectF(bx - ro, by - ro, bx + ro, by + ro), st, sw)
                arcTo(RectF(bx - ri, by - ri, bx + ri, by + ri), st + sw, -sw)
                close()
            }
        }

        private fun drawSector(start: Float, sweep: Float, ri: Float, ro0: Float, on: Boolean, hot: Boolean, icon: String, off: Boolean = false) {
            val ro = if (hot) ro0 + grow else ro0
            val path = sector(start, sweep, ri, ro)
            // white sectors, black icons, no colour: a black bar along the outer rim marks "on" / the current mode, light grey under the finger
            fill.style = Paint.Style.FILL
            fill.color = if (off) (if (hot) 0xFFA8A8AE.toInt() else 0xFF8E8E93.toInt()) else if (hot) 0xFFDADADF.toInt() else 0xFFFFFFFF.toInt()   // grey = switched off
            canvasRef?.drawPath(path, fill)
            if (on) {
                val rr = ro - 7f * d; val pad = 3.5f
                line.style = Paint.Style.STROKE; line.strokeWidth = 5f * d; line.color = 0xFF111111.toInt(); line.strokeCap = Paint.Cap.BUTT
                canvasRef?.drawArc(RectF(bx - rr, by - rr, bx + rr, by + rr), start + pad, Math.max(1f, sweep - 2 * pad), false, line)
                line.strokeCap = Paint.Cap.ROUND
            }
            line.strokeWidth = (if (hot) 3.5f else 2f) * d; line.color = edge
            canvasRef?.drawPath(path, line)
            val mid = Math.toRadians(start + sweep / 2.0); val rm = (ri + ro) / 2
            val cx = (bx + rm * Math.cos(mid)).toFloat(); val cy = (by + rm * Math.sin(mid)).toFloat()
            icon(canvasRef!!, icon, cx, cy, 15f * d, if (off) 0xFF48484A.toInt() else 0xFF111111.toInt())
        }

        private var canvasRef: Canvas? = null

        /** Drawn glyphs (no text, no emoji), [h] = half size. */
        private fun icon(cv: Canvas, kind: String, cx: Float, cy: Float, h: Float, color: Int) {
            line.style = Paint.Style.STROKE; line.color = color; line.strokeWidth = 2.4f * d
            fill.style = Paint.Style.FILL; fill.color = color
            val q = Path()
            when (kind) {
                "engOn" -> {   // piston: cylinder, piston head, rod and crank
                    cv.drawRoundRect(cx - h * 0.45f, cy - h, cx + h * 0.45f, cy + h * 0.1f, 2 * d, 2 * d, line)
                    cv.drawRect(cx - h * 0.45f, cy - h * 0.35f, cx + h * 0.45f, cy - h * 0.05f, fill)
                    cv.drawLine(cx, cy - h * 0.05f, cx, cy + h * 0.45f, line)
                    cv.drawCircle(cx, cy + h * 0.7f, h * 0.3f, line)
                }
                VOL -> {   // speaker with two waves
                    q.moveTo(cx - h, cy - h * 0.35f); q.lineTo(cx - h * 0.45f, cy - h * 0.35f); q.lineTo(cx + h * 0.1f, cy - h * 0.85f)
                    q.lineTo(cx + h * 0.1f, cy + h * 0.85f); q.lineTo(cx - h * 0.45f, cy + h * 0.35f); q.lineTo(cx - h, cy + h * 0.35f); q.close()
                    cv.drawPath(q, fill)
                    cv.drawArc(RectF(cx - h * 0.4f, cy - h * 0.55f, cx + h * 0.6f, cy + h * 0.55f), -45f, 90f, false, line)
                    cv.drawArc(RectF(cx - h * 0.5f, cy - h * 1.0f, cx + h * 1.0f, cy + h * 1.0f), -45f, 90f, false, line)
                }
                HUD -> {   // windscreen with a bar on it
                    q.moveTo(cx - h, cy + h * 0.6f); q.lineTo(cx - h * 0.7f, cy - h * 0.7f); q.lineTo(cx + h * 0.7f, cy - h * 0.7f); q.lineTo(cx + h, cy + h * 0.6f); q.close()
                    cv.drawPath(q, line); cv.drawLine(cx - h * 0.45f, cy, cx + h * 0.45f, cy, line)
                }
                LIVE -> {   // screen with a play triangle
                    cv.drawRoundRect(cx - h, cy - h * 0.7f, cx + h, cy + h * 0.7f, 3 * d, 3 * d, line)
                    q.moveTo(cx - h * 0.25f, cy - h * 0.35f); q.lineTo(cx + h * 0.35f, cy); q.lineTo(cx - h * 0.25f, cy + h * 0.35f); q.close(); cv.drawPath(q, fill)
                }
                "slOn" -> {   // speed limit sign: ring with a bar inside
                    line.strokeWidth = 3.4f * d; cv.drawCircle(cx, cy, h, line); line.strokeWidth = 2.4f * d
                    cv.drawLine(cx - h * 0.4f, cy, cx + h * 0.4f, cy, line)
                }
                "slDebug" -> {   // text lines
                    cv.drawLine(cx - h, cy - h * 0.6f, cx + h, cy - h * 0.6f, line); cv.drawLine(cx - h, cy, cx + h * 0.5f, cy, line); cv.drawLine(cx - h, cy + h * 0.6f, cx + h, cy + h * 0.6f, line)
                }
                "slBeep" -> {   // speaker with one wave
                    q.moveTo(cx - h, cy - h * 0.35f); q.lineTo(cx - h * 0.45f, cy - h * 0.35f); q.lineTo(cx + h * 0.1f, cy - h * 0.85f)
                    q.lineTo(cx + h * 0.1f, cy + h * 0.85f); q.lineTo(cx - h * 0.45f, cy + h * 0.35f); q.lineTo(cx - h, cy + h * 0.35f); q.close()
                    cv.drawPath(q, fill)
                    cv.drawArc(RectF(cx - h * 0.4f, cy - h * 0.55f, cx + h * 0.6f, cy + h * 0.55f), -45f, 90f, false, line)
                }
                "mmOn" -> {   // three rising bars (Comfort / Standard / Sport)
                    line.strokeWidth = 3.4f * d
                    cv.drawLine(cx - h * 0.7f, cy + h * 0.8f, cx - h * 0.7f, cy + h * 0.1f, line)
                    cv.drawLine(cx, cy + h * 0.8f, cx, cy - h * 0.3f, line)
                    cv.drawLine(cx + h * 0.7f, cy + h * 0.8f, cx + h * 0.7f, cy - h * 0.8f, line)
                    line.strokeWidth = 2.4f * d
                }
                "bsSignal" -> {   // turn arrow
                    cv.drawLine(cx - h, cy, cx + h * 0.3f, cy, line); q.moveTo(cx + h * 0.2f, cy - h * 0.6f); q.lineTo(cx + h, cy); q.lineTo(cx + h * 0.2f, cy + h * 0.6f); q.close(); cv.drawPath(q, fill)
                }
                "flLeft", "flRight" -> {   // a window with an arrow to the left or right
                    cv.drawRoundRect(cx - h, cy - h * 0.8f, cx + h, cy + h * 0.8f, 3 * d, 3 * d, line)
                    val s = if (kind == "flLeft") 1f else -1f
                    q.moveTo(cx + s * h * 0.35f, cy - h * 0.4f); q.lineTo(cx - s * h * 0.35f, cy); q.lineTo(cx + s * h * 0.35f, cy + h * 0.4f); q.close(); cv.drawPath(q, fill)
                }
                "mcOn" -> {   // Zed: rounded body, visor strip with a light bar, two wheels, antenna
                    cv.drawRoundRect(cx - h, cy - h * 0.55f, cx + h, cy + h * 0.65f, 3 * d, 3 * d, line)
                    cv.drawLine(cx - h * 0.55f, cy - h * 0.05f, cx + h * 0.55f, cy - h * 0.05f, line)
                    cv.drawCircle(cx - h * 0.5f, cy + h * 0.95f, h * 0.2f, fill); cv.drawCircle(cx + h * 0.5f, cy + h * 0.95f, h * 0.2f, fill)
                    cv.drawLine(cx, cy - h * 0.55f, cx, cy - h, line)
                }
                "revOn" -> {   // gauge: open arc and a needle
                    cv.drawArc(RectF(cx - h, cy - h, cx + h, cy + h), 150f, 240f, false, line)
                    cv.drawLine(cx, cy + h * 0.1f, cx + h * 0.55f, cy - h * 0.5f, line)
                    cv.drawCircle(cx, cy + h * 0.1f, h * 0.14f, fill)
                }
                "twOn" -> {   // speedometer: open arc, tick marks, a needle
                    cv.drawArc(RectF(cx - h, cy - h, cx + h, cy + h), 150f, 240f, false, line)
                    for (a in intArrayOf(210, 270, 330)) { val r = Math.toRadians(a.toDouble()); cv.drawLine(cx + h * 0.72f * Math.cos(r).toFloat(), cy + h * 0.72f * Math.sin(r).toFloat(), cx + h * 0.9f * Math.cos(r).toFloat(), cy + h * 0.9f * Math.sin(r).toFloat(), line) }
                    cv.drawLine(cx, cy + h * 0.15f, cx + h * 0.5f, cy - h * 0.35f, line)
                    cv.drawCircle(cx, cy + h * 0.15f, h * 0.15f, fill)
                }
                "webHudOn", "webFloatOn" -> {
                    cv.drawCircle(cx, cy, h, line); cv.drawOval(RectF(cx - h * 0.45f, cy - h, cx + h * 0.45f, cy + h), line); cv.drawLine(cx - h, cy, cx + h, cy, line)
                }
                PA -> {   // microphone: capsule, holder arc, stem and base
                    cv.drawRoundRect(cx - h * 0.35f, cy - h, cx + h * 0.35f, cy + h * 0.2f, h * 0.35f, h * 0.35f, fill)
                    cv.drawArc(RectF(cx - h * 0.7f, cy - h * 0.5f, cx + h * 0.7f, cy + h * 0.55f), 0f, 180f, false, line)
                    cv.drawLine(cx, cy + h * 0.55f, cx, cy + h, line); cv.drawLine(cx - h * 0.4f, cy + h, cx + h * 0.4f, cy + h, line)
                }
                DIR -> {   // car body: a dot inside for the cabin speaker, an arrow leaving it for the outside one
                    cv.drawRoundRect(cx - h, cy - h * 0.55f, cx + h * 0.3f, cy + h * 0.55f, 3 * d, 3 * d, line)
                    if (outside()) {
                        cv.drawLine(cx + h * 0.3f, cy, cx + h, cy, line); cv.drawLine(cx + h * 0.65f, cy - h * 0.3f, cx + h, cy, line); cv.drawLine(cx + h * 0.65f, cy + h * 0.3f, cx + h, cy, line)
                    } else cv.drawCircle(cx - h * 0.35f, cy, h * 0.28f, fill)
                }
                APP -> {   // the app icon's Z and dash, black, no background (launcher icon coordinates, 108 grid)
                    val k = h / 17f; val ox = cx - 55.5f * k; val oy = cy - 52f * k
                    q.moveTo(ox + 33 * k, oy + 40 * k); q.lineTo(ox + 55 * k, oy + 40 * k); q.lineTo(ox + 33 * k, oy + 64 * k); q.lineTo(ox + 55 * k, oy + 64 * k)
                    line.strokeWidth = 7f * k; cv.drawPath(q, line)
                    line.strokeWidth = 6f * k; cv.drawLine(ox + 64 * k, oy + 64 * k, ox + 78 * k, oy + 64 * k, line)
                }
                DRIVE -> {   // steering wheel
                    cv.drawCircle(cx, cy, h, line); cv.drawCircle(cx, cy, h * 0.25f, line)
                    cv.drawLine(cx - h, cy, cx - h * 0.25f, cy, line); cv.drawLine(cx + h * 0.25f, cy, cx + h, cy, line); cv.drawLine(cx, cy + h * 0.25f, cx, cy + h, line)
                }
                HCAM, "bsHud" -> {   // camera
                    cv.drawRoundRect(cx - h, cy - h * 0.55f, cx + h, cy + h * 0.75f, 3 * d, 3 * d, line)
                    cv.drawCircle(cx, cy + h * 0.1f, h * 0.38f, line)
                    cv.drawLine(cx - h * 0.4f, cy - h * 0.55f, cx - h * 0.2f, cy - h * 0.95f, line); cv.drawLine(cx - h * 0.2f, cy - h * 0.95f, cx + h * 0.2f, cy - h * 0.95f, line)
                }
                "flRear", "flFront" -> {   // a window with an arrow: rear down, front up
                    cv.drawRoundRect(cx - h, cy - h * 0.8f, cx + h, cy + h * 0.8f, 3 * d, 3 * d, line)
                    val up = kind == "flFront"; val s = if (up) -1f else 1f
                    q.moveTo(cx - h * 0.4f, cy - s * h * 0.25f); q.lineTo(cx + h * 0.4f, cy - s * h * 0.25f); q.lineTo(cx, cy + s * h * 0.35f); q.close()
                    cv.drawPath(q, fill)
                }
                "comfort" -> {   // a car, driving slowly (one short speed dash behind it)
                    q.moveTo(cx - h * 0.55f, cy + h * 0.45f); q.lineTo(cx - h * 0.55f, cy - h * 0.05f); q.lineTo(cx - h * 0.25f, cy - h * 0.05f); q.lineTo(cx - h * 0.05f, cy - h * 0.5f)
                    q.lineTo(cx + h * 0.4f, cy - h * 0.5f); q.lineTo(cx + h * 0.65f, cy - h * 0.05f); q.lineTo(cx + h * 0.95f, cy - h * 0.05f); q.lineTo(cx + h * 0.95f, cy + h * 0.45f); q.close()
                    cv.drawPath(q, line)
                    cv.drawCircle(cx - h * 0.1f - h * 0.0f, cy + h * 0.5f, h * 0.2f, fill); cv.drawCircle(cx + h * 0.65f, cy + h * 0.5f, h * 0.2f, fill)
                    cv.drawLine(cx - h * 1.05f, cy + h * 0.1f, cx - h * 0.75f, cy + h * 0.1f, line)
                }
                "standard" -> {   // highway: road narrowing to the horizon, dashed centre line
                    cv.drawLine(cx - h * 0.95f, cy + h, cx - h * 0.2f, cy - h, line); cv.drawLine(cx + h * 0.95f, cy + h, cx + h * 0.2f, cy - h, line)
                    cv.drawLine(cx, cy + h * 0.95f, cx, cy + h * 0.55f, line); cv.drawLine(cx, cy + h * 0.2f, cx, cy - h * 0.05f, line); cv.drawLine(cx, cy - h * 0.4f, cx, cy - h * 0.55f, line)
                }
                "sport" -> {   // chequered racing flag on a pole
                    cv.drawLine(cx - h * 0.8f, cy - h, cx - h * 0.8f, cy + h, line)
                    val fw = h * 1.7f / 4; val fh = h * 1.3f / 3
                    for (r in 0 until 3) for (k in 0 until 4) if ((r + k) % 2 == 0) cv.drawRect(cx - h * 0.7f + k * fw, cy - h * 0.95f + r * fh, cx - h * 0.7f + (k + 1) * fw, cy - h * 0.95f + (r + 1) * fh, fill)
                    line.strokeWidth = 1.6f * d; cv.drawRect(cx - h * 0.7f, cy - h * 0.95f, cx - h * 0.7f + 4 * fw, cy - h * 0.95f + 3 * fh, line)
                }
                "snow" -> { for (k in 0..2) { val a = Math.toRadians(k * 60.0); cv.drawLine(cx - h * Math.cos(a).toFloat(), cy - h * Math.sin(a).toFloat(), cx + h * Math.cos(a).toFloat(), cy + h * Math.sin(a).toFloat(), line) } }
            }
        }

        /** One volume arc at display [slot] (inner to outer) editing channel [b]: white track, black fill up to
         *  the current value, its channel name along the arc, a knob at its end. */
        private fun drawBand(slot: Int, b: Int) {
            val ri = bandIn[slot]; val ro = bandOut[slot]
            val v = bandVal(b).coerceIn(bandMin[b], bandMax[b])
            val t = (v - bandMin[b]) / (bandMax[b] - bandMin[b]).toFloat()
            val cv = canvasRef!!
            fill.style = Paint.Style.FILL; fill.color = if (hover == 1000 + b) 0xFFDADADF.toInt() else 0xFFFFFFFF.toInt()
            cv.drawPath(sector(vlo.toFloat(), vspan.toFloat(), ri, ro), fill)
            fill.color = 0xFF111111.toInt()
            cv.drawPath(sector(vlo.toFloat(), Math.max(3f, (vspan * t).toFloat()), ri, ro), fill)
            line.style = Paint.Style.STROKE; line.strokeWidth = 2f * d; line.color = edge
            cv.drawPath(sector(vlo.toFloat(), vspan.toFloat(), ri, ro), line)
            drawBandLabel(b, ri, ro, t)
            val a = Math.toRadians(vlo + vspan * t); val rm = (ri + ro) / 2
            fill.color = 0xFFFFFFFF.toInt(); line.color = 0xFF111111.toInt(); line.strokeWidth = 2.5f * d
            val kx = (bx + rm * Math.cos(a)).toFloat(); val ky = (by + rm * Math.sin(a)).toFloat()
            cv.drawCircle(kx, ky, (ro - ri) / 2 - 3f * d, fill); cv.drawCircle(kx, ky, (ro - ri) / 2 - 3f * d, line)
        }

        /** Channel name curved along the arc: white where the arc is filled (black), black on the white track,
         *  so it always reads regardless of the fill level under it. */
        private fun drawBandLabel(b: Int, ri: Float, ro: Float, t: Float) {
            val cv = canvasRef!!
            val label = bandShort[b]
            val rm = (ri + ro) / 2
            val ts = (ro - ri) * 0.46f
            arcTxt.textSize = ts
            val arc = Path().apply { arcTo(RectF(bx - rm, by - rm, bx + rm, by + rm), vlo.toFloat() + 3f, vspan.toFloat() - 6f) }
            val baseOff = ts * 0.35f                 // nudge the baseline so glyphs sit centred in the band
            val filled = sector(vlo.toFloat(), Math.max(3f, (vspan * t).toFloat()), ri, ro)
            cv.save(); cv.clipPath(filled)
            arcTxt.color = 0xFFFFFFFF.toInt(); cv.drawTextOnPath(label, arc, 12f * d, baseOff, arcTxt); cv.restore()
            cv.save(); cv.clipOutPath(filled)
            arcTxt.color = 0xFF111111.toInt(); cv.drawTextOnPath(label, arc, 12f * d, baseOff, arcTxt); cv.restore()
        }

        private fun modeIcon(code: Int) = when (code) { 0x22010101 -> "comfort"; 0x22010102 -> "standard"; 0x22010103 -> "sport"; else -> "snow" }

        override fun onDraw(cv: Canvas) {
            getLocationOnScreen(loc)
            layout()
            canvasRef = cv
            cv.save(); cv.translate(-loc[0].toFloat(), -loc[1].toFloat())
            cv.drawRect(loc[0].toFloat(), loc[1].toFloat(), loc[0] + width.toFloat(), loc[1] + height.toFloat(), dim)
            // thin guide ring hugging the button
            line.style = Paint.Style.STROKE; line.strokeWidth = 2.5f * d; line.color = edge
            cv.drawCircle(bx, by, r0 - 2f * d, line)
            for ((i, it) in ITEMS.withIndex()) {
                val on = if (grp(i) != null) grp(i)!!.any { h -> p.getBoolean(h.key, false) } else !isAction(it.key) && flag(it.key)
                drawSector(a0[i], sweep1, r0, r1, on || (it.key == PA && LivePa.live()), hover == i || (i == driveIdx && open) || (i == volIdx && vopen) || (i == gopen), it.key, !isAction(it.key) && !on)
            }
            if (gopen >= 0) for ((k, it) in grp(gopen)!!.withIndex()) { val on = p.getBoolean(it.key, false); drawSector((g0[gopen] + gSweep[gopen] * k).toFloat(), gSweep[gopen].toFloat(), r2a, r2, on, hover == 2000 + k, it.key, !on) }
            if (open) for ((j, m) in modes.withIndex()) drawSector(b0[j], sweep2, r2a, r2, m.first == curMode, hover == n + j, modeIcon(m.first))
            if (vopen) { val vb = visBands(); for (s in vb.indices) drawBand(s, vb[s]) }
            // name of the sector under the finger, at the top of the screen
            val label = when {
                hover == paIdx -> if (LivePa.status.isNotEmpty()) LivePa.status else "LIVE outside  " + "|".repeat((LivePa.level * 20).toInt().coerceIn(0, 20))
                hover in 0 until n -> ITEMS[hover].label + if (ITEMS[hover].key == DIR) (if (outside()) ": Outside" else ": Cabin") else if (ITEMS[hover].key == APP || ITEMS[hover].key == VOL || grp(hover) != null) "" else if (hover != driveIdx) (if (flag(ITEMS[hover].key)) ": ON" else ": OFF") else (curMode?.let { c -> modes.firstOrNull { it.first == c }?.second?.let { m -> ": $m" } } ?: "")
                hover >= 2000 -> grp(gopen)!![hover - 2000].label + if (p.getBoolean(grp(gopen)!![hover - 2000].key, false)) ": ON" else ": OFF"
                hover >= 1000 -> bandName[hover - 1000] + ": " + bandText(hover - 1000)
                hover >= n -> modes[hover - n].second + if (modes[hover - n].first == curMode) " (current)" else ""
                else -> "Slide to a sector, lift to choose"
            }
            txt.textSize = 32f * d; txt.color = pal.fg
            val tw = txt.measureText(label); val ph = 64f * d; val py = loc[1] + 28f * d
            fill.style = Paint.Style.FILL; fill.color = pal.card
            cv.drawRoundRect(bx.coerceIn(tw / 2 + 24f * d, resources.displayMetrics.widthPixels - tw / 2 - 24f * d) - tw / 2 - 16f * d, py, bx.coerceIn(tw / 2 + 24f * d, resources.displayMetrics.widthPixels - tw / 2 - 24f * d) + tw / 2 + 16f * d, py + ph, ph / 2, ph / 2, fill)
            cv.drawText(label, bx.coerceIn(tw / 2 + 24f * d, resources.displayMetrics.widthPixels - tw / 2 - 24f * d), py + ph / 2 + 11f * d, txt)
            cv.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // the ring stays open after the finger lifted: touch down and drag again on an arc or a sector, lift to choose
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> hover(e.rawX, e.rawY)
                MotionEvent.ACTION_UP -> { main.removeCallbacks(closer); main.postDelayed(closer, IDLE_MS); if (!releaseAt(e.rawX, e.rawY)) close() }
            }
            return true
        }
    }
}
