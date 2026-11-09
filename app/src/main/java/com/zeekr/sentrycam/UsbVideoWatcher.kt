package com.zeekr.sentrycam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches for new video files Sentry mode writes to the car's USB dashcam storage. Two
 * independent sources, both reported into the same catalog/pipeline:
 *
 *  - The Sentry folder (found via direct filesystem access, see MainActivity's
 *    pickSentryFolder flow -- ZeekrOS automotive has no DocumentsUI, so Storage Access
 *    Framework's picker isn't usable here): Sentry's actual incident clips live in dated
 *    subfolders (e.g. "SentryMode/2026-10-23_16_42_44/..._60.mp4") that Android's MediaStore
 *    does NOT automatically index -- confirmed live: 372 older continuous-recording clips were
 *    indexed, zero of the SentryMode folder's clips ever were. Listing the folder directly is
 *    independent of MediaStore, so this is the primary/reliable source.
 *  - MediaStore (legacy): whatever else happens to already be indexed (the 372 older clips
 *    above). Kept as a secondary source so those remain browsable; no longer relied on for
 *    detecting new Sentry recordings.
 *
 * Sentry-folder entries are reported with a dashed name ("2026-10-23_16_42_44_sentry.mp4",
 * matching the folder's own naming) so the dashboard can tell the two sources' filenames
 * apart and parse either -- see dashpage.js's videoDateMs().
 */
class UsbVideoWatcher(private val ctx: Context) {
    private var observer: ContentObserver? = null
    private val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    // What the watcher is doing right now, folded into the next diagnostic report so a
    // "Check now" from the phone says more than just "OK, N files" while a clip is mid-flight.
    @Volatile private var currentActivity: String? = null

    // All background work runs on two fixed threads instead of a fresh Thread per trigger (a MediaStore
    // change storm or a slow network used to stack dozens of threads, and overlapping checks reported
    // clips twice): [checks] for scans/reports, [uploads] for dashboard-requested files so a long
    // upload never blocks the next scan. A job already waiting in the queue is not queued twice.
    private val checks = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val uploads = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun once(key: String, job: () -> Unit) {
        if (!queued.add(key)) return
        try {
            checks.execute { queued.remove(key); try { job() } catch (e: Throwable) { log("$key failed: ${e.message}") } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { queued.remove(key) }
    }

    fun start() {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { log("MediaStore video content changed -- checking."); checkNew() }
        }
        observer = obs
        try { ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs) }
        catch (e: Throwable) { log("Couldn't register the MediaStore observer: ${e.message}") }
        reportDiagnostics()   // one clear line the phone can see without anyone going to the car
        checkNew()            // legacy MediaStore source
        checkSentryFolder()   // primary source: the picked SentryMode folder
        listAllVideos()       // populate the dashboard's browsable catalog of everything on USB
    }

    // Called when the dashboard's "Check now" button sets videoCheckReqMs -- same diagnostic
    // as a service start, on demand, plus a fresh scan for anything not yet picked up.
    fun forceCheck() { log("Check now requested from the dashboard."); reportDiagnostics(); checkNew(); checkSentryFolder(); listAllVideos() }

    private fun sentryFolder(): java.io.File? {
        val s = prefs.getString("sentryFolderPath", null) ?: return null
        return java.io.File(s)
    }

    // ---- Sentry folder (direct filesystem access) -- the primary, reliable source ----

    // True for the dashed name convention this class reports Sentry-folder clips under
    // ("2026-10-23_16_42_44_sentry.mp4"), vs MediaStore's compact one ("20261023_164244_x.mp4").
    private fun isSentryFolderName(name: String): Boolean = name.contains("-")

    // Guards against two overlapping runs (e.g. the service restarting while a previous check's
    // backlog of network reports is still in flight) both reading the same stale lastSentryFolder
    // and reporting the same clips twice -- confirmed live, every incident in one burst appeared
    // exactly twice in the Activity tab.
    @Volatile private var checkingSentryFolder = false

    private fun checkSentryFolder() {
        val root = sentryFolder() ?: return   // not picked yet -- reportDiagnostics() says so
        if (checkingSentryFolder) return
        checkingSentryFolder = true
        once("sentryFolder") {
            try {
                val base = (prefs.getString("url", "") ?: "").trimEnd('/')
                val token = prefs.getString("token", "")
                if (base.isEmpty() || token.isNullOrEmpty()) { log("Sentry folder check skipped: not linked yet."); return@once }
                if (!root.isDirectory) { log("Sentry folder check: picked folder is no longer accessible."); return@once }
                // The folder could only ever be read starting with the fix that made it
                // accessible at all (ZeekrOS has no SAF picker) -- so the first check here can
                // find a long pre-existing backlog with nothing actually "new". Same baselining
                // idiom as checkNew()'s firstEver: skip straight to the newest existing folder
                // instead of reporting the whole backlog as incidents just detected.
                val firstEver = !prefs.contains("lastSentryFolder")
                val lastFolder = prefs.getString("lastSentryFolder", "") ?: ""
                val folders = (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
                    .sortedBy { it.name }   // "YYYY-MM-DD_HH_MM_SS" sorts chronologically as a plain string
                if (firstEver) {
                    val newest = folders.lastOrNull()?.name ?: ""
                    log("First Sentry folder check ever -- baselining to $newest so the existing backlog isn't all reported as new.")
                    prefs.edit().putString("lastSentryFolder", newest).apply()
                    return@once
                }
                var newest = lastFolder
                var found = 0
                // Stops advancing the cursor past the first still-writing folder, even if a
                // later (newer) one already finished and gets reported -- otherwise a finished
                // newer incident would push lastSentryFolder ahead of a still-writing older one,
                // and that older incident would never be examined again once it does finish.
                var gap = false
                for (folder in folders) {
                    val fname = folder.name
                    if (fname <= lastFolder) continue
                    val video = folder.listFiles()?.firstOrNull { it.name.endsWith(".mp4", ignoreCase = true) }
                    if (video == null) { log("Sentry folder entry $fname has no .mp4 inside (still writing?) -- will retry next check."); gap = true; continue }
                    if (video.length() == 0L) { log("Sentry folder entry $fname's clip is 0 bytes (still writing?) -- will retry next check."); gap = true; continue }
                    found++
                    reportSentryFolderClip(base, token, fname, video)
                    if (!gap) newest = fname
                }
                log("Sentry folder check: $found new incident(s) since " + (if (lastFolder.isEmpty()) "(first check)" else lastFolder) + ".")
                prefs.edit().putString("lastSentryFolder", newest).apply()
            } catch (e: Throwable) { log("Sentry folder check failed: ${e.message}") }
            finally { checkingSentryFolder = false }
        }
    }

    private fun reportSentryFolderClip(base: String, token: String?, folderName: String, video: java.io.File) {
        val displayName = "${folderName}_sentry.mp4"
        val size = video.length()
        val mb = Math.round(size / 1024.0 / 1024.0 * 10) / 10.0
        log("New Sentry incident detected: $folderName ($mb MB)", remote = true)
        currentActivity = "processing $folderName"
        try {
            val res = Net.postJson("$base/cam/video/new", token, JSONObject().put("filename", displayName).put("sizeBytes", size).put("ts", System.currentTimeMillis()))
            if (res == null) { log("Couldn't reach the relay to report $folderName (network error).", remote = false); return }
            if (!res.optBoolean("ok", false)) { log("Relay rejected the report for $folderName: $res", remote = false); return }
            val id = res.optLong("id", 0L)
            if (id <= 0) { log("$folderName not recorded -- Sentry video alerts is probably turned off in the dashboard.", remote = false); return }
            if (!res.optBoolean("wantUpload", false)) { log("$folderName recorded, upload not requested (toggle off in the dashboard)."); return }
            currentActivity = "uploading $folderName"
            log("Uploading $folderName ($mb MB)…", remote = true)
            val r = uploadFile(base, token, video, id)
            log(if (r.ok) "Uploaded $folderName successfully." else "Upload failed for $folderName: " + uploadFailReason(r), remote = true)
        } finally { currentActivity = null }
    }

    // Finds the dated subfolder matching a dashed display name ("2026-10-23_16_42_44_sentry.mp4"
    // -> folder "2026-10-23_16_42_44") and its .mp4, for an explicit "Request" from the catalog.
    private fun findSentryFolderClip(name: String): java.io.File? {
        val root = sentryFolder() ?: return null
        val folderName = name.removeSuffix("_sentry.mp4")
        val folder = java.io.File(root, folderName)
        if (!folder.isDirectory) return null
        return folder.listFiles()?.firstOrNull { it.name.endsWith(".mp4", ignoreCase = true) }
    }

    private fun listSentryFolderVideos(): List<Triple<String, Long, Long>> {
        val root = sentryFolder() ?: return emptyList()
        return try {
            (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
                .sortedByDescending { it.name }
                .mapNotNull { folder ->
                    val video = folder.listFiles()?.firstOrNull { it.name.endsWith(".mp4", ignoreCase = true) } ?: return@mapNotNull null
                    Triple("${folder.name}_sentry.mp4", video.length(), video.lastModified())
                }
        } catch (e: Throwable) { log("Listing Sentry folder failed: ${e.message}"); emptyList() }
    }

    // ---- Called when the dashboard's "Request" button on a catalog row sets requestedVideo --
    // uploads that specific already-existing file regardless of whether Sentry video alerts'
    // own "Detect"/"Upload" toggles are even on, since this is an explicit user pull. ----
    // Set when the dashboard cancels the request: the in-flight requested upload stops at the next piece.
    @Volatile var abortUpload = false

    fun uploadRequested(name: String) {
        try { uploads.execute { uploadRequestedNow(name) } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun uploadRequestedNow(name: String) {
        run {
            abortUpload = false
            val base = (prefs.getString("url", "") ?: "").trimEnd('/')
            val token = prefs.getString("token", "")
            if (base.isEmpty() || token.isNullOrEmpty()) { log("Requested upload skipped: not linked yet."); return }
            currentActivity = "uploading requested $name"
            try {
                if (isSentryFolderName(name)) {
                    val video = findSentryFolderClip(name)
                    if (video == null) { log("Requested upload: $name not found in the Sentry folder (removed?)."); return }
                    log("Uploading requested file $name…", remote = true)
                    val res = Net.postJson("$base/cam/video/request/report", token, JSONObject().put("filename", name).put("sizeBytes", video.length()))
                    val id = res?.optLong("id", 0L) ?: 0L
                    if (res == null || !res.optBoolean("ok", false) || id <= 0) { log("Relay rejected the request-report for $name.", remote = true); return }
                    val r = uploadFile(base, token, video, id, res.optInt("have", 0))
                    log(if (r.ok) "Uploaded requested file $name successfully." else "Upload failed for requested file $name: " + uploadFailReason(r), remote = true)
                } else {
                    uploadRequestedFromMediaStore(base, token, name)
                }
            } finally { currentActivity = null }
        }
    }

    private fun uploadRequestedFromMediaStore(base: String, token: String, name: String) {
        val volumes = usbVolumes()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.VOLUME_NAME)
        val sel = MediaStore.Video.Media.DISPLAY_NAME + " = ?"
        var mediaId = -1L; var size = 0L
        try {
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(name), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val volCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME) else -1
                while (c.moveToNext()) {
                    if (volCol >= 0 && volumes.isNotEmpty() && !volumes.contains(c.getString(volCol))) continue
                    mediaId = c.getLong(idCol); size = c.getLong(sizeCol); break
                }
            }
        } catch (e: Throwable) { log("Requested upload: query for $name failed: ${e.message}"); return }
        if (mediaId < 0) { log("Requested upload: $name not found on USB storage (removed?)."); return }
        log("Uploading requested file $name…", remote = true)
        try {
            val res = Net.postJson("$base/cam/video/request/report", token, JSONObject().put("filename", name).put("sizeBytes", size))
            val id = res?.optLong("id", 0L) ?: 0L
            if (res == null || !res.optBoolean("ok", false) || id <= 0) { log("Relay rejected the request-report for $name.", remote = true); return }
            val r = uploadMedia(base, token, mediaId, id, res.optInt("have", 0))
            log(if (r.ok) "Uploaded requested file $name successfully." else "Upload failed for requested file $name: " + uploadFailReason(r), remote = true)
        } catch (e: Throwable) { log("Requested upload threw for $name: ${e.message}") }
    }

    // The car app's full view of what's on USB storage, not just newly-detected clips -- lets
    // the dashboard show (and let the user request) anything already on the drive. Merges both
    // sources: the Sentry folder first (the one that actually matters), MediaStore second.
    private fun listAllVideos() {
        once("listAll") {
            val base = (prefs.getString("url", "") ?: "").trimEnd('/')
            val token = prefs.getString("token", "")
            if (base.isEmpty() || token.isNullOrEmpty()) return@once
            val arr = JSONArray()
            for ((name, size, dateMs) in listSentryFolderVideos()) {
                if (arr.length() >= 500) break
                arr.put(JSONObject().put("name", name).put("size", size).put("dateMs", dateMs))
            }
            val volumes = usbVolumes()
            if (!(Build.VERSION.SDK_INT >= 29 && volumes.isEmpty())) {
                try {
                    val proj = arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.VOLUME_NAME)
                    ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                        val volCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME) else -1
                        while (c.moveToNext() && arr.length() < 500) {
                            if (volCol >= 0 && volumes.isNotEmpty() && !volumes.contains(c.getString(volCol))) continue
                            arr.put(JSONObject().put("name", c.getString(nameCol) ?: "video.mp4").put("size", c.getLong(sizeCol)).put("dateMs", c.getLong(dateCol) * 1000))
                        }
                    }
                } catch (e: Throwable) { log("Listing MediaStore videos failed: ${e.message}") }
            }
            val ok = Net.postJson("$base/cam/video/full-list", token, JSONObject().put("videos", arr))?.optBoolean("ok", false) ?: false
            log("Sent USB video catalog (${arr.length()} file(s)) to relay: " + (if (ok) "ok" else "failed"))
        }
    }

    // Local-only log (always) plus an optional relay/Activity-tab copy (meaningful events only,
    // so routine per-check noise doesn't spam the dashboard -- it's still all visible locally).
    private fun log(msg: String, remote: Boolean = false) {
        Logger.log(ctx, msg)
        if (!remote) return
        val base = (prefs.getString("url", "") ?: "").trimEnd('/')
        val token = prefs.getString("token", "")
        if (base.isEmpty() || token.isNullOrEmpty()) return
        try { Net.postJson("$base/cam/log", token, JSONObject().put("msg", msg).put("ts", System.currentTimeMillis()).put("log", true)) } catch (_: Throwable) {}
    }

    // Runs once per service start (and on "Check now"): says plainly whether this install can
    // actually see Sentry's clips, so a "Sentry video alerts" toggle that silently never fires
    // isn't the only feedback the user gets. Logged to the relay's Activity tab (not just the
    // CCTV card's "last report", which the next routine status message overwrites) AND to the
    // local log, which works even with no relay connection at all.
    private fun reportDiagnostics() {
        once("diag") {
            val busy = currentActivity
            if (busy != null) { log("Check now: $busy", remote = true); return@once }
            val root = sentryFolder()
            if (root == null) {
                log("USB storage check: Sentry folder not selected yet -- tap \"Select SentryMode folder\" in the car app so new incidents can be detected (MediaStore does not see them).", remote = true)
            } else if (!root.isDirectory) {
                log("USB storage check: the picked Sentry folder is no longer accessible (USB unplugged, or permission lost). Re-select it.", remote = true)
            } else {
                val n = try { root.listFiles()?.count { it.isDirectory } ?: -1 } catch (e: Throwable) { -1 }
                log("USB storage check: OK -- Sentry folder has $n incident(s). Watching for new ones.", remote = true)
            }
            val videoPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(ctx, videoPerm) != PackageManager.PERMISSION_GRANTED) {
                log("MediaStore check: no permission to read video storage (legacy source only -- the Sentry folder above doesn't need this).")
                return@once
            }
            val volumes = usbVolumes()
            log("USB volumes visible to MediaStore: " + (if (volumes.isEmpty()) "none" else volumes.joinToString(", ")))
        }
    }

    fun stop() {
        observer?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Throwable) {} }
        observer = null
        checks.shutdownNow(); uploads.shutdownNow()
    }

    // The head unit's own internal storage is "external_primary" in MediaStore terms (it's
    // "external" only relative to app-private storage); an actual USB drive shows up as a
    // separate, non-primary volume. Restrict to that so this app's own test recordings (if
    // any end up on internal storage) and other apps' media on internal storage don't count.
    private fun usbVolumes(): Set<String> {
        if (Build.VERSION.SDK_INT < 29) return emptySet()
        return try { MediaStore.getExternalVolumeNames(ctx).filter { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }.toSet() }
        catch (e: Throwable) { log("getExternalVolumeNames failed: ${e.message}"); emptySet() }
    }

    // ---- MediaStore (legacy secondary source) ----

    private fun checkNew() {
        once("mediaStore") {
            try {
                val base = (prefs.getString("url", "") ?: "").trimEnd('/')
                val token = prefs.getString("token", "")
                if (base.isEmpty() || token.isNullOrEmpty()) { log("MediaStore check skipped: not linked yet."); return@once }
                val firstEver = !prefs.contains("lastVideoMs")
                val lastMs = prefs.getLong("lastVideoMs", System.currentTimeMillis())
                if (firstEver) log("First MediaStore check ever -- baselining to now ($lastMs) so existing clips aren't all reported as new.")
                val volumes = if (Build.VERSION.SDK_INT >= 29) usbVolumes() else null
                if (Build.VERSION.SDK_INT >= 29 && volumes!!.isEmpty()) { log("MediaStore check skipped: no USB volume mounted."); return@once }

                val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.VOLUME_NAME)
                val sel = MediaStore.Video.Media.DATE_ADDED + " > ?"
                val args = arrayOf((lastMs / 1000).toString())
                var newestMs = lastMs
                var found = 0
                val c = ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, MediaStore.Video.Media.DATE_ADDED + " ASC")
                if (c == null) { log("MediaStore check: query returned null."); return@once }
                c.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val volCol = if (Build.VERSION.SDK_INT >= 29) it.getColumnIndex(MediaStore.Video.Media.VOLUME_NAME) else -1
                    while (it.moveToNext()) {
                        if (volCol >= 0 && volumes != null && !volumes.contains(it.getString(volCol))) continue
                        found++
                        val id = it.getLong(idCol)
                        val name = it.getString(nameCol) ?: "video.mp4"
                        val size = it.getLong(sizeCol)
                        val addedMs = it.getLong(dateCol) * 1000
                        if (addedMs > newestMs) newestMs = addedMs
                        report(base, token, id, name, size)
                    }
                }
                log("MediaStore check: $found new video(s) since " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastMs)) + ".")
                prefs.edit().putLong("lastVideoMs", newestMs).apply()
            } catch (e: Throwable) { log("MediaStore check failed: ${e.message}") }
        }
    }

    private fun report(base: String, token: String?, mediaId: Long, name: String, size: Long) {
        val mb = Math.round(size / 1024.0 / 1024.0 * 10) / 10.0
        log("New video detected (MediaStore): $name ($mb MB)", remote = true)
        currentActivity = "processing $name"
        try {
            val res = Net.postJson("$base/cam/video/new", token, JSONObject().put("filename", name).put("sizeBytes", size).put("ts", System.currentTimeMillis()))
            if (res == null) { log("Couldn't reach the relay to report $name (network error).", remote = false); return }
            if (!res.optBoolean("ok", false)) { log("Relay rejected the report for $name: $res", remote = false); return }
            val id = res.optLong("id", 0L)
            if (id <= 0) { log("$name not recorded -- Sentry video alerts is probably turned off in the dashboard.", remote = false); return }
            if (!res.optBoolean("wantUpload", false)) { log("$name recorded, upload not requested (toggle off in the dashboard)."); return }
            currentActivity = "uploading $name"
            log("Uploading $name ($mb MB)…", remote = true)
            val r = uploadMedia(base, token, mediaId, id)
            log(if (r.ok) "Uploaded $name successfully." else "Upload failed for $name: " + uploadFailReason(r), remote = true)
        } finally { currentActivity = null }
    }

    // Streams one MediaStore item's bytes to the relay for a clip already reported via
    // /cam/video/new or /cam/video/request/report (which hands back `id`).
    private fun uploadMedia(base: String, token: String?, mediaId: Long, id: Long, have: Int = 0): Net.UploadResult {
        return try {
            val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId.toString())
            var result = Net.UploadResult(false, -1, "couldn't open the file (gone / permission?)")
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                result = Net.uploadInPieces("$base/cam/video/upload?id=$id", "$base/cam/video/upload/complete?id=$id", token, stream, startSeq = have, abort = { abortUpload })
            }
            result
        } catch (e: Throwable) { Net.UploadResult(false, -1, e.message) }
    }

    // Same as uploadMedia() but for a plain File (the Sentry folder's own clips).
    private fun uploadFile(base: String, token: String?, file: java.io.File, id: Long, have: Int = 0): Net.UploadResult {
        return try {
            var result = Net.UploadResult(false, -1, "couldn't open the file (gone / permission?)")
            file.inputStream().use { stream ->
                result = Net.uploadInPieces("$base/cam/video/upload?id=$id", "$base/cam/video/upload/complete?id=$id", token, stream, startSeq = have, abort = { abortUpload })
            }
            result
        } catch (e: Throwable) { Net.UploadResult(false, -1, e.message) }
    }

    // The relay streams uploads straight into D1 now (no buffered size cap for real clips), but
    // still rejects anything past a generous 300MB sanity ceiling -- called out by name rather
    // than just showing the raw HTTP status, in case that's ever actually hit.
    private fun uploadFailReason(r: Net.UploadResult): String {
        if (r.status == 413 || (r.error?.contains("too_large", ignoreCase = true) == true)) {
            return "the clip is over the 300MB upload limit."
        }
        if (r.status > 0) return "relay returned HTTP ${r.status}" + (if (!r.error.isNullOrBlank()) " (${r.error})" else "")
        return r.error ?: "network error"
    }
}
