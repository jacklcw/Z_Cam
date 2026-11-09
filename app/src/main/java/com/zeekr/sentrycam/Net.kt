package com.zeekr.sentrycam

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP for the relay. token = the account's relay_token (Bearer). */
object Net {
    fun get(url: String, token: String?, headers: Map<String, String> = emptyMap()): JSONObject? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            readJson(c, url, 0L)
        } catch (e: Throwable) { null }
    }

    fun postJson(url: String, token: String?, body: JSONObject): JSONObject? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            val bytes = body.toString().toByteArray()
            c.outputStream.use { it.write(bytes) }
            DataMeter.sent(DataMeter.categoryOf(url), bytes.size.toLong() + 300)   // counted when written, so a timed-out upload still shows
            readJson(c, url, 0L)
        } catch (e: Throwable) { null }
    }

    // Result of an upload: ok/status/error so a caller can log *why* an upload failed
    // (e.g. the relay's 413 "too_large") instead of just that it did.
    data class UploadResult(val ok: Boolean, val status: Int, val error: String?)

    // Posts a single in-memory piece of a file as the raw request body. Used by
    // uploadInPieces() below -- one small, fixed-size request per piece instead of one huge
    // streamed request for the whole clip.
    fun uploadBytes(url: String, token: String?, contentType: String, body: ByteArray, len: Int): UploadResult {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
            c.setFixedLengthStreamingMode(len)
            c.setRequestProperty("Content-Type", contentType)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(body, 0, len) }
            val status = c.responseCode
            val ok = status in 200..299
            // Reading .inputStream on a non-2xx response throws FileNotFoundException (message:
            // just the URL) -- confirmed live: that exception was caught below and silently
            // replaced a real "too_large"/etc error with the request URL as the "reason". Only
            // ever touch the stream that matches the actual status.
            val err = if (ok) { c.inputStream?.close(); null } else { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
            DataMeter.request(url, len.toLong(), (err?.length ?: 0).toLong())
            UploadResult(ok, status, err)
        } catch (e: Throwable) { UploadResult(false, -1, e.message) }
    }

    // Splits `stream` into fixed-size pieces and POSTs each one to `$baseUrl&seq=N`, retrying a
    // piece a few times before giving up (a dropped cellular connection then only costs that one
    // piece, not the whole clip), then POSTs `$completeUrl&chunks=N` so the relay can verify the
    // full set arrived. startSeq resumes after pieces the relay already has. pieceBytes must stay under the relay's per-chunk D1 limit (1.5MB).
    fun uploadInPieces(baseUrl: String, completeUrl: String, token: String?, stream: InputStream, pieceBytes: Int = 1_000_000, maxRetries: Int = 4, startSeq: Int = 0, abort: () -> Boolean = { false }): UploadResult {
        val buf = ByteArray(pieceBytes)
        var seq = startSeq
        try {
            // Resume: the relay already holds pieces 0 until startSeq, so skip those bytes.
            var toSkip = startSeq.toLong() * pieceBytes
            while (toSkip > 0) { val s = stream.skip(toSkip); if (s <= 0) break; toSkip -= s }
            while (true) {
                var len = 0
                while (len < pieceBytes) {
                    val n = stream.read(buf, len, pieceBytes - len)
                    if (n < 0) break
                    len += n
                }
                if (len == 0) break
                if (abort()) return UploadResult(false, -1, "cancelled")
                var result = UploadResult(false, -1, null)
                for (attempt in 1..maxRetries) {
                    result = uploadBytes("$baseUrl&seq=$seq", token, "application/octet-stream", buf, len)
                    if (result.ok) break
                    if (attempt < maxRetries) Thread.sleep(1500L * attempt)
                }
                if (!result.ok) return result
                seq++
                if (len < pieceBytes) break   // short read = end of stream
            }
        } catch (e: Throwable) { return UploadResult(false, -1, e.message) }
        var result = postJson(completeUrl + "&chunks=$seq", token, JSONObject())
        for (attempt in 2..maxRetries) {
            if (result != null && result.optBoolean("ok", false)) break
            Thread.sleep(1500L * attempt)
            result = postJson(completeUrl + "&chunks=$seq", token, JSONObject())
        }
        if (result == null) return UploadResult(false, -1, "network error on complete")
        val ok = result.optBoolean("ok", false)
        return UploadResult(ok, result.optInt("_status", -1), if (ok) null else result.toString())
    }

    fun postText(url: String, token: String?, text: String): Boolean {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection)
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            val tb = text.toByteArray(Charsets.UTF_8)
            c.outputStream.use { it.write(tb) }
            DataMeter.request(url, tb.size.toLong(), 0L)
            val ok = c.responseCode in 200..299
            if (ok) { c.inputStream?.close() } else { c.errorStream?.close() }
            ok
        } catch (e: Throwable) { false }
    }

    private fun readJson(c: HttpURLConnection, url: String, sentBody: Long): JSONObject? {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream ?: return JSONObject().put("_status", code)
        val txt = stream.bufferedReader().use(BufferedReader::readText)
        DataMeter.request(url, sentBody, txt.length.toLong())
        return try { JSONObject(txt).put("_status", code) } catch (e: Throwable) { JSONObject().put("_status", code) }
    }
}
