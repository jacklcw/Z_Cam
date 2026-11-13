package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot JPEG capture from a Camera2 device (no preview surface needed on this
 * head unit). On the Zeekr 7X: id "2" = surround composite, "0"/"1" = cabins.
 * The composite JPEG is large, so we downscale + recompress before returning.
 */
object CameraCapture {

    /** Result of a capture attempt: JPEG bytes on success, or a human-readable reason. */
    class Result(val jpeg: ByteArray?, val error: String?)

    // The car's camera HAL is shared with Sentry clip playback's MediaCodec decoder (see
    // VodFramePlayer) -- running both at once is what was crashing the car's camera pipeline
    // (confirmed: live view/manual snapshot broke only after Sentry clip streaming shipped).
    // A ReentrantLock, not a plain monitor: captureOrError() below only ever TRIES this lock
    // with a timeout. A wedged or crashed decoder must never be able to block this call
    // forever -- that would freeze CamService's single poll loop entirely (the same thread
    // also does /cam/cmd polling and the control WebSocket), killing the relay connection
    // along with the capture. VodFramePlayer can afford to block on it normally since it
    // runs on its own dedicated thread.
    val halLock = java.util.concurrent.locks.ReentrantLock()
    /** Who holds [halLock] (for the "camera busy" message and the log). */
    @Volatile var halOwner = ""

    // An openCamera whose callback never came back (camera service wedged) is still pending inside the car. Opening again on
    // top of it stacks more calls into a service that is not answering, so further opens wait until it answers, or 60 s pass.
    @Volatile private var pendingSince = 0L
    private const val PENDING_RETRY_MS = 60_000L

    private fun openErr(code: Int): String = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use (held by the car's own system)"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras open"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera disabled by policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device fatal error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service fatal error"
        else -> "open error $code"
    }

    /** Blocking capture. Returns downscaled JPEG bytes or null. Call off the main thread. */
    fun capture(ctx: Context, cameraId: String, maxWidth: Int, quality: Int): ByteArray? =
        captureOrError(ctx, cameraId, maxWidth, quality).jpeg

    /** Blocking capture with a failure reason for diagnostics. Call off the main thread. */
    fun captureOrError(ctx: Context, cameraId: String, maxWidth: Int, quality: Int): Result {
        if (pendingSince != 0L && System.currentTimeMillis() - pendingSince < PENDING_RETRY_MS)
            return Result(null, "camera service not answering (earlier open still pending)")
        if (!halLock.tryLock(3, TimeUnit.SECONDS)) return Result(null, "camera busy (held by " + (if (halOwner.isEmpty()) "unknown" else halOwner) + ")")
        try { return captureLocked(ctx, cameraId, maxWidth, quality) } finally { halLock.unlock() }
    }

    private val sizeLogged = HashSet<String>()   // log each camera's chosen size once per process

    private fun captureLocked(ctx: Context, cameraId: String, maxWidth: Int, quality: Int): Result {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val thread = HandlerThread("cam").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var openError: String? = null
        val openLock = Any()
        var openRequested = false
        var abandoned = false
        var resolved = false   // the open callback has fired (opened or failed)
        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Only use a size the HAL actually declares (never invent one).
            val jpegSizes: Array<Size> = map?.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: emptyArray()
            // Four panels are stacked in one frame, so what matters is the size of ONE panel (width x height/4), not the
            // total pixels -- same rule as CamHub's blind-spot stream: 3840x2160 has more pixels than 1280x5140 but only
            // 540 rows per panel, 1280x5140 gives 1280x1285. Falls back to total pixels for a non-stacked camera.
            val stacked = cameraId == "2"
            val size = jpegSizes.maxWithOrNull(compareBy<Size>({ if (stacked) minOf(it.width, it.height / 4) else 0 }, { it.width.toLong() * it.height })) ?: Size(1280, 1280)
            if (!sizeLogged.contains(cameraId)) {
                sizeLogged.add(cameraId)
                Logger.log(ctx, "capture: cam $cameraId uses ${size.width}x${size.height}; declared jpeg sizes: ${jpegSizes.joinToString(",") { "${it.width}x${it.height}" }}")
            }
            reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 1)

