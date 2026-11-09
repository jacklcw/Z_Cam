package com.zeekr.sentrycam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts every payload byte this app sends to / receives from the internet (relay HTTP, live-view
 * WebSocket, update download), per category, and logs a line every minute while there is traffic.
 * Daily totals persist across restarts. Counts payload + a flat per-request header estimate; TLS
 * and TCP overhead are not included (add roughly 3-8%).
 */
object DataMeter {
    private const val HEADER_EST = 300L
    private val sent = ConcurrentHashMap<String, AtomicLong>()
    private val recv = ConcurrentHashMap<String, AtomicLong>()
    private var started = false

    fun sent(cat: String, bytes: Long) { sent.getOrPut(cat) { AtomicLong() }.addAndGet(bytes) }
    fun recv(cat: String, bytes: Long) { recv.getOrPut(cat) { AtomicLong() }.addAndGet(bytes) }
    fun request(url: String, body: Long, response: Long) {
        val cat = category(url)
        sent(cat, body + HEADER_EST + url.length); recv(cat, response + HEADER_EST)
    }
    fun categoryOf(url: String) = category(url)
    private fun category(url: String): String {
        val p = url.substringAfter("//").substringAfter('/', "").substringBefore('?')
        return when {
            p.startsWith("cam/video/upload") -> "clip-upload"
            p.startsWith("cam/log") -> "log"
            p.startsWith("cam/snap") -> "snapshot"
            p.startsWith("cam/apk") -> "apk"
            p.isEmpty() -> "other"
            else -> "api:" + p.take(24)
        }
    }

    private fun fmt(b: Long) = if (b >= 1_048_576) String.format(Locale.US, "%.2f MB", b / 1_048_576.0) else String.format(Locale.US, "%.1f KB", b / 1024.0)

    private const val KEEP_HOURS = 24 * 14
    private const val HOUR_MS = 3_600_000L
    private lateinit var app: Context
    private val dirty = java.util.TreeSet<Long>()
    private var lastUpload = 0L

