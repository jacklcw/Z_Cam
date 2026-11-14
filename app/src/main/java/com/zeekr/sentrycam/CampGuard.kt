package com.zeekr.sentrycam

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.lang.reflect.Method

/**
 * Turns Camping mode off by itself when it was left on after getting out: camping is on, the driver and passenger seats
 * are both empty and the car is locked, continuously for the chosen number of minutes. Any doubt (a reading that is
 * missing, a seat taken, the car unlocked, the charge port open) restarts the wait, so it never cuts camping while someone may be inside.
 * Function names and the seat / lock readings are the ones Zeekr Shortcut (dts88) uses on this head unit.
 * Prefs: "campOff" (on/off, default off), "campMin" (minutes, default 15).
 */
object CampGuard {
    private const val POLL_MS = 30_000L
    private const val SEAT_DRIVER = 0x00203300
    private const val SEAT_PASSENGER = 0x00203400
    private const val CHARGE_PORT = 0x21020500   // 1 = charge port open (cable in / charging), 0 = closed
    private const val ZONE_ALL = 0x80000000.toInt()
    private val PKGS = listOf("vehicle.", "hvac.", "base.", "")

    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var emptySince = 0L
    private var lastLine = ""
    private var fn: Any? = null; private var getFn: Method? = null; private var getFnZ: Method? = null
    private var setFn: Method? = null; private var setFnZ: Method? = null
    private var sensor: Any? = null; private var getEvent: Method? = null
    private var campId: Int? = null; private var lockId: Int? = null

    fun sync(ctx: Context, safe: Boolean) {
        val on = !safe && ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("campOff", false)
        if (on) start(ctx.applicationContext) else stop()
    }

    @Synchronized private fun start(ctx: Context) {
        if (thread != null) return
        val t = HandlerThread("campguard").also { it.start() }
        thread = t; h = Handler(t.looper); emptySince = 0L
        Logger.log(ctx, "campguard: on")
        h!!.post(object : Runnable {
            override fun run() { try { tick(ctx) } catch (e: Throwable) { Logger.log(ctx, "campguard: ${e.javaClass.simpleName} ${e.message ?: ""}") }; if (thread === t) h?.postDelayed(this, POLL_MS) }
        })
    }

    @Synchronized private fun stop() {
        val t = thread ?: return
        thread = null; h = null; emptySince = 0L
        t.quitSafely()
    }

