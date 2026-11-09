package com.zeekr.sentrycam

import android.content.Context
import java.lang.reflect.Modifier

/**
 * Read-only probe of the ECARX car API: lists the ICarFunction methods and every int constant whose
 * name looks like ambient light, drive mode or regen/energy recovery, with isFunctionSupported + current value. Never writes.
 * Output goes to Logger (and is uploaded to the relay by the caller).
 */
object CarProbe {
    private val WANT = Regex("AMBIENT|ATMOS|LIGHT|DRIVE_?MODE|DRIVING_?MODE|SPORT|COMFORT|ECO|REGEN|RECUP|ENERGY|PEDAL|ONE_?PEDAL|DECEL|BRAKE|MONO|EFFECT|THEME|SINGLE|VOL|AUDIO|SOUND|MEDIA|BLUETOOTH|BT_|SPEAKER|MUTE|NAVI|RADIO|CHARG|AMP|CURRENT|EVSE|GUN|PLUG|CABLE|SOC|BATT", RegexOption.IGNORE_CASE)

    /** Classes whose int constants are all logged, whatever their names (regen / propulsion / ambient experience values). */
    private val ALL_CLASSES = Regex("PropulsionSysMode|BpfMode|RabMode|HybridFunction|AmbienceLightExperienceMode|AmbienceLightMainColorMode|AmbienceLightFunction|DriveModeFunction|IExtensionVehicle|VehicleFunction|IDriveMode$|IHybrid$|IVehicle$|Charg|Battery|Energy")

