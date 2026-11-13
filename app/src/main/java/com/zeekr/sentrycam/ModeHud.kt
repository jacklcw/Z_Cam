package com.zeekr.sentrycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Short futuristic click for a drive mode switch (a rising chirp, then a ring-modulated blip), through the cabin speakers. */
object ModeClick {
    @Volatile var lastAt = 0L
    private var track: AudioTrack? = null
    @Synchronized fun play() {
        lastAt = System.currentTimeMillis()
        try {
            val t = track ?: run {
                val rate = 44_100
                val a = rate * 38 / 1000; val gap = rate * 12 / 1000; val b = rate * 45 / 1000
                val pcm = ShortArray(a + gap + b)
                var ph = 0.0
                for (i in 0 until a) {
                    val u = i / a.toDouble(); ph += 2 * Math.PI * (1200 + 3000 * u * u) / rate
                    pcm[i] = (Math.sin(ph) * Math.exp(-u * 3.0) * 11000).toInt().toShort()
                }
                for (i in 0 until b) {
                    val u = i / b.toDouble(); val tt = i / rate.toDouble()
                    val v = Math.sin(2 * Math.PI * 5600 * tt) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 140 * tt)) * Math.exp(-u * 5.0)
                    pcm[a + gap + i] = (v * 9000).toInt().toShort()
                }
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build().also { it.write(pcm, 0, pcm.size); track = it }
            }
            t.stop(); t.reloadStaticData(); t.play()
        } catch (_: Throwable) {}
    }
}

/**
 * Drive mode name on the HUD: fades in, holds, fades out whenever the car's drive mode changes (switch "mmOn").
 * Prefs: mmX/mmY (centre, % of the HUD), mmSize (% of the HUD height). Same HUD display pick as [SpeedLimitHud].
 */
object ModeHud {
    private const val HUD_W = 1440; private const val HUD_H = 480
    private const val HUD_FALLBACK_ID = 3
    private const val CLUSTER_W = 2560; private const val CLUSTER_H = 536
    private const val FADE_IN = 300L; private const val HOLD = 1800L; private const val FADE_OUT = 700L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val kill = Runnable { removeView() }

    fun show(ctx: Context, name: String, color: Int) {
        val app = ctx.applicationContext
        main.post {
            try { showNow(app, name, color) } catch (e: Throwable) { Logger.log(app, "modehud: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        }
    }

    private fun showNow(ctx: Context, name: String, color: Int) {
        if (Build.VERSION.SDK_INT < 30 || !Settings.canDrawOverlays(ctx)) return
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val on = dm.displays.filter { it.state == Display.STATE_ON && it.displayId != Display.DEFAULT_DISPLAY }
        fun size(d: Display) = Point().also { d.getRealSize(it) }
        val hud = on.firstOrNull { size(it).let { s -> s.x == HUD_W && s.y == HUD_H } }
            ?: on.firstOrNull { it.displayId == HUD_FALLBACK_ID && size(it).let { s -> !(s.x == CLUSTER_W && s.y == CLUSTER_H) } } ?: return
        removeView()
        val sz = size(hud)
        val h = (sz.y * p.getInt("mmSize", 22).coerceIn(8, 60) / 100).coerceAtLeast(16)
        val dctx = ctx.createDisplayContext(hud)
        val ovl = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        fun lp(type: Int, w: Int) = WindowManager.LayoutParams(w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (sz.x * p.getInt("mmX", 50) / 100f - w / 2f).toInt(); y = (sz.y * p.getInt("mmY", 35) / 100f - h / 2f).toInt()
        }
        val w = (h * 6.5f).toInt()
        val type = BlindSpotHud.WIN_TYPES[BlindSpotHud.winTypeIndex(p)]
        var wctx = try { dctx.createWindowContext(type, null) } catch (_: Throwable) { try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx } }
        var v = ModeView(wctx, name, color)
        try { wctx.getSystemService(WindowManager::class.java).addView(v, lp(type, w)) }
        catch (_: Throwable) {
            wctx = try { dctx.createWindowContext(ovl, null) } catch (_: Throwable) { dctx }
            v = ModeView(wctx, name, color)
            wctx.getSystemService(WindowManager::class.java).addView(v, lp(ovl, w))
        }
        view = v
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(FADE_IN).withEndAction {
            v.animate().alpha(0f).setStartDelay(HOLD).setDuration(FADE_OUT).start()
        }.start()
        main.removeCallbacks(kill); main.postDelayed(kill, FADE_IN + HOLD + FADE_OUT + 200)
    }

    private fun removeView() {
        val v = view ?: return
        view = null
        try { v.animate().cancel() } catch (_: Throwable) {}
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }
}

/** Mode name in bold capitals with a soft glow in the mode's colour. */
private class ModeView(c: Context, private val text: String, private val col: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val t = text.uppercase()
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = height * 0.55f
        paint.letterSpacing = 0.12f
        val y = height / 2f - (paint.ascent() + paint.descent()) / 2f
        paint.color = col; paint.setShadowLayer(height * 0.18f, 0f, 0f, col)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        cv.drawText(t, width / 2f, y, paint)
    }
}
