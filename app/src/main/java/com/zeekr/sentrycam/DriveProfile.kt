package com.zeekr.sentrycam

import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/**
 * Driving profiles: when the car's drive mode changes, set that mode's ambient colour and engine-sound style.
 * Optionally also switches the car into a chosen drive mode when the ignition comes on.
 * Car API ids come from the carprobe log (IDriveMode / IAmbienceLight); writes go through ICarFunction.setFunctionValue.
 */
object DriveProfile {
    const val FUNC_DRIVE_MODE = 0x22010100
    private const val FUNC_IGNITION = 0x20259000
    private val IGN_ON = setOf(0x00200105, 0x00200107)
    /** Ambient colour functions (ARGB int): main zones, bottom zones. */
    private val FUNC_COLOR = intArrayOf(0x2a040100, 0x2a020100)
    private const val FUNC_AMBIENT_ON = 0x2a080600
    private const val FUNC_REGEN = 0x20020500
    private const val FUNC_POWER_FLOW = 0x24010100
    /** POWER_FLOW values that mean the car is regenerating (REGENERATION, _FRONT, _AWD and the engine-off / engine-on regen-brake flows). */
    private val REGEN_FLOWS = setOf(0x24010115, 0x24010116, 0x24010117, 0x2401010a, 0x2401010b, 0x2401010c)
    private const val FUNC_STEER = 0x20070800
    /** (regen level code, label), from the car's IVehicle ENERGY_REGENERATION_LEVEL_* constants (the car screen only offers these two). */
    val REGEN = listOf(0x20020502 to "Standard", 0x20020503 to "Strong")
    /** Steering feel, from IVehicle STEERING_ASSISTANCE_LEVEL_* (the car's Light / Standard / Heavy follow low / medium / high assistance codes 03 / 02 / 01 as seen in the change log). */
    val STEER = listOf(0x20070803 to "Light", 0x20070802 to "Standard", 0x20070801 to "Heavy")
    private val AMBIENT_MODE_CANDIDATES = intArrayOf(0x2a092f00, 0x2a080900, 0x2a080a00, 0x2a091d00, 0x2a092900, 0x2a092d00, 0x2a080400)

