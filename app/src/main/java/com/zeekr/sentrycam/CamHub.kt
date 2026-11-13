package com.zeekr.sentrycam

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One picture on screen: a TextureView's [st] plus which panel of the composite to show and how to flatten it. */
class PanelOut(val st: SurfaceTexture) {
    @Volatile var panel = 0
    @Volatile var fovDeg = 90f
    @Volatile var tiltDeg = 30f
    @Volatile var yawDeg = 0f   // signed: positive turns toward +x of the output picture
    @Volatile var rollDeg = 0f
    /** Set by the owner the moment its surface is about to die, so the hub stops drawing to it even if the hub thread is busy. */
    @Volatile var dead = false
    @Volatile var mirror = false
    /** Stitched view: the picture above is the LEFT half, this second camera the RIGHT half, cross-faded over [blend] (fractions of the width) around [split]. */
    @Volatile var stitch = false
    @Volatile var panel2 = 0
    @Volatile var fov2 = 90f
    @Volatile var tilt2 = 30f
    @Volatile var yaw2 = 0f
    @Volatile var roll2 = 0f
    @Volatile var mirror2 = false
    @Volatile var shift = 0f    // stitch halves: move the picture up (+) / down (-), % of the half height
    @Volatile var shift2 = 0f
    /** Soft fade at the picture's edges, as a fraction of its size (0 = hard edge); the output is premultiplied-alpha. */
    @Volatile var feather = 0f
    /** Picture look (see CamTune.applyLook): brightness -1..1 (0 = off), contrast / saturation multipliers (1 = off), opacity 0..1. */
    @Volatile var bri = 0f
    @Volatile var con = 1f
    @Volatile var sat = 1f
    @Volatile var opa = 1f
    /** warmth -1..1, night boost 0..1 (lifts shadows), sharpness multiplier of the built-in unsharp mask (1 = as before). */
    @Volatile var warm = 0f
    @Volatile var nite = 0f
    @Volatile var shp = 1f
    @Volatile var split = 0.5f
    @Volatile var blend = 0.12f
    /** Size of the owner's view in pixels (0 = unknown), set from its TextureView callbacks: the viewport and aspect come from it, so one output never borrows another's size. */
    @Volatile var boxW = 0
    @Volatile var boxH = 0
    internal var sig = 0
    internal var lastW = 0; internal var lastH = 0
    internal var egl: EGLSurface = EGL14.EGL_NO_SURFACE
    internal var swapOff = false
    internal var bad = false
}

/**
 * The surround camera ("2": 4 fisheyes stacked vertically: Front, Rear, Left, Right) opened ONCE with a
 * single capture stream, drawn by one GL context into every registered [PanelOut] (the HUD blind-spot
 * view and the floating windows). A single stream keeps within the car's camera stream limit however
 * many pictures are up. Each panel is an equidistant fisheye (LENS_HALF_FOV at its rim); the cameras
 * point DOWN at the ground, so a virtual pinhole camera is tilted up by tiltDeg and renders at fovDeg
 * (same maths as the dashboard). Holds CameraCapture.halLock while any output is registered (Sentry
 * snapshots report "camera busy").
 */