    fun run(ctx: Context) {
        // the dump is hundreds of lines and used to push the real log (engine sound etc.) out of the capped log, so only the summary lines go there
        fun log(m: String) {
            if (m.startsWith("start") || m.startsWith("done") || m.startsWith("failed")) Logger.log(ctx, "carprobe: $m")
            synchronized(keep) { keep.append(m).append('\n') }   // the whole dump goes to the relay (upload below), not into the capped log
        }
        try {
            val car = BlindSpotHud.carCache ?: Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, ctx.applicationContext)
            val fn = car.javaClass.getMethod("getICarFunction").invoke(car)
            val get = fn.javaClass.getMethod("getFunctionValue", Int::class.javaPrimitiveType)
            val sup = try { fn.javaClass.getMethod("isFunctionSupported", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
            log("start impl=${fn.javaClass.name}")
            val ifaces = fn.javaClass.interfaces.toList() + fn.javaClass.superclass?.interfaces.orEmpty().toList()
            ifaces.forEach { i -> log("iface ${i.name}: " + i.methods.joinToString(",") { m -> m.name + "(" + m.parameterTypes.joinToString("") { it.simpleName.take(1) } + ")" }) }
            val seen = HashSet<Class<*>>()
            fun scan(c: Class<*>) {
                if (!seen.add(c)) return
                c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }.forEach { f ->
                    try { f.isAccessible = true; val id = f.getInt(null); if (id >= 0x20000000 && (id and 0xFF) == 0) pollIds.add(id) } catch (_: Throwable) {}
                }
                c.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && (WANT.containsMatchIn(it.name) || ALL_CLASSES.containsMatchIn(c.name)) }.forEach { f ->
                    try {
                        f.isAccessible = true
                        val id = f.getInt(null)
                        val s = try { sup?.invoke(fn, id) } catch (_: Throwable) { "?" }
                        val v = try { get.invoke(fn, id) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
                        val line = "${c.simpleName}.${f.name}=0x${Integer.toHexString(id)} sup=$s val=$v"
                        log(line)
                        if (ALL_CLASSES.containsMatchIn(c.name)) keep.append(line).append('\n')
                    } catch (_: Throwable) {}
                }
                c.declaredClasses.forEach { scan(it) }
            }
            ifaces.forEach { scan(it) }
            // Also sibling classes in the same package that expose function-id constants.
            listOf("ICarFunction", "ICarFunction\$IFunctionId", "ICarFunction\$IFunctionValue", "IFunctionId", "IFunctionValue").forEach { n ->
                for (pkg in listOf("com.ecarx.xui.adaptapi.car", "com.ecarx.xui.adaptapi.car.base", "com.ecarx.xui.adaptapi.car.vehicle", "com.ecarx.xui.adaptapi.car.hvac")) {
                    try { scan(Class.forName("$pkg.$n")) } catch (_: Throwable) {}
                }
            }
            // The function ids live in classes we can't enumerate: list the framework jars and pull candidate class names out of their dex files.
            val names = java.util.TreeSet<String>()
            for (dir in listOf("/system/framework", "/system_ext/framework", "/vendor/framework", "/product/framework")) {
                java.io.File(dir).listFiles()?.filter { it.name.contains("ecarx", true) || it.name.contains("adaptapi", true) || it.name.contains("zeekr", true) }?.forEach { f ->
                    log("jar ${f.path} ${f.length()}")
                    try {
                        java.util.zip.ZipFile(f).use { z ->
                            for (e in z.entries()) if (e.name.endsWith(".dex")) {
                                val txt = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
                                Regex("Lcom/(?:ecarx|zeekrlife)/[A-Za-z0-9_/$]*(?:Ambien|Light|Drive|Mode|Function|Const|Audio|Volume|Sound|Media|Bluetooth|Speaker|Charg|Battery|Energy)[A-Za-z0-9_/$]*;").findAll(txt).forEach { names.add(it.value.drop(1).dropLast(1).replace('/', '.')) }
                            }
                        }
                    } catch (t: Throwable) { log("dex scan ${f.name}: ${t.javaClass.simpleName}") }
                }
            }
            log("candidate classes: " + names.joinToString(" "))
            names.forEach { n -> try { scan(Class.forName(n)) } catch (_: Throwable) {}; if (n.contains('$')) try { scan(Class.forName(n.substringBefore('$'))) } catch (_: Throwable) {} }
            // audio / volume API: list its methods and constants so the right call can be found
            val dumpNames = listOf("com.ecarx.xui.adaptapi.car.Car", "com.ecarx.xui.adaptapi.audio.audiofx.Audio", "com.ecarx.xui.adaptapi.audio.audiofx.IAudioState",
                "com.zeekrlife.adaptapi.audio.impl.AudioImpl", "com.zeekrlife.adaptapi.audio.impl.CarAudioInternalManager", "com.zeekrlife.adaptapi.audio.impl.ZeekrCarAudioConstants",
                "com.zeekrlife.adaptapi.audio.impl.IAudioStateImpl", "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction", "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$VolumeSourceType",
                "com.ecarx.xui.adaptapi.diminteraction.IHardKeyInteraction", "com.ecarx.xui.adaptapi.diminteraction.IHardKeyInteraction\$IVolumeControlObserver",
                "com.ecarx.xui.adaptapi.diminteraction.IHardKeyInteraction\$IVolumeControlDataInfo", "com.ecarx.xui.adaptapi.audio.audiofx.Audio\$AudioSettings",
                "com.ecarx.xui.adaptapi.audio.audiofx.Audio\$SoundSource", "com.ecarx.xui.adaptapi.audio.audiofx.Audio\$AudioChannel")
            for (n in dumpNames) try {
                val c = Class.forName(n)
                log("CLASS $n extends ${c.superclass?.name} implements ${c.interfaces.joinToString { it.name }}")
                c.declaredMethods.forEach { m -> log("  M ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})") }
                c.declaredFields.filter { Modifier.isStatic(it.modifiers) && (it.type == Int::class.javaPrimitiveType || it.type == String::class.java) }.forEach { f ->
                    try { f.isAccessible = true; log("  F ${f.name}=${f.get(null)}") } catch (_: Throwable) {}
                }
            } catch (e: Throwable) { log("CLASS $n: ${e.javaClass.simpleName}") }
            // Android Automotive's own volume groups (android.car): is it there, and may an app read / set them?
            try {
                val carCls = Class.forName("android.car.Car")
                val latch = java.util.concurrent.CountDownLatch(1)
                var carObj: Any? = null
                val looper = android.os.Looper.getMainLooper()
                val conn = java.lang.reflect.Proxy.newProxyInstance(carCls.classLoader, arrayOf(android.content.ServiceConnection::class.java)) { _, m, _ ->
                    if (m.name == "onServiceConnected") latch.countDown(); null
                } as android.content.ServiceConnection
                carObj = carCls.getMethod("createCar", Context::class.java, android.content.ServiceConnection::class.java).invoke(null, ctx, conn)
                carCls.getMethod("connect").invoke(carObj)
                log("android.car: connecting, latch=" + latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(carObj, "car_audio")
                log("android.car: car_audio manager=${mgr?.javaClass?.name}")
                if (mgr != null) {
                    mgr.javaClass.methods.filter { it.name.contains("olume") || it.name.contains("Zone") }.forEach { m -> log("  CM ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})") }
                    try {
                        val cnt = mgr.javaClass.getMethod("getVolumeGroupCount").invoke(mgr) as Int
                        log("android.car: volume groups=$cnt")
                        for (g in 0 until cnt) {
                            val cur = try { mgr.javaClass.getMethod("getGroupVolume", Int::class.javaPrimitiveType).invoke(mgr, g) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName + " " + (e.cause ?: e).message }
                            val max = try { mgr.javaClass.getMethod("getGroupMaxVolume", Int::class.javaPrimitiveType).invoke(mgr, g) } catch (e: Throwable) { "err:" + (e.cause ?: e).javaClass.simpleName }
                            log("android.car: group $g volume=$cur max=$max")
                        }
                    } catch (e: Throwable) { log("android.car: groups failed ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message}") }
                }
            } catch (e: Throwable) { log("android.car: failed ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message}") }
            log("done classes=${seen.size}")
            upload(ctx)
            watch(ctx, fn)
            poll(ctx, get)
        } catch (e: Throwable) {
            log("failed ${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}")
        }
    }

    private var watching = false
    private val pollIds = java.util.TreeSet<Int>()
    private var polling = false

    /** The watcher gets nothing on this car, so also poll every function-looking id and log the ones whose value changes. */
    private fun poll(ctx: Context, get: java.lang.reflect.Method) {
        if (polling) return
        polling = true
        val fn = BlindSpotHud.carCache?.let { it.javaClass.getMethod("getICarFunction").invoke(it) } ?: return
        Thread {
            val last = HashMap<Int, Int>()
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val streams = HashMap<Int, Int>()
            val ids = pollIds.toList()
            Logger.log(ctx, "carpoll: polling ${ids.size} ids, change settings in the car now")
            while (true) {
                for (st in 0..11) {   // Android's own stream volumes: does the car's volume change show up on any of them?
                    val v = try { am?.getStreamVolume(st) } catch (_: Throwable) { null } ?: continue
                    val old = streams.put(st, v)
                    if (old != null && old != v) {
                        val line = "carpoll: AudioManager stream $st $old -> $v"
                        Logger.log(ctx, line); synchronized(keep) { keep.append(line).append('\n') }; upload(ctx)
                    }
                }
                for (id in ids) {
                    val v = try { (get.invoke(fn, id) as? Number)?.toInt() } catch (_: Throwable) { null } ?: continue
                    val old = last.put(id, v)
                    if (old != null && old != v) {
                        val line = "carpoll: 0x${Integer.toHexString(id)} $old -> $v (0x${Integer.toHexString(v)})"
                        Logger.log(ctx, line); synchronized(keep) { keep.append(line).append('\n') }; upload(ctx)
                    }
                }
                Thread.sleep(500)
            }
        }.start()
    }
    /** The log is capped, so the dump of the interesting classes and every watched change are also uploaded on their own. */
    private val keep = StringBuilder()
    private var uploadPending = false

    private fun upload(ctx: Context) {
        if (uploadPending) return
        uploadPending = true
        Thread {
            Thread.sleep(2000)
            uploadPending = false
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val base = p.getString("url", "").orEmpty().trimEnd('/'); val token = p.getString("token", "")
            if (base.isNotEmpty() && !token.isNullOrEmpty()) Net.postText("$base/cam/log/upload", token, synchronized(keep) { keep.toString() })
        }.start()
    }

    /** Logs every function value change the car reports, so a setting toggled on the car screen shows its id and value. */
    private fun watch(ctx: Context, fn: Any) {
        if (watching) return
        try {
            val wc = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction\$IFunctionValueWatcher")
            val w = java.lang.reflect.Proxy.newProxyInstance(wc.classLoader, arrayOf(wc)) { _, m, a ->
                if (m.declaringClass != Any::class.java) {
                    val line = "carwatch: ${m.name}(" + (a ?: emptyArray()).joinToString(",") { v -> (v as? Int)?.let { "0x" + Integer.toHexString(it) + "=" + it } ?: v.toString() } + ")"
                    Logger.log(ctx, line)
                    synchronized(keep) { keep.append(line).append('\n') }
                    upload(ctx)
                }
                null
            }
            fn.javaClass.getMethod("registerFunctionValueWatcher", wc).invoke(fn, w)
            watching = true
            Logger.log(ctx, "carwatch: registered, change settings in the car now")
        } catch (e: Throwable) { Logger.log(ctx, "carwatch: failed ${(e.cause ?: e).javaClass.simpleName} ${(e.cause ?: e).message}") }
    }
}
