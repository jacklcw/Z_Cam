package com.zeekr.sentrycam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Heartbeat: while the service runs, one timestamp a minute is appended to heartbeat.txt (local, last 48 h). Every ~5 min (and as
 * soon as the network is back) the beats the relay has not seen yet are pushed in one batch, so a gap that was only "no internet"
 * is filled in afterwards and a gap that stays means Z_Cam was not running (killed, wiped by the car's app cleaner, crashed).
 * The relay graphs it in ZDash (menu > Logs > Z_Cam heartbeat). Works standalone: with no relay linked it only writes locally.
 */
object Heartbeat {
    private const val EVERY_MS = 60_000L
    private const val PUSH_MS = 5 * 60_000L
    private const val KEEP = 2880
    private val bootMs = System.currentTimeMillis()   // process start: a change tells the relay Z_Cam restarted
    @Volatile private var thread: Thread? = null

    @Synchronized fun start(ctx: Context) {
        if (thread?.isAlive == true) return
        val app = ctx.applicationContext
        thread = Thread {
            var lastPush = 0L
            beat(app)
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(EVERY_MS) } catch (_: InterruptedException) { break }
                try {
                    beat(app)
                    val now = System.currentTimeMillis()
                    if (now - lastPush >= PUSH_MS) { push(app); lastPush = now }
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; name = "heartbeat"; start() }
    }

    @Synchronized fun stop() { thread?.interrupt(); thread = null }

    private fun file(ctx: Context) = File(ctx.filesDir, "heartbeat.txt")

    @Synchronized private fun beat(ctx: Context) {
        val f = file(ctx)
        val now = System.currentTimeMillis()
        try {
            f.appendText("$now\n")
            val lines = f.readLines()
            if (lines.size > KEEP) f.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
        } catch (_: Throwable) {}
    }

    private fun push(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val base = p.getString("url", "")?.trimEnd('/') ?: ""
        val token = p.getString("token", "")
        if (base.isEmpty() || token.isNullOrEmpty()) return
        val sent = p.getLong("hbSentTs", 0L)
        val beats = JSONArray()
        synchronized(this) { try { file(ctx).readLines().mapNotNull { it.toLongOrNull() }.filter { it > sent }.takeLast(600).forEach { beats.put(it) } } catch (_: Throwable) {} }
        if (beats.length() == 0) return
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Throwable) { "" }
        val r = Net.postJson("$base/cam/heartbeat", token, JSONObject().put("beats", beats).put("boot", bootMs).put("ver", ver))
        if (r != null && r.optBoolean("ok")) p.edit().putLong("hbSentTs", beats.getLong(beats.length() - 1)).apply()
    }
}
