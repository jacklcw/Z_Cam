package com.zeekr.sentrycam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zed, the pixel pet: a little digital creature that lives on the screen. It walks on the floor, hops onto the floating windows,
 * crawls up the walls and over the ceiling, naps, plays with a ball or chases a pixel bug. Its energy follows the time of day
 * (rests at night, hyper in the evening) and it sometimes reacts, at random, when the car goes very fast.
 * One full-screen overlay that does not take touches. Prefs "mc*" (see the Mascot card in the app). Owned by CamService.
 */
object Mascot {
    private val main = Handler(Looper.getMainLooper())
    private var view: MascotView? = null
    private var hit: View? = null
    private var hitLp: WindowManager.LayoutParams? = null

    fun sync(ctx: Context) {
        val c = ctx.applicationContext
        main.post {
            val on = c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("mcOn", true) && Settings.canDrawOverlays(c)
            if (!on) { remove(); return@post }
            val v = view
            if (v == null) add(c) else v.loadCfg()
        }
    }

    fun stop() { main.post { remove() } }

    /** Re-adds the overlay so it stacks above the floating windows (stacking is by add order). */
    fun raise() {
        main.post {
            val v = view ?: return@post
            val lp = v.layoutParams ?: return@post
            val wm = v.context.getSystemService(WindowManager::class.java)
            try { wm.removeView(v); wm.addView(v, lp) } catch (_: Throwable) {}
            val h = hit; val hl = hitLp
            if (h != null && hl != null) try { wm.removeView(h); wm.addView(h, hl) } catch (_: Throwable) {}
        }
    }

    /** "Play now" button in settings: a random trick right away. */
    fun cheer() { main.post { view?.cheer() } }

    private fun remove() {
        val v = view ?: return
        view = null
        v.halt()
        val wm = v.context.getSystemService(WindowManager::class.java)
        try { wm.removeView(v) } catch (_: Throwable) {}
        hit?.let { try { wm.removeView(it) } catch (_: Throwable) {} }; hit = null; hitLp = null
    }