    private fun constant(iface: String, field: String): Int? {
        for (p in PKGS) try {
            val f = Class.forName("com.ecarx.xui.adaptapi.car.$p$iface").getField(field)
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType) return f.getInt(null)
        } catch (_: Throwable) {}
        return null
    }

    private fun connect(ctx: Context): Boolean {
        if (getFn != null && campId != null) return true
        val car = BlindSpotHud.carCache ?: Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, ctx).also { BlindSpotHud.carCache = it } ?: return false
        fn = car.javaClass.getMethod("getICarFunction").invoke(car)
        val c = fn?.javaClass ?: return false
        val i = Int::class.javaPrimitiveType
        getFn = c.getMethod("getFunctionValue", i); setFn = c.getMethod("setFunctionValue", i, i)
        getFnZ = try { c.getMethod("getFunctionValue", i, i) } catch (_: Throwable) { null }
        setFnZ = try { c.getMethod("setFunctionValue", i, i, i) } catch (_: Throwable) { null }
        try { sensor = car.javaClass.getMethod("getSensorManager").invoke(car); getEvent = sensor?.javaClass?.getMethod("getSensorEvent", i) } catch (_: Throwable) {}
        campId = constant("IVehicle", "SETTING_FUNC_CAMPING_MOD_SWITCH")
        lockId = constant("IVehicle", "SETTING_FUNC_CENTRAL_LOCK") ?: constant("ISafety", "SETTING_FUNC_CENTRAL_LOCK")
        Logger.log(ctx, "campguard: car api, camping id ${campId?.let { "0x" + Integer.toHexString(it) }}, lock id ${lockId?.let { "0x" + Integer.toHexString(it) }}")
        return campId != null
    }

    private fun readFn(id: Int): Int? {
        try { (getFnZ?.invoke(fn, id, ZONE_ALL) as? Number)?.toInt()?.let { return it } } catch (_: Throwable) {}
        return try { (getFn?.invoke(fn, id) as? Number)?.toInt() } catch (_: Throwable) { null }
    }

    /** Central lock reading (1 = locked), for the welcome sound; null when unreadable. */
    @Synchronized fun lockState(ctx: Context): Int? {
        if (!connect(ctx.applicationContext)) return null
        return lockId?.let { readFn(it) }
    }

    /** Any car function reading (for the welcome sound's ignition check); null when unreadable. */
    @Synchronized fun readCode(ctx: Context, id: Int): Int? {
        if (!connect(ctx.applicationContext)) return null
        return readFn(id)
    }

    /** True while someone sits in the driver's seat; null when unreadable. */
    @Synchronized fun driverSeated(ctx: Context): Boolean? {
        if (!connect(ctx.applicationContext)) return null
        return seat(SEAT_DRIVER)
    }

    /** True while the driver's or passenger's seat is occupied; false when both read empty; null when unreadable. */
    @Synchronized fun anySeated(ctx: Context): Boolean? {
        if (!connect(ctx.applicationContext)) return null
        val d = seat(SEAT_DRIVER); val q = seat(SEAT_PASSENGER)
        return if (d == true || q == true) true else if (d == null && q == null) null else false
    }

    /** True while the gear selector is in P, false in R/N/D; null when unreadable (same sensor the trip widget shows). */
    @Synchronized fun inPark(ctx: Context): Boolean? {
        if (!connect(ctx.applicationContext)) return null
        return try {
            when ((getEvent?.invoke(sensor, 0x00200200) as? Number)?.toInt()) { 0x00200230 -> true; 0x00200240, 0x00200210, 0x00200220 -> false; else -> null }
        } catch (_: Throwable) { null }
    }

    private fun seat(id: Int): Boolean? = try {
        when (((getEvent?.invoke(sensor, id) as? Number)?.toInt() ?: -1) and 0xFF) { 1 -> false; 2 -> true; else -> null }   // true = occupied
    } catch (_: Throwable) { null }

    private fun tick(ctx: Context) {
        if (!connect(ctx)) return
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val camping = readFn(campId!!) == 1
        val drv = seat(SEAT_DRIVER); val pas = seat(SEAT_PASSENGER)
        val lockRaw = lockId?.let { readFn(it) }
        val locked = lockRaw == 1
        val port = readFn(CHARGE_PORT)
        val line = "camping=$camping driverSeat=$drv passengerSeat=$pas lock=$lockRaw chargePort=$port"
        if (line != lastLine) { lastLine = line; Logger.log(ctx, "campguard: $line") }
        // Someone may be sleeping in the rear while it charges: never cut while the charge port is open (or unreadable).
        val empty = camping && drv == false && pas == false && locked && port == 0
        val now = System.currentTimeMillis()
        if (!empty) { emptySince = 0L; return }
        if (emptySince == 0L) { emptySince = now; Logger.log(ctx, "campguard: nobody inside, car locked, waiting"); return }
        val waitMs = p.getInt("campMin", 15).coerceIn(1, 120) * 60_000L
        if (now - emptySince < waitMs) return
        emptySince = 0L
        var ok = try { setFnZ?.invoke(fn, campId!!, ZONE_ALL, 0) } catch (_: Throwable) { null }
        if (ok != true) ok = try { setFn?.invoke(fn, campId!!, 0) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
        Thread.sleep(1000)
        val now0 = readFn(campId!!)
        Logger.log(ctx, "campguard: camping off after ${waitMs / 60000} min empty, returned $ok, now $now0")
        if (now0 == 0) {   // tell the relay so ZDash can push a notification to the phone
            val base = p.getString("url", "").orEmpty().trimEnd('/'); val token = p.getString("token", "")
            if (base.isNotEmpty()) try { Net.postJson("$base/cam/campguard/fired", token, org.json.JSONObject().put("min", waitMs / 60000)) } catch (_: Throwable) {}
        }
    }

    private const val PRE_CAMP_ID = 0x20110100
    private fun preName(id: Int): String {
        for (p in PKGS) for (iface in listOf("IVehicle", "ISafety", "ICarFunction", "IDrive", "IBcm", "IBody", "ILight", "IComfort", "ISetting"))
            try { for (f in Class.forName("com.ecarx.xui.adaptapi.car.$p$iface").fields) if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType && f.getInt(null) == id) return "$iface.${f.name}" } catch (_: Throwable) {}
        return "0x" + Integer.toHexString(id)
    }
    private var dumped2 = false
    /** One-off diagnostics for finding the proper "start camping" path: every API constant with CAMP in its name (value + current reading), and nearby function ids. */
    private fun dumpCampInfo(ctx: Context) {
        if (dumped2) return; dumped2 = true
        try {
            for (p in PKGS) for (iface in listOf("IVehicle", "ISafety", "ICarFunction", "IDrive", "IBcm", "ICar", "IBody", "IComfort", "IAirConditioner", "ISetting", "ILight", "IChassis", "ISuspension", "IPower", "ISeat", "IDoor", "IWindow", "ICem", "IHvac", "IClimate", "ISunroof", "ICharge", "IBattery", "IInfotainment"))
                try {
                    val c = Class.forName("com.ecarx.xui.adaptapi.car.$p$iface")
                    for (f in c.fields) if (Regex("CAMP|ONE_KEY|SLEEP|REST_|LIE_|BED|PICNIC|TENT|OUTING|REMOTE_V", RegexOption.IGNORE_CASE).containsMatchIn(f.name) && java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type == Int::class.javaPrimitiveType) {
                        val id = f.getInt(null)
                        Logger.log(ctx, "campinfo: $p$iface.${f.name} = 0x${Integer.toHexString(id)} reads ${readFn(id)}")
                    }
                } catch (_: Throwable) {}
            val base = campId ?: return
            val sb = StringBuilder("campinfo: ids near 0x${Integer.toHexString(base)}:")
            for (i in -2..12) { val id = base + i; sb.append(" 0x${Integer.toHexString(id)}=${readFn(id)}") }
            Logger.log(ctx, sb.toString())
            Logger.log(ctx, "campinfo: ICarFunction methods: " + (fn?.javaClass?.methods?.map { it.name }?.distinct()?.sorted()?.joinToString(",") ?: "-"))
        } catch (e: Throwable) { Logger.log(ctx, "campinfo: ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    /** The car's real Camping switch (null when unreadable). */
    @Synchronized fun campingState(ctx: Context): Boolean? {
        if (!connect(ctx.applicationContext)) return null
        return readFn(campId!!)?.let { it == 1 }
    }

    /** Remote Camping on/off from the dashboard. True when the car now reads the wanted state. */
    @Synchronized fun setCamping(ctx: Context, on: Boolean): Boolean {
        if (!connect(ctx.applicationContext)) { Logger.log(ctx, "campguard: remote set: car api not available"); return false }
        val v = if (on) 1 else 0
        // Starting Camping from the car's screen first flips 0x20110100 from 1 to 0 (one second before the camping switch itself), so do the same.
        if (on) try {
            val pre = readFn(PRE_CAMP_ID)
            Logger.log(ctx, "campguard: remote set: pre-step ${preName(PRE_CAMP_ID)} reads $pre")
            if (pre == 1) {
                var r = try { setFnZ?.invoke(fn, PRE_CAMP_ID, ZONE_ALL, 0) } catch (_: Throwable) { null }
                if (r != true) r = try { setFn?.invoke(fn, PRE_CAMP_ID, 0) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                Logger.log(ctx, "campguard: remote set: pre-step wrote 0, returned $r, now ${readFn(PRE_CAMP_ID)}")
            }
        } catch (e: Throwable) { Logger.log(ctx, "campguard: pre-step failed ${e.javaClass.simpleName}") }
        Logger.log(ctx, "campguard: remote set: camping reads ${readFn(campId!!)}, writing $v")
        var ok = try { setFnZ?.invoke(fn, campId!!, ZONE_ALL, v) } catch (_: Throwable) { null }
        if (ok != true) ok = try { setFn?.invoke(fn, campId!!, v) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        val now = readFn(campId!!)
        Logger.log(ctx, "campguard: remote camping ${if (on) "on" else "off"}, returned $ok, now $now")
        return now == v
    }

    /** Diagnostic: scan the car's setting ids for [seconds] and log every one that changes (start Camping from the car's own screen meanwhile). */
    fun watchChanges(ctx: Context, seconds: Int) {
        if (!connect(ctx.applicationContext)) { Logger.log(ctx, "campwatch: car api not available"); return }
        val ranges = listOf(0x20300000..0x20340000, 0x20200000..0x20300000, 0x20000000..0x20200000, 0x22000000..0x22100000, 0x24000000..0x24100000, 0x2a000000..0x2a0b0000, 0x21000000..0x21100000, 0x23000000..0x23100000)
        val last = HashMap<Int, Int?>()
        val t0 = System.currentTimeMillis(); var pass = 0
        Logger.log(ctx, "campwatch: start, ${seconds}s. Start Camping on the car screen now.")
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            var n = 0
            for (r in ranges) { var id = r.first; while (id < r.last) {
                val v = try { readFn(id) } catch (_: Throwable) { null }
                if (pass > 0 && last[id] != v && !(last[id] == null && v == null)) Logger.log(ctx, "campwatch: 0x${Integer.toHexString(id)} ${last[id]} -> $v")
                last[id] = v; id += 0x100; n++
                if (System.currentTimeMillis() - t0 > seconds * 1000L) break } }
            if (pass == 0) Logger.log(ctx, "campwatch: baseline of $n ids taken in ${(System.currentTimeMillis() - t0) / 1000}s")
            pass++
        }
        Logger.log(ctx, "campwatch: done after $pass passes")
    }
}
