package com.zeekr.sentrycam

import android.animation.Animator
import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

/** Fades a floating window out when it is hidden by the floating button, and back in when shown. Main thread only. */
object FloatAnim {
    private const val MS = 320L

    /** True shortly after the floating button brought the windows back, so new windows should fade in. */
    fun revealing() = android.os.SystemClock.uptimeMillis() - FloatMaster.shownAt < 1500L

    /** Initial window alpha: 0 when the window is about to fade in, so its first frame is already transparent. */
    fun startAlpha() = if (revealing()) 0f else 1f

    private fun fade(wm: WindowManager, v: View, lp: WindowManager.LayoutParams, to: Float, onEnd: () -> Unit) {
        val from = lp.alpha
        val a = ValueAnimator.ofFloat(from, to).apply { duration = MS; interpolator = AccelerateDecelerateInterpolator() }
        a.addUpdateListener {
            lp.alpha = it.animatedValue as Float
            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) { a.cancel() }
        }
        a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { onEnd() } })
        a.start()
    }

    /** Configurable in/out for blinker windows: style "none" | "fade" | "slide" | "zoom" over [ms]; slide moves in from / out toward the screen edge on [dir] (-1 left, +1 right). */
    fun run(wm: WindowManager, v: View, lp: WindowManager.LayoutParams, style: String, ms: Long, inward: Boolean, dir: Int, onEnd: () -> Unit) {
        if (style == "none" || ms <= 0L) { lp.alpha = if (inward) 1f else 0f; try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}; onEnd(); return }
        val baseX = lp.x; val shift = (v.width.coerceAtLeast(200) * 0.6f * dir).toInt()
        val a = ValueAnimator.ofFloat(0f, 1f).apply { duration = ms; interpolator = AccelerateDecelerateInterpolator() }
        a.addUpdateListener {
            val t = it.animatedValue as Float; val k = if (inward) t else 1f - t   // k: 0 = gone, 1 = shown
            lp.alpha = k
            if (style == "slide") lp.x = baseX + ((1f - k) * shift).toInt()
            if (style == "zoom") { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f; v.scaleX = 0.6f + 0.4f * k; v.scaleY = 0.6f + 0.4f * k }
            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) { a.cancel() }
        }
        a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) {
            lp.x = baseX; v.scaleX = 1f; v.scaleY = 1f; if (inward) lp.alpha = 1f
            try { if (inward) wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
            onEnd() } })
        a.start()
    }

    /** Fades the whole window surface (frame and content together) to nothing, then calls [onEnd] (which removes it). */
    fun out(wm: WindowManager, v: View, lp: WindowManager.LayoutParams, onEnd: () -> Unit) = fade(wm, v, lp, 0f, onEnd)

    /** Fades the whole window in; it was added with [startAlpha]. */
    fun into(wm: WindowManager, v: View, lp: WindowManager.LayoutParams) = fade(wm, v, lp, 1f) {}

    /** Minimize / restore of a floating window with the animation chosen in settings (same set as the HUD exit animation). */
    fun genie(wm: WindowManager, v: View, lp: WindowManager.LayoutParams, inward: Boolean, onEnd: () -> Unit) {
        val sp = v.context.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
        val kind = sp.getInt("flMinAnim", 1).coerceIn(0, ExitAnim.NAMES.size - 1)
        val ms = sp.getInt("flMinMs", 200).coerceIn(100, 1000).toLong()
        fun finish() { ExitAnim.reset(v); if (inward) { lp.alpha = 1f; try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {} }; onEnd() }
        if (kind == 0) { finish(); return }
        fun go() {
            if (inward) { ExitAnim.at(v, kind, 1f); lp.alpha = 1f; try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {} }
            val a = ValueAnimator.ofFloat(0f, 1f).apply { duration = ms; interpolator = android.view.animation.LinearInterpolator() }
            a.addUpdateListener { ExitAnim.at(v, kind, if (inward) 1f - (it.animatedValue as Float) else it.animatedValue as Float) }
            a.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { if (inward) finish() else onEnd() } })
            a.start()
        }
        if (v.width > 0) go() else v.post { go() }
    }
}
