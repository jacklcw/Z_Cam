package com.zeekr.sentrycam

/**
 * One regen estimate (0..100) shared by the HUD rev bar and the trip window's regen pill, so both show the same thing.
 * Foot off the pedal and the car's regen flag (or a measured slowdown) switch it on; the size follows the larger of brake pedal depth and
 * deceleration over the last ~0.35 s (the speed sensor updates in coarse steps), smoothed by time so it does not depend on the call rate.
 */
class RegenMeter(private val log: (String) -> Unit = {}) {
    companion object {
        const val BRAKE_FULL = 44f
        const val DECEL_FULL = 4f   // m/s2 that fills the bar
    }
    private val hist = ArrayDeque<Pair<Long, Float>>()
    private var decel = 0f; private var brakeF = 0f; private var level = 0f
    private var seenAt = 0L; private var lastAt = 0L
    private var liftAt = 0L; private var lastThr = 0f; private var gotFlow = false; private var gotDecel = false

    /** The car's power flow said "regenerating" at [now]. */
    fun flowSeen(now: Long) { seenAt = now }

    private fun k(dt: Float, tau: Float) = 1f - Math.exp((-dt / tau).toDouble()).toFloat()

    /** [speedMs] m/s, [brakeRaw] raw brake sensor, [throttle] 0..100 (null = unknown). Returns the smoothed regen level 0..100. */
    fun step(now: Long, speedMs: Float?, brakeRaw: Float?, throttle: Float): Float {
        val dt = if (lastAt == 0L) 0.04f else ((now - lastAt) / 1000f).coerceIn(0.005f, 0.5f); lastAt = now
        if (speedMs != null) {
            hist.addLast(Pair(now, speedMs))
            while (hist.size > 2 && now - hist.first().first > 900) hist.removeFirst()
            val old = hist.firstOrNull { now - it.first in 250..450 } ?: hist.first()
            val span = (now - old.first) / 1000f
            val raw = if (span >= 0.2f) ((old.second - speedMs) / span / DECEL_FULL * 100f).coerceIn(0f, 100f) else decel
            decel += (raw - decel) * k(dt, if (raw > decel) 0.044f else 0.18f)
        }
        val brake = ((brakeRaw ?: 0f) / BRAKE_FULL * 100f).coerceIn(0f, 100f)
        brakeF += (brake - brakeF) * k(dt, if (brake > brakeF) 0.044f else 0.11f)
        val on = now - seenAt < 500 || (throttle < 1.5f && decel > 4f)   // the flag flickers and can arrive late
        // timing trace for the Car cam log: how long after the foot lifted each signal showed up
        if (throttle >= 3f) { liftAt = 0L; gotFlow = false; gotDecel = false } else if (liftAt == 0L && throttle < 1.5f && lastThr >= 3f) liftAt = now
        lastThr = throttle
        if (liftAt != 0L && now - liftAt < 4000) {
            if (!gotFlow && seenAt >= liftAt) { gotFlow = true; log("regen: lift -> car regen flow after ${seenAt - liftAt} ms") }
            if (!gotDecel && decel > 4f) { gotDecel = true; log("regen: lift -> measured decel after ${now - liftAt} ms") }
        }
        val target = if (on) maxOf(brakeF, decel) else 0f
        level += (target - level) * k(dt, if (target > level) 0.058f else 0.25f)
        if (level < 0.3f) level = 0f
        return level
    }
}
