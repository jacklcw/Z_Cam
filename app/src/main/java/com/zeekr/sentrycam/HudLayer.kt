package com.zeekr.sentrycam

import android.content.SharedPreferences

/**
 * Layering of the items on the HUD (pref "hudZ" = ids from back to front). Windows only stack by add order, so when an item is
 * (re)added, every item meant to be in front of it is re-added right after, in order. Main thread only.
 */
object HudLayer {
    val IDS = listOf("web", "rev", "lim", "cam", "arr")
    val NAMES = mapOf("web" to "Web", "rev" to "Rev bar", "lim" to "Limit", "cam" to "Cam", "arr" to "Arrow")
    /** id -> brings that item's window back on top of everything added so far (set by each component while it runs). */
    val raisers = HashMap<String, () -> Unit>()
    private var busy = false

    /** The pad ids ("camL", "arrR", ...) share their layer with the HUD item. */
    fun layerOf(padId: String) = when (padId) { "camL", "camR" -> "cam"; "arrL", "arrR" -> "arr"; else -> padId }

    fun order(p: SharedPreferences): List<String> {
        val saved = (p.getString("hudZ", "") ?: "").split(",").filter { it in IDS }.distinct()
        return saved + IDS.filter { it !in saved }
    }

    fun save(p: SharedPreferences, order: List<String>) { p.edit().putString("hudZ", order.joinToString(",")).apply() }

    /** Move [id] one step toward the front (+1) or back (-1). */
    fun move(p: SharedPreferences, id: String, by: Int) {
        val o = order(p).toMutableList(); val i = o.indexOf(id); if (i < 0) return
        val j = (i + by).coerceIn(0, o.size - 1); if (j == i) return
        o.removeAt(i); o.add(j, id); save(p, o)
    }

    /** [id]'s window was just added: put everything meant to be in front of it back on top, back to front. */
    fun added(p: SharedPreferences, id: String) {
        if (busy) return
        val o = order(p); val i = o.indexOf(id); if (i < 0) return
        busy = true
        try { for (k in o.drop(i + 1)) try { raisers[k]?.invoke() } catch (_: Throwable) {} } finally { busy = false }
    }

    /** The order was changed in the editor: re-add every item that is on the HUD, back to front. */
    fun restackAll(p: SharedPreferences) {
        if (busy) return
        busy = true
        try { for (k in order(p)) try { raisers[k]?.invoke() } catch (_: Throwable) {} } finally { busy = false }
    }
}
