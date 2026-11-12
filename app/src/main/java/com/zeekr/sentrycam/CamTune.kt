package com.zeekr.sentrycam

import android.content.SharedPreferences

/**
 * Per-camera picture tuning shared by the live-view editor, the floating windows and (left/right only) the HUD.
 * Cameras: "L" left, "R" right, "B" rear, "F" front. Slider prefs are "<key>_<cam>" (bsFov_B ...).
 */
object CamTune {
    val CAMS = listOf("L", "R", "B", "F")
    /**
     * Stitch halves ("XLs" = left blinker's side half, "XLr" = its rear half, "XRs", "XRr") have their own picture settings, starting
     * out as a copy of the real camera until changed; everything else about them (mirror, flip, ranges) follows that real camera.
     */
    fun isX(cam: String) = cam.startsWith("X")
    fun base(cam: String) = if (isX(cam)) (if (cam.endsWith("r")) "B" else cam.substring(1, 2)) else cam
    fun stSeam(p: SharedPreferences, blinker: String) = p.getInt("stSeam_$blinker", 55).coerceIn(30, 70)
    fun stBlend(p: SharedPreferences, blinker: String) = p.getInt("stBlend_$blinker", if (blinker == "L") 29 else 17).coerceIn(0, 40)

    fun side(cam: String) = base(cam).let { it == "L" || it == "R" }
    fun panel(cam: String) = when (base(cam)) { "L" -> 2; "R" -> 3; "B" -> 1; else -> 0 }
    fun name(cam: String) = when (base(cam)) { "L" -> "Left"; "R" -> "Right"; "B" -> "Rear"; else -> "Front" }

    fun def(key: String, cam: String): Int =
        if (side(cam)) BlindSpotHud.defaultFor(key, base(cam) == "L") else when (key) { "bsFov" -> 140; "bsTilt" -> 25; else -> 0 }   // rear/front default to a full, wide view
    fun min(key: String, cam: String) = when (key) { "bsTilt" -> if (isX(cam)) -30 else 0; "bsShift" -> -50; "bsSize", "bsAspect" -> 40; "bsFov" -> 50; "bsYaw" -> if (side(cam) && !isX(cam)) 0 else -60; "bsRoll" -> -45; else -> 0 }
    fun max(key: String, cam: String = "L") = when (key) { "bsShift" -> 50; "bsSize" -> 100; "bsAspect" -> 250; "bsFov" -> if (side(cam)) 120 else 160; "bsTilt" -> 60; "bsYaw" -> 60; "bsRoll" -> 45; else -> 100 }

    // Stitch defaults: tuned on the car (fov, tilt, turn, rotate, up/down) per half
    private val X_DEF = mapOf(
        "XLs" to intArrayOf(83, 22, 60, -36, 8), "XLr" to intArrayOf(138, 30, 0, 0, 1),
        "XRs" to intArrayOf(83, 22, 60, 29, 3), "XRr" to intArrayOf(138, 30, 0, 3, 0))
    private val X_KEYS = listOf("bsFov", "bsTilt", "bsYaw", "bsRoll", "bsShift")
    fun get(p: SharedPreferences, key: String, cam: String): Int =
        p.getInt("${key}_$cam", if (isX(cam)) (X_DEF[cam]?.getOrNull(X_KEYS.indexOf(key)) ?: get(p, key, base(cam))) else def(key, cam)).coerceIn(min(key, cam), max(key, cam))

    // Rear keeps its original keys so existing settings carry over.
    fun mirrorKey(cam: String) = when (base(cam)) { "B" -> "flRearMirror"; "F" -> "flFrontMirror"; else -> "bsMirror_$cam" }
    fun flipKey(cam: String) = if (base(cam) == "B") "flRearFlip" else "flFrontFlip"
    fun mirrorDef(cam: String) = base(cam) != "F"
    fun mirror(p: SharedPreferences, cam: String) = p.getBoolean(mirrorKey(cam), mirrorDef(cam))
    fun flip(p: SharedPreferences, cam: String) = !side(cam) && p.getBoolean(flipKey(cam), false)

    /** Every pref key that belongs to [cam] (for "reset to default"). */
    fun keys(cam: String) = if (isX(cam)) listOf("bsFov", "bsTilt", "bsYaw", "bsRoll", "bsShift").map { "${it}_$cam" } else listOf("bsSize", "bsAspect", "bsFov", "bsTilt", "bsYaw", "bsRoll").map { "${it}_$cam" } + lookKeys(cam) + mirrorKey(cam) + (if (side(cam)) emptyList() else listOf(flipKey(cam)))