    /** (drive-mode code, label). Labels follow the existing engine-sound naming of codes 01/02/03. */
    val MODES = listOf(0x22010101 to "Comfort", 0x22010102 to "Standard", 0x22010103 to "Sport")
    val COLOR_NAMES = listOf("Keep", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Violet", "Pink", "White")
    private val COLORS = intArrayOf(0, 0xFFFF2020.toInt(), 0xFFFF8000.toInt(), 0xFFFFE000.toInt(), 0xFF20FF40.toInt(),
        0xFF00E5FF.toInt(), 0xFF2040FF.toInt(), 0xFF9040FF.toInt(), 0xFFFF40B0.toInt(), 0xFFFFFFFF.toInt())

    fun swatch(i: Int) = COLORS[i]
    /** Stored colour: "0" = keep, 1..9 = an old preset index, else the ARGB int itself. */
    fun colorOf(s: String?): Int { val v = s?.toIntOrNull() ?: 0; return if (v in 1 until COLORS.size) COLORS[v] else v }

    fun hex(code: Int) = Integer.toHexString(code)

    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    @Volatile private var running = false
    private var lastMode = -1
    private var lastIgn = -1
    private var setFn: java.lang.reflect.Method? = null
    private var getFn: java.lang.reflect.Method? = null
    private var fn: Any? = null
    private var sensor: Any? = null
    private var latestFn: java.lang.reflect.Method? = null
    private var gradient = false
    private var lastTarget = 0
    private var regenOn = false          // the braking colour is on the lights right now
    private var regenOffAt = 0L          // when regen was last seen (a short hold, so flicker does not flash the lights)
    private var preRegen = 0             // the light before the braking colour, for modes whose own colour is "keep as is"
    private var savedColor = 0   // the light before a fast colour replaced it, for modes whose own colour is "keep as is"

    fun sync(ctx: Context, on: Boolean) {
        if (on && !running) {
            running = true; lastMode = -1; lastIgn = -1
            thread = HandlerThread("driveprofile").apply { start() }
            bg = Handler(thread!!.looper).also { h ->
                h.post(object : Runnable { override fun run() { if (!running) return; try { regenTick(ctx.applicationContext) } catch (_: Throwable) {}; h.postDelayed(this, 250) } })
                h.post(object : Runnable { override fun run() { if (!running) return; try { tick(ctx.applicationContext) } catch (e: Throwable) { log(ctx, "tick ${e.javaClass.simpleName} ${e.message}") }; h.postDelayed(this, 1000) } }) }
            log(ctx, "started")
        } else if (!on && running) {
            running = false; thread?.quitSafely(); thread = null; bg = null
        }
    }

    /** Set the ambient colour right now (the colour picker lifting its finger). */
    fun previewColor(ctx: Context, argb: Int) {
        val app = ctx.applicationContext
        Thread { try { if (read(app, FUNC_AMBIENT_ON) == 0) write(app, FUNC_AMBIENT_ON, 1); FUNC_COLOR.forEach { write(app, it, argb) } } catch (e: Throwable) { log(app, "preview ${e.javaClass.simpleName} ${e.message}") } }.start()
    }

    /** Apply a mode's profile now (the Test button), whatever the car is in. */
    fun test(ctx: Context, mode: Int) {
        val app = ctx.applicationContext
        Thread { try { apply(app, mode) } catch (e: Throwable) { log(app, "test ${e.javaClass.simpleName} ${e.message}") } }.start()
    }

    /** Switch the car to a drive mode (the Test button next to "Start in"); only meant to be used while parked. */
    fun switchMode(ctx: Context, mode: Int) {
        val app = ctx.applicationContext
        Thread { try { write(app, FUNC_DRIVE_MODE, mode) } catch (e: Throwable) { log(app, "switch ${e.javaClass.simpleName} ${e.message}") } }.start()
    }

    /** The car's current drive-mode code, or null if unreadable. */
    fun currentMode(ctx: Context): Int? = read(ctx, FUNC_DRIVE_MODE)?.takeIf { it > 0 }

    private fun log(ctx: Context, m: String) = Logger.log(ctx, "profile: $m")

    private fun connect(ctx: Context): Boolean {
        if (fn != null && setFn != null) return true
        val car = BlindSpotHud.carCache ?: Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, ctx.applicationContext).also { BlindSpotHud.carCache = it }
        fn = car?.javaClass?.getMethod("getICarFunction")?.invoke(car)
        val c = fn?.javaClass ?: return false
        getFn = c.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
        setFn = c.getMethod("setFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        try {
            sensor = car?.javaClass?.getMethod("getSensorManager")?.invoke(car)
            latestFn = sensor?.javaClass?.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
        } catch (_: Throwable) {}
        return true
    }

    /** Car speed in km/h from the speed sensor (m/s), or null. */
    private fun speedKmh(ctx: Context): Float? = try {
        if (!connect(ctx)) null else (latestFn?.invoke(sensor, 0x00100100) as? Number)?.toFloat()?.takeIf { !it.isNaN() && it != 255f && it != -65535f }?.times(3.6f)
    } catch (_: Throwable) { null }

    private fun read(ctx: Context, id: Int): Int? = try { if (connect(ctx)) (getFn!!.invoke(fn, id) as? Number)?.toInt() else null } catch (_: Throwable) { null }

    private fun write(ctx: Context, id: Int, value: Int) {
        if (!connect(ctx)) { log(ctx, "no car api"); return }
        if (id in FUNC_COLOR) fadeToken++   // a direct colour write cancels a fade in progress (other writes, like steering or the rev bar, must not)
        val before = read(ctx, id)
        val ok = try { setFn!!.invoke(fn, id, value) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName + " " + (e.cause ?: e).message }
        Thread.sleep(150)
        log(ctx, "set 0x${hex(id)} ${before?.let { "0x" + hex(it) }} -> 0x${hex(value)} returned $ok, now ${read(ctx, id)?.let { "0x" + hex(it) }}")
    }

