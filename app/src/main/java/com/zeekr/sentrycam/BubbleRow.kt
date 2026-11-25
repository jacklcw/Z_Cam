package com.zeekr.sentrycam

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The collapsed form of every floating window (cameras and web): one slim dock at the top centre of the screen, a
 * dark rounded bar with one chip per hidden window (accent dot + name). Tap a chip to bring its window back.
 * The dock exists only while something is collapsed. Main thread only.
 */
object BubbleRow {
    private val order = listOf("fsBack", "fsReload", "fsHome", "fsUrl", "fsList", "fsTabs", "fsMark", "fsExit", "web", "flLeft", "flRight", "flRear", "flFront", "tw", "fsHide")
    private class Entry(val label: String, val icon: IconButton.Kind?, val on: Boolean, val badge: String, val onTap: () -> Unit)
    private val items = HashMap<String, Entry>()
    private var dock: LinearLayout? = null

    // dock placement: default top centre; drag anywhere; released near the left/right edge it snaps there and stacks vertically
    private var grav = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    private var posX = 0
    private var posY = -1
    private var vertical = false
    private var inset = -1   // window y origin below the system bar (screen y minus lp.y), measured on the first drag

    /** Also the media dock's bar ([MediaDock]): a drag past 24 dp moves it, a tap still reaches the buttons. */
    internal class DragBar(ctx: Context, val onDrag: (Float, Float) -> Unit, val onEnd: (Float) -> Unit) : LinearLayout(ctx) {
        private var dx = 0f; private var dy = 0f; private var drag = false
        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; drag = false }
                android.view.MotionEvent.ACTION_MOVE -> if (!drag && Math.hypot((e.rawX - dx).toDouble(), (e.rawY - dy).toDouble()) > 24 * resources.displayMetrics.density) { drag = true; dx = e.rawX; dy = e.rawY; return true }
            }
            return false
        }
        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> if (drag) { onDrag(e.rawX - dx, e.rawY - dy); dx = e.rawX; dy = e.rawY }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (drag) { drag = false; onEnd(e.rawX) }
            }
            return true
        }
    }

    private fun applyPlacement(ctx: Context) {
        val bar = dock ?: return
        val lp = bar.layoutParams as? WindowManager.LayoutParams ?: return
        lp.gravity = grav; lp.x = posX; lp.y = posY
        try { ctx.getSystemService(WindowManager::class.java).updateViewLayout(bar, lp) } catch (_: Throwable) {}
    }

    private fun onDrag(ctx: Context, ddx: Float, ddy: Float) {
        val bar = dock ?: return
        if (inset < 0) { val l = IntArray(2); bar.getLocationOnScreen(l); inset = (l[1] - (bar.layoutParams as WindowManager.LayoutParams).y).coerceAtLeast(0) }
        if (grav != (Gravity.TOP or Gravity.LEFT)) {   // convert to absolute left/top first
            val loc = IntArray(2); bar.getLocationOnScreen(loc)
            grav = Gravity.TOP or Gravity.LEFT; posX = loc[0]; posY = loc[1] - inset
        }
        posX += ddx.toInt(); posY += ddy.toInt()
        applyPlacement(ctx)
    }

    private fun onDragEnd(ctx: Context, rawX: Float) {
        val bar = dock ?: return
        val dm = ctx.resources.displayMetrics
        val m = (10 * dm.density).toInt()
        val loc = IntArray(2); bar.getLocationOnScreen(loc)
        val y = (loc[1] - inset).coerceIn(0, (dm.heightPixels - inset - bar.height).coerceAtLeast(0))
        val wasVertical = vertical
        when {
            rawX < dm.widthPixels * 0.2f || loc[0] < 3 * m -> { grav = Gravity.TOP or Gravity.LEFT; posX = m; vertical = true }
            rawX > dm.widthPixels * 0.8f || loc[0] + bar.width > dm.widthPixels - 3 * m -> { grav = Gravity.TOP or Gravity.RIGHT; posX = m; vertical = true }
            y < 60 * dm.density -> { grav = Gravity.TOP or Gravity.LEFT; posX = loc[0].coerceIn(0, (dm.widthPixels - bar.width).coerceAtLeast(0)); vertical = false }   // near the top: sticks to the top edge, keeps its sideways place
            else -> { grav = Gravity.TOP or Gravity.LEFT; posX = loc[0].coerceIn(0, (dm.widthPixels - bar.width).coerceAtLeast(0)); vertical = false }
        }
        posY = if (y < 60 * dm.density && !vertical) m else y
        if (wasVertical != vertical) rebuild(ctx, reuse = true)
        applyPlacement(ctx)
    }

    private var compact = false
    /** Hide bar: only the "fsHide" chip stays, so the full-screen page is not covered; false brings everything back. */
    fun setCompact(ctx: Context, on: Boolean) { if (compact != on) { compact = on; rebuild(ctx) } }
    private var dim = false
    /** Fades the dock while a web video plays full screen, so it doesn't spoil the picture. */
    fun setDim(on: Boolean) { dim = on; dock?.alpha = if (on) 0.3f else 1f }

    fun has(key: String) = items.containsKey(key)

    fun add(ctx: Context, key: String, label: String, icon: IconButton.Kind? = null, on: Boolean = false, badge: String = "", onTap: () -> Unit) {
        if (items.containsKey(key)) return
        items[key] = Entry(label, icon, on, badge, onTap)
        rebuild(ctx, reuse = true, pop = key)
    }

    fun remove(ctx: Context, key: String) {
        if (items.remove(key) == null) return
        rebuild(ctx, reuse = true)
    }

    /** Re-adds the dock above every other overlay window. */
    fun raise(ctx: Context) { if (dock != null) rebuild(ctx) }

    /** [reuse]: refill the dock window in place (no remove/add, so it does not flicker); [pop]: the chip that pops in. */
    private fun rebuild(ctx: Context, reuse: Boolean = false, pop: String? = null) {
        val d = ctx.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val wm = ctx.getSystemService(WindowManager::class.java)
        val keys = order.filter { (!compact || it == "fsHide") && items.containsKey(it) }
        val entries = keys.map { items[it]!! }
        val old = dock
        val inPlace = reuse && old != null && old.isAttachedToWindow && entries.isNotEmpty()
        if (entries.isEmpty()) {
            if (old != null) { dock = null; try { wm.removeView(old) } catch (_: Throwable) {} }
            return
        }
        val bar = if (inPlace) old!!.also { it.removeAllViews(); it.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL } else DragBar(ctx, { dx, dy -> onDrag(ctx, dx, dy) }, { rx -> onDragEnd(ctx, rx) }).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10)); alpha = if (dim) 0.3f else 1f
            background = GradientDrawable().apply { setColor(0xE61C1C1E.toInt()); cornerRadius = dp(44).toFloat(); setStroke(dp(1), 0xFF3A3A3C.toInt()) }
        }
        for ((i, e) in entries.withIndex()) {
            if (e.icon != null) {
                // icon chip: easy to read at a glance, no text
                val ib = IconButton(ctx, e.icon).apply { this.on = e.on; badge = e.badge; background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(34).toFloat() }; setOnClickListener { e.onTap() } }
                bar.addView(ib, LinearLayout.LayoutParams(dp(96), dp(72)).apply { gap(i, dp(10)) })
                if (inPlace && keys[i] == pop) popIn(ib)
                continue
            }
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(28), 0, dp(30), 0)
                background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(34).toFloat() }
                setOnClickListener { e.onTap() }
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFA3E635.toInt()) }
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) }
                })
                addView(TextView(ctx).apply {
                    text = e.label; setTextColor(0xFFF5F5F7.toInt()); textSize = 24f; typeface = ZFont.reg; maxLines = 1; isSingleLine = true
                })
            }
            bar.addView(chip, LinearLayout.LayoutParams(-2, dp(72)).apply { gap(i, dp(10)) })
            if (inPlace && keys[i] == pop) popIn(chip)
        }
        if (inPlace) { try { wm.updateViewLayout(bar, bar.layoutParams) } catch (_: Throwable) {}; return }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply { gravity = grav; x = posX; y = if (posY < 0) dp(10) else posY }
        try {
            if (old != null) try { wm.removeView(old) } catch (_: Throwable) {}
            wm.addView(bar, lp); dock = bar
            if (reuse) { bar.alpha = 0f; bar.scaleX = 0.7f; bar.scaleY = 0.7f; bar.animate().alpha(if (dim) 0.3f else 1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start() }
            FloatMaster.raise()   // the floating button stays above the dock
        } catch (e: Throwable) { dock = null; Logger.log(ctx, "bubble dock failed ${e.message ?: ""}") }
    }

    private fun LinearLayout.LayoutParams.gap(i: Int, g: Int) { if (i > 0) { if (vertical) topMargin = g else marginStart = g } }

    /** A chip popping into the row. */
    private fun popIn(v: View) {
        v.scaleX = 0f; v.scaleY = 0f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
    }
}
