package com.zeekr.sentrycam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** Best-effort current location from the head unit's GPS (last known fix). */
object Loc {
    fun current(ctx: Context): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
        } catch (_: SecurityException) {} catch (_: Throwable) {}
        val b = best ?: return null
        // Ignore an obviously stale/zeroed fix.
        if (b.latitude == 0.0 && b.longitude == 0.0) return null
        return Pair(b.latitude, b.longitude)
    }
}
