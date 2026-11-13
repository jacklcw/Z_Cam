package com.zeekr.sentrycam

import android.content.Context

/**
 * Continuous capture for live view. Uses the SAME per-frame capture path as snapshots
 * (CameraCapture.captureOrError) — the one the head unit is known to allow — just in a
 * tight loop, handing each frame to [onFrame]. Reusing a single open session was
 * unreliable on this HAL, and per-frame open/close also recovers automatically if the
 * first frames fail while Sentry is still being armed. Frame rate is modest (a few fps)
 * but it actually works. [onStatus] is called with short human-readable progress/errors.
 */
object CameraStream {
    // Each run owns a generation number; stop() (or a newer run) bumps it, which ends any older loop. A plain
    // running flag let a restart within stopStreaming()'s join timeout leave the old loop capturing alongside the new one.
    private val gen = java.util.concurrent.atomic.AtomicInteger()

    fun stop() { gen.incrementAndGet() }

    /** Claims a new generation synchronously, so a stop() that lands before the thread gets going still wins. */
    fun begin(): Int = gen.incrementAndGet()

    fun run(my: Int, ctx: Context, cameraId: String, maxWidth: Int, quality: Int,
            onFrame: (ByteArray) -> Unit, onStatus: (String) -> Unit, paused: () -> Boolean = { false }) {
        var frames = 0
        var lastErr: String? = null
        var errStreak = 0
        while (gen.get() == my) {
            if (paused()) { try { Thread.sleep(1000) } catch (_: InterruptedException) {}; continue }   // nobody watching: no capture, no data
            val hubJpeg = if (cameraId == "2") CamHub.snapshot(maxWidth, quality) else null   // the windows' own stream: no camera open per frame, windows stay up
            var res = if (hubJpeg != null) CameraCapture.Result(hubJpeg, null) else CameraCapture.captureOrError(ctx, cameraId, maxWidth, quality)
            // a window opened after the stream started and holds the camera: ask the windows to let go (once), then capture
            if (res.jpeg == null && res.error?.contains("camhub") == true && !Park.yieldCam) {
                Park.yieldCam = true; CamHub.yieldTune(true); try { Thread.sleep(700) } catch (_: InterruptedException) {}
                res = CameraCapture.captureOrError(ctx, cameraId, maxWidth, quality)
            }
            if (gen.get() != my) break
            val jpeg = res.jpeg
            if (jpeg != null) {
                errStreak = 0
                if (frames == 0) onStatus("live: streaming")
                frames++
                onFrame(jpeg)
            } else {
                errStreak++
                val e = res.error ?: "no frame"
                if (e != lastErr || errStreak % 5 == 0) { onStatus("live: $e"); lastErr = e }
                // Back off a little while the camera is unavailable (e.g. Sentry still arming).
                try { Thread.sleep(if (e.contains("not answering") || e.contains("timed out")) 5000 else 800) } catch (_: InterruptedException) {}
            }
        }
    }
}
