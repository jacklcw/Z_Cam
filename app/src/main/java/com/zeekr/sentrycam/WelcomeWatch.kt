package com.zeekr.sentrycam

import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/**
 * Plays the welcome sound on the outside speaker when the car goes from locked to unlocked.
 * Prefs: "welcomeOn" (default off), "welcomeStyle" (an engine style key).
 */
object WelcomeWatch {
    private const val POLL_MS = 1500L
    private var thread: HandlerThread? = null
    private var lastLocked: Boolean? = null
    private var lastRaw = -1
    private var seatedSince = 0L
    private const val SEATED_MS = 4000L
    private const val IGNITION = 0x20259000; private const val IGN_ON = 0x00200105; private const val IGN_DRIVING = 0x00200107
    private var unreadLogged = false

    fun sync(ctx: Context, safe: Boolean) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val on = !safe && (p.getString("welcomeOn", "off") == "on" || p.getString("lockOn", "off") == "on")
        if (on) start(ctx.applicationContext) else stop()
    }

    @Synchronized private fun start(ctx: Context) {
        if (thread != null) return
        val t = HandlerThread("welcomewatch").also { it.start() }
        thread = t; lastLocked = null
        val h = Handler(t.looper)
        h.post(object : Runnable {
            override fun run() {
                try { tick(ctx) } catch (e: Throwable) { Logger.log(ctx, "welcome: ${e.javaClass.simpleName} ${e.message ?: ""}") }
                if (thread === t) h.postDelayed(this, POLL_MS)
            }
        })
    }

    @Synchronized private fun stop() {
        val t = thread ?: return
        thread = null; lastLocked = null
        t.quitSafely()
    }

    private fun tick(ctx: Context) {
        val raw = CampGuard.lockState(ctx)
        if (raw == null) { if (!unreadLogged) { unreadLogged = true; Logger.log(ctx, "welcome: lock state unreadable") }; return }
        val locked = raw == 1
        if (raw != lastRaw) { lastRaw = raw; Logger.log(ctx, "welcome: lock=$raw") }
        val was = lastLocked
        lastLocked = locked
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (was == false && locked && p.getString("lockOn", "off") == "on") {
            // Locking while sitting in the driver's seat is not walking away: no chirp.
            if (CampGuard.driverSeated(ctx) == true) Logger.log(ctx, "lock sound: locked from inside, skipped")
            else { Logger.log(ctx, "lock sound: locked, chirp"); com.zeekr.sentrycam.sound.LockSound.play(ctx) }
        }
        if (p.getString("welcomeOn", "off") != "on") return
        val playing = com.zeekr.sentrycam.sound.WelcomeSound.isPlaying()
        if (was == true && !locked && !playing) {
            // Unlocking from inside (someone in the driver's seat, or the car already on) is leaving, not arriving: no welcome.
            val ign = CampGuard.readCode(ctx, IGNITION)
            if (CampGuard.driverSeated(ctx) == true || ign == IGN_ON || ign == IGN_DRIVING) { Logger.log(ctx, "welcome: unlocked from inside, skipped"); return }
            Logger.log(ctx, "welcome: unlocked, playing until you are in the car")
            seatedSince = 0L
            com.zeekr.sentrycam.sound.WelcomeSound.playHeld(ctx)
            return
        }
        if (!playing) return
        // The engine idles after unlock and stops once you are in and settled, or hands over to the driving engine sound when that is set up.
        // Opening the door wakes the car (ignition goes ON), so that cannot tell "in the car"; the driver's seat can. Once you sit and settle
        // (door closed), the welcome stops; with driving profiles on the driving engine sound is armed by then and carries on.
        val stop = when {
            locked -> "locked again"
            CampGuard.driverSeated(ctx) == true -> {
                val now = System.currentTimeMillis()
                if (seatedSince == 0L) seatedSince = now
                if (now - seatedSince >= SEATED_MS) "driver seated" else null
            }
            else -> { seatedSince = 0L; null }
        }
        if (stop != null) { Logger.log(ctx, "welcome: stopping ($stop)"); com.zeekr.sentrycam.sound.WelcomeSound.requestStop() }
    }
}
