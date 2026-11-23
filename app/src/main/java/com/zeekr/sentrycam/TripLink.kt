package com.zeekr.sentrycam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Opt-in "Link trip data" (pref twSync, off by default): while driving, the trip widget's readings are sampled about every 2 s
 * and sent in ~2 minute chunks to the user's OWN relay (the same url + token this app is linked with). The relay matches them
 * to Zeekr's journeys by time, so the ZDash Day tab can play them back. Nothing goes anywhere else. Chunks that cannot be sent
 * wait in a small on-disk queue and go out later.
 *
 * Row = [ts ms, km/h, throttle %, brake %, regen %, regen active 0/1, gear letter, battery %].
 */
class TripLink(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "tripq").apply { mkdirs() }
    private val buf = JSONArray()
    private var lastRow = 0L; private var lastMove = 0L; private var firstRow = 0L
    @Volatile private var busy = false

    companion object {
        private const val ROW_MS = 2000L
        private const val CHUNK_MS = 120_000L
        private const val MAX_QUEUE = 150
        fun linked(p: android.content.SharedPreferences) = !p.getString("url", "").isNullOrEmpty() && !p.getString("token", "").isNullOrEmpty()
    }

    /** [o] is the JSON the widget already reads from the car each tick. Call from the bg thread. */
    fun sample(o: JSONObject, now: Long) {
        val kmh = o.optDouble("speed", 0.0); val gear = o.optString("gear", "-")
        val driving = kmh >= 1.0 || (gear != "P" && gear != "-")
        if (driving) lastMove = now
        if (driving && now - lastRow >= ROW_MS) {
            lastRow = now; if (buf.length() == 0) firstRow = now
            buf.put(JSONArray().put(now).put(Math.round(kmh * 10) / 10.0).put(Math.round(o.optDouble("thr", 0.0))).put(Math.round(o.optDouble("brk", 0.0)))
                .put(Math.round(o.optDouble("rg", 0.0))).put(if (o.optBoolean("regen", false)) 1 else 0).put(gear.take(1))
                .put(if (o.has("batt")) Math.round(o.optDouble("batt") * 10) / 10.0 else JSONObject.NULL))
        }
        val idle = buf.length() > 0 && now - lastMove > 30_000L
        if (buf.length() > 0 && (now - firstRow >= CHUNK_MS || idle || buf.length() >= 350)) flush()
        else if (buf.length() == 0 && !busy && queued() > 0 && now % 30_000L < 400L) send()
    }

    private fun queued() = dir.listFiles()?.size ?: 0

    private fun flush() {
        try {
            val f = File(dir, "c${firstRow}.json"); f.writeText(JSONObject().put("rows", buf).toString())
            val q = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            if (q.size > MAX_QUEUE) q.take(q.size - MAX_QUEUE).forEach { it.delete() }
        } catch (_: Throwable) {}
        while (buf.length() > 0) buf.remove(0)
        send()
    }

    private fun send() {
        if (busy || !linked(p)) return
        val base = p.getString("url", "").orEmpty().trimEnd('/'); val token = p.getString("token", "")
        busy = true
        Thread {
            try {
                for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    val r = try { Net.postJson("$base/cam/tripdata", token, JSONObject(f.readText())) } catch (_: Throwable) { null }
                    if (r != null && r.optBoolean("ok", false)) f.delete()
                    else if (r != null && r.optInt("_status", 0) in 400..499 && r.optInt("_status", 0) != 401 && r.optInt("_status", 0) != 429) f.delete()   // a chunk the relay will never accept
                    else break
                }
            } finally { busy = false }
        }.start()
    }

    /** Called when the option is switched off: drop what is still waiting to be sent. */
    fun clear() { while (buf.length() > 0) buf.remove(0); dir.listFiles()?.forEach { it.delete() } }
}