    const val REV_SIZE = 12
    const val REV_GAP = 20
    const val REGEN_BAR_DEF = 0xFF00E65A.toInt()
    /** App defaults for a mode's rev bar: Comfort has it off; Standard on with the Ice colours; Sport on with the Sport colours; bottom edge, slanted. */
    fun revDef(code: Int, key: String): String = when (key) {
        "pfRev" -> if (code == 0x22010101) "1" else "2"   // 0 keep, 1 off, 2 on
        "pfRevPos" -> "1"                                  // bottom edge
        "pfRevShape" -> "2"                                // slanted
        "pfRevScheme" -> if (code == 0x22010103) "3" else "2"   // Sport / Ice
        else -> "0"
    }

    /** The mode's rev bar choice (0 keep, 1 off, 2 on with its own look) copied into the rev bar settings; the service is poked so the bar starts or stops. */
    private fun applyRevBar(ctx: Context, p: android.content.SharedPreferences, code: Int) {
        val h = hex(code)
        fun v(key: String) = p.getString("${key}_$h", revDef(code, key))?.toIntOrNull() ?: 0
        val mode = v("pfRev")
        if (mode == 0) return
        val e = p.edit().putBoolean("revOn", mode == 2)
        if (mode == 2) e.putInt("revPos", v("pfRevPos")).putInt("revShape", v("pfRevShape")).putInt("revScheme", v("pfRevScheme"))
            .putInt("revSize", p.getInt("pfRevSize_$h", REV_SIZE)).putInt("revInset", p.getInt("pfRevInset_$h", REV_GAP))
            .putBoolean("revRegen", p.getBoolean("pfRevRegen_$h", false)).putInt("revRegenColor", colorOf(p.getString("pfRevRegenColor_$h", REGEN_BAR_DEF.toString())).let { if (it == 0) REGEN_BAR_DEF else it })
        e.apply()
        try { ctx.startService(android.content.Intent(ctx, CamService::class.java)) } catch (t: Throwable) { log(ctx, "rev bar sync ${t.javaClass.simpleName}") }
        log(ctx, "rev bar ${if (mode == 2) "on" else "off"}")
    }

    /** A rev bar setting of [mode]'s profile was edited: if that is the mode the car is in, show it on the HUD at once (with the bar's short preview). */
    fun revEdited(ctx: Context, mode: Int) {
        val app = ctx.applicationContext
        Thread {
            try {
                if (currentMode(app).let { it == null || it == mode }) {
                    val p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    applyRevBar(app, p, mode)
                    p.edit().putLong("revPreviewUntil", System.currentTimeMillis() + 4_000).apply()
                }
            } catch (e: Throwable) { log(app, "rev edit ${e.javaClass.simpleName} ${e.message}") }
        }.start()
    }

    /** Make the engine sound match a mode's profile right away (picking a sound in the profile card), without touching the light. */
    fun previewSound(ctx: Context, mode: Int) {
        val app = ctx.applicationContext
        Thread { try { apply(app, mode, colour = false) } catch (e: Throwable) { log(app, "preview sound ${e.javaClass.simpleName} ${e.message}") } }.start()
    }

