package com.zeekr.sentrycam

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * The web view's bookmarks and browsing history, kept in the "cfg" prefs as JSON ("webBookmarks" / "webHistory") so they
 * ride along with the relay settings backup. Main thread only.
 */
object WebStore {
    class Entry(val url: String, var title: String, val t: Long)

    private const val BM = "webBookmarks"
    private const val HIST = "webHistory"
    private const val MAX_BM = 100
    private const val MAX_HIST = 200

    private fun load(p: SharedPreferences, key: String): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(p.getString(key, "[]"))
            for (i in 0 until a.length()) { val r = a.getJSONArray(i); out += Entry(r.getString(0), r.optString(1, ""), r.optLong(2, 0)) }
        } catch (_: Throwable) {}
        return out
    }

    private fun save(p: SharedPreferences, key: String, l: List<Entry>) {
        val a = JSONArray(); for (e in l) a.put(JSONArray().put(e.url).put(e.title).put(e.t))
        p.edit().putString(key, a.toString()).apply()
    }

    fun bookmarks(p: SharedPreferences) = load(p, BM)
    fun history(p: SharedPreferences) = load(p, HIST)
    fun isBookmarked(p: SharedPreferences, url: String) = bookmarks(p).any { it.url == url }

    /** Adds or removes a bookmark; true if it is bookmarked now. */
    fun toggleBookmark(p: SharedPreferences, url: String, title: String): Boolean {
        val l = bookmarks(p)
        if (l.removeAll { it.url == url }) { save(p, BM, l); return false }
        if (url.isEmpty() || url == "about:blank") return false
        l.add(0, Entry(url.take(300), title.take(60), System.currentTimeMillis()))
        save(p, BM, l.take(MAX_BM)); return true
    }

    fun addVisit(p: SharedPreferences, url: String, title: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val l = history(p)
        val old = l.firstOrNull { it.url == url }
        l.removeAll { it.url == url }
        l.add(0, Entry(url.take(300), (title.ifEmpty { old?.title ?: "" }).take(60), System.currentTimeMillis()))
        save(p, HIST, l.take(MAX_HIST))
    }

    fun setTitle(p: SharedPreferences, url: String, title: String) {
        if (title.isEmpty()) return
        val l = history(p); val e = l.firstOrNull { it.url == url } ?: return
        if (e.title == title.take(60)) return
        e.title = title.take(60); save(p, HIST, l)
        val b = bookmarks(p); b.firstOrNull { it.url == url && it.title.isEmpty() }?.let { it.title = title.take(60); save(p, BM, b) }
    }

    fun clearHistory(p: SharedPreferences) { p.edit().remove(HIST).apply() }

    /**
     * A dark panel with Bookmarks / History tabs, one row per entry (tap opens it, X deletes it) and a Clear button on the
     * history tab. [heightPx] is the height of the scrolling list.
     */
    fun panel(ctx: Context, p: SharedPreferences, heightPx: Int, startOnHistory: Boolean, onOpen: (String) -> Unit): View {
        val d = ZScale.density(ctx)   // same scale as the settings screen, not the (possibly re-based) window density
        fun dp(x: Int) = (x * d).toInt()
        fun chip(fill: Int, r: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = r * d }
        var history = startOnHistory
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(list); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx) }
        fun label(t: String, on: Boolean, click: () -> Unit) = TextView(ctx).apply {
            text = t; gravity = Gravity.CENTER; textSize = 24f; typeface = ZFont.reg
            setTextColor(if (on) 0xFF1C1C1E.toInt() else 0xFFF5F5F7.toInt())
            background = chip(if (on) 0xFFA3E635.toInt() else 0xFF2C2C2E.toInt(), 12)
            setOnClickListener { click() }
        }
        lateinit var refresh: () -> Unit
        refresh = {
            tabs.removeAllViews(); list.removeAllViews()
            tabs.addView(label("Bookmarks", !history) { history = false; refresh() }, LinearLayout.LayoutParams(0, dp(72), 1f))
            tabs.addView(label("History", history) { history = true; refresh() }, LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(8) })
            if (history) tabs.addView(label("Clear", false) { clearHistory(p); refresh() }, LinearLayout.LayoutParams(0, dp(72), 0.7f).apply { marginStart = dp(8) })
            val items = if (history) history(p) else bookmarks(p)
            if (items.isEmpty()) list.addView(TextView(ctx).apply {
                text = if (history) "No history yet" else "No bookmarks yet. Tap the star to add the page."
                setTextColor(0xFF98989D.toInt()); textSize = 20f; setPadding(dp(6), dp(18), dp(6), dp(18))
            })
            for (e in items) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = chip(0xFF2C2C2E.toInt(), 14); setPadding(dp(16), dp(10), 0, dp(10)); minimumHeight = dp(84)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
                }
                val txt = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnClickListener { onOpen(e.url) }
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(TextView(ctx).apply {
                        text = e.title.ifEmpty { e.url }; setTextColor(0xFFF5F5F7.toInt()); textSize = 24f; typeface = ZFont.reg; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = e.url.removePrefix("https://").removePrefix("http://"); setTextColor(0xFF98989D.toInt()); textSize = 20f; typeface = ZFont.reg; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }
                row.addView(txt)
                row.addView(TextView(ctx).apply {
                    text = "X"; gravity = Gravity.CENTER; textSize = 24f; typeface = ZFont.reg; setTextColor(0xFFFF6B6B.toInt())
                    setOnClickListener {
                        if (history) { val l = history(p); l.removeAll { it.url == e.url }; save(p, HIST, l) }
                        else { val l = bookmarks(p); l.removeAll { it.url == e.url }; save(p, BM, l) }
                        refresh()
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(56))
                })
                list.addView(row)
            }
        }
        refresh()
        root.addView(tabs); root.addView(scroll, (scroll.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8) })
        return root
    }
}
