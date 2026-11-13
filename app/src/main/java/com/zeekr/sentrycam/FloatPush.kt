package com.zeekr.sentrycam

import android.graphics.Rect
import android.view.View
import android.view.WindowManager

/**
 * Floating windows shove each other aside: while one is dragged or resized, any window it runs into is pushed away
 * (and pushes the next one in turn), staying on screen. Main thread only. Shared by the camera windows and the web window.
 */
object FloatPush {
    class Item(val lp: WindowManager.LayoutParams, val view: () -> View?, val wm: WindowManager, val moved: (Int, Int) -> Unit)

    private val items = HashMap<String, Item>()

    fun register(key: String, item: Item) { items[key] = item }
    fun unregister(key: String) { items.remove(key) }

    private fun rect(i: Item): Rect {
        val v = i.view()
        val w = if (i.lp.width > 0) i.lp.width else v?.width ?: 0
        val h = if (i.lp.height > 0) i.lp.height else v?.height ?: 0
        return Rect(i.lp.x, i.lp.y, i.lp.x + w, i.lp.y + h)
    }

    /** Every visible floating window in screen pixels (the mascot walks on their tops). */
    fun screenRects(): List<Rect> {
        val out = ArrayList<Rect>(); val loc = IntArray(2)
        for (i in items.values) {
            val v = i.view() ?: continue
            if (!v.isAttachedToWindow || v.visibility != View.VISIBLE || i.lp.alpha < 0.5f || v.width <= 0 || v.height <= 0) continue
            v.getLocationOnScreen(loc)
            out.add(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
        }
        return out
    }

    fun overlapsOther(key: String): Boolean {
        val m = items[key] ?: return false
        val mr = rect(m)
        return items.any { (k, o) -> k != key && Rect.intersects(mr, rect(o)) }
    }

    /** True if a window of [w] x [h] at ([x], [y]) would touch any window other than [key]: resizing stops there instead of shoving. */
    fun hits(key: String, x: Int, y: Int, w: Int, h: Int): Boolean {
        val r = Rect(x, y, x + w, y + h)
        return items.any { (k, o) -> k != key && Rect.intersects(r, rect(o)) }
    }

    /** [key]'s window just moved/resized: push whatever it now overlaps. */
    fun moved(key: String, screenW: Int, screenH: Int, depth: Int = 0) {
        return   // pushing other windows aside is switched off: windows just overlap
        @Suppress("UNREACHABLE_CODE")
        val m = items[key] ?: return
        if (depth > 4) return
        val mr = rect(m)
        for ((k, o) in items.toMap()) {
            if (k == key) continue
            val or = rect(o)
            if (!Rect.intersects(mr, or)) continue
            val ox = minOf(mr.right, or.right) - maxOf(mr.left, or.left)
            val oy = minOf(mr.bottom, or.bottom) - maxOf(mr.top, or.top)
            val ow = or.width(); val oh = or.height()
            val gap = 6
            var nx = or.left; var ny = or.top
            if (ox <= oy) nx += (if (or.centerX() >= mr.centerX()) ox + gap else -(ox + gap))
            else ny += (if (or.centerY() >= mr.centerY()) oy + gap else -(oy + gap))
            nx = nx.coerceIn(0, maxOf(0, screenW - ow)); ny = ny.coerceIn(0, maxOf(0, screenH - oh))
            // pinned against the screen edge and still overlapping: slide along the other axis instead
            if (Rect.intersects(mr, Rect(nx, ny, nx + ow, ny + oh))) {
                if (ox <= oy) ny = (or.top + (if (or.centerY() >= mr.centerY()) oy + gap else -(oy + gap))).coerceIn(0, maxOf(0, screenH - oh))
                else nx = (or.left + (if (or.centerX() >= mr.centerX()) ox + gap else -(ox + gap))).coerceIn(0, maxOf(0, screenW - ow))
            }
            if (nx == o.lp.x && ny == o.lp.y) continue
            o.lp.x = nx; o.lp.y = ny
            val v = o.view() ?: continue
            try { o.wm.updateViewLayout(v, o.lp) } catch (_: Throwable) {}
            o.moved(nx, ny)
            moved(k, screenW, screenH, depth + 1)
        }
    }
}
