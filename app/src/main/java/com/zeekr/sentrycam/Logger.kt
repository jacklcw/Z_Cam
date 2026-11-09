package com.zeekr.sentrycam

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log the user can open from the app itself -- separate from the relay's
 * Activity tab, which needs a working connection to even show up. Bounded so it never grows
 * unbounded on a car that's been running for weeks: lines are only APPENDED (a few bytes of
 * flash per line); the file is compacted back to the last MAX_LINES (via a temp file + atomic
 * rename, so a power cut can't leave a half-written log) only once it reaches 2 x MAX_LINES.
 */
object Logger {
    private const val MAX_LINES = 600                 // what read() returns (relay upload, tail checks)
    private const val KEEP_MS = 3L * 24 * 3600 * 1000 // on-device retention
    private const val MAX_KEEP_LINES = 100_000        // safety cap on the file
    private const val COMPACT_EVERY = 2000
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var lineCount = -1   // lazily counted from the file on first use
    private var sinceCompact = 0

    // Lines older than KEEP_MS are dropped (timestamps carry no year: one that parses into the future belongs to last year).
    private fun fresh(line: String, now: Long): Boolean {
        if (line.length < 14) return true
        val t = try { fmt.parse(line.substring(0, 14)) } catch (_: Throwable) { null } ?: return true
        val c = java.util.Calendar.getInstance().apply { time = t; set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) }
        var ms = c.timeInMillis
        if (ms > now + 24 * 3600 * 1000L) { c.add(java.util.Calendar.YEAR, -1); ms = c.timeInMillis }
        return now - ms <= KEEP_MS
    }

    @Synchronized fun log(ctx: Context, msg: String) {
        try {
            val file = File(ctx.filesDir, "cam_log.txt")
            if (lineCount < 0) { lineCount = try { file.readLines().size } catch (_: Throwable) { 0 }; sinceCompact = COMPACT_EVERY }
            file.appendText(fmt.format(Date()) + "  " + msg + "\n")
            lineCount++; sinceCompact++
            if (sinceCompact >= COMPACT_EVERY) {
                sinceCompact = 0
                val now = System.currentTimeMillis()
                val keep = file.readLines().filter { fresh(it, now) }.takeLast(MAX_KEEP_LINES)
                val tmp = File(ctx.filesDir, "cam_log.tmp")
                tmp.writeText(keep.joinToString("\n") + "\n")
                if (tmp.renameTo(file)) lineCount = keep.size else tmp.delete()
            }
        } catch (_: Throwable) {}
    }

    @Synchronized fun read(ctx: Context): String = try {
        File(ctx.filesDir, "cam_log.txt").readLines().takeLast(MAX_LINES).joinToString("\n") + "\n"
    } catch (_: Throwable) { "" }

    /** The whole retained log (up to 3 days) for the on-device viewer; the relay upload keeps using read(). */
    @Synchronized fun readAll(ctx: Context, maxLines: Int = 20_000): String = try {
        File(ctx.filesDir, "cam_log.txt").readLines().takeLast(maxLines).joinToString("\n") + "\n"
    } catch (_: Throwable) { "" }

    @Synchronized fun clear(ctx: Context) { lineCount = 0; try { File(ctx.filesDir, "cam_log.txt").delete() } catch (_: Throwable) {} }
}
