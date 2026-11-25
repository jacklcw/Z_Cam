package com.zeekr.sentrycam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader

/**
 * The rev bar's look: ten styles and 26 colour sets, drawn into any rectangle, horizontal or vertical (vertical = the same bar turned,
 * filling from the bottom). Used by [RevView] for the Custom position and for any edge position with a style from the Smooth/Segments/Slanted
 * three onward. Ids 0..2 match the original shapes.
 */
object RevStyles {
    val STYLE_NAMES = listOf("Smooth", "Segments", "Slanted", "Centre-out (Ferrari wheel)", "Shift lights (F1 / Porsche)", "Wedge (Lamborghini)",
        "Laser line", "Stepped blocks (McLaren)", "Type R (white, red zone)", "Tacho blocks (Japanese cluster)")

    /** (name, low / middle / high colour). The first four are the original schemes, ids unchanged. */
    private val SCHEMES = listOf(
        "Traffic" to intArrayOf(0x00e65a, 0xffd600, 0xff2828),
        "Neon" to intArrayOf(0x00e5ff, 0x5a50ff, 0xff00c8),
        "Ice" to intArrayOf(0xaae6ff, 0x00beff, 0xffffff),
        "Sport" to intArrayOf(0xff9600, 0xff4600, 0xff003c),
        "F1 (green, red, violet)" to intArrayOf(0x00e65a, 0xff2828, 0x8a5cff),
        "Ferrari (yellow, red, white)" to intArrayOf(0xffd600, 0xff1a1a, 0xffffff),
        "Lamborghini (lime, yellow, orange)" to intArrayOf(0xa6ff00, 0xffd600, 0xff6a00),
        "McLaren (papaya, orange, white)" to intArrayOf(0xff8000, 0xffb347, 0xffffff),
        "Porsche (green, amber, red)" to intArrayOf(0x39ff7a, 0xffb000, 0xff2020),
        "Bugatti (blue, cyan, white)" to intArrayOf(0x1f4bff, 0x00c8ff, 0xffffff),
        "Aston (racing green, lime, white)" to intArrayOf(0x00a86b, 0xb6ff3b, 0xffffff),
        "Pagani (amber, gold, white)" to intArrayOf(0xff7a00, 0xffc400, 0xfff4cc),
        "Koenigsegg (ice, white, cyan)" to intArrayOf(0x8fdcff, 0xffffff, 0x00e5ff),
        "Audi R8 (white, orange, red)" to intArrayOf(0xffffff, 0xff7a00, 0xff0033),
        "Nissan GT-R (magenta, red, white)" to intArrayOf(0xff00aa, 0xff2d2d, 0xffffff),
        "Civic Type R (white, red, red)" to intArrayOf(0xffffff, 0xff2a2a, 0xff0000),
        "Honda NSX (blue, ice, white)" to intArrayOf(0x1e63ff, 0x7fc4ff, 0xffffff),
        "Toyota GR (red, orange, white)" to intArrayOf(0xff1f1f, 0xff7a00, 0xffffff),
        "Supra (amber, orange, red)" to intArrayOf(0xffb000, 0xff6a00, 0xff1a00),
        "Mazda RX-7 (amber, red, white)" to intArrayOf(0xffa500, 0xff2a00, 0xffffff),
        "Lexus LFA (white, orange, red)" to intArrayOf(0xffffff, 0xff8a00, 0xff0000),
        "Subaru WRX (blue, cyan, gold)" to intArrayOf(0x1a3cff, 0x00c8ff, 0xffc400),
        "Mitsubishi Evo (red, white, red)" to intArrayOf(0xff1a1a, 0xffffff, 0xff1a1a),
        "Mono white" to intArrayOf(0xffffff, 0xffffff, 0xffffff),
        "Mono red" to intArrayOf(0xff4040, 0xff2020, 0xff0000),
        "Mono cyan" to intArrayOf(0x00e5ff, 0x00e5ff, 0x00e5ff))
    val SCHEME_NAMES = SCHEMES.map { it.first }
    fun colors(scheme: Int) = cols(scheme)
    private fun cols(scheme: Int) = SCHEMES[scheme.coerceIn(0, SCHEMES.size - 1)].second.map { it or (0xFF shl 24) }.toIntArray()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val stops = floatArrayOf(0f, 0.6f, 1f)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = Math.round(((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun colorAt(c: IntArray, f: Float): Int { val x = f.coerceIn(0f, 1f); return if (x < 0.6f) mix(c[0], c[1], x / 0.6f) else mix(c[1], c[2], (x - 0.6f) / 0.4f) }
    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** 0 centre, 1 stick to the top / left edge, 2 bottom / right edge: top of a bar's cross axis for a piece [hh] tall inside [h]. */
    private fun ay(salign: Int, h: Float, hh: Float) = when (salign) { 1 -> 0f; 2 -> h - hh; else -> (h - hh) / 2f }

    /**
     * Draws the bar into (x, y, w, h). [f] fill 0..1, [pk] peak 0..1, [rg] regen 0..1. [vertical] turns it a quarter so it fills upward.
     * [flashOff] dims the whole bar (the blink at the shift point), [now] drives the redline blink of some styles.
     */
    fun draw(cv: Canvas, style: Int, scheme: Int, x: Float, y: Float, w: Float, h: Float, vertical: Boolean, salign: Int,
             f: Float, pk: Float, rg: Float, regenColor: Int, track: Boolean, flashOff: Boolean, now: Long) {
        if (w <= 1f || h <= 1f) return
        val L = if (vertical) h else w; val T = if (vertical) w else h
        val sc = cols(scheme)
        cv.save()
        if (vertical) { cv.translate(x + w / 2f, y + h / 2f); cv.rotate(-90f); cv.translate(-L / 2f, -T / 2f) } else cv.translate(x, y)
        if (flashOff) cv.saveLayerAlpha(-4f, -4f, L + 4f, T + 4f, 30)
        body(cv, style, sc, L, T, f.coerceIn(0f, 1f), pk.coerceIn(0f, 1f), salign, now)
        if (rg > 0.003f || track) {   // regen: a thin strip along the start edge that grows with the braking
            if (track) { fill.shader = null; fill.color = alpha(Color.WHITE, 30); cv.drawRect(0f, T * 0.84f, L, T, fill) }
            if (rg > 0.003f) { fill.shader = null; fill.color = regenColor; cv.drawRect(0f, T * 0.84f, L * rg.coerceIn(0f, 1f), T, fill) }
        }
        if (flashOff) cv.restore()
        cv.restore()
    }

    private fun body(cv: Canvas, style: Int, sc: IntArray, w: Float, h: Float, f: Float, pk: Float, sal: Int, now: Long) {
        fill.shader = null; fill.style = Paint.Style.FILL
        when (style) {
            0 -> {   // smooth
                fill.color = alpha(Color.WHITE, 36); cv.drawRect(0f, h * .15f, w, h * .85f, fill)
                fill.color = alpha(colorAt(sc, f), 46); cv.drawRect(0f, 0f, w * f, h, fill)
                fill.shader = LinearGradient(0f, 0f, w, 0f, sc, stops, Shader.TileMode.CLAMP)
                cv.drawRect(0f, h * .15f, w * f, h * .85f, fill); fill.shader = null
                fill.color = Color.WHITE; val px = w * pk; cv.drawRect(px - maxOf(3f, h * .2f), 0f, px, h, fill)
            }
            1, 2 -> {   // segments, slanted
                val n = if (style == 1) 40 else 32; val sw = w / n; val gap = sw * .22f; val sk = if (style == 2) h * .6f else 0f
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val x0 = i * sw
                    path.reset(); path.moveTo(x0 + sk, 0f); path.lineTo(x0 + sw - gap + sk, 0f); path.lineTo(x0 + sw - gap, h); path.lineTo(x0, h); path.close()
                    fill.color = alpha(colorAt(sc, ff), if (ff <= f) 255 else 46); cv.drawPath(path, fill)
                }
            }
            3 -> {   // centre-out
                val n = 24; val half = w / 2f; val sw = half / n; val gap = sw * .2f
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val lit = ff <= f
                    for (s in intArrayOf(-1, 1)) {
                        val x0 = half + s * (i * sw + (if (i == 0) gap * 2 else 0f))
                        val xa = if (s > 0) x0 else x0 - (sw - gap); val xb = xa + (sw - gap); val c = minOf(h * .35f, (sw - gap) * .5f)
                        path.reset(); path.moveTo(xa + (if (s > 0) 0f else c), 0f); path.lineTo(xb - (if (s > 0) c else 0f), 0f); path.lineTo(xb, h); path.lineTo(xa, h); path.close()
                        fill.color = alpha(colorAt(sc, ff), if (lit) 255 else 41); cv.drawPath(path, fill)
                    }
                }
            }
            4 -> {   // shift lights
                val n = 15; val d = minOf(h * .8f, w / n * .8f); val step = (w - d) / (n - 1); val blink = f > .97f && (now / 90) % 2 == 0L
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val lit = ff <= f; val col = if (blink) sc[2] else colorAt(sc, ff)
                    val cx = d / 2 + i * step; val cy = ay(sal, h, d) + d / 2
                    if (lit) { fill.color = alpha(col, 60); cv.drawCircle(cx, cy, d * .62f, fill) }
                    fill.color = alpha(col, if (lit) 255 else 36); cv.drawCircle(cx, cy, d / 2, fill)
                }
            }
            5 -> {   // wedge
                val n = 36; val sw = w / n; val gap = sw * .3f
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val hh = h * (.25f + .75f * ff); val y0 = if (sal == 0) h - hh else ay(sal, h, hh)
                    path.reset(); path.moveTo(i * sw + h * .25f, y0); path.lineTo(i * sw + sw - gap + h * .25f, y0); path.lineTo(i * sw + sw - gap, y0 + hh); path.lineTo(i * sw, y0 + hh); path.close()
                    fill.color = alpha(colorAt(sc, ff), if (ff <= f) 255 else 41); cv.drawPath(path, fill)
                }
            }
            6 -> {   // laser line
                val lt = maxOf(3f, h * .22f); val yy = ay(sal, h, lt * 2.2f) + lt * 1.1f; val tip = maxOf(h * .1f, 1f)
                line.shader = null; line.color = alpha(Color.WHITE, 36); line.strokeWidth = maxOf(2f, h * .12f); cv.drawLine(tip, yy, w - tip, yy, line)
                line.shader = LinearGradient(0f, 0f, w, 0f, sc, stops, Shader.TileMode.CLAMP)
                line.strokeWidth = lt * 2.6f; line.alpha = 70; cv.drawLine(tip, yy, maxOf(tip, w * f), yy, line)   // soft glow under the line
                line.strokeWidth = lt; line.alpha = 255; cv.drawLine(tip, yy, maxOf(tip, w * f), yy, line)
                line.shader = null
                fill.color = Color.WHITE; cv.drawCircle(maxOf(tip, w * f), yy, h * .22f, fill)
            }
            7 -> {   // stepped blocks
                val n = 18; val sw = w / n; val gap = sw * .18f
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val hh = h * (.4f + .6f * ff); val y0 = ay(sal, h, hh); val lit = ff <= f
                    val col = colorAt(sc, ff)
                    if (lit && ff > .8f) { fill.color = alpha(col, 70); cv.drawRect(i * sw - gap, y0 - h * .1f, i * sw + sw, y0 + hh + h * .1f, fill) }
                    fill.color = alpha(col, if (lit) 255 else 41); cv.drawRect(i * sw, y0, i * sw + sw - gap, y0 + hh, fill)
                }
            }
            8 -> {   // Type R: first colour body, last colour redline zone and marker
                val n = 30; val sw = w / n; val gap = sw * .2f; val red = .8f; val flash = f > .97f && (now / 80) % 2 == 0L
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val lit = ff <= f; val zr = ff > red
                    fill.color = if (lit && zr && flash) Color.WHITE else alpha(if (zr) sc[2] else sc[0], if (lit) 255 else if (zr) 56 else 36)
                    cv.drawRect(i * sw, 0f, i * sw + sw - gap, h, fill)
                }
                fill.color = sc[2]; cv.drawRect(w * red - 1f, -h * .12f, w * red + 1f, h * 1.12f, fill)
            }
            else -> {   // 9: tacho blocks, thin in the lower part, full height from 82%
                val n = 28; val sw = w / n; val gap = sw * .25f
                for (i in 0 until n) {
                    val ff = (i + .5f) / n; val lit = ff <= f; val hh = if (ff > .82f) h else h * .6f; val y0 = ay(sal, h, hh)
                    fill.color = alpha(colorAt(sc, ff), if (lit) 255 else 38); cv.drawRect(i * sw, y0, i * sw + sw - gap, y0 + hh, fill)
                }
            }
        }
    }
}
