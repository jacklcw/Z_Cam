package com.zeekr.sentrycam

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Offline snapshot queue: a snapshot the relay couldn't take is kept on disk (with its original capture time
 * and place) and sent, oldest first, once the relay answers again. The image is stored as the raw encoded
 * bytes (<ts>_<cam>.img) next to a small <ts>_<cam>.meta JSON -- base64 inside JSON would cost 33% more
 * flash for nothing, and an already-encoded WebP/JPEG doesn't compress further without losing quality.
 * Older <ts>_<cam>.json entries (image inside the JSON) are still flushed. Capped at [MAX_FILES] snapshots
 * and [MAX_BYTES] in total; the oldest go first.
 */
object SnapQueue {
    private const val MAX_FILES = 40
    private const val MAX_BYTES = 24_000_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "snapq").also { it.mkdirs() }

    // One entry per snapshot, oldest first: the .img (or legacy .json) file that carries it.
    private fun entries(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.name.endsWith(".img") || it.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun metaOf(img: File) = File(img.parentFile, img.name.removeSuffix(".img") + ".meta")
    private fun remove(img: File) { img.delete(); if (img.name.endsWith(".img")) metaOf(img).delete() }
    private fun size(img: File) = img.length() + (if (img.name.endsWith(".img")) metaOf(img).length() else 0L)

    /** Snapshots waiting and their size on disk (cheap: file listing only). */
    @Synchronized fun stats(ctx: Context): Pair<Int, Long> {
        val e = entries(ctx); return e.size to e.sumOf { size(it) }
    }

    /** [meta] is the upload body without the image. Returns how many snapshots are waiting afterwards. */
    @Synchronized fun add(ctx: Context, jpeg: ByteArray, meta: JSONObject): Int {
        try {
            val name = "${meta.optLong("ts")}_${meta.optString("cam")}"
            val d = dir(ctx)
            // meta first, image last (and renamed into place): a half-written entry is never picked up as an .img
            File(d, "$name.meta").writeText(meta.toString())
            val tmp = File(d, "$name.tmp"); tmp.writeBytes(jpeg); tmp.renameTo(File(d, "$name.img"))
            val e = entries(ctx).toMutableList()
            var total = e.sumOf { size(it) }
            while (e.size > MAX_FILES || (total > MAX_BYTES && e.size > 1)) { val o = e.removeAt(0); total -= size(o); remove(o) }
            return e.size
        } catch (_: Throwable) { return stats(ctx).first }
    }

    /**
     * Sends up to [max] queued snapshots through [send] (returns the HTTP status, 0 = unreachable). Stops at the
     * first one the relay can't take right now. Returns (sent, left).
     */
    @Synchronized fun flush(ctx: Context, max: Int, send: (JSONObject) -> Int): Pair<Int, Int> {
        var sent = 0
        for (f in entries(ctx).take(max)) {
            val body = try {
                if (f.name.endsWith(".json")) JSONObject(f.readText())
                else JSONObject(metaOf(f).readText()).put("jpeg", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            } catch (_: Throwable) { remove(f); continue }
            val st = send(body)
            if (st in 200..299 || st in 400..499 && st != 401 && st != 429) { remove(f); if (st in 200..299) sent++ } else break
        }
        // sweep leftovers of an interrupted add()
        dir(ctx).listFiles()?.filter { it.name.endsWith(".tmp") || (it.name.endsWith(".meta") && !File(it.parentFile, it.name.removeSuffix(".meta") + ".img").exists()) }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 60_000L }?.forEach { it.delete() }
        return sent to stats(ctx).first
    }
}
