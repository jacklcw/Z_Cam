package com.zeekr.sentrycam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * "Parked" test for the always-on camera views (HUD always-show, floating windows), so they release the
 * camera instead of holding it for hours. Parked = the screen is off, or nothing has moved/indicated for
 * [IDLE_MS] while the GPS is alive (no recent fix: can't tell, so only the screen rule applies -- never
 * cut a feed on a car that might be driving). Pref "parkCut" (default on) turns it off.
 */
object Park {
    private const val IDLE_MS = 5 * 60_000L
    private const val MOVING_MS = 1.5f          // m/s
    private const val FIX_FRESH_MS = 60_000L
    private const val FIX_LOST_MS = 120_000L    // no fix this long + idle = car off / left (GPS goes silent), not "can't tell"
    /** Live view / capture needs the camera: the always-on views let go of it while this is set. */
    @Volatile var yieldCam = false
    @Volatile private var lastActive = System.currentTimeMillis()
    @Volatile private var lastFix = 0L
    private var started = false
    private var listener: LocationListener? = null
    private var lm: LocationManager? = null

    /** Moving, indicating, or the user just changed a setting: restarts the idle timer. */
    fun poke() { lastActive = System.currentTimeMillis() }

    fun start(ctx: Context) {
        if (started) return
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return
        try {
            val mgr = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    lastFix = System.currentTimeMillis()
                    if (l.hasSpeed() && l.speed > MOVING_MS) poke()
                }
                @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, l, Looper.getMainLooper())
            lm = mgr; listener = l
            started = true
        } catch (e: Throwable) { Logger.log(ctx, "park: gps updates failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    /** GPS updates stop when nothing needs the parked test (HUD always-show / floating windows all off). */
    fun stop() {
        if (!started) return
        try { listener?.let { lm?.removeUpdates(it) } } catch (_: Throwable) {}
        listener = null; lm = null; started = false
    }

    /** Why the always-on views are letting go of the camera, or null when they may hold it. */
    fun reason(ctx: Context): String? {
        if (yieldCam) return "live view or capture needs the camera"
        if (!ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("parkCut", true)) return null
        if (!(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return "screen off"
        val now = System.currentTimeMillis()
        if (started && now - lastFix > FIX_LOST_MS && now - lastActive > IDLE_MS) return "no GPS fix for 2 min and idle for 5 min"
        if (now - lastFix < FIX_FRESH_MS && now - lastActive > IDLE_MS) return "parked: no movement for 5 min"
        return null
    }

    fun parked(ctx: Context): Boolean = reason(ctx) != null

    /** Real "car is parked" signal for the relay (Auto Sentry): same screen / idle rules, but ignores the camera yield and the parkCut pref. */
    fun parkedSignal(ctx: Context): Boolean {
        if (!(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return true
        val now = System.currentTimeMillis()
        if (started && now - lastFix > FIX_LOST_MS && now - lastActive > IDLE_MS) return true
        return now - lastFix < FIX_FRESH_MS && now - lastActive > IDLE_MS
    }
}