            val opened = CountDownLatch(1)
            openRequested = true
            @Suppress("MissingPermission")
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                // A camera that finishes opening after the caller gave up (timeout / interrupt) must be closed here,
                // or the car's camera service keeps it held until our process dies.
                override fun onOpened(c: CameraDevice) {
                    synchronized(openLock) { resolved = true; if (abandoned) { pendingSince = 0L; c.close(); thread.quitSafely() } else { device = c; opened.countDown() } }
                }
                override fun onDisconnected(c: CameraDevice) {
                    synchronized(openLock) { resolved = true; c.close(); if (abandoned) { pendingSince = 0L; thread.quitSafely() } else { openError = "camera disconnected"; opened.countDown() } }
                }
                override fun onError(c: CameraDevice, e: Int) {
                    synchronized(openLock) { resolved = true; c.close(); if (abandoned) { pendingSince = 0L; thread.quitSafely() } else { openError = openErr(e); opened.countDown() } }
                }
            }, handler)
            if (!opened.await(6, TimeUnit.SECONDS)) return Result(null, "cam $cameraId open timed out")
            if (device == null) return Result(null, "cam $cameraId: ${openError ?: "could not open"}")

            val jpegLatch = CountDownLatch(1)
            var jpeg: ByteArray? = null
            reader!!.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
                } finally { img.close(); jpegLatch.countDown() }
            }, handler)

            val surface: Surface = reader!!.surface
            val sessionLatch = CountDownLatch(1)
            var session: CameraCaptureSession? = null
            var sessionFailed = false
            device!!.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; sessionLatch.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { sessionFailed = true; sessionLatch.countDown() }
            }, handler)
            if (!sessionLatch.await(6, TimeUnit.SECONDS)) return Result(null, "cam $cameraId session timed out")
            if (session == null) return Result(null, "cam $cameraId session ${if (sessionFailed) "config failed" else "null"}")

            val req = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(surface)
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            session!!.capture(req.build(), null, handler)

            if (!jpegLatch.await(6, TimeUnit.SECONDS)) return Result(null, "cam $cameraId frame timed out")
            val bytes = jpeg?.let { downscale(it, maxWidth, quality) }
            return if (bytes != null) Result(bytes, null) else Result(null, "cam $cameraId produced no frame")
        } catch (e: Throwable) {
            return Result(null, "cam $cameraId: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            try { reader?.close() } catch (_: Throwable) {}
            // Left before the camera finished opening (timeout, interrupt): its callback closes it and stops
            // the thread, so the thread has to stay alive until then.
            val keepThread = synchronized(openLock) { if (openRequested && !resolved) { abandoned = true; pendingSince = System.currentTimeMillis(); true } else false }
            try { device?.close() } catch (_: Throwable) {}
            if (!keepThread) thread.quitSafely()
        }
    }

    /**
     * Decode -> cap the WIDTH at maxWidth (never the height) -> recompress JPEG.
     * The surround camera is a tall 1280x5140 composite of 4 stacked panels, so
     * scaling by the longest side (5140) would crush each panel to ~180px. Capping
     * width keeps each panel's full horizontal resolution and sharpness.
     */
    private fun downscale(src: ByteArray, maxWidth: Int, quality: Int): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return src
        var scaled: Bitmap? = null
        try {
            scaled = if (bmp.width > maxWidth) {
                val f = maxWidth.toFloat() / bmp.width
                Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * f).toInt().coerceAtLeast(1), true)
            } else bmp
            return encodeBitmap(scaled, quality, src.size / 2)
        } finally {
            // The decoded composite is ~26MB of native memory; free it now instead of waiting for the GC.
            if (scaled != null && scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
    }

    /** WebP is ~30% smaller than JPEG at the same quality -> more retention, less bandwidth. */
    fun encodeBitmap(bmp: Bitmap, quality: Int, sizeHint: Int = 1 shl 20): ByteArray {
        val out = ByteArrayOutputStream(sizeHint)
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
        } else bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** The encoding this device produces (matches downscale), reported to the relay. */
    fun encFormat(): String = if (Build.VERSION.SDK_INT >= 30) "webp" else "jpeg"
}
