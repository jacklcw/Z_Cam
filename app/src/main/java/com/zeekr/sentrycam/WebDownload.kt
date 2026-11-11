package com.zeekr.sentrycam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads for the floating browser. Plain links are fetched natively (with the page's cookies); blob: links, data: links and links
 * on the page's own site (Telegram Web streams files through its service worker) are read by the page itself and handed over in
 * pieces. Files land in the app's Downloads folder and are copied to the public Downloads folder; an APK is then offered to the installer.
 */
object WebDownload {
    private val main = Handler(Looper.getMainLooper())
    private val open = ConcurrentHashMap<String, Job>()
    private var seq = 0

    /** Run once per page: remembers the file name a page gives to a blob download (a[download]), which the download callback does not carry. */
    const val HOOK_JS = "(function(){if(window.__zdl)return;window.__zdl=1;var oc=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){if(this.download)window.__dlname=this.download;return oc.apply(this,arguments)};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(a)window.__dlname=a.download},true)})()"

    private class Job(val ctx: Context, val file: File, val out: FileOutputStream, val mime: String)

    private fun toast(ctx: Context, t: String) { main.post { try { Toast.makeText(ctx, t, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} } }

    private fun dir(ctx: Context): File = (ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "Download")).apply { mkdirs() }

    private fun safe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "download" }.take(120)

    private fun target(ctx: Context, name: String): File {
        var f = File(dir(ctx), safe(name))
        if (!f.exists()) return f
        val dot = f.name.lastIndexOf('.').let { if (it <= 0) f.name.length else it }
        val base = f.name.substring(0, dot); val ext = f.name.substring(dot)
        var i = 1
        while (f.exists()) { f = File(f.parentFile, "$base ($i)$ext"); i++ }
        return f
    }

    fun start(ctx: Context, wv: WebView, url: String, ua: String?, cd: String?, mime: String?, pageUrl: String?) {
        val guess = URLUtil.guessFileName(url, cd, mime)
        val sameSite = try { val h = URL(url).host; val p = URL(pageUrl ?: "").host; h.isNotEmpty() && h == p } catch (_: Throwable) { false }
        when {
            url.startsWith("blob:") || (sameSite && url.startsWith("http")) -> viaPage(ctx, wv, url, guess, mime ?: "")
            url.startsWith("data:") -> Thread { try { fromData(ctx, url, guess) } catch (e: Throwable) { toast(ctx, "Download failed: ${e.message}") } }.start()
            url.startsWith("http") -> Thread { try { fromHttp(ctx, url, ua, pageUrl, guess, mime) } catch (e: Throwable) { toast(ctx, "Download failed: ${e.message}") } }.start()
            else -> toast(ctx, "Cannot download this kind of link")
        }
    }

    private fun fromHttp(ctx: Context, url: String, ua: String?, ref: String?, guess: String, mime: String?) {
        toast(ctx, "Downloading $guess")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        ua?.let { c.setRequestProperty("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        ref?.let { c.setRequestProperty("Referer", it) }
        if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
        val f = target(ctx, guess)
        c.inputStream.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        done(ctx, f, c.contentType ?: mime ?: "")
    }

    private fun fromData(ctx: Context, url: String, guess: String) {
        val comma = url.indexOf(','); val head = url.substring(5, comma); val body = url.substring(comma + 1)
        val bytes = if (head.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
        val f = target(ctx, guess); f.writeBytes(bytes)
        done(ctx, f, head.substringBefore(';'))
    }

    private fun viaPage(ctx: Context, wv: WebView, url: String, guess: String, mime: String) {
        val id = "d" + (++seq)
        // the page's own name for the file (a[download]) beats the guessed one
        wv.evaluateJavascript("(function(){var n=window.__dlname||'';window.__dlname='';return n})()") { raw ->
            val fromPage = try { JSONObject("{\"v\":" + (raw ?: "\"\"") + "}").optString("v") } catch (_: Throwable) { "" }
            val name = if (fromPage.isNotEmpty()) fromPage else guess
            toast(ctx, "Downloading $name")
            val js = "(function(u,id,name){fetch(u,{credentials:'include'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.blob()})" +
                ".then(function(b){var n=0,S=393216;ZDl.begin(id,name,b.type||'');function nxt(){if(n>=b.size){ZDl.end(id);return}var rd=new FileReader();" +
                "rd.onload=function(){var s=rd.result;ZDl.chunk(id,s.substring(s.indexOf(',')+1));n+=S;nxt()};rd.readAsDataURL(b.slice(n,n+S))}nxt()})" +
                ".catch(function(e){ZDl.fail(id,String(e))})})(${JSONObject.quote(url)},'$id',${JSONObject.quote(name)})"
            wv.evaluateJavascript(js, null)
        }
    }

    /** The page's side of a hand-over: begin / chunk / end / fail, called from the page's thread. */
    class Bridge(private val ctx: Context) {
        @android.webkit.JavascriptInterface fun begin(id: String, name: String, mime: String) {
            try { val f = target(ctx, name); open[id] = Job(ctx, f, FileOutputStream(f), mime) } catch (e: Throwable) { toast(ctx, "Download failed: ${e.message}") }
        }
        @android.webkit.JavascriptInterface fun chunk(id: String, b64: String) {
            try { open[id]?.out?.write(Base64.decode(b64, Base64.DEFAULT)) } catch (e: Throwable) { open.remove(id)?.let { try { it.out.close(); it.file.delete() } catch (_: Throwable) {} }; toast(ctx, "Download failed: ${e.message}") }
        }
        @android.webkit.JavascriptInterface fun end(id: String) {
            val j = open.remove(id) ?: return
            try { j.out.close() } catch (_: Throwable) {}
            Thread { done(ctx, j.file, j.mime) }.start()
        }
        @android.webkit.JavascriptInterface fun fail(id: String, msg: String) {
            open.remove(id)?.let { try { it.out.close(); it.file.delete() } catch (_: Throwable) {} }
            toast(ctx, "Download failed: $msg")
        }
    }

    private fun done(ctx: Context, f: File, mime: String) {
        Logger.log(ctx, "download: saved ${f.name} (${f.length()} bytes)")
        publicCopy(ctx, f, mime)
        val apk = f.name.endsWith(".apk", true) || mime.contains("android.package-archive")
        toast(ctx, "Saved to Downloads: ${f.name}")
        if (apk) main.post { install(ctx, f) }
    }

    private fun publicCopy(ctx: Context, f: File, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, if (f.name.endsWith(".apk", true)) "application/vnd.android.package-archive" else mime.ifEmpty { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
                ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            } else {
                f.copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name), overwrite = true)
            }
        } catch (e: Throwable) { Logger.log(ctx, "download: public copy failed ${e.javaClass.simpleName} ${e.message ?: ""}") }
    }

    /** The files saved by the browser, newest first. */
    fun files(ctx: Context): List<File> = (dir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }

    fun sizeText(n: Long): String = when {
        n >= 1_000_000_000L -> String.format("%.1f GB", n / 1e9)
        n >= 1_000_000L -> String.format("%.1f MB", n / 1e6)
        else -> String.format("%d KB", (n + 999) / 1000)
    }

    /** Opens a saved file: an APK goes to the installer, anything else to whichever app handles its type. */
    fun openFile(ctx: Context, f: File) {
        if (f.name.endsWith(".apk", true)) { install(ctx, f); return }
        try {
            val ext = f.extension.lowercase()
            val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Throwable) { toast(ctx, "No app on this car can open ${f.name}") }
    }

    // No canRequestPackageInstalls() pre-check: on this head unit it says false although the system installer opens fine (see UpdateInstaller);
    // the permission screen is only opened if the installer actually refuses.
    private fun install(ctx: Context, f: File) {
        val uri = try { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f) } catch (e: Throwable) {
            Logger.log(ctx, "download: no uri for ${f.name}: ${e.message}"); toast(ctx, "Cannot open ${f.name}"); return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(flags))
            Logger.log(ctx, "download: ${f.name} handed to the installer (ACTION_VIEW)")
            return
        } catch (e: SecurityException) {
            Logger.log(ctx, "download: installer refused (${e.message}), asking for install permission")
            toast(ctx, "Allow installing apps from Z Cam, then tap the file again")
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (e2: Throwable) { Logger.log(ctx, "download: unknown-sources screen unavailable: ${e2.message}") }
            try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (e3: Throwable) { Logger.log(ctx, "download: app settings unavailable: ${e3.message}") }
            return
        } catch (e: Throwable) { Logger.log(ctx, "download: ACTION_VIEW installer failed: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        try {
            @Suppress("DEPRECATION")
            ctx.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(flags))
            Logger.log(ctx, "download: ${f.name} handed to the installer (ACTION_INSTALL_PACKAGE)")
        } catch (e: Throwable) {
            Logger.log(ctx, "download: no installer found: ${e.javaClass.simpleName} ${e.message ?: ""}")
            toast(ctx, "No installer on this car accepted ${f.name}")
        }
    }
}
