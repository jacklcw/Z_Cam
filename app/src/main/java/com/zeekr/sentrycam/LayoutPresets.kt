package com.zeekr.sentrycam

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named layout sets (pref "layoutPresets" = JSON array): only the HUD and the floating windows (places, sizes, layering,
 * turn arrows, rev bar per drive mode), not which features are switched on. Each set keeps its created and edited time.
 */
object LayoutPresets {
    class Preset(val id: String, var name: String, val created: Long, var edited: Long, var data: JSONObject)

    private val EXACT = setOf("mmX", "mmY", "mmSize", "slX", "slY", "slSize", "webPosX", "webPosY", "webSize", "hudZ", "fmX", "fmY", "fmSize", "webFloatX", "webFloatY", "webFloatW", "webLand", "webAsp", "twX", "twY", "twOrient", "twDetail", "twChart", "twSlots", "revX", "revY", "revLen", "revVert", "revAlign", "revGearX", "revGearY", "revGearSize")
    private val PREFIX = listOf("bsPosX_", "bsPosY_", "bsSize_", "bsAspect_", "bsArr", "pfRevPos_", "pfRevSize_", "pfRevInset_", "twW_", "twH_")
    private val FLOAT = Regex("^fl(Left|Right|Rear|Front)(X|Y|W|H|Size)$")

    /** Which floating windows are on. Sets saved before these were added do not hold them: applying such a set leaves the switches alone. */
    private val SWITCHES = listOf("flLeft", "flRight", "flRear", "flFront", "webFloatOn", "twOn")

    fun isLayoutKey(k: String) = k in EXACT || PREFIX.any { k.startsWith(it) } || FLOAT.matches(k)

    fun list(p: SharedPreferences): MutableList<Preset> {
        val out = ArrayList<Preset>()
        try {
            val a = JSONArray(p.getString("layoutPresets", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out += Preset(o.getString("id"), o.getString("name"), o.getLong("created"), o.getLong("edited"), o.getJSONObject("data")) }
        } catch (_: Throwable) {}
        return out
    }

    private fun save(p: SharedPreferences, l: List<Preset>, active: String? = null) {
        val a = JSONArray(); for (s in l) a.put(JSONObject().put("id", s.id).put("name", s.name).put("created", s.created).put("edited", s.edited).put("data", s.data))
        val e = p.edit().putString("layoutPresets", a.toString()); if (active != null) e.putString("layoutActive", active); e.apply()
    }

    fun active(p: SharedPreferences): String = p.getString("layoutActive", "") ?: ""

    /** The layout as it is now (saved values only: untouched items stay at their defaults when applied). */
    fun snapshot(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        for (k in SWITCHES) o.put(k, p.getBoolean(k, false))
        for ((k, v) in p.all) if (isLayoutKey(k) && (v is Boolean || v is Int || v is Long || v is String)) o.put(k, v)
        return o
    }

    private fun uniqueName(l: List<Preset>, want: String): String {
        val base = want.trim().ifEmpty { "Layout" }
        if (l.none { it.name.equals(base, true) }) return base
        var n = 2; while (l.any { it.name.equals("$base $n", true) }) n++
        return "$base $n"
    }

    fun create(p: SharedPreferences, name: String, data: JSONObject = snapshot(p)): Preset {
        val l = list(p); val now = System.currentTimeMillis()
        val s = Preset("L$now", uniqueName(l, name), now, now, JSONObject(data.toString()))
        l += s; save(p, l, s.id); return s
    }

    fun copy(p: SharedPreferences, src: Preset, name: String) = create(p, name, src.data)

    fun overwrite(p: SharedPreferences, id: String) {
        val l = list(p); val s = l.firstOrNull { it.id == id } ?: return
        s.data = snapshot(p); s.edited = System.currentTimeMillis(); save(p, l, id)
    }

    /** Moves a set to position [to] (0 = first). The list order is also the order on the floating arc. */
    fun move(p: SharedPreferences, id: String, to: Int) {
        val l = list(p); val i = l.indexOfFirst { it.id == id }; if (i < 0) return
        val s = l.removeAt(i); l.add(to.coerceIn(0, l.size), s); save(p, l)
    }

    fun rename(p: SharedPreferences, id: String, name: String) {
        val l = list(p); val s = l.firstOrNull { it.id == id } ?: return
        s.name = uniqueName(l.filter { it.id != id }, name); s.edited = System.currentTimeMillis(); save(p, l)
    }

    fun delete(p: SharedPreferences, id: String) {
        val l = list(p).filter { it.id != id }
        save(p, l, if (active(p) == id) "" else null)
    }

    /** Replace the current layout: keys the set does not hold go back to their defaults. Caller refreshes the UI/service. */
    fun apply(p: SharedPreferences, s: Preset) {
        val e = p.edit()
        for (k in p.all.keys) if (isLayoutKey(k) && k !in SWITCHES) e.remove(k)
        for (k in s.data.keys()) {
            when (val v = s.data.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
            }
        }
        for (k in listOf("twMin", "webMin", "flLeftMin", "flRightMin", "flRearMin", "flFrontMin")) e.putBoolean(k, false)   // a new layout brings every window back from the dock
        FloatMaster.restoreSeq++
        val now = System.currentTimeMillis()
        val cams = listOf("flLeft", "flRight", "flRear", "flFront").joinToString(" ") { k -> "$k=${s.data.optBoolean(k, false)}/${s.data.optInt(k + "X", -1)},${s.data.optInt(k + "Y", -1)},${s.data.optInt(k + "W", -1)}x${s.data.optInt(k + "H", -1)}" }
        android.util.Log.d("LayoutPresets", "apply ${s.name}: $cams")
        e.putString("layoutActive", s.id).putLong("flSizeStamp", now).putLong("layoutStamp", now).commit()   // layoutStamp: every window rebuilds from the saved layout
    }
}