    private fun add(ctx: Context) {
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = 0.8f   // Android 12 only lets touches through a see-through overlay up to 80% opaque
        }
        val v = MascotView(ctx)
        try { ctx.getSystemService(WindowManager::class.java).addView(v, lp); view = v; v.begin() } catch (e: Throwable) { Logger.log(ctx, "mascot: addView failed ${e.message ?: ""}"); return }
        // a small invisible window that follows the pet and takes the taps; everything else on screen stays untouched
        var held = false; var dragging = false; var dx0 = 0f; var dy0 = 0f
        val slop = 14f * ctx.resources.displayMetrics.density
        val hv = View(ctx)
        val hold = Runnable { held = true; v.holdStart() }
        hv.setOnTouchListener { vw, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { held = false; dragging = false; dx0 = e.rawX; dy0 = e.rawY; vw.postDelayed(hold, 450L) }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.hypot((e.rawX - dx0).toDouble(), (e.rawY - dy0).toDouble()) > slop) { dragging = true; vw.removeCallbacks(hold); v.dragStart() }
                    if (dragging) v.dragTo(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_UP -> { vw.removeCallbacks(hold); if (dragging) v.dragEnd() else if (held) v.holdEnd() else v.poke() }
                MotionEvent.ACTION_CANCEL -> { vw.removeCallbacks(hold); if (dragging) v.dragEnd() else if (held) v.holdEnd() }
            }
            true
        }
        val hl = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
        try { ctx.getSystemService(WindowManager::class.java).addView(hv, hl); hit = hv; hitLp = hl; v.attachHit(hv, hl) } catch (_: Throwable) {}
    }

    private enum class St { IDLE, WALK, JUMP, FALL, CLIMB, SLEEP, BALL, BUG, SPIN, WAVE, GLITCH, REACT, ACT, HELD, DRAG }
    private enum class Sf(val a: Float) { FLOOR(0f), RIGHT(-90f), CEIL(180f), LEFT(90f) }
    private class Plat(val l: Float, val t: Float, val r: Float, val b: Float = 0f)
    private class Pt(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val kind: Int, val col: Int)

    private val HEART = arrayOf(".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X..")
    private val ZZ = arrayOf("XXX", "..X", ".X.", "X..", "XXX")
    private val BANG = arrayOf(".X.", ".X.", ".X.", "...", ".X.")
    private val STAR = arrayOf("..X..", "..X..", "XXXXX", "..X..", "..X..")

    @SuppressLint("ViewConstructor")
    private class MascotView(c: Context) : View(c) {
        private val d = c.resources.displayMetrics.density
        private val rnd = java.util.Random()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val loc = IntArray(2)
        private val pts = ArrayList<Pt>()
        private var plats: List<Plat> = emptyList(); private var unders: List<Plat> = emptyList()
        private var hitV: View? = null; private var hitLp: WindowManager.LayoutParams? = null; private var hitAt = 0L
        private var hangOn = false; private var hangPending = false; private var hangL = 0f; private var hangR = 0f; private var hangY = 0f
        private var flashA = 0f; private var lastPoke = 0L
        private var platAt = 0L
        private var running = false
        private var last = 0L

        // settings
        private var base = 0xFFB6FF3B.toInt(); private var rainbow = false; private var u = 5f * d; private var pace = 1f
        private var dtx = 0f; private var dty = 0f; private var dvx = 0f; private var dvy = 0f; private var hsub = 0; private var wig = 0f; private var lifeOn = true; private var act = 0; private var flushed = false; private var faceStyle = 2; private var climbOn = true; private var toysOn = true; private var jumpOn = true; private var napOn = true; private var reactOn = true

        // world
        private var W = 0f; private var H = 0f
        private var x = 0f; private var y = -100f; private var vx = 0f; private var vy = 0f
        private var face = 1; private var ang = 0f; private var angT = 0f; private var flip = 0f; private var flipDir = 0
        private var st = St.FALL; private var stT = 0f; private var stDur = 0f
        private var surf = Sf.FLOOR
        private var goalX = Float.NaN; private var climbAfter = false
        private var sq = 0f; private var sqv = 0f
        private var t = 0f; private var legs = 0f; private var blinkAt = 2f; private var look = 0f; private var lookAt = 1f
        private var hop = 0f; private var arm = false; private var ghost = 0f; private var scare = 0f
        private var sl = 0f; private var sr = 0f
        private var jx0 = 0f; private var jy0 = 0f; private var jvx = 0f; private var jvy = 0f; private var jT = 1f; private var jtx = 0f; private var jty = 0f
        private var kickAt = 0f; private var chain = 0; private var pouncing = false; private var sub = 0; private var climbTurn = 0f
        // toys
        private var ball = false; private var bx = 0f; private var by = 0f; private var bvx = 0f; private var bvy = 0f
        private var bug = false; private var bgx = 0f; private var bgv = 0f
        // mood and speed
        private var mood = 1f; private var moodAt = 0L
        @Volatile private var kmh = 0f; @Volatile private var batt = -1f
        private var pollBusy = false; private var pollAt = 0L; private var reactAt = 0L; private var reactPending = false; private var secT = 0f
        private var prev = Rect(); private var cur = Rect()

        private val g get() = 2400f * d

        init { loadCfg() }

        fun loadCfg() {
            val p = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            base = p.getInt("mcColor", 0xFFB6FF3B.toInt()); rainbow = p.getBoolean("mcRainbow", false)
            u = p.getInt("mcSize", 5).coerceIn(3, 12) * d; pace = p.getInt("mcSpeed", 100).coerceIn(40, 220) / 100f
            faceStyle = p.getInt("mcFace", 2).coerceIn(0, 2); climbOn = p.getBoolean("mcClimb", true); toysOn = p.getBoolean("mcToys", true); jumpOn = p.getBoolean("mcJump", true)
            napOn = p.getBoolean("mcNap", true); reactOn = p.getBoolean("mcReact", true); lifeOn = p.getBoolean("mcLife", true)
        }

        fun attachHit(v: View, lp: WindowManager.LayoutParams) { hitV = v; hitLp = lp }
        fun begin() { running = true; last = SystemClock.uptimeMillis(); postDelayed(loop, 33L) }
        fun halt() { running = false; removeCallbacks(loop) }
        private val loop = object : Runnable { override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.06f); last = now
            if (width > 0) try { step(dt) } catch (_: Throwable) {}
            postDelayed(this, 33L)
        } }

        /** Drag: Zed is picked up by the scruff, dangles and kicks under the finger, and is dropped (or thrown) on release. */
        fun dragStart() { post {
            dropToys(); hangOn = false; pouncing = false; arm = false; flip = 0f; flipDir = 0; hop = 0f; goalX = Float.NaN
            surf = Sf.FLOOR; st = St.DRAG; stT = 0f; dvx = 0f; dvy = 0f; sq = 0f; sqv = 0f; wig = 0f
            dtx = x; dty = y - 9 * u
            burst(x, y - 8 * u, 3, 1, pink(), 120f)
        } }
        fun dragTo(rx: Float, ry: Float) { getLocationOnScreen(loc); dtx = rx - loc[0]; dty = ry - loc[1] }
        fun dragEnd() { post {
            if (st != St.DRAG) return@post
            vx = (dvx * 0.5f).coerceIn(-500f * d, 500f * d); vy = (dvy * 0.5f).coerceIn(-900f * d, 300f * d)
            st = St.FALL; stT = 0f; angT = 0f; sq = -0.2f; sqv = 500f
        } }
        private fun drag(dt0: Float) {
            val k = min(1f, dt0 * 16f)
            val tx = dtx.coerceIn(0f, W); val ty = dty + 9 * u
            val ox = x; val oy = y
            x += (tx - x) * k; y += (ty - y) * k
            dvx = (x - ox) / dt0; dvy = (y - oy) / dt0
            angT = (dvx * 0.015f / d).coerceIn(-28f, 28f) + sin(t * 7f) * 5f
            if (rnd.nextFloat() < dt0 * 4f) burst(x, y - 8 * u, 1, 2, 0xFFFFE066.toInt(), 80f)
        }

        /** Finger held on the pet: pushes it away, struggles, melts, giggles or gets squished (random). Ends with holdEnd. */
        fun holdStart() { post {
            if (st == St.JUMP || st == St.FALL) return@post
            if (st == St.CLIMB) { scare = 1.5f; burst(x, y - 8 * u, 4, 1, pink(), 140f); return@post }
            dropToys(); hangOn = false; pouncing = false; arm = false; hop = 0f; flipDir = 0; flip = 0f; goalX = Float.NaN
            hsub = rnd.nextInt(5); st = St.HELD; stT = 0f
            if (hsub == 4) { sq = 0f; sqv = 0f }
        } }
        fun holdEnd() { post {
            if (st != St.HELD) return@post
            wig = 0f; angT = 0f; hop = 0f; look = 0f
            when (hsub) {
                0 -> { face = if (rnd.nextBoolean()) 1 else -1; st = St.REACT; sub = 1; stT = 0f; stDur = 1.8f; rise(x, y - 11 * u, 3, 0xFFFF5A5A.toInt()) }   // wriggles free and bolts
                1 -> { burst(x, y - 6 * u, 4, 3, 0xFFCCCCCC.toInt(), 140f); idle(0.9f) }                                                         // dusts itself off
                2 -> { sq = 0.25f; sqv = 0f; burst(x, y - 8 * u, 4, 1, pink(), 140f); if (napOn && rnd.nextFloat() < 0.4f) { st = St.SLEEP; stT = 0f; stDur = 6f + rnd.nextFloat() * 4f } else idle(0.7f) }   // stretches, maybe dozes off
                3 -> { st = St.WAVE; stT = 0f; stDur = 1.3f; burst(x, y - 8 * u, 6, 1, pink(), 180f) }                                           // laughing fit
                else -> { sq = 0.6f; sqv = 0f; burst(x, y, 6, 3, 0xFFCCCCCC.toInt(), 160f); launch(x, y, 40 * d + 3 * u, 0) }                    // boing
            }
        } }
        private fun held(dt: Float) {
            when (hsub) {
                0 -> { wig = sin(stT * 28f) * 1.3f * u; scare = 0.1f; if (rnd.nextFloat() < dt * 3f) rise(x, y - 11 * u, 3, 0xFFFF5A5A.toInt()) }
                1 -> { angT = -10f * face; look = if (sin(stT * 9f) > 0f) 1f else -1f; wig = sin(stT * 16f) * 0.3f * u; if (rnd.nextFloat() < dt * 3f) burst(x + face * 6 * u, y - 5 * u, 1, 3, 0xFFCCCCCC.toInt(), 100f) }
                2 -> { sq = -0.12f + sin(stT * 3f) * 0.03f; sqv = 0f; if (rnd.nextFloat() < dt * 2f) rise(x + (rnd.nextFloat() - 0.5f) * 8 * u, y - 11 * u, 1, pink()) }
                3 -> { wig = sin(stT * 35f) * 0.6f * u; hop = abs(sin(stT * 18f)) * 1.5f * u; if (rnd.nextFloat() < dt * 6f && pts.size < 60) pts.add(Pt(x + (rnd.nextFloat() - 0.5f) * 5 * u, y - 7 * u, (rnd.nextFloat() - 0.5f) * 90f * d, -40f * d, 0.6f, 0.6f, 5, 0xFF6EC8FF.toInt())) }
                else -> { sq = -min(stT / 1.2f, 1f) * 0.35f; sqv = 0f }
            }
            if (stT > 12f) holdEnd()
        }

        /** A tap on the pet: always a random, fun reaction. */
        fun poke() { post { tapped() } }
        private fun tapped() {
            val now = SystemClock.uptimeMillis(); if (now - lastPoke < 350L) return; lastPoke = now
            if (st == St.JUMP || st == St.FALL) { burst(x, y - 8 * u, 4, 1, pink(), 160f); return }
            if (st == St.CLIMB) { scare = 1.2f; burst(x, y - 8 * u, 5, 1, pink(), 160f); return }
            dropToys(); hangOn = false; pouncing = false; arm = false; hop = 0f; flipDir = 0; flip = 0f
            when (rnd.nextInt(5)) {   // small, calm reactions only: no jumping or spinning
                0 -> { rise(x, y - 11 * u, 3, 0xFFFF5A5A.toInt()); sq = 0.2f; sqv = 0f; idle(0.6f) }                                   // startled "!"
                1 -> { st = St.WAVE; stT = 0f; stDur = 1.1f; burst(x, y - 8 * u, 3, 1, pink(), 120f) }                                // little wave and hearts
                2 -> { flashA = 1f; burst(x - 4 * u, y - 9 * u, 3, 2, 0xFFFFE066.toInt(), 180f); idle(0.7f) }                          // camera flash: say cheese
                3 -> { sq = 0.4f; sqv = 0f; burst(x, y - 5 * u, 5, 0, bodyColor(), 160f); idle(0.5f) }                                  // boop
                else -> { scare = 1.0f; st = St.WAVE; stT = 0f; stDur = 1.2f; burst(x, y - 8 * u, 4, 1, pink(), 140f) }               // ticklish wiggle
            }
        }

        fun cheer() { if (st == St.IDLE || st == St.WALK || st == St.SLEEP) trick() }

        // ---- time of day: night rests, evening is hyper ----
        private fun moodNow(): Float {
            val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
            return when {
                h < 5.5f -> 0.25f      // deep night: resting
                h < 7f -> 0.5f         // waking up
                h < 11f -> 0.8f        // active
                h < 14f -> 0.6f       // after lunch: calm
                h < 18f -> 0.85f       // active
                h < 22f -> 1.2f        // evening: hyperactive
                h < 23.5f -> 0.55f      // winding down
                else -> 0.25f
            }
        }

        private fun speedPoll(now: Long) {
            if (pollBusy || now - pollAt < 2000L) return
            pollAt = now; pollBusy = true
            Thread {
                try { kmh = if (reactOn) DriveProfile.speedKmhPublic(context) ?: 0f else 0f } catch (_: Throwable) { kmh = 0f }
                try { batt = DriveProfile.battPctPublic(context) ?: -1f } catch (_: Throwable) { batt = -1f }
                pollBusy = false
            }.start()
        }

        // ---- world helpers ----
        private fun refreshPlats() {
            val now = SystemClock.uptimeMillis()
            if (now - platAt < 120L) return
            platAt = now
            if (!jumpOn || FloatMaster.hidden) { plats = emptyList(); unders = emptyList(); return }
            getLocationOnScreen(loc)
            val all = FloatPush.screenRects().filter { it.width() > 8 * u }.map { Plat(it.left - loc[0].toFloat(), it.top - loc[1].toFloat(), it.right - loc[0].toFloat(), it.bottom - loc[1].toFloat()) }
            plats = all.filter { it.t > 6 * u && it.t < H - 4 * d }      // windows pushed against the top give no standing room
            unders = all.filter { it.b > 14 * u && it.b < H - 18 * u }   // their lower edge is something to hang from
        }

        private fun support(px: Float, py: Float): Float {
            var best = H; var l = 0f; var r = W
            val tol = 24 * d
            for (p in plats) if (px >= p.l && px <= p.r && p.t >= py - tol && p.t < best) { best = p.t; l = p.l; r = p.r }
            sl = l; sr = r; return best
        }

        private fun burst(px: Float, py: Float, n: Int, kind: Int, col: Int, spd: Float = 260f) {
            repeat(n) {
                if (pts.size > 70) return
                val a = rnd.nextFloat() * 6.2832f; val s = (0.3f + rnd.nextFloat()) * spd * d
                pts.add(Pt(px, py, cos(a) * s, sin(a) * s - 120 * d, 0.6f + rnd.nextFloat() * 0.5f, 1.1f, kind, col))
            }
        }
        private fun rise(px: Float, py: Float, kind: Int, col: Int) { if (pts.size < 70) pts.add(Pt(px, py, (rnd.nextFloat() - 0.5f) * 40 * d, -60 * d, 1.6f, 1.6f, kind, col)) }
        private fun pink() = 0xFFFF6FA5.toInt()
        private fun bodyColor(): Int = if (rainbow) Color.HSVToColor(floatArrayOf((t * 70f) % 360f, 0.6f, 1f)) else base

        // ---- main step ----
        private fun step(dt0: Float) {
            W = width.toFloat(); H = height.toFloat()
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - moodAt > 30_000L) { mood = moodNow(); moodAt = nowMs }
            speedPoll(nowMs)
            refreshPlats()
            val battF = if (batt < 0f) 1f else if (batt >= 50f) 1f else if (batt >= 30f) 0.85f else if (batt >= 20f) 0.65f else if (batt >= 10f) 0.5f else 0.4f   // a low car battery slows the pet down
            val dt = dt0 * pace * mood.coerceAtLeast(0.5f) * battF
            t += dt0; stT += dt; secT += dt0
            if (secT >= 1f) {   // once a second: maybe react to the car going very fast (not every time)
                secT = 0f
                if (reactOn && kmh > 110f && nowMs - reactAt > 25_000L && rnd.nextFloat() < 0.12f) { reactPending = true; reactAt = nowMs }
            }
            legs += dt * 14f
            if (st != St.HELD) wig = 0f
            if (t > blinkAt) blinkAt = t + 2f + rnd.nextFloat() * 3f
            if (t > lookAt) { lookAt = t + 0.8f + rnd.nextFloat() * 1.6f; look = (rnd.nextInt(3) - 1).toFloat() }
            val da = (((angT - ang) % 360f) + 540f) % 360f - 180f
            ang += da.coerceIn(-720f * dt0, 720f * dt0); ang = ((ang + 540f) % 360f) - 180f
            sqv += (-sq * 240f - sqv * 14f) * dt0; sq += sqv * dt0
            ghost = max(0f, ghost - dt0); scare = max(0f, scare - dt0); flashA = max(0f, flashA - dt0 * 4f)
            updatePts(dt0)
            if (batt in 0f..20f && st != St.ACT && rnd.nextFloat() < dt0 * 0.7f && pts.size < 60) pts.add(Pt(x + face * 3 * u, y - 9 * u, face * 10f * d, 20f * d, 0.9f, 0.9f, 5, 0xFF6EC8FF.toInt()))

            val grounded = st == St.IDLE || st == St.WALK || st == St.SLEEP || st == St.BALL || st == St.BUG || st == St.SPIN || st == St.WAVE || st == St.GLITCH || st == St.ACT || st == St.HELD || st == St.REACT && sub != 3
            if (grounded && st != St.CLIMB) {
                val s = support(x, y)
                if (s < y - 0.5f) y = s
                else if (s > y + 3 * d) { dropToys(); st = St.FALL; vx = 0f; vy = 0f; stT = 0f }
            }
            if (reactPending && (st == St.IDLE || st == St.WALK || st == St.SLEEP) && surf == Sf.FLOOR) { reactPending = false; startReact() }

            when (st) {
                St.IDLE -> { hop = 0f; if (stT > stDur) think() }
                St.WALK -> walk(dt, 55f * d)
                St.JUMP -> jump()
                St.FALL -> fall(dt)
                St.CLIMB -> climb(dt)
                St.SLEEP -> { if (rnd.nextFloat() < dt * 0.8f) rise(x + face * 4 * u, y - 9 * u, 2, 0xFFBBD7FF.toInt()); if (stT > stDur) { st = St.IDLE; stT = 0f; stDur = 0.5f; sq = 0.15f } }
                St.BALL -> playBall(dt)
                St.BUG -> playBug(dt)
                St.SPIN -> { hop = abs(sin(stT * 9f)) * 6 * u; if ((stT * 8f).toInt() % 2 == 0) face = 1 else face = -1; if (rnd.nextFloat() < dt * 8f) burst(x, y - hop - 4 * u, 1, 0, bodyColor(), 160f); if (stT > stDur) { hop = 0f; idle(0.4f) } }
                St.WAVE -> { arm = ((stT * 5f).toInt() % 2) == 0; if (rnd.nextFloat() < dt * 3f) rise(x + (rnd.nextFloat() - 0.5f) * 6 * u, y - 10 * u, 1, pink()); if (stT > stDur) { arm = false; idle(0.5f) } }
                St.GLITCH -> glitch()
                St.REACT -> react(dt)
                St.ACT -> doAct(dt)
                St.HELD -> held(dt)
                St.DRAG -> drag(dt0)
            }
            // keep inside the screen
            if (surf == Sf.FLOOR) x = x.coerceIn(0f, W)
            moveHit(); updateBounds()
        }

        private fun moveHit() {
            val v = hitV ?: return; val lp = hitLp ?: return
            val now = SystemClock.uptimeMillis(); if (now - hitAt < 40L) return; hitAt = now
            val a = Math.toRadians(ang.toDouble()); val cx = x + 5 * u * sin(a).toFloat(); val cy = y - hop - 5 * u * cos(a).toFloat()
            val s = (15 * u).toInt(); val nx = (cx - s / 2f).toInt(); val ny = (cy - s / 2f).toInt()
            if (nx == lp.x && ny == lp.y && lp.width == s) return
            lp.x = nx; lp.y = ny; lp.width = s; lp.height = s
            try { context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch (_: Throwable) {}
        }

        private fun idle(s: Float) { st = St.IDLE; stT = 0f; stDur = s }
        private fun dropToys() { if (ball) burst(bx, by, 8, 0, 0xFFFFFFFF.toInt(), 200f); ball = false; bug = false; pouncing = false }

        // ---- choosing what to do ----
        private fun think() {
            val low = batt in 0f..20f; val dead = batt in 0f..10f
            val hyper = mood >= 1.1f && !low; val rest = mood <= 0.3f || dead
            val opts = ArrayList<Pair<Int, Int>>()   // action id to weight
            opts += 0 to (if (rest) 8 else 44)                         // walk
            opts += 1 to (if (rest) 1 else if (hyper) 12 else 7)   // jump
            opts += 2 to (if (hyper) 6 else 3)                        // spin
            opts += 3 to (if (hyper) 6 else 3)                        // backflip
            opts += 4 to 6                                             // wave
            opts += 5 to (if (hyper) 4 else 2)                         // glitch hop
            if (climbOn && y >= H - 1f) opts += 6 to (if (rest) 2 else if (hyper) 24 else 18)
            if (unders.isNotEmpty() && y >= H - 1f) opts += 10 to (if (rest) 2 else if (hyper) 20 else 14)
            if (lifeOn) { opts += 11 to (if (rest) 3 else 9); opts += 12 to (if (rest) 2 else 6); opts += 13 to (if (rest) 4 else 5); opts += 14 to (if (rest) 0 else if (hyper) 14 else 5); opts += 15 to 5 }
            if (napOn) opts += 7 to (if (rest) 70 else if (mood < 0.65f) 12 else 3)
            if (toysOn) { opts += 8 to (if (rest) 2 else if (hyper) 16 else 9); opts += 9 to (if (rest) 2 else if (hyper) 16 else 9) }
            if (low) {   // low car battery: no jumping about, it eats, charges and sleeps instead
                opts.removeAll { it.first in intArrayOf(1, 2, 3, 5, 10, 14) }
                if (lifeOn) { opts += 15 to 30; opts += 11 to 12 }
                if (napOn) opts += 7 to (if (dead) 40 else 14)
            }
            var tot = 0; for (o in opts) tot += o.second
            var r = rnd.nextInt(tot)
            var pick = 0
            for (o in opts) { r -= o.second; if (r < 0) { pick = o.first; break } }
            when (pick) {
                0 -> startWalk()
                1 -> if (!randomJump()) startWalk()
                2 -> { st = St.SPIN; stT = 0f; stDur = 1.1f }
                3 -> hopFlip()
                4 -> { st = St.WAVE; stT = 0f; stDur = 2f; burst(x, y - 8 * u, 3, 1, pink(), 120f) }
                5 -> { st = St.GLITCH; stT = 0f; stDur = 1.1f; sub = 0 }
                6 -> { goalX = if (x < W / 2) 3 * u else W - 3 * u; climbAfter = true; face = if (goalX > x) 1 else -1; st = St.WALK; stT = 0f }
                7 -> { st = St.SLEEP; stT = 0f; stDur = (if (rest) 14f else 6f) + rnd.nextFloat() * 8f }
                8 -> startBall()
                10 -> if (!startHang()) startWalk()
                in 11..15 -> startAct(pick - 11)
                else -> startBug()
            }
        }

        /** A random surprise (also the settings button). */
        private fun trick() {
            when (rnd.nextInt(5)) {
                0 -> { st = St.SPIN; stT = 0f; stDur = 1.4f }
                1 -> hopFlip()
                2 -> { st = St.WAVE; stT = 0f; stDur = 2f; burst(x, y - 8 * u, 6, 1, pink(), 160f) }
                3 -> { st = St.GLITCH; stT = 0f; stDur = 1.1f; sub = 0 }
                else -> startReact()
            }
        }

        private fun startWalk() {
            st = St.WALK; stT = 0f
            support(x, y)
            val a = if (sr > sl) sl + 2 * u else 3 * u; val b = if (sr > sl) sr - 2 * u else W - 3 * u
            goalX = if (b > a) a + rnd.nextFloat() * (b - a) else x
            face = if (goalX >= x) 1 else -1
            climbAfter = false
        }

        private fun walk(dt: Float, sp: Float) {
            val nx = x + face * sp * dt
            if (!goalX.isNaN() && (goalX - nx) * face <= 0f) {
                x = goalX; goalX = Float.NaN
                if (climbAfter) { climbAfter = false; startClimb(if (x < W / 2) Sf.LEFT else Sf.RIGHT) } else idle(0.4f + rnd.nextFloat() * 1.6f)
                return
            }
            if (nx < 3 * u || nx > W - 3 * u) { face = -face; goalX = Float.NaN; idle(0.3f); return }
            val s = support(nx, y)
            if (s > y + 3 * d) {   // the edge of a window
                val r = rnd.nextFloat()
                if (r < 0.15f && randomJump()) return
                if (r < 0.7f) { face = -face; goalX = Float.NaN; idle(0.3f); return }
                x = nx; dropToys(); st = St.FALL; vx = face * sp * 0.6f; vy = 0f; stT = 0f; return
            }
            x = nx; if (s < y) y = s
        }

        // ---- jumping ----
        private fun launch(tx: Float, ty: Float, clr0: Float, flipTurn: Int) {
            st = St.JUMP; stT = 0f; jx0 = x; jy0 = y; jtx = tx; jty = ty
            val clr = max(clr0, abs(tx - x) * 0.22f)
            val apex = min(y, ty) - clr
            val tu = sqrt(2f * (y - apex) / g); val td = sqrt(2f * (ty - apex) / g)
            jT = tu + td; jvx = (tx - x) / jT; jvy = -g * tu
            if (abs(tx - x) > 2 * u) face = if (tx > x) 1 else -1
            flipDir = flipTurn; sq = -0.3f; sqv = 900f
            angT = 0f
        }

        /** Jump up and hang from the lower edge of a floating window, crawling along it upside down. */
        private fun startHang(): Boolean {
            val c = unders.filter { y - it.b <= H * 0.78f }
            if (c.isEmpty()) return false
            val p = c[rnd.nextInt(c.size)]
            hangL = p.l; hangR = p.r
            val tx = p.l + (p.r - p.l) * (0.2f + 0.6f * rnd.nextFloat())
            launch(tx, p.b, 40 * d, 0); hangPending = true; angT = 180f
            return true
        }

        // ---- daily life: eat, shower, toilet, sports, charge ----
        private fun startAct(k: Int) {
            act = k; st = St.ACT; stT = 0f; flushed = false; goalX = Float.NaN
            stDur = when (k) { 0 -> 5f; 1 -> 6f; 2 -> 5.5f; 3 -> 6f; else -> 6f }
        }
        private fun doAct(dt: Float) {
            val blue = 0xFF6EC8FF.toInt()
            when (act) {
                0 -> if (rnd.nextFloat() < dt * 6f) burst(x + face * 5 * u, y - 3 * u, 1, 0, 0xFFFFD37A.toInt(), 80f)
                1 -> {
                    if (rnd.nextFloat() < dt * 40f && pts.size < 60) pts.add(Pt(x + (rnd.nextFloat() - 0.5f) * 5 * u, y - 15 * u, 0f, 260f * d, 0.32f, 0.32f, 5, blue))
                    if (rnd.nextFloat() < dt * 3f && pts.size < 60) pts.add(Pt(x + (rnd.nextFloat() - 0.5f) * 8 * u, y - 6 * u, 0f, -40f * d, 1.4f, 1.4f, 6, 0xFFFFFFFF.toInt()))
                }
                2 -> {
                    hop = 3 * u
                    if (stT < 3.2f) scare = 0.2f
                    else if (!flushed) { flushed = true; for (i in 0 until 10) { val a = i * 0.628f; pts.add(Pt(x + cos(a) * 3 * u, y - 3 * u + sin(a) * 0.8f * u, -sin(a) * 40f * d, cos(a) * 10f * d, 0.8f, 0.8f, 5, blue)) } }
                }
                3 -> {
                    sq = sin(stT * 8f) * 0.1f
                    if (rnd.nextFloat() < dt * 2.5f && pts.size < 60) pts.add(Pt(x + (rnd.nextFloat() - 0.5f) * 6 * u, y - 10 * u, (rnd.nextFloat() - 0.5f) * 60f * d, -60f * d, 0.7f, 0.7f, 5, blue))
                }
                else -> { sq = sin(stT * 3f) * 0.04f; if (rnd.nextFloat() < dt * 5f) rise(x + face * 9 * u, y - 4 * u, 2, 0xFFFFE066.toInt()); if (rnd.nextFloat() < dt * 2.5f) rise(x + (rnd.nextFloat() - 0.5f) * 8 * u, y - 11 * u, 1, pink()) }
            }
            if (stT > stDur) {
                if (act == 0 || act == 4) burst(x, y - 8 * u, 3, 1, pink(), 120f)
                sq = 0f; idle(0.6f)
            }
        }

        private fun drawProp(cv: Canvas, front: Boolean) {
            if (st != St.ACT) return
            paint.style = Paint.Style.FILL
            val ink = 0xFF1B1B1F.toInt(); val steel = 0xFFB9BCC4.toInt()
            fun r(l: Float, tp: Float, rr: Float, b: Float, c: Int) { paint.color = c; cv.drawRect(x + l * u, y + tp * u, x + rr * u, y + b * u, paint) }
            when (act) {
                2 -> if (!front) { r(-3f, -10f, 3f, -4.5f, 0xFFD9DCE3.toInt()); r(-2.5f, -9.5f, 2.5f, -5f, 0xFFEEF0F5.toInt()); r(-5.5f, -4f, 5.5f, -2.2f, 0xFFF4F5F8.toInt()); r(-3.5f, -2.2f, 3.5f, 0f, 0xFFD9DCE3.toInt()) }
                0 -> if (front) {   // a burger that shrinks with every bite
                    val bites = (stT / stDur * 4f).toInt().coerceAtMost(3); val w = 2.6f * (1f - bites * 0.28f); val fx = face * 7.5f
                    r(fx - w, -6.4f, fx + w, -5.2f, 0xFFE8A64A.toInt()); r(fx - w, -5.2f, fx + w, -4.7f, 0xFF5BC236.toInt()); r(fx - w, -4.7f, fx + w, -3.8f, 0xFF7A4326.toInt()); r(fx - w, -3.8f, fx + w, -2.8f, 0xFFE8A64A.toInt())
                }
                1 -> if (front) { r(-0.5f, -26f, 0.5f, -16f, steel); r(-3.5f, -16f, 3.5f, -14.8f, steel); r(-3.5f, -15.2f, 3.5f, -14.8f, 0xFF8E9098.toInt()) }
                3 -> if (front) {
                    val by0 = -14f - abs(sin(stT * 4f)) * 3f
                    r(-7f, by0, 7f, by0 + 0.7f, steel); r(-8.6f, by0 - 1.6f, -7f, by0 + 2.3f, ink); r(7f, by0 - 1.6f, 8.6f, by0 + 2.3f, ink); r(-9.6f, by0 - 0.8f, -8.6f, by0 + 1.5f, ink); r(8.6f, by0 - 0.8f, 9.6f, by0 + 1.5f, ink)
                }
                else -> if (front) {   // plugged in: cable, plug and a filling battery
                    val fx = face * 9f
                    r(fx - 1.5f, -4.5f, fx + 1.5f, -2f, 0xFF3A3A3C.toInt()); r(fx - 0.6f, -5.5f, fx + 0.6f, -4.5f, steel)
                    for (i in 0 until 8) { val cx = fx + face * (1.5f + i * 1.6f); val cy = -3f + sin(i * 0.9f) * 0.6f; r(min(cx, cx + face * 1.6f), cy - 0.25f, max(cx, cx + face * 1.6f), cy + 0.25f, 0xFF3A3A3C.toInt()) }
                    val f = (stT / stDur).coerceIn(0f, 1f)
                    r(-4.3f, -17.3f, 4.3f, -12.7f, steel); r(-4f, -17f, 4f, -13f, ink); r(-3.6f, -16.6f, -3.6f + 7.2f * f, -13.4f, 0xFFB6FF3B.toInt()); r(4.3f, -15.8f, 5f, -14.2f, steel)
                }
            }
        }

        /** A blinking empty battery above the head while the car battery is low. */
        private fun drawLowIcon(cv: Canvas) {
            if (batt !in 0f..20f || surf != Sf.FLOOR || st == St.ACT || st == St.SLEEP) return
            val bx0 = x - 3 * u; val by0 = y - 17 * u - hop
            paint.style = Paint.Style.FILL
            paint.color = 0xFFB9BCC4.toInt(); cv.drawRect(bx0, by0, bx0 + 6 * u, by0 + 3 * u, paint); cv.drawRect(bx0 + 6 * u, by0 + 0.9f * u, bx0 + 6.6f * u, by0 + 2.1f * u, paint)
            paint.color = 0xFF1B1B1F.toInt(); cv.drawRect(bx0 + 0.4f * u, by0 + 0.4f * u, bx0 + 5.6f * u, by0 + 2.6f * u, paint)
            if ((t * 2f).toInt() % 2 == 0) { paint.color = 0xFFFF3B30.toInt(); cv.drawRect(bx0 + 0.7f * u, by0 + 0.7f * u, bx0 + 1.7f * u, by0 + 2.3f * u, paint) }
        }

        private fun hopFlip() { launch(x, y, 70 * d + 6 * u, if (rnd.nextBoolean()) 1 else -1) }

        private fun randomJump(): Boolean {
            val c = ArrayList<FloatArray>()
            fun tryT(l: Float, tp: Float, r: Float) {
                val tx = l + (r - l) * (0.15f + 0.7f * rnd.nextFloat())
                val up = y - tp; val dx = abs(tx - x)
                if (up <= H * 0.55f && dx <= W * 0.6f && (abs(tp - y) > 4 * d || dx > 12 * u)) c.add(floatArrayOf(tx, tp))
            }
            for (p in plats) { if (abs(p.t - y) < 6 * d && x >= p.l && x <= p.r) continue; tryT(p.l, p.t, p.r) }
            if (y < H - 6 * d) tryT(3 * u, H, W - 3 * u)
            if (c.isEmpty()) return false
            val k = c[rnd.nextInt(c.size)]
            launch(k[0], k[1], 34 * d + 3 * u, if (rnd.nextFloat() < 0.15f) (if (rnd.nextBoolean()) 1 else -1) else 0)
            return true
        }

        private fun jump() {
            val tt = min(stT, jT)
            x = jx0 + jvx * tt; y = jy0 + jvy * tt + 0.5f * g * tt * tt
            flip = flipDir * 360f * min(1f, tt / jT)
            hop = 0f
            if (stT >= jT) {
                x = jtx; y = jty; flip = 0f; flipDir = 0; sq = 0.3f; sqv = -600f
                if (hangPending) { hangPending = false; hangOn = true; hangY = y; surf = Sf.CEIL; st = St.CLIMB; stT = 0f; stDur = 9f + rnd.nextFloat() * 8f; climbTurn = 2f; angT = 180f; face = if (rnd.nextBoolean()) 1 else -1; burst(x, y + 2 * u, 5, 3, 0xFFCCCCCC.toInt(), 120f); return }
                burst(x, y, 4, 3, 0xFFCCCCCC.toInt(), 120f)
                if (pouncing) {
                    pouncing = false
                    if (bug && abs(x - bgx) < 6 * u) { bug = false; burst(bgx, y - 2 * u, 12, 0, 0xFF7CFF6B.toInt(), 300f); burst(x, y - 8 * u, 4, 1, pink(), 160f); st = St.WAVE; stT = 0f; stDur = 1.3f; return }
                    bug = false
                }
                if (st == St.JUMP) {
                    if (chain > 0) {
                        chain--; support(x, y)
                        val lo = if (sr > sl) sl + 3 * u else 3 * u; val hi = if (sr > sl) sr - 3 * u else W - 3 * u
                        launch((x + (rnd.nextFloat() - 0.5f) * 24 * u).coerceIn(lo, max(lo, hi)), y, 60 * d + 5 * u, if (rnd.nextBoolean()) 1 else -1)
                    } else idle(0.3f + rnd.nextFloat() * 0.8f)
                }
            }
        }

        private fun fall(dt: Float) {
            vy += g * dt
            val nx = (x + vx * dt).coerceIn(0f, W); val ny = y + vy * dt
            val s = support(nx, y)
            flip = 0f; angT = 0f; surf = Sf.FLOOR
            if (ny >= s) { x = nx; y = s; sq = 0.35f; sqv = -700f; burst(x, y, 5, 3, 0xFFCCCCCC.toInt(), 140f); idle(0.4f) } else { x = nx; y = ny }
        }

        // ---- crawling over the screen edges ----
        private fun startClimb(side: Sf) {
            hangOn = false; surf = side; st = St.CLIMB; stT = 0f; stDur = 12f + rnd.nextFloat() * 10f; climbTurn = 3f
            if (side == Sf.RIGHT) { x = W; face = 1 } else { x = 0f; face = -1 }   // RIGHT: +1 climbs; LEFT: -1 climbs
            y = H; angT = side.a
        }

        private fun climb(dt: Float) {
            val sp = 42f * d
            val a = Math.toRadians(surf.a.toDouble())
            x += (cos(a) * face * sp * dt).toFloat(); y += (sin(a) * face * sp * dt).toFloat()
            val m = 6 * u
            when (surf) {
                Sf.RIGHT -> { x = W
                    if (y <= m && face == 1) { surf = Sf.CEIL; y = 0f; x = W - m; face = 1; angT = 180f }
                    else if (y >= H && face == -1) { toFloor(W - m, -1) } }
                Sf.LEFT -> { x = 0f
                    if (y <= m && face == -1) { surf = Sf.CEIL; y = 0f; x = m; face = -1; angT = 180f }
                    else if (y >= H && face == 1) { toFloor(m, 1) } }
                Sf.CEIL -> if (hangOn) { y = hangY
                        if (x <= hangL + 3 * u && face == 1) face = -1 else if (x >= hangR - 3 * u && face == -1) face = 1 }
                    else { y = 0f
                    if (x <= m && face == 1) { surf = Sf.LEFT; x = 0f; y = m; face = 1; angT = 90f }
                    else if (x >= W - m && face == -1) { surf = Sf.RIGHT; x = W; y = m; face = -1; angT = -90f } }
                Sf.FLOOR -> {}
            }
            if (surf == Sf.CEIL && rnd.nextFloat() < dt * 0.5f) burst(x, y + 3 * u, 1, 0, bodyColor(), 60f)
            climbTurn -= dt
            if (climbTurn <= 0f) { climbTurn = 2f + rnd.nextFloat() * 4f; if (rnd.nextFloat() < 0.3f) face = -face }
            if (stT > stDur && st == St.CLIMB) {   // let go
                vx = when (surf) { Sf.RIGHT -> -80f * d; Sf.LEFT -> 80f * d; else -> (rnd.nextFloat() - 0.5f) * 120f * d }
                vy = 0f; st = St.FALL; stT = 0f; flip = 0f; hangOn = false
            }
        }
        private fun toFloor(nx: Float, f: Int) { surf = Sf.FLOOR; x = nx; y = H; face = f; angT = 0f; idle(0.5f) }

        // ---- toys ----
        private fun startBall() {
            ball = true; bx = x; by = y - 12 * u; bvx = (rnd.nextFloat() - 0.5f) * 100f * d; bvy = -300f * d
            st = St.BALL; stT = 0f; stDur = 6f + rnd.nextFloat() * 2f
        }
        private fun playBall(dt: Float) {
            val r = 2.4f * u
            bvy += g * 0.6f * dt; bx += bvx * dt; val pby = by + r; by += bvy * dt
            if (bx < r) { bx = r; bvx = abs(bvx) * 0.9f }; if (bx > W - r) { bx = W - r; bvx = -abs(bvx) * 0.9f }
            if (bvy > 0f) {
                val s = support(bx, pby - 1f)
                if (by + r >= s) { by = s - r; bvy = -bvy * 0.6f; bvx *= 0.95f; if (abs(bvy) < 120f * d) bvy = 0f }
            }
            // chase it along this surface
            support(x, y)
            val lo = if (sr > sl) sl + 2 * u else 3 * u; val hi = if (sr > sl) sr - 2 * u else W - 3 * u
            val tx = (bx + bvx * 0.2f).coerceIn(lo, max(lo, hi))
            if (abs(tx - x) > 2 * u) { face = if (tx > x) 1 else -1; x += face * min(90f * d * dt, abs(tx - x)) }
            val dx = bx - x
            if (t > kickAt && abs(dx) < 7 * u && by + r >= y - 9 * u && by + r <= y + u && (bvy > 0f || abs(bvy) < 1f)) {   // header / kick, with a cooldown so it never re-triggers while overlapping
                kickAt = t + 0.7f
                bvy = -(270f + rnd.nextFloat() * 110f) * d; bvx = dx * 1.5f + (rnd.nextFloat() - 0.5f) * 80f * d
            }
            if (stT > stDur) { burst(bx, by, 3, 0, 0xFFFFFFFF.toInt(), 120f); ball = false; idle(0.5f) }
        }

        private fun startBug() {
            support(x, y)
            val lo = if (sr > sl) sl + u else 2 * u; val hi = if (sr > sl) sr - u else W - 2 * u
            if (hi - lo < 24 * u) { startWalk(); return }
            bgx = if (x - lo > hi - x) lo + 4 * u else hi - 4 * u; bgv = 0f; bug = true; pouncing = false
            st = St.BUG; stT = 0f; stDur = 12f
        }
        private fun playBug(dt: Float) {
            support(x, y)
            val lo = if (sr > sl) sl + u else 2 * u; val hi = if (sr > sl) sr - u else W - 2 * u
            if (!bug) { idle(0.3f); return }
            val dist = bgx - x
            val flee = if (abs(dist) < 16 * u) (if (dist > 0) 1f else -1f) else (if (rnd.nextFloat() < dt * 1.5f) (rnd.nextInt(3) - 1).toFloat() else bgv / (70f * d + 0.001f))
            bgv = flee * 70f * d
            bgx += bgv * dt
            if (bgx < lo) { bgx = lo; bgv = abs(bgv) }; if (bgx > hi) { bgx = hi; bgv = -abs(bgv) }
            face = if (dist >= 0) 1 else -1
            if (abs(dist) > 10 * u) x += face * 55f * d * dt
            else if (!pouncing) { pouncing = true; bgv = 0f; launch(bgx, y, 22 * d, 0) }
            if (stT > stDur) { bug = false; burst(bgx, y - u, 6, 0, 0xFF7CFF6B.toInt(), 120f); idle(0.4f) }
        }

        private fun glitch() {
            ghost = 0.2f
            if (sub == 0 && stT > 0.55f) {
                sub = 1; burst(x, y - 4 * u, 8, 0, 0xFF00E5FF.toInt(), 300f)
                val c = ArrayList<FloatArray>()
                for (p in plats) c.add(floatArrayOf(p.l + (p.r - p.l) * rnd.nextFloat(), p.t))
                c.add(floatArrayOf(4 * u + rnd.nextFloat() * (W - 8 * u), H))
                val k = c[rnd.nextInt(c.size)]; x = k[0]; y = k[1]
                burst(x, y - 4 * u, 8, 0, 0xFFFF2BD6.toInt(), 300f)
            }
            if (stT > stDur) idle(0.4f)
        }

        // ---- reactions to a very fast car: always random ----
        private fun startReact() {
            sub = rnd.nextInt(5); st = St.REACT; stT = 0f; stDur = 3f + rnd.nextFloat() * 3f
            goalX = Float.NaN
            when (sub) {
                0 -> { burst(x, y - 8 * u, 6, 2, 0xFFFFE066.toInt(), 200f); chain = 2 + rnd.nextInt(3); launch(x, y, 70 * d + 5 * u, if (rnd.nextBoolean()) 1 else -1) }   // wheee: flips and stars
                1 -> { }                                                       // panic run
                2 -> { face = -1 }                                             // wind blown
                3 -> { goalX = if (x < W / 2) 3 * u else W - 3 * u; face = if (goalX > x) 1 else -1 }   // hold on to the wall
                else -> { }                                                    // zoom eyes and speed lines
            }
        }
        private fun react(dt: Float) {
            when (sub) {
                1 -> { if (rnd.nextFloat() < dt * 1.4f) face = -face
                    val nx = x + face * 190f * d * dt; if (nx in 3 * u..W - 3 * u && support(nx, y) <= y + 3 * d) x = nx else face = -face
                    if (rnd.nextFloat() < dt * 4f) rise(x, y - 11 * u, 3, 0xFFFF5A5A.toInt()); scare = 0.3f; if (stT > stDur) idle(0.5f) }
                2 -> { val nx = x - 110f * d * dt; if (nx > 3 * u && support(nx, y) <= y + 3 * d) x = nx
                    if (rnd.nextFloat() < dt * 14f) pts.add(Pt(W, y - rnd.nextFloat() * 12 * u, -900f * d, 0f, 0.5f, 0.5f, 4, 0xFFFFFFFF.toInt()))
                    scare = 0.3f; if (stT > stDur) idle(0.5f) }
                3 -> { if (!goalX.isNaN()) { val nx = x + face * 150f * d * dt; if ((goalX - nx) * face <= 0f) { x = goalX; goalX = Float.NaN; stT = 0f; stDur = 3f; startClimbHold() } else x = nx } }
                else -> { if (rnd.nextFloat() < dt * 12f) pts.add(Pt(W, y - rnd.nextFloat() * 12 * u, -1200f * d, 0f, 0.4f, 0.4f, 4, 0xFFFFFFFF.toInt()))
                    scare = 0.3f; hop = 0f; if (stT > stDur) idle(0.5f) }
            }
        }
        private fun startClimbHold() { startClimb(if (x < W / 2) Sf.LEFT else Sf.RIGHT); stDur = 4f; y = H - 14 * u; scare = 4f }

        private fun updatePts(dt: Float) {
            val it = pts.iterator()
            while (it.hasNext()) {
                val p = it.next(); p.life -= dt
                if (p.life <= 0f) { it.remove(); continue }
                p.x += p.vx * dt; p.y += p.vy * dt
                when (p.kind) { 0, 3, 5 -> p.vy += g * 0.35f * dt; 1, 2, 6 -> { p.vx += sin(t * 5f + p.max) * 30f * d * dt } }
            }
        }

        // ---- drawing ----
        private fun updateBounds() {
            val r = (28 * u).toInt()
            cur.set((x - r).toInt(), (y - r - 6 * u).toInt(), (x + r).toInt(), (y + r).toInt())
            if (ball) cur.union((bx - 4 * u).toInt(), (by - 4 * u).toInt(), (bx + 4 * u).toInt(), (by + 4 * u).toInt())
            if (bug) cur.union((bgx - 4 * u).toInt(), (y - 4 * u).toInt(), (bgx + 4 * u).toInt(), (y + 2 * u).toInt())
            for (p in pts) cur.union((p.x - 4 * u).toInt(), (p.y - 4 * u).toInt(), (p.x + 4 * u).toInt(), (p.y + 4 * u).toInt())
            if (st == St.REACT && (sub == 2 || sub == 4)) cur.union(0, (y - 14 * u).toInt(), width, (y + u).toInt())
            if (flashA > 0f) cur.set(0, 0, width, height)
            val d2 = Rect(prev); d2.union(cur)
            prev.set(cur)
            invalidate(d2)
        }

        private fun rect(cv: Canvas, l: Float, tp: Float, r: Float, b: Float, c: Int) { paint.color = c; cv.drawRect(l * u, tp * u, r * u, b * u, paint) }
        private fun pix(cv: Canvas, pat: Array<String>, px: Float, py: Float, q: Float, c: Int, a: Float) {
            paint.color = c; paint.alpha = (255 * a.coerceIn(0f, 1f)).toInt()
            for (j in pat.indices) for (i in pat[j].indices) if (pat[j][i] == 'X') cv.drawRect(px + i * q, py + j * q, px + (i + 1) * q, py + (j + 1) * q, paint)
            paint.alpha = 255
        }
        private fun shade(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt().coerceAtMost(255), (Color.green(c) * f).toInt().coerceAtMost(255), (Color.blue(c) * f).toInt().coerceAtMost(255))

        override fun onDraw(cv: Canvas) {
            paint.style = Paint.Style.FILL
            if (flashA > 0f) { paint.color = 0xFFFFFFFF.toInt(); paint.alpha = (200 * flashA).toInt(); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.alpha = 255 }
            // toys
            if (ball) { paint.color = 0xFFFFFFFF.toInt(); cv.drawCircle(bx, by, 2.4f * u, paint); paint.color = 0xFFFF5A8A.toInt(); cv.drawRect(bx - 2.4f * u, by - 0.5f * u, bx + 2.4f * u, by + 0.5f * u, paint) }
            if (bug) { val bb = 0xFF7CFF6B.toInt(); paint.color = bb; cv.drawRect(bgx - 1.5f * u, y - 1.6f * u, bgx + 1.5f * u, y - 0.4f * u, paint)
                cv.drawRect(bgx - 1.5f * u, y - 0.4f * u, bgx - 1f * u, y, paint); cv.drawRect(bgx + 1f * u, y - 0.4f * u, bgx + 1.5f * u, y, paint) }
            // speed lines / wind
            for (p in pts) if (p.kind == 4) { paint.color = 0xFFFFFFFF.toInt(); paint.alpha = 160; cv.drawRect(p.x, p.y, p.x + 9 * u, p.y + 0.4f * u, paint); paint.alpha = 255 }
            drawProp(cv, false); drawMascot(cv); drawProp(cv, true); drawLowIcon(cv)
            for (p in pts) {
                val a = (p.life / p.max).coerceIn(0f, 1f)
                when (p.kind) {
                    0 -> { paint.color = p.col; paint.alpha = (255 * a).toInt(); cv.drawRect(p.x, p.y, p.x + 0.8f * u, p.y + 0.8f * u, paint); paint.alpha = 255 }
                    1 -> pix(cv, HEART, p.x, p.y, 0.6f * u, p.col, a)
                    2 -> if (p.col == 0xFFFFE066.toInt()) pix(cv, STAR, p.x, p.y, 0.6f * u, p.col, a) else pix(cv, ZZ, p.x, p.y, 0.6f * u, p.col, a)
                    5 -> { paint.color = p.col; paint.alpha = (230 * a).toInt(); cv.drawRect(p.x, p.y, p.x + 0.5f * u, p.y + 1f * u, paint); paint.alpha = 255 }
                    6 -> { paint.color = p.col; paint.alpha = (200 * a).toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.25f * u; cv.drawCircle(p.x, p.y, 0.7f * u, paint); paint.style = Paint.Style.FILL; paint.alpha = 255 }
                    3 -> if (p.col == 0xFFFF5A5A.toInt()) pix(cv, BANG, p.x, p.y, 0.8f * u, p.col, a) else { paint.color = p.col; paint.alpha = (200 * a).toInt(); cv.drawRect(p.x, p.y, p.x + 0.7f * u, p.y + 0.7f * u, paint); paint.alpha = 255 }
                }
            }
        }

        private fun drawMascot(cv: Canvas) {
            val body = bodyColor(); val dark = shade(body, 0.75f); val light = shade(body, 1.25f)
            val ink = 0xFF1B1B1F.toInt()
            val suffer = batt in 0f..20f && !(st == St.ACT && act == 4) && st != St.HELD; val bliss = (st == St.ACT && act == 4) || (st == St.HELD && hsub == 2)
            val alarmed = st == St.REACT || (st == St.HELD && (hsub == 0 || hsub == 1 || hsub == 4))
            val sy = (1f + sq) * (if (suffer) 0.94f else 1f); val sx = 1f / sy
            val shake = (if (scare > 0f) sin(t * 70f) * 0.35f * u else if (suffer) sin(t * 35f) * 0.12f * u else 0f) + wig + (if (st == St.HELD && hsub == 2) sin(t * 45f) * 0.08f * u else 0f)
            val sleeping = st == St.SLEEP
            val walking = st == St.WALK || st == St.CLIMB || st == St.DRAG || (st == St.BALL || st == St.BUG) || (st == St.REACT && (sub == 1 || sub == 3))
            val air = st == St.JUMP || st == St.FALL || st == St.DRAG
            fun pass(ox: Float, col: Int, aMul: Float) {
                cv.save()
                cv.translate(x + ox + shake, y - hop)
                cv.rotate(ang)
                if (flip != 0f) { cv.translate(0f, -4 * u); cv.rotate(flip * face); cv.translate(0f, 4 * u) }
                cv.scale(sx * face, sy)
                val c = if (col != 0) col else body
                paint.alpha = (255 * aMul).toInt()
                val k = if (col != 0) col else dark
                // wheels (spin while walking, tucked in the air)
                for (wx in floatArrayOf(-3.5f, 3.5f)) {
                    val wy = if (air) -2.2f else -1.2f
                    paint.color = if (col != 0) col else ink; cv.drawCircle(wx * u, wy * u, 1.25f * u, paint)
                    if (col == 0) { paint.color = 0xFFB9BCC4.toInt(); cv.drawCircle(wx * u, wy * u, 0.5f * u, paint)
                        val sa = if (walking) legs else 0f
                        paint.color = 0xFFB9BCC4.toInt(); paint.strokeWidth = 0.35f * u
                        cv.drawLine((wx + cos(sa) * 1.0f) * u, (wy + sin(sa) * 1.0f) * u, (wx - cos(sa) * 1.0f) * u, (wy - sin(sa) * 1.0f) * u, paint) }
                }
                // camera body: blocky rounded
                rect(cv, -5f, -8f, 5f, -2f, c); rect(cv, -6f, -7f, 6f, -3f, c)
                // grip handles
                val up = arm || air || st == St.REACT && (sub == 0) || st == St.SPIN || (st == St.ACT && (act == 0 || act == 3)) || (st == St.HELD && (hsub == 0 || hsub == 1 || hsub == 4))
                val wob = if (arm) sin(t * 22f) * 0.5f else 0f
                rect(cv, -8f, if (up) -8.5f else -6f, -6f, if (up) -7.5f else -5f, k)
                rect(cv, 6f, if (up) -8.5f + wob else -6f, 8f, if (up) -7.5f + wob else -5f, k)
                if (col == 0) {
                    rect(cv, -5f, -3f, 5f, -2f, dark); rect(cv, -6f, -4f, -5f, -3f, dark); rect(cv, 5f, -4f, 6f, -3f, dark); rect(cv, -5f, -8f, -3f, -7f, light)
                    val happy = st == St.WAVE || (st == St.REACT && sub == 0) || st == St.SPIN || (st == St.ACT && (act == 1 || act == 3 || act == 4 || (act == 2 && stT >= 3.2f))) || (st == St.HELD && (hsub == 2 || hsub == 3)) || st == St.DRAG
                    // viewfinder bump, flash, and the blinking REC light
                    rect(cv, -2f, -9.4f, 2f, -8f, dark); rect(cv, -1f, -9.1f, 1f, -8.3f, 0xFF2A4B7C.toInt())
                    rect(cv, -5.5f, -9f, -4f, -8f, if (happy && (t * 8f).toInt() % 2 == 0) 0xFFFFF3A0.toInt() else 0xFFE8E2B8.toInt())
                    val rec = !sleeping && (t * 1.2f).toInt() % 2 == 0
                    rect(cv, 3.2f, -9.2f, 4.7f, -8f, if (st == St.GLITCH || (st == St.REACT && sub != 0)) 0xFFFF3B30.toInt() else if (batt in 0f..20f) (if (rec) 0xFFFFA000.toInt() else 0xFF6B4300.toInt()) else if (rec) 0xFFFF3B30.toInt() else 0xFF7A1A14.toInt())
                    val lk = if (st == St.WALK || st == St.CLIMB) 0.8f else look * 0.7f
                    val blink = t > blinkAt - 0.15f
                    val glow = if (st == St.GLITCH || (st == St.REACT && sub != 0)) 0xFFFF6B6B.toInt() else if (suffer) 0xFFFFA000.toInt() else 0xFF7DF9FF.toInt()
                    val dim = 0xFF3A6E73.toInt()
                    if (bliss) { rect(cv, -5.7f, -3.9f, -4.5f, -3.1f, 0xAAFF6FA5.toInt()); rect(cv, 4.5f, -3.9f, 5.7f, -3.1f, 0xAAFF6FA5.toInt()) }
                    if (faceStyle == 1) {
                        // two round lens eyes
                        rect(cv, -1.5f, -3f, 1.5f, -2.6f, 0xFFFF3B30.toInt())
                        for (ex in floatArrayOf(-2.7f, 2.7f)) {
                            val ly = -5.3f
                            paint.color = ink; cv.drawCircle(ex * u, ly * u, 2.2f * u, paint)
                            paint.color = 0xFF2A4B7C.toInt(); cv.drawCircle(ex * u, ly * u, 1.8f * u, paint)
                            when {
                                sleeping || blink -> { paint.color = ink; cv.drawArc((ex - 2.2f) * u, (ly - 2.2f) * u, (ex + 2.2f) * u, (ly + 2.2f) * u, 180f, 180f, true, paint); rect(cv, ex - 1.9f, ly - 0.15f, ex + 1.9f, ly + 0.25f, ink) }
                                suffer -> { paint.color = 0xFF08162B.toInt(); cv.drawCircle(ex * u, (ly + 0.5f) * u, 1.0f * u, paint); paint.color = ink; cv.drawArc((ex - 2.2f) * u, (ly - 2.2f) * u, (ex + 2.2f) * u, (ly + 2.2f) * u, 180f, 180f, true, paint); rect(cv, ex - 0.3f, ly + 1.4f, ex + 0.3f, ly + 2.4f, 0xFF6EC8FF.toInt()) }
                                happy -> { paint.color = 0xFF9FE8FF.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.5f * u; paint.strokeCap = Paint.Cap.SQUARE
                                    cv.drawLine((ex - 1.1f) * u, (ly + 0.6f) * u, ex * u, (ly - 0.6f) * u, paint); cv.drawLine(ex * u, (ly - 0.6f) * u, (ex + 1.1f) * u, (ly + 0.6f) * u, paint); paint.style = Paint.Style.FILL }
                                alarmed -> { paint.color = 0xFFFFFFFF.toInt(); cv.drawCircle(ex * u, ly * u, 1.8f * u, paint); paint.color = ink; cv.drawCircle((ex + lk * 0.3f) * u, ly * u, 0.6f * u, paint) }
                                else -> { paint.color = 0xFF08162B.toInt(); cv.drawCircle((ex + lk * 0.45f) * u, ly * u, 1.0f * u, paint)
                                    paint.color = 0xFFFFFFFF.toInt(); cv.drawRect((ex + lk * 0.45f - 0.7f) * u, (ly - 0.7f) * u, (ex + lk * 0.45f - 0.2f) * u, (ly - 0.2f) * u, paint) }
                            }
                        }
                    } else if (faceStyle == 2) {
                        // a visor strip with one glowing bar
                        rect(cv, -5f, -7f, 5f, -3.6f, ink); rect(cv, -5.4f, -6.4f, 5.4f, -4.2f, ink)
                        rect(cv, -1.5f, -3.2f, 1.5f, -2.7f, 0xFFFF3B30.toInt())
                        val vx0 = lk * 0.8f
                        when {
                            sleeping -> rect(cv, -1.5f, -5.3f, 1.5f, -5f, dim)
                            blink -> rect(cv, -3.5f + vx0, -5.4f, 3.5f + vx0, -5f, glow)
                            suffer -> { if ((t * 6f).toInt() % 7 != 0) { rect(cv, -3.5f, -5.8f, -2f, -5.3f, glow); rect(cv, -2f, -5.3f, -0.5f, -4.8f, glow); rect(cv, -0.5f, -4.8f, 0.5f, -4.3f, glow); rect(cv, 0.5f, -5.3f, 2f, -4.8f, glow); rect(cv, 2f, -5.8f, 3.5f, -5.3f, glow) } }
                            happy -> { rect(cv, -3.5f, -5f, -2f, -4.5f, glow); rect(cv, -2f, -5.5f, -0.5f, -5f, glow); rect(cv, -0.5f, -6f, 0.5f, -5.5f, glow); rect(cv, 0.5f, -5.5f, 2f, -5f, glow); rect(cv, 2f, -5f, 3.5f, -4.5f, glow) }
                            alarmed -> rect(cv, -3.8f + vx0, -6.5f, 3.8f + vx0, -4.5f, 0xFFFFFFFF.toInt())
                            else -> { rect(cv, -3.5f + vx0, -5.9f, 3.5f + vx0, -4.7f, glow); rect(cv, -3.5f + vx0, -5.9f, -2.5f + vx0, -5.5f, 0xFFFFFFFF.toInt()) }
                        }
                    } else {
                    // the face: a dark dashboard screen with two glowing LED eyes and a mouth
                    rect(cv, -4f, -7.2f, 4f, -3.2f, ink); rect(cv, -4.6f, -6.6f, 4.6f, -3.8f, ink)
                    rect(cv, -1.5f, -3f, 1.5f, -2.6f, 0xFFFF3B30.toInt())   // the red recording underscore of the app icon
                    val ex = floatArrayOf(-2.6f, 1.4f)
                    when {
                        sleeping -> for (e in ex) rect(cv, e + lk * 0.3f, -5.1f, e + 1.2f + lk * 0.3f, -4.8f, dim)
                        blink -> for (e in ex) rect(cv, e + lk * 0.5f, -5.4f, e + 1.2f + lk * 0.5f, -4.9f, glow)
                        suffer -> for (e in ex) { rect(cv, e + lk * 0.3f, -5.4f, e + 1.2f + lk * 0.3f, -4.6f, glow); rect(cv, e - 0.3f + (if (e < 0f) 0f else 0.6f), -6.2f, e + 0.9f + (if (e < 0f) 0f else 0.6f), -5.8f, dim) }
                        happy -> for (e in ex) { rect(cv, e, -5.2f, e + 1.2f, -4.7f, glow); rect(cv, e + 0.3f, -5.8f, e + 0.9f, -5.2f, glow) }
                        alarmed -> for (e in ex) { rect(cv, e - 0.3f + lk * 0.3f, -6.6f, e + 1.5f + lk * 0.3f, -4.4f, 0xFFFFFFFF.toInt()); rect(cv, e + 0.1f + lk * 0.6f, -5.8f, e + 1.1f + lk * 0.6f, -5f, ink) }
                        else -> for (e in ex) rect(cv, e + lk * 0.5f, -6.3f, e + 1.2f + lk * 0.5f, -4.5f, glow)
                    }
                    // mouth
                    val mouth = if (sleeping) 0 else if (suffer) 3 else if (st == St.ACT && act == 0) (if ((t * 7f).toInt() % 2 == 0) 2 else 1) else if (air || st == St.REACT && sub == 1) 2 else if (happy || st == St.BALL || st == St.BUG) 1 else 0
                    if (mouth == 3) { rect(cv, -1.2f, -4.1f, 1.2f, -3.7f, glow); rect(cv, -1.8f, -3.7f, -1.2f, -3.2f, glow); rect(cv, 1.2f, -3.7f, 1.8f, -3.2f, glow) }
                    else if (mouth == 2) rect(cv, -0.6f, -4.2f, 0.6f, -3.4f, glow)
                    else if (mouth == 1) { rect(cv, -1.2f, -4f, 1.2f, -3.6f, glow); rect(cv, -1.8f, -4.5f, -1.2f, -4f, glow); rect(cv, 1.2f, -4.5f, 1.8f, -4f, glow) }
                    else if (!sleeping) rect(cv, -0.8f, -3.9f, 0.8f, -3.6f, dim)
                    }
                }
                cv.restore(); paint.alpha = 255
            }
            if (ghost > 0f || st == St.GLITCH) {
                val o = (rnd.nextFloat() - 0.5f) * 3f * u
                pass(-o, 0xFF00E5FF.toInt(), 0.55f); pass(o, 0xFFFF2BD6.toInt(), 0.55f)
                if (rnd.nextFloat() < 0.4f) return
            }
            pass(0f, 0, 1f)
        }
    }
}