object CamHub {
    private const val CAM_ID = "2"
    private const val LENS_HALF_FOV_DEG = 100f   // same as dashpage.js LENS_HALF_FOV
    private const val VS = """
        attribute vec2 p; varying vec2 uv;
        void main(){ uv = (p + 1.0) * 0.5; gl_Position = vec4(p, 0.0, 1.0); }"""
    private const val FS = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 uv;
        uniform samplerExternalOES s;
        uniform mat4 st;
        uniform float fov; uniform float fin; uniform float panel; uniform float mirror;
        uniform float yaw;    // radians, turn sideways in the image (toward the car's rear)
        uniform float roll;   // radians, rotates the picture
        uniform float tilt;   // radians, positive = look up from the (downward-pointing) camera axis
        uniform vec2 texel;  // one source pixel in panel coordinates
        uniform float aspect; // window width / height: the angle is the vertical one, the angle applies to the longer side, so a wide window crops top/bottom instead of stretching
        uniform float bri; uniform float con; uniform float sat; uniform float opa; uniform float warm; uniform float nite; uniform float shp;
        uniform float stitch; uniform float split; uniform float blend; uniform float feather;
        uniform float shift1; uniform float shift2u; uniform float fov2; uniform float tilt2; uniform float yaw2; uniform float roll2; uniform float panel2; uniform float mirror2;
        vec4 cam(vec2 uvv, float fv, float tl, float yw, float rl, float pn, float mr, float asp, float sh){
            vec2 q = (uvv - 0.5) * 2.0;
            q = vec2(cos(rl) * q.x - sin(rl) * q.y, sin(rl) * q.x + cos(rl) * q.y);
            q.y -= sh * 0.02;
            vec2 sc = asp >= 1.0 ? vec2(1.0, 1.0 / asp) : vec2(asp, 1.0);
            vec3 d = vec3(q.x * sc.x * tan(fv * 0.5), q.y * sc.y * tan(fv * 0.5), 1.0);
            float ct = cos(tl), stl = sin(tl);
            d = vec3(d.x, ct * d.y + stl * d.z, -stl * d.y + ct * d.z);
            float cy = cos(yw), sy = sin(yw);
            d = vec3(cy * d.x + sy * d.z, d.y, -sy * d.x + cy * d.z);
            d = normalize(d);
            float th = acos(clamp(d.z, -1.0, 1.0));
            float r = th / fin;
            vec2 dir = normalize(d.xy + vec2(1e-6, 0.0));
            vec2 su = 0.5 + 0.5 * r * dir;
            if (r > 1.0 || su.x < 0.0 || su.x > 1.0 || su.y < 0.0 || su.y > 1.0) return vec4(0.0);
            if (mr > 0.5) su.x = 1.0 - su.x;
            // light unsharp mask: the fisheye flatten + bilinear sampling softens the picture
            vec4 c0 = texture2D(s, (st * vec4(su.x, 1.0 - (pn + 1.0 - su.y) / 4.0, 0.0, 1.0)).xy);
            vec4 nb = vec4(0.0);
            float ks = (0.3 + 0.35 * clamp((fv - 1.047) / 1.047, 0.0, 1.0)) * shp;   // wider angle = more scene per pixel: sharpen harder to keep it crisp
            for (int i = 0; i < 4; i++) {
                vec2 o = (i == 0 ? vec2(texel.x, 0.0) : i == 1 ? vec2(-texel.x, 0.0) : i == 2 ? vec2(0.0, texel.y) : vec2(0.0, -texel.y));
                vec2 sn = clamp(su + o, 0.0, 1.0);
                nb += texture2D(s, (st * vec4(sn.x, 1.0 - (pn + 1.0 - sn.y) / 4.0, 0.0, 1.0)).xy);
            }
            return vec4(clamp(c0.rgb * (1.0 + 4.0 * ks) - nb.rgb * ks, 0.0, 1.0), 1.0);
        }
        vec4 fe(vec3 c) {
            c = pow(clamp(c, 0.0, 1.0), vec3(1.0 - 0.5 * nite));
            c = clamp(c + vec3(warm * 0.12, 0.0, -warm * 0.12), 0.0, 1.0);
            c = clamp((c + bri - 0.5) * con + 0.5, 0.0, 1.0);
            c = clamp(mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, sat), 0.0, 1.0);
            float a = opa;
            if (feather > 0.0) { vec2 q = min(uv, 1.0 - uv); a *= smoothstep(0.0, feather, q.x) * smoothstep(0.0, feather, q.y); }
            return vec4(c * a, a);
        }
        void main(){
            if (stitch < 0.5) { vec4 c = cam(uv, fov, tilt, yaw, roll, panel, mirror, aspect, 0.0); gl_FragColor = fe(c.rgb); return; }
            // left camera covers x in [0, a1], right camera [a0, 1]; each is drawn as a normal picture of its own slice, cross-faded in the overlap
            float a0 = split - blend * 0.5; float a1 = split + blend * 0.5;
            float t = smoothstep(a0, a1, uv.x);
            vec2 la = vec2(uv.x / a1, uv.y); vec2 lb = vec2((uv.x - a0) / (1.0 - a0), uv.y);
            float aa = aspect * a1; float ab = aspect * (1.0 - a0);
            vec4 ca = vec4(0.0); vec4 cb = vec4(0.0);
            if (t < 1.0) ca = cam(la, fov, tilt, yaw, roll, panel, mirror, aa, shift1);
            if (t > 0.0) cb = cam(lb, fov2, tilt2, yaw2, roll2, panel2, mirror2, ab, shift2u);
            // past the edge of one lens (black) the other camera carries on over that part instead of showing a black strip
            if (t < 1.0 && ca.a < 0.5) { cb = cam(lb, fov2, tilt2, yaw2, roll2, panel2, mirror2, ab, shift2u); t = 1.0; }
            else if (t > 0.0 && cb.a < 0.5) { ca = cam(la, fov, tilt, yaw, roll, panel, mirror, aa, shift1); t = 0.0; }
            gl_FragColor = fe(mix(ca, cb, t).rgb);
        }"""

    private val thread = HandlerThread("camhub").apply { start() }
    private val h = Handler(thread.looper)

    // hub thread only
    private val outs = LinkedHashMap<String, PanelOut>()
    private var ctx: Context? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var locked = false
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var cfg: EGLConfig? = null
    private var prog = 0; private var tex = 0
    private var uSt = 0; private var uFov = 0; private var uFin = 0; private var uPanel = 0
    private var uTexel = 0; private var bufW = 1; private var bufH = 1; private var uAspect = 0; private var uMirror = 0; private var uTilt = 0; private var uYaw = 0; private var uRoll = 0
    private var uBri = 0; private var uCon = 0; private var uSat = 0; private var uOpa = 0; private var uWarm = 0; private var uNite = 0; private var uShp = 0
    private var uShift1 = 0; private var uShift2 = 0; private var uFeather = 0; private var uStitch = 0; private var uSplit = 0; private var uBlend = 0; private var uFov2 = 0; private var uTilt2 = 0; private var uYaw2 = 0; private var uRoll2 = 0; private var uPanel2 = 0; private var uMirror2 = 0
    private var camSt: SurfaceTexture? = null
    private var camSurface: Surface? = null
    private val stMat = FloatArray(16)
    private val wh = IntArray(1); private val hh = IntArray(1)
    private val quad = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)); position(0) }

    @Volatile private var lastFrame = 0L
    private var watching = false
    private var busyLogged = false
    // Every 2s: rebuild any picture whose surface failed or never got one, and reopen the camera if it stalled or the car took it
    // (nothing reopened it before, so every window stayed dark until it was toggled).
    private val watchdog = object : Runnable {
        override fun run() {
            if (outs.isEmpty()) {
                if (device != null || opening || locked) { log("camera still held with no output, releasing it"); try { teardown() } catch (_: Throwable) {} }   // safety net: nothing draws, so nothing may hold the camera
                watching = false; return
            }
            if (outs.values.all { it.dead }) { log("only dead outputs left, releasing the camera"); outs.clear(); try { teardown() } catch (_: Throwable) {}; watching = false; return }
            try {
                for ((k, o) in outs) {
                    if (o.dead || dpy == EGL14.EGL_NO_DISPLAY) continue
                    if (o.egl == EGL14.EGL_NO_SURFACE || o.bad) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (o.egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, o.egl)
                        o.egl = EGL14.EGL_NO_SURFACE
                        attach(o)
                        log("surface rebuilt: $k (${if (o.egl == EGL14.EGL_NO_SURFACE) "failed" else "ok"})")
                    }
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (device == null && !opening) ensure()
                else if (device != null && lastFrame != 0L && now - lastFrame > 6000) { log("no frames for 6s, reopening camera"); lastFrame = now; closeCamera(); ensure() }
            } catch (e: Throwable) { log("watchdog: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            h.postDelayed(this, 2000)
        }
    }

    private fun log(m: String) { ctx?.let { Logger.log(it, "camhub: $m") } }

    fun add(c: Context, key: String, o: PanelOut) {
        h.post {
            try {
                ctx = c.applicationContext; outs[key] = o; attach(o); ensure(); log("add $key (outputs: ${outs.keys})")
            } catch (e: Throwable) { log("add $key failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); outs.remove(key); if (outs.isEmpty()) teardown() }   // a dead surface must not take the app down
            if (!watching) { watching = true; h.postDelayed(watchdog, 2000) }
        }
    }

    private var pausedTune: PanelOut? = null
    /** Live view / capture needs the camera: the app's own tuning preview lets go of it, and takes it back after. */
    fun yieldTune(on: Boolean) {
        h.post {
            try {
                if (on) { outs.remove("tune")?.let { detach(it); pausedTune = it; log("tune preview released the camera") }; if (outs.isEmpty()) teardown() }
                else pausedTune?.let { pausedTune = null; if (!it.dead) { outs["tune"] = it; attach(it); ensure(); log("tune preview back on the camera") } }
            } catch (e: Throwable) { log("yieldTune: ${e.message}") }
        }
    }

    /** Blocks (briefly) until [key] is no longer drawn to, so the caller's surface can die. */
    fun remove(key: String, o: PanelOut? = null) {
        o?.dead = true   // immediately, from the caller's thread: draw() skips it from now on
        val done = CountDownLatch(1)
        h.post {
            try { outs.remove(key)?.let { detach(it) }; log("remove $key (outputs: ${outs.keys})"); if (outs.isEmpty()) teardown() } catch (e: Throwable) { log("remove: ${e.message}") }
            done.countDown()
        }
        if (!done.await(3, TimeUnit.SECONDS)) log("remove $key: hub thread busy, surface released while queued")
    }

    // ---- GL (hub thread) ----

    private fun attach(o: PanelOut) {
        if (dpy == EGL14.EGL_NO_DISPLAY && !setupGl()) return
        o.egl = EGL14.eglCreateWindowSurface(dpy, cfg, o.st, intArrayOf(EGL14.EGL_NONE), 0)
        o.swapOff = false; o.bad = false
    }

    private fun detach(o: PanelOut) {
        if (dpy == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (o.egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, o.egl)
        o.egl = EGL14.EGL_NO_SURFACE
    }

    private fun setupGl(): Boolean = try {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2); EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        cfg = cfgs[0]
        eglCtx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        // the first output's surface is needed to make the context current for shader/texture setup
        true
    } catch (e: Throwable) { log("GL setup failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); false }

    private var glReady = false
    private fun initProgram(bufW: Int, bufH: Int) {
        this.bufW = bufW; this.bufH = bufH
        fun sh(t: Int, src: String): Int {
            val s = GLES20.glCreateShader(t); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, VS))
        GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, FS))
        GLES20.glLinkProgram(prog)
        GLES20.glUseProgram(prog)
        val loc = GLES20.glGetAttribLocation(prog, "p")
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        uSt = GLES20.glGetUniformLocation(prog, "st"); uFov = GLES20.glGetUniformLocation(prog, "fov")
        uFin = GLES20.glGetUniformLocation(prog, "fin"); uPanel = GLES20.glGetUniformLocation(prog, "panel")
        uMirror = GLES20.glGetUniformLocation(prog, "mirror"); uTilt = GLES20.glGetUniformLocation(prog, "tilt")
        uTexel = GLES20.glGetUniformLocation(prog, "texel"); uAspect = GLES20.glGetUniformLocation(prog, "aspect"); uYaw = GLES20.glGetUniformLocation(prog, "yaw"); uRoll = GLES20.glGetUniformLocation(prog, "roll")
        for ((n, f) in listOf("bri" to { l: Int -> uBri = l }, "con" to { l: Int -> uCon = l }, "sat" to { l: Int -> uSat = l }, "opa" to { l: Int -> uOpa = l }, "warm" to { l: Int -> uWarm = l }, "nite" to { l: Int -> uNite = l }, "shp" to { l: Int -> uShp = l }, "feather" to { l: Int -> uFeather = l }, "stitch" to { l: Int -> uStitch = l }, "shift1" to { l: Int -> uShift1 = l }, "shift2u" to { l: Int -> uShift2 = l }, "split" to { l: Int -> uSplit = l }, "blend" to { l: Int -> uBlend = l }, "fov2" to { l: Int -> uFov2 = l },
            "tilt2" to { l: Int -> uTilt2 = l }, "yaw2" to { l: Int -> uYaw2 = l }, "roll2" to { l: Int -> uRoll2 = l }, "panel2" to { l: Int -> uPanel2 = l }, "mirror2" to { l: Int -> uMirror2 = l }))
            f(GLES20.glGetUniformLocation(prog, n))
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        camSt = SurfaceTexture(tex).also {
            it.setDefaultBufferSize(bufW, bufH)
            it.setOnFrameAvailableListener({ if (drawQueued.compareAndSet(false, true)) h.post(drawRun) }, h)
            camSurface = Surface(it)
        }
        glReady = true
    }

    // One queued draw at a time: if frames arrive faster than we can draw, extra posts are dropped (updateTexImage takes the newest anyway).
    private val drawQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---- snapshot from the running stream (so a snapshot / live view does not have to take the camera away from the floating windows) ----
    private class SnapReq(val maxW: Int, val quality: Int) { val latch = CountDownLatch(1); @Volatile var out: ByteArray? = null }
    @Volatile private var snapReq: SnapReq? = null
    private val snapLock = Any()
    private var snapProg = 0

    /** True while the hub is streaming (a window or the HUD holds the camera and frames are arriving). */
    fun live(): Boolean = lastFrame != 0L && android.os.SystemClock.uptimeMillis() - lastFrame < 1500

    /** Whole composite (all four panels, as a normal snapshot) from the next frame of the running stream; null if the hub is not streaming. Call off the main and hub threads. */
    fun snapshot(maxW: Int, quality: Int): ByteArray? {
        if (!live()) return null
        synchronized(snapLock) {
            val r = SnapReq(maxW, quality); snapReq = r
            val ok = try { r.latch.await(2500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
            if (!ok) snapReq = null
            return r.out
        }
    }

    /** Hub thread, right after updateTexImage: draw the raw camera frame (no flattening) into an offscreen buffer and encode it. */
    private fun renderSnap(r: SnapReq): ByteArray? {
        val w = minOf(bufW, r.maxW).coerceAtLeast(16); val hgt = (bufH.toLong() * w / bufW).toInt().coerceAtLeast(16)
        val fbo = IntArray(1); val tx = IntArray(1)
        try {
            if (snapProg == 0) {
                fun sh(t: Int, src: String): Int { val s = GLES20.glCreateShader(t); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }
                snapProg = GLES20.glCreateProgram()
                GLES20.glAttachShader(snapProg, sh(GLES20.GL_VERTEX_SHADER, VS))
                GLES20.glAttachShader(snapProg, sh(GLES20.GL_FRAGMENT_SHADER, """
                    #extension GL_OES_EGL_image_external : require
                    precision mediump float; varying vec2 uv; uniform samplerExternalOES s; uniform mat4 st;
                    void main(){ gl_FragColor = vec4(texture2D(s, (st * vec4(uv, 0.0, 1.0)).xy).rgb, 1.0); }"""))
                GLES20.glLinkProgram(snapProg)
            }
            GLES20.glGenTextures(1, tx, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tx[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, hgt, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, fbo, 0); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tx[0], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) { log("snapshot: framebuffer incomplete"); return null }
            GLES20.glUseProgram(snapProg)
            val loc = GLES20.glGetAttribLocation(snapProg, "p")
            GLES20.glEnableVertexAttribArray(loc); GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(snapProg, "s"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(snapProg, "st"), 1, false, stMat, 0)
            GLES20.glViewport(0, 0, w, hgt)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
            val buf = ByteBuffer.allocateDirect(w * hgt * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, hgt, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            val raw = android.graphics.Bitmap.createBitmap(w, hgt, android.graphics.Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            val up = android.graphics.Bitmap.createBitmap(raw, 0, 0, w, hgt, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)   // GL rows run bottom to top
            raw.recycle()
            val bytes = CameraCapture.encodeBitmap(up, r.quality)
            up.recycle()
            return bytes
        } catch (e: Throwable) { log("snapshot failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); return null }
        finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (fbo[0] != 0) GLES20.glDeleteFramebuffers(1, fbo, 0)
            if (tx[0] != 0) GLES20.glDeleteTextures(1, tx, 0)
            // back to the picture program the windows draw with
            GLES20.glUseProgram(prog)
            val loc = GLES20.glGetAttribLocation(prog, "p")
            GLES20.glEnableVertexAttribArray(loc); GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        }
    }
    private val drawRun = Runnable { drawQueued.set(false); draw() }

    private fun draw() {
        // A window whose EGL surface failed must not stall the others (that left every picture dark): use the first usable one.
        val first = outs.values.firstOrNull { !it.dead && it.egl != EGL14.EGL_NO_SURFACE } ?: return
        val c = camSt ?: return
        try {
            if (!EGL14.eglMakeCurrent(dpy, first.egl, first.egl, eglCtx)) return
            c.updateTexImage(); c.getTransformMatrix(stMat)
            lastFrame = android.os.SystemClock.uptimeMillis()
            snapReq?.let { r -> snapReq = null; r.out = renderSnap(r); r.latch.countDown() }
            for ((k, o) in outs) {
                if (o.dead || o.egl == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(dpy, o.egl, o.egl, eglCtx)) continue
                if (!o.swapOff) { EGL14.eglSwapInterval(dpy, 0); o.swapOff = true }   // four windows must not wait on vsync one after another
                wh[0] = 0; hh[0] = 0   // a failed query must not leave the previous output's size behind
                if (o.boxW > 0 && o.boxH > 0) { wh[0] = o.boxW; hh[0] = o.boxH }   // the owner's view size: authoritative, one per output
                else {
                    EGL14.eglQuerySurface(dpy, o.egl, EGL14.EGL_WIDTH, wh, 0); EGL14.eglQuerySurface(dpy, o.egl, EGL14.EGL_HEIGHT, hh, 0)
                    if (wh[0] > 0 && hh[0] > 0) { o.lastW = wh[0]; o.lastH = hh[0] }
                    else if (o.lastW > 0) { wh[0] = o.lastW; hh[0] = o.lastH }   // this output's own last good size
                }
                if (wh[0] <= 0 || hh[0] <= 0) { wh[0] = 1; hh[0] = 1 }
                GLES20.glViewport(0, 0, wh[0], hh[0])
                val sig = wh[0] * 100000 + hh[0]
                if (sig != o.sig) { o.sig = sig; log("draw $k ${wh[0]}x${hh[0]} (view ${o.boxW}x${o.boxH}) panel ${o.panel}") }
                GLES20.glUniform2f(uTexel, 1f / bufW, 4f / bufH)
                GLES20.glUniform1f(uAspect, if (hh[0] > 0) wh[0].toFloat() / hh[0] else 1f)
                GLES20.glUniformMatrix4fv(uSt, 1, false, stMat, 0)
                GLES20.glUniform1f(uFov, Math.toRadians(o.fovDeg.toDouble()).toFloat())
                GLES20.glUniform1f(uFin, Math.toRadians(LENS_HALF_FOV_DEG.toDouble()).toFloat())
                GLES20.glUniform1f(uTilt, Math.toRadians(o.tiltDeg.toDouble()).toFloat())
                GLES20.glUniform1f(uYaw, Math.toRadians(o.yawDeg.toDouble()).toFloat())
                GLES20.glUniform1f(uRoll, Math.toRadians(o.rollDeg.toDouble()).toFloat())
                GLES20.glUniform1f(uPanel, o.panel.toFloat())
                GLES20.glUniform1f(uMirror, if (o.mirror) 1f else 0f)
                GLES20.glUniform1f(uStitch, if (o.stitch) 1f else 0f)
                GLES20.glUniform1f(uFeather, o.feather)
                GLES20.glUniform1f(uBri, o.bri); GLES20.glUniform1f(uCon, o.con); GLES20.glUniform1f(uSat, o.sat); GLES20.glUniform1f(uOpa, o.opa)
                GLES20.glUniform1f(uWarm, o.warm); GLES20.glUniform1f(uNite, o.nite); GLES20.glUniform1f(uShp, o.shp)
                if (o.stitch) {
                    GLES20.glUniform1f(uShift1, o.shift); GLES20.glUniform1f(uShift2, o.shift2); GLES20.glUniform1f(uSplit, o.split); GLES20.glUniform1f(uBlend, o.blend)
                    GLES20.glUniform1f(uFov2, Math.toRadians(o.fov2.toDouble()).toFloat())
                    GLES20.glUniform1f(uTilt2, Math.toRadians(o.tilt2.toDouble()).toFloat())
                    GLES20.glUniform1f(uYaw2, Math.toRadians(o.yaw2.toDouble()).toFloat())
                    GLES20.glUniform1f(uRoll2, Math.toRadians(o.roll2.toDouble()).toFloat())
                    GLES20.glUniform1f(uPanel2, o.panel2.toFloat()); GLES20.glUniform1f(uMirror2, if (o.mirror2) 1f else 0f)
                }
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                if (!EGL14.eglSwapBuffers(dpy, o.egl)) { o.bad = true }
            }
        } catch (_: Throwable) {}
    }

    // ---- camera (hub thread) ----

    private fun ensure() {
        val c = ctx ?: return
        val first = outs.values.firstOrNull() ?: return
        if (dpy == EGL14.EGL_NO_DISPLAY || first.egl == EGL14.EGL_NO_SURFACE) return
        val size = try {
            (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(CAM_ID)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                // Four panels are stacked in one frame, so what matters is the size of ONE panel (width x height/4), not the
                // total pixels: 3840x2160 has more pixels than 1280x5140 but only 540 rows per panel; 1280x5140 gives 1280x1280.
                ?.getOutputSizes(SurfaceTexture::class.java)?.maxWithOrNull(compareBy<android.util.Size>({ minOf(it.width, it.height / 4) }, { it.width.toLong() * it.height }))
        } catch (_: Throwable) { null } ?: run { log("no preview size"); return }
        if (!glReady) {
            try {
                check(EGL14.eglMakeCurrent(dpy, first.egl, first.egl, eglCtx)) { "eglMakeCurrent" }
                initProgram(size.width, size.height)
                val m = (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(CAM_ID).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                fun sizes(cls: Class<*>) = m?.getOutputSizes(cls)?.joinToString(",") { "${it.width}x${it.height}" }
                log("stream ${size.width}x${size.height} (best per-panel size); texture sizes: ${sizes(SurfaceTexture::class.java)}; yuv: ${m?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.joinToString(",") { "${it.width}x${it.height}" }}; jpeg: ${m?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.joinToString(",") { "${it.width}x${it.height}" }}")
            } catch (e: Throwable) { log("GL init failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); return }
        }
        if (device != null || opening) return
        if (!CameraCapture.halLock.tryLock()) { if (!busyLogged) log("camera busy (held by " + (if (CameraCapture.halOwner.isEmpty()) "unknown" else CameraCapture.halOwner) + ")"); busyLogged = true; return }
        busyLogged = false
        locked = true; opening = true; CameraCapture.halOwner = "camhub " + outs.keys
        try {
            @Suppress("MissingPermission")
            (c.getSystemService(Context.CAMERA_SERVICE) as CameraManager).openCamera(CAM_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (outs.isEmpty()) { d.close(); unlock(); return }
                    device = d
                    val surf = camSurface ?: run { closeCamera(); return }   // the GL side was torn down meanwhile: do not keep the camera without a session
                    try {
                        d.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (device !== d) { try { s.close() } catch (_: Throwable) {}; return }
                                session = s
                                try {
                                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf) }
                                    s.setRepeatingRequest(req.build(), null, h)
                                } catch (e: Throwable) { log("preview failed: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { log("session config failed") }
                        }, h)
                    } catch (e: Throwable) { log("createCaptureSession: ${e.javaClass.simpleName} ${e.message ?: ""}") }
                }
                override fun onDisconnected(d: CameraDevice) { log("camera taken by the car"); d.close(); closeCamera() }
                override fun onError(d: CameraDevice, e: Int) { log("camera error $e"); d.close(); closeCamera() }
            }, h)
        } catch (e: Throwable) { log("open failed: ${e.javaClass.simpleName} ${e.message ?: ""}"); opening = false; unlock() }
    }

    private fun unlock() { if (locked) { locked = false; CameraCapture.halOwner = ""; try { CameraCapture.halLock.unlock() } catch (_: Throwable) {} } }

    private fun closeCamera() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null; device = null; opening = false
        unlock()
    }

    // Last output gone: stop the camera and free GL (hub thread).
    private fun teardown() {
        closeCamera()
        try { camSt?.setOnFrameAvailableListener(null) } catch (_: Throwable) {}
        try { camSurface?.release() } catch (_: Throwable) {}
        try { camSt?.release() } catch (_: Throwable) {}
        camSt = null; camSurface = null; glReady = false; snapProg = 0; lastFrame = 0L
        try {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, eglCtx)
            EGL14.eglTerminate(dpy)
        } catch (_: Throwable) {}
        eglCtx = EGL14.EGL_NO_CONTEXT; dpy = EGL14.EGL_NO_DISPLAY
    }
}