    /** [panel, fov, tilt, yaw, roll, mirror(0/1)] for [cam]. */
    private fun tuneOf(p: SharedPreferences, cam: String): FloatArray {
        val mir = mirror(p, cam)
        return if (side(cam)) floatArrayOf(panel(cam).toFloat(), get(p, "bsFov", cam).toFloat(), get(p, "bsTilt", cam).toFloat(),
            get(p, "bsYaw", cam).toFloat() * (if (base(cam) == "L") 1 else -1) * (if (mir) 1 else -1), get(p, "bsRoll", cam).toFloat(), if (mir) 1f else 0f)
        else {
            // "flip" = upside down (a 180 degree turn plus a left/right swap)
            val flip = flip(p, cam)
            floatArrayOf(panel(cam).toFloat(), get(p, "bsFov", cam).toFloat(), get(p, "bsTilt", cam).toFloat(), get(p, "bsYaw", cam).toFloat(),
                get(p, "bsRoll", cam) + (if (flip) 180f else 0f), if (mir != flip) 1f else 0f)
        }
    }

    // Picture look, per real camera ("lkBri_L" ...): brightness / contrast / saturation -100..100 (0 = off), opacity 20..100 (100 = off). Shared by HUD, floating windows and the editor.
    val LOOK = listOf("lkBri", "lkCon", "lkSat", "lkOpa", "lkWrm", "lkNit", "lkShp")
    fun lookDef(key: String) = when (key) { "lkOpa" -> 100; "lkShp" -> 50; else -> 0 }
    fun lookMin(key: String) = when (key) { "lkOpa" -> 20; "lkNit", "lkShp" -> 0; else -> -100 }
    fun lookGet(p: SharedPreferences, key: String, cam: String): Int {
        val v = try { p.getInt("${key}_${base(cam)}", lookDef(key)) } catch (_: Throwable) { lookDef(key) }   // a restored/imported value of another type must never crash the app
        return v.coerceIn(lookMin(key), 100)
    }
    fun lookKeys(cam: String) = LOOK.map { "${it}_${base(cam)}" }
    fun applyLook(p: SharedPreferences, cam: String, r: PanelOut) {
        r.bri = lookGet(p, "lkBri", cam) / 200f
        r.con = 1f + lookGet(p, "lkCon", cam) / 200f * (if (lookGet(p, "lkCon", cam) < 0) 1.6f else 2f)
        r.sat = 1f + lookGet(p, "lkSat", cam) / 100f
        r.opa = lookGet(p, "lkOpa", cam) / 100f
        r.warm = lookGet(p, "lkWrm", cam) / 100f
        r.nite = lookGet(p, "lkNit", cam) / 100f
        r.shp = lookGet(p, "lkShp", cam) / 50f
    }

    fun apply(p: SharedPreferences, cam: String, r: PanelOut) {
        applyLook(p, cam, r)
        val t = tuneOf(p, cam)
        r.stitch = false
        r.panel = t[0].toInt(); r.fovDeg = t[1]; r.tiltDeg = t[2]; r.yawDeg = t[3]; r.rollDeg = t[4]; r.mirror = t[5] > 0.5f
    }

    /** Left/right camera stitched with the rear (mirror order: left blinker = side | rear, right = rear | side) when [on]; otherwise plain [apply]. */
    fun applyStitch(p: SharedPreferences, cam: String, r: PanelOut, on: Boolean) {
        if (!on || !side(cam)) { apply(p, cam, r); return }
        applyLook(p, cam, r)
        val s = tuneOf(p, "X${cam}s"); val b = tuneOf(p, "X${cam}r")
        val l = if (cam == "L") s else b; val rt = if (cam == "L") b else s
        r.split = stSeam(p, cam) / 100f; r.blend = stBlend(p, cam) / 100f
        r.panel = l[0].toInt(); r.fovDeg = l[1]; r.tiltDeg = l[2]; r.yawDeg = l[3]; r.rollDeg = l[4]; r.mirror = l[5] > 0.5f
        r.shift = get(p, "bsShift", "X${cam}" + (if (cam == "L") "s" else "r")).toFloat(); r.shift2 = get(p, "bsShift", "X${cam}" + (if (cam == "L") "r" else "s")).toFloat()
        r.panel2 = rt[0].toInt(); r.fov2 = rt[1]; r.tilt2 = rt[2]; r.yaw2 = rt[3]; r.roll2 = rt[4]; r.mirror2 = rt[5] > 0.5f
        r.stitch = true
    }
}
