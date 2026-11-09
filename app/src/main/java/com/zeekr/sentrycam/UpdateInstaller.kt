package com.zeekr.sentrycam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the latest APK (proxied by the relay from GitHub's private repo -- see
 * MainActivity.kt's checkForUpdate() and the relay's /cam/update* routes) into the app's
 * cache dir and hands it straight to the system installer. Same mechanism as the phone
 * app's UpdateInstaller, so the user never has to plug the head unit into anything or
 * manually sideload a file in the car.
 */
object UpdateInstaller {
    const val BASE = "https://zdash-updates.wai10691.workers.dev"
    // Gates the Worker against scanners only (embedded in compiled code, not real auth). Same key as the phone app.
    private const val CLIENT_KEY = "242dd459cbf963243bc9d0dc4a6b7f03c023fd8ea449e3ed76f2f6116d01f2c8"
    val HEADERS = mapOf("X-ZDash-Key" to CLIENT_KEY)

    /** Asks the update server and stores the result in prefs: "updAvail" = the newer version name, or "" when up to date. Null = server not reachable. */
    fun refresh(ctx: android.content.Context): String? {
        val r = Net.get("$BASE/v1/cam/update", null, HEADERS)
        if (r == null || !r.optBoolean("ok", false)) return null
        val my = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Throwable) { Int.MAX_VALUE }
        val ver = if (r.optInt("versionCode", 0) > my) r.optString("version", "").ifEmpty { "new" } else ""
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit().putString("updAvail", ver).putLong("updCheckedMs", System.currentTimeMillis()).apply()
        return ver
    }

    fun downloadAndInstall(act: Activity, url: String, onStatus: (String) -> Unit) {
        act.runOnUiThread {
            // The head unit's installer has a bug: tapping Open after install breaks later updates.
            ZDialog.show(act, "Before installing", "When the installer finishes, tap Back (top left), NOT Open. Otherwise later installs and updates fail.", "Got it, download") { download(act, url, onStatus) }
        }
    }

    private fun download(act: Activity, url: String, onStatus: (String) -> Unit) {
        act.runOnUiThread {
            // No canRequestPackageInstalls() pre-check: it returns false inside App Lab although the
            // system installer opens fine; launchInstaller() asks for permission only if it is refused.
            Logger.log(act, "Update: downloading from $url")
            onStatus("Downloading…")
            Thread {
                try {
                    val dir = File(act.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }   // no stale APKs piling up in the cache
                    val file = File(dir, "sentrycam-update.apk")
                    val conn = (URL(url).openConnection() as HttpURLConnection)
                    conn.connectTimeout = 10_000; conn.readTimeout = 60_000
                    HEADERS.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    try { conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } } }
                    finally { conn.disconnect() }
                    DataMeter.recv("update-download", file.length())
                    Logger.log(act, "Update: downloaded ${file.length()} bytes to ${file.path}")
                    val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
                    act.runOnUiThread { launchInstaller(act, uri, onStatus) }
                } catch (e: Throwable) {
                    Logger.log(act, "Update: download failed: ${e.message}")
                    act.runOnUiThread { onStatus("Download failed: ${e.message ?: "network error"}") }
                }
            }.start()
        }
    }

    // ACTION_VIEW with a package-archive mime type is the modern (Nougat+) way to hand an APK
    // to the installer, but some locked-down automotive builds only resolve a generic chooser
    // (no app actually registered to install it) or nothing at all -- confirmed: tapping Update
    // now opened a plain app chooser with no installer in it. Falls back to the older
    // ACTION_INSTALL_PACKAGE, which a few custom ROMs still wire up even without ACTION_VIEW
    // support. If neither resolves, this device likely has no system package installer at all,
    // so the only path left is sideloading over ADB (`adb install -r <apk>`).
    private fun launchInstaller(act: Activity, uri: Uri, onStatus: (String) -> Unit) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            Logger.log(act, "Update: handed APK to the installer via ACTION_VIEW.")
            return
        } catch (e: SecurityException) {
            Logger.log(act, "Update: installer refused (${e.message}), asking for install permission.")
            openInstallPermissionSettings(act, onStatus)
            return
        } catch (e: Throwable) { Logger.log(act, "Update: ACTION_VIEW installer failed: ${e.message}") }
        try {
            @Suppress("DEPRECATION")
            act.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            Logger.log(act, "Update: handed APK to the installer via ACTION_INSTALL_PACKAGE.")
            return
        } catch (e: Throwable) { Logger.log(act, "Update: ACTION_INSTALL_PACKAGE also failed: ${e.message}") }
        Logger.log(act, "Update: no package-installer app found on this device at all.")
        onStatus("This device has no app installer UI -- the update can't be installed in-app. It'll need sideloading over ADB (adb install -r) instead.")
    }

    // ACTION_MANAGE_UNKNOWN_APP_SOURCES (the direct per-app toggle) doesn't exist on every
    // automotive/custom ROM -- confirmed: tapping "Update now" did nothing on one, because the
    // startActivity threw and got silently swallowed, leaving no feedback at all. Falls back to
    // the app's details page (near-universal on Android 8+, usually has the same toggle inside
    // it), then to a plain-text instruction if even that isn't reachable, so there's always
    // some visible outcome instead of a dead button.
    private fun openInstallPermissionSettings(act: Activity, onStatus: (String) -> Unit) {
        onStatus("Allow this app to install updates, then tap Update now again.")
        val pkgUri = Uri.parse("package:${act.packageName}")
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Logger.log(act, "Update: opened the unknown-sources settings screen.")
            return
        } catch (e: Throwable) { Logger.log(act, "Update: ACTION_MANAGE_UNKNOWN_APP_SOURCES unavailable: ${e.message}") }
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Logger.log(act, "Update: opened the app-details settings screen instead.")
            onStatus("Opened this app's Settings page -- look for \"Install unknown apps\" there, turn it on, then tap Update now again.")
            return
        } catch (e: Throwable) { Logger.log(act, "Update: ACTION_APPLICATION_DETAILS_SETTINGS also unavailable: ${e.message}") }
        onStatus("Couldn't open any settings screen on this device to grant install permission. Check View log for details.")
    }
}