    /** Stored hourly buckets (hour index = epoch ms / 1 h, UTC), oldest first: [hour, sent, recv]. */
    @Synchronized fun hours(ctx: Context): List<LongArray> {
        val o = try { JSONObject(ctx.applicationContext.getSharedPreferences("datameter", Context.MODE_PRIVATE).getString("hours", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        return o.keys().asSequence().mapNotNull { k -> o.optJSONArray(k)?.let { longArrayOf(k.toLong(), it.optLong(0), it.optLong(1)) } }.sortedBy { it[0] }.toList()
    }

    @Synchronized private fun addToHour(ds: Long, dr: Long) {
        val prefs = app.getSharedPreferences("datameter", Context.MODE_PRIVATE)
        val o = try { JSONObject(prefs.getString("hours", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val h = System.currentTimeMillis() / HOUR_MS
        val a = o.optJSONArray(h.toString())
        o.put(h.toString(), JSONArray().put((a?.optLong(0) ?: 0L) + ds).put((a?.optLong(1) ?: 0L) + dr))
        val cut = h - KEEP_HOURS
        o.keys().asSequence().toList().filter { it.toLong() < cut }.forEach { o.remove(it) }
        prefs.edit().putString("hours", o.toString()).apply()
        dirty.add(h)
    }

    /** Pushes the changed hourly buckets (a few dozen bytes) to the relay; it merges them, the dashboard graphs them. */
    private fun upload() {
        val base = app.getSharedPreferences("cfg", Context.MODE_PRIVATE).let { (it.getString("url", "") ?: "").trimEnd('/') }
        val token = app.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("token", "")
        if (base.isEmpty() || token.isNullOrEmpty()) return
        val send = synchronized(this) { dirty.toList() }
        if (send.isEmpty()) return
        val all = hours(app).associateBy { it[0] }
        val arr = JSONArray()
        send.forEach { h -> all[h]?.let { arr.put(JSONArray().put(h).put(it[1]).put(it[2])) } }
        val body = JSONObject().put("hours", arr).put("tzMin", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
        val r = Net.postJson("$base/cam/usage", token, body)
        if (r != null && r.optBoolean("ok", false)) synchronized(this) { dirty.removeAll(send.toSet()) }
    }

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        synchronized(this) { val h = System.currentTimeMillis() / HOUR_MS; dirty.add(h); dirty.add(h - 1) }
        val prefs = app.getSharedPreferences("datameter", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        Thread {
            var lastSent = 0L; var lastRecv = 0L
            while (true) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { return@Thread }
                val s = sent.values.sumOf { it.get() }; val r = recv.values.sumOf { it.get() }
                if (s != lastSent || r != lastRecv) {
                    val today = day.format(Date())
                    var daySent = prefs.getLong("sent", 0L); var dayRecv = prefs.getLong("recv", 0L)
                    if (prefs.getString("day", "") != today) { daySent = 0; dayRecv = 0 }
                    daySent += s - lastSent; dayRecv += r - lastRecv
                    prefs.edit().putString("day", today).putLong("sent", daySent).putLong("recv", dayRecv).apply()
                    addToHour(s - lastSent, r - lastRecv)
                    val parts = sent.entries.filter { it.value.get() > 0 }.sortedByDescending { it.value.get() }
                        .joinToString(", ") { it.key + " " + fmt(it.value.get()) }
                    Logger.log(app, "data: +" + fmt(s - lastSent) + " sent, +" + fmt(r - lastRecv) + " recv this minute | today " + fmt(daySent) + " sent, " + fmt(dayRecv) + " recv | since start sent: " + parts)
                    lastSent = s; lastRecv = r
                }
                if (System.currentTimeMillis() - lastUpload >= 15 * 60_000L && synchronized(this) { dirty.isNotEmpty() }) {
                    lastUpload = System.currentTimeMillis(); try { upload() } catch (_: Throwable) {}
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}

/** Bar graph of the stored usage: hourly (last 24 h) or daily (last 14 days), sent (lime) stacked under received (grey). */
class DataGraph(ctx: Context, private val daily: Boolean) : android.view.View(ctx) {
    private val pal = ZTheme.palette(ZTheme.Mode.of(ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("theme", null)))
    private val d = ZScale.density(ctx)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val labels = ArrayList<String>(); private val s = ArrayList<Long>(); private val r = ArrayList<Long>()
    init {
        val now = System.currentTimeMillis(); val hourNow = now / 3_600_000L
        val off = java.util.TimeZone.getDefault().getOffset(now) / 3_600_000L.toDouble()
        val map = DataMeter.hours(ctx).associateBy { it[0] }
        if (!daily) {
            for (i in 23 downTo 0) {
                val h = hourNow - i; val row = map[h]
                s.add(row?.get(1) ?: 0L); r.add(row?.get(2) ?: 0L)
                labels.add(if (i % 6 == 0) String.format(Locale.US, "%02d", Math.floorMod((h + Math.round(off)), 24L)) else "")
            }
        } else {
            val dayNow = Math.floorDiv(hourNow + Math.round(off), 24L)
            for (i in 13 downTo 0) {
                val dd = dayNow - i
                var ss = 0L; var rr = 0L
                map.values.forEach { if (Math.floorDiv(it[0] + Math.round(off), 24L) == dd) { ss += it[1]; rr += it[2] } }
                s.add(ss); r.add(rr)
                labels.add(SimpleDateFormat("d/M", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(dd * 86_400_000L)))
            }
        }
    }
    private fun fmt(b: Long) = if (b >= 1_048_576) String.format(Locale.US, "%.1f MB", b / 1_048_576.0) else String.format(Locale.US, "%.0f KB", b / 1024.0)
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val top = 18 * d; val bot = 22 * d
        val mx = (0 until s.size).maxOfOrNull { s[it] + r[it] }?.coerceAtLeast(1L) ?: 1L
        paint.textSize = 11 * d; paint.color = pal.mut
        c.drawText(fmt(mx), 0f, 12 * d, paint)
        paint.color = pal.line; c.drawLine(0f, h - bot, w, h - bot, paint)
        val n = s.size; val slot = w / n; val bw = slot * 0.7f; val ph = h - bot - top
        for (i in 0 until n) {
            val x = i * slot + (slot - bw) / 2
            val hs = ph * s[i] / mx; val hr = ph * r[i] / mx
            paint.color = ZTheme.LIME; c.drawRect(x, h - bot - hs, x + bw, h - bot, paint)
            paint.color = pal.mut; c.drawRect(x, h - bot - hs - hr, x + bw, h - bot - hs, paint)
            if (labels[i].isNotEmpty()) { paint.color = pal.mut; paint.textAlign = android.graphics.Paint.Align.CENTER; c.drawText(labels[i], x + bw / 2, h - 6 * d, paint); paint.textAlign = android.graphics.Paint.Align.LEFT }
        }
    }
    fun totals() = "sent " + fmt(s.sum()) + "  |  received " + fmt(r.sum())
}
