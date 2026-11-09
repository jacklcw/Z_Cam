package com.zeekr.sentrycam

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Colour wheel like the ZDash accent picker: hue clockwise from the top, saturation from the centre (white) to the rim. */
class ColorWheelView(c: Context, h: Float, s: Float, private val onChange: (Float, Float, Boolean) -> Unit) : View(c) {
    private val d = ZScale.density(c)
    var hue = h; var sat = s; var value = 1f
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { val s = (230 * d).toInt(); setMeasuredDimension(s, s) }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 2 * d
        val hues = intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
        cv.save(); cv.rotate(-90f, cx, cy)   // sweep gradients start at 3 o'clock; ours starts at the top
        paint.shader = SweepGradient(cx, cy, hues, null)
        cv.drawCircle(cx, cy, r, paint)
        cv.restore()
        paint.shader = RadialGradient(cx, cy, r, intArrayOf(Color.WHITE, Color.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.04f, 0.72f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * d; paint.color = 0x33888888
        cv.drawCircle(cx, cy, r, paint)
        // marker
        val a = Math.toRadians(hue.toDouble()); val dist = sat * r
        val mx = (cx + dist * Math.sin(a)).toFloat(); val my = (cy - dist * Math.cos(a)).toFloat()
        paint.style = Paint.Style.FILL; paint.color = Color.HSVToColor(floatArrayOf(hue, sat, value)); cv.drawCircle(mx, my, 11 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Color.WHITE; cv.drawCircle(mx, my, 11 * d, paint)
        paint.strokeWidth = 1 * d; paint.color = 0x55000000; cv.drawCircle(mx, my, 13 * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f - 2 * d
        val dx = e.x - cx; val dy = e.y - cy
        var deg = Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())); if (deg < 0) deg += 360.0
        hue = deg.toFloat(); sat = (Math.hypot(dx.toDouble(), dy.toDouble()) / r).coerceIn(0.0, 1.0).toFloat()
        invalidate()
        onChange(hue, sat, e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
        return true
    }
}

/** Brightness slider (20-100 %), a bar from dark to the chosen colour. */
class BrightnessBar(c: Context, private val onChange: (Float, Boolean) -> Unit) : View(c) {
    private val d = ZScale.density(c)
    var hue = 0f; var sat = 0f; var value = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), (40 * d).toInt()) }

    override fun onDraw(cv: Canvas) {
        val pad = 14 * d; val y = height / 2f; val th = 14 * d
        val lo = Color.HSVToColor(floatArrayOf(hue, sat, MIN)); val hi = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
        paint.shader = LinearGradient(pad, 0f, width - pad, 0f, lo, hi, Shader.TileMode.CLAMP)
        cv.drawRoundRect(pad, y - th / 2, width - pad, y + th / 2, th / 2, th / 2, paint)
        paint.shader = null
        val x = pad + (value - MIN) / (1f - MIN) * (width - 2 * pad)
        paint.color = Color.HSVToColor(floatArrayOf(hue, sat, value)); cv.drawCircle(x, y, 13 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Color.WHITE; cv.drawCircle(x, y, 13 * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val pad = 14 * d
        value = (MIN + ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * (1f - MIN))
        invalidate()
        onChange(value, e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)
        return true
    }

    companion object { const val MIN = 0.2f }   // below about 20 % an ambient colour just reads as off
}

object ColorPicker {
    /**
     * Wheel dialog in the app's theme. [onLive] fires when the finger lifts (so the car's light can follow without being flooded);
     * [onDone] gets the chosen ARGB, or 0 for "keep as is".
     */
    fun show(ctx: Context, pal: ZTheme.Palette, title: String, initial: Int, onLive: (Int) -> Unit, onDone: (Int) -> Unit, swatches: List<Pair<String, Int>> = emptyList()) {
        val d = ZScale.density(ctx)
        fun dp(x: Int) = (x * d).toInt()
        val hsv = FloatArray(3).also { Color.colorToHSV(if (initial != 0) initial else 0xFF2040FF.toInt(), it) }
        hsv[2] = hsv[2].coerceIn(BrightnessBar.MIN, 1f)
        val dlg = Dialog(ctx)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable().apply { setColor(pal.card); cornerRadius = 20 * d; setStroke(dp(1), pal.line) }
        }
        box.addView(TextView(ctx).apply { tag = "t"; text = title }, LinearLayout.LayoutParams(-2, -2))
        box.addView(TextView(ctx).apply { tag = "m"; text = "Drag anywhere on the wheel, then set the brightness." }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        val chip = View(ctx); val hex = TextView(ctx).apply { tag = "m"; gravity = Gravity.CENTER_VERTICAL }
        lateinit var wheel: ColorWheelView; lateinit var bar: BrightnessBar
        fun cur() = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], hsv[2]))
        fun refresh() {
            bar.hue = hsv[0]; bar.sat = hsv[1]; bar.value = hsv[2]; bar.invalidate(); wheel.value = hsv[2]
            chip.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cur()); setStroke(dp(1), pal.line) }
            hex.text = String.format("#%06X", cur() and 0xFFFFFF)
        }
        wheel = ColorWheelView(ctx, hsv[0], hsv[1]) { h, s, up -> hsv[0] = h; hsv[1] = s; refresh(); if (up) onLive(cur()) }
        bar = BrightnessBar(ctx) { v, up -> hsv[2] = v; refresh(); if (up) onLive(cur()) }
        box.addView(wheel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        box.addView(TextView(ctx).apply { tag = "m"; text = "Brightness" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); gravity = Gravity.START })
        box.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(chip, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }); row.addView(hex)
        box.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        if (swatches.isNotEmpty()) {
            // copy a colour that is already used somewhere, instead of dragging the wheel to it
            box.addView(TextView(ctx).apply { tag = "m"; text = "Copy a colour you already use" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); gravity = Gravity.START })
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((name, c) in swatches) {
                strip.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply { setColor(pal.chip); cornerRadius = 14 * d; setStroke(dp(1), pal.line) }
                    addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(1), pal.line) } }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
                    addView(TextView(ctx).apply { tag = "m"; text = name; maxLines = 1 })
                    setOnClickListener {
                        Color.colorToHSV(c, hsv); hsv[2] = hsv[2].coerceIn(BrightnessBar.MIN, 1f)
                        wheel.hue = hsv[0]; wheel.sat = hsv[1]; refresh(); onLive(cur())
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
            box.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(ctx).apply { tag = "btn2"; text = "Keep as is"; setOnClickListener { onDone(0); dlg.dismiss() } }, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(Button(ctx).apply { tag = "btn"; text = "Done"; setOnClickListener { onDone(cur()); dlg.dismiss() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        refresh()
        ZTheme.style(box, pal)
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((dp(460)).coerceAtMost(ctx.resources.displayMetrics.widthPixels - dp(24)), WindowManagerLayoutWrap)
        dlg.show()
    }
    private const val WindowManagerLayoutWrap = android.view.WindowManager.LayoutParams.WRAP_CONTENT
}