    private fun apply(ctx: Context, mode: Int, colour: Boolean = true) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val h = hex(mode)
        val argb = colorOf(p.getString("pfColor_$h", "0"))
        val show = argb
        if (colour && show != 0) {
            // ambient mode candidates (mono vs effect): log only until the right id is known
            log(ctx, "ambient modes " + AMBIENT_MODE_CANDIDATES.joinToString(" ") { "0x${hex(it)}=${read(ctx, it)}" })
            if (read(ctx, FUNC_AMBIENT_ON) == 0) write(ctx, FUNC_AMBIENT_ON, 1)
            // a drive-mode switch slides to the new colour instead of jumping
            // (the car itself snaps the light when the mode changes, so start from the colour seen just before the switch)
            val cur = stableColor.takeIf { it != 0 } ?: (read(ctx, FUNC_COLOR[0]) ?: 0)
            if (cur != 0 && cur != show && bg != null) { fadeTo(cur, show, 1500L); shown = show } else FUNC_COLOR.forEach { write(ctx, it, show) }
        }
        if (colour) p.getString("pfRegen_$h", "0")?.toIntOrNull()?.takeIf { it != 0 }?.let { write(ctx, FUNC_REGEN, it) }
        if (colour) p.getString("pfSteer_$h", "0")?.toIntOrNull()?.takeIf { it != 0 }?.let { write(ctx, FUNC_STEER, it) }
        if (colour) applyRevBar(ctx, p, mode)
        val style = p.getString("pfSound_$h", "") ?: ""
        // silent profile = mute only (the audio outputs stay open, nothing is torn down or restarted)
        if (p.getBoolean("pfMute", false) != (style == "off")) { p.edit().putBoolean("pfMute", style == "off").apply(); log(ctx, if (style == "off") "engine sound muted" else "engine sound unmuted") }
        if (style == "off") return
        if (style.isNotEmpty() && style != p.getString("engStyle", "typer")) {
            p.edit().putString("engStyle", style).apply()
            try { com.zeekr.sentrycam.sound.DriveSound.refresh(ctx) } catch (_: Throwable) {}
            log(ctx, "sound style -> $style")
        }
    }

    private fun tick(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val profiles = p.getBoolean("pfOn", false)
        val ign = read(ctx, FUNC_IGNITION) ?: -1
        val mode = read(ctx, FUNC_DRIVE_MODE) ?: -1
        val ignOn = ign in IGN_ON
        // ignition just came on (not on the first read, so starting the app mid-drive changes nothing): switch to the chosen start mode
        if (profiles && ignOn && lastIgn != -1 && lastIgn !in IGN_ON) {
            val start = (p.getString("pfStart", "0") ?: "0").toIntOrNull() ?: 0
            if (start != 0 && start != mode) { log(ctx, "ignition on, starting in 0x${hex(start)}"); write(ctx, FUNC_DRIVE_MODE, start) }
        }
        lastIgn = ign
        if (mode > 0 && mode != lastMode && MODES.any { it.first == mode }) {
            log(ctx, "drive mode 0x${hex(mode)}")
            lastMode = mode
            gradient = false; lastTarget = 0
            if (profiles) apply(ctx, mode)
        } else if (mode > 0) lastMode = mode
        if (profiles && lastMode > 0 && !regenOn) speedColour(ctx, p, lastMode)
    }

    /** Fast loop (4 times a second): while the car regenerates and the mode has "Change the ambient colour while regen braking" ticked, show the braking colour; restore afterwards. */
    private fun regenTick(ctx: Context) {
        // notice a drive-mode switch within 250 ms (and remember the light from before it, which the car overwrites)
        val m = read(ctx, FUNC_DRIVE_MODE) ?: -1
        if (m > 0 && m != lastMode && MODES.any { it.first == m }) { try { tick(ctx) } catch (_: Throwable) {} }
        else if (m == lastMode) stableColor = read(ctx, FUNC_COLOR[0]) ?: stableColor
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val h = if (lastMode > 0) hex(lastMode) else ""
        val want = p.getBoolean("pfOn", false) && h.isNotEmpty() && p.getBoolean("pfRegenCol_$h", false)
        val now = System.currentTimeMillis()
        val regen = want && (read(ctx, FUNC_POWER_FLOW) ?: 0) in REGEN_FLOWS
        if (regen) regenOffAt = now
        if (regen && !regenOn) {
            regenOn = true
            preRegen = read(ctx, FUNC_COLOR[0]) ?: 0
            if (read(ctx, FUNC_AMBIENT_ON) == 0) write(ctx, FUNC_AMBIENT_ON, 1)
            val c = colorOf(p.getString("pfRegenColor_$h", 0xFFFF2020.toInt().toString())).takeIf { it != 0 } ?: 0xFFFF2020.toInt()
            fadeTo(preRegen, c, 500L)
            log(ctx, "regen braking colour on")
        } else if (regenOn && (!want || now - regenOffAt > 700)) {
            regenOn = false
            val base = colorOf(p.getString("pfColor_$h", "0"))
            val kind = p.getString("pfSpd_$h", "0")
            if (kind == "1" || kind == "2") lastTarget = -1                      // the speed colour rewrites itself on the next tick
            else (if (base != 0) base else preRegen).takeIf { it != 0 }?.let { c -> fadeTo(0, c, 1200L) }
            log(ctx, "regen braking colour off")
        }
    }

    @Volatile private var stableColor = 0   // the light as of the last 250 ms check with an unchanged drive mode
    @Volatile private var fadeToken = 0
    private var shown = 0   // the colour the lights were last set to by a fade
    /** Slides the ambient lights from [from] (0 = last shown) to [to] in small steps instead of jumping. Background thread only. */
    private fun fadeTo(from: Int, to: Int, ms: Long = 900L) {
        val a = if (from != 0) from else if (shown != 0) shown else to
        val token = ++fadeToken
        val steps = 15
        for (i in 1..steps) {
            bg?.postDelayed({
                if (token == fadeToken && running) { val c = mix(a, to, i / steps.toFloat()); shown = c; FUNC_COLOR.forEach { setNow(it, c) } }
            }, ms * i / steps)
        }
    }

    private fun setNow(id: Int, value: Int) { try { setFn?.invoke(fn, id, value) } catch (_: Throwable) {} }

    /**
     * Colour follows speed: blue when slow blending to red at the chosen speed, in 12 steps (so the car is not flooded with writes).
     * Stopped (under 3 km/h) shows the mode's own ambient colour.
     */
    private fun speedColour(ctx: Context, p: android.content.SharedPreferences, mode: Int) {
        val h = hex(mode)
        val kind = p.getString("pfSpd_$h", "0")
        if (kind != "1" && kind != "2") { if (gradient) { gradient = false; lastTarget = 0; apply(ctx, mode) }; return }
        val v = speedKmh(ctx) ?: return
        val base = colorOf(p.getString("pfColor_$h", "0"))
        if (!gradient) { gradient = true; savedColor = if (base == 0) read(ctx, FUNC_COLOR[0]) ?: 0 else 0 }
        if (kind == "2") {
            // same colour, brightness follows speed: dim when slow/stopped, full at the chosen speed
            val c0 = if (base != 0) base else savedColor
            if (c0 == 0) return
            val fullB = p.getInt("pfSpeed_$h", 120).coerceIn(60, 200)
            val tb = Math.round((v / fullB).coerceIn(0f, 1f) * 12f) / 12f
            val tgt = scale(c0, 0.12f + 0.88f * tb)
            if (tgt != lastTarget) { val prev = if (lastTarget != 0 && lastTarget != -1) lastTarget else shown; lastTarget = tgt; if (prev != 0) fadeTo(prev, tgt) else FUNC_COLOR.forEach { write(ctx, it, tgt) } }
            return
        }
        val slow = colorOf(p.getString("pfSlow_$h", "0")).takeIf { it != 0 } ?: 0xFF2040FF.toInt()
        val fastC = colorOf(p.getString("pfFastC_$h", "0")).takeIf { it != 0 } ?: 0xFFFF2020.toInt()
        val full = p.getInt("pfSpeed_$h", 120).coerceIn(60, 200)
        val target = if (v < 3f) (if (base != 0) base else savedColor)
        else { val t = Math.round(((v - 3f) / (full - 3f)).coerceIn(0f, 1f) * 12f) / 12f; mix(slow, fastC, t) }
        if (target != 0 && target != lastTarget) {
            val prev = if (lastTarget != 0 && lastTarget != -1) lastTarget else shown; lastTarget = target
            if (prev != 0) fadeTo(prev, target) else FUNC_COLOR.forEach { write(ctx, it, target) }
        }
    }

    private fun scale(c: Int, f: Float): Int {
        fun ch(s: Int) = Math.round(((c shr s) and 0xFF) * f).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = Math.round(((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
