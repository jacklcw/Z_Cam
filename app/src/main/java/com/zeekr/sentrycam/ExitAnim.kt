package com.zeekr.sentrycam

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.LinearInterpolator

/** Exit animations for the HUD camera picture (and the in-app preview of them), done with plain view properties. Main thread only. */
object ExitAnim {
    val NAMES = listOf("Cut", "Fade", "Dissolve", "Wipe left", "Wipe right", "Wipe up", "TV off", "Shrink")
    const val MS_DEF = 500

    fun kind(p: android.content.SharedPreferences) = p.getInt("bsExitAnim", 0).coerceIn(0, NAMES.size - 1)
    fun ms(p: android.content.SharedPreferences) = p.getInt("bsExitMs", MS_DEF).coerceIn(200, 1500)

    fun reset(v: View) { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.clipBounds = null }

    /** Puts [v] at point [t] (0 = fully shown, 1 = gone) of animation [kind]. */
    fun at(v: View, kind: Int, t: Float) {
        val w = v.width; val h = v.height
        reset(v)
        when (kind) {
            1 -> v.alpha = 1f - t
            2 -> { v.alpha = 1f - t; v.scaleX = 1f + 0.12f * t; v.scaleY = 1f + 0.12f * t }
            3 -> v.clipBounds = Rect(0, 0, (w * (1f - t)).toInt(), h)
            4 -> v.clipBounds = Rect((w * t).toInt(), 0, w, h)
            5 -> v.clipBounds = Rect(0, 0, w, (h * (1f - t)).toInt())
            6 -> if (t < 0.6f) v.scaleY = 1f - 0.98f * (t / 0.6f)
                 else { v.scaleY = 0.02f; v.scaleX = 1f - (t - 0.6f) / 0.4f }
            7 -> { val s = 1f - t; v.scaleX = s; v.scaleY = s; v.alpha = 1f - 0.5f * t }
        }
    }

    /** Plays [kind] on [v] over [ms]; [onEnd] runs when it finishes (not when cancelled). Returns the animator so the caller can cancel it. */
    fun run(v: View, kind: Int, ms: Int, onEnd: () -> Unit): ValueAnimator {
        val a = ValueAnimator.ofFloat(0f, 1f).apply { duration = ms.toLong(); interpolator = LinearInterpolator() }
        var cancelled = false
        a.addUpdateListener { at(v, kind, it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) { cancelled = true }
            override fun onAnimationEnd(an: Animator) { if (!cancelled) onEnd() }
        })
        a.start()
        return a
    }
}
