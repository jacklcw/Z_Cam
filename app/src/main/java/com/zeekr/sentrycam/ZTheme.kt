package com.zeekr.sentrycam

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Z_Cam look, same tokens as the ZDash dashboard (relay/src/dashpage.js): Dark / Black (true
 * black, outlined cards) / Light, monospace type, one fixed lime accent (not customisable).
 *
 * Views opt in with android:tag (layout) or view.tag (code); [apply] walks the tree and styles by tag:
 *   root  window/scroll background        card   rounded panel          h  section label (muted caps)
 *   t     primary text                    m      muted text             in  text input
 *   btn   lime primary button             btn2   outlined button        bad destructive outlined button
 *   seg   segmented button (isSelected = lime)    sw  switch            seek  slider
 *   chip  filled placeholder block        accent lime text
 * Big touch targets on purpose: this runs on the car's large screen.
 */
object ZTheme {
    /** App text is drawn this much larger than the sizes below; element sizes (heights, paddings) are unchanged. */
    private const val FONT_SCALE = 1.32f

    enum class Mode(val key: String) { DARK("dark"), BLACK("black"), LIGHT("light");
        companion object { fun of(key: String?) = values().firstOrNull { it.key == key } ?: DARK } }

    class Palette(val bg: Int, val card: Int, val line: Int, val fg: Int, val mut: Int, val chip: Int, val light: Boolean)

    const val LIME = 0xFFA3E635.toInt()
    private const val ON_LIME = 0xFF10170A.toInt()
    private const val BAD = 0xFFFF453A.toInt()

    fun palette(m: Mode) = when (m) {
        Mode.DARK -> Palette(0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF2C2C2E.toInt(), 0xFFF5F5F7.toInt(), 0xFF98989D.toInt(), 0xFF2C2C2E.toInt(), false)
        Mode.BLACK -> Palette(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF2A2A2C.toInt(), 0xFFF5F5F7.toInt(), 0xFF98989D.toInt(), 0xFF000000.toInt(), false)
        Mode.LIGHT -> Palette(0xFFF5F5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8E8ED.toInt(), 0xFF1D1D1F.toInt(), 0xFF86868B.toInt(), 0xFFF5F5F7.toInt(), true)
    }

    // lime text needs a darker shade on the light theme to stay readable
    private fun accentText(p: Palette) = if (p.light) 0xFF4D7C0F.toInt() else LIME

    /** Window chrome + the whole tree under [root]. */
    fun apply(act: Activity, root: View, mode: Mode) {
        val p = palette(mode)
        act.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        act.window.statusBarColor = p.bg
        act.window.navigationBarColor = p.bg
        WindowInsetsControllerCompat(act.window, act.window.decorView).apply {
            isAppearanceLightStatusBars = p.light; isAppearanceLightNavigationBars = p.light
        }
        style(root, p)
    }

    /** Style a subtree (also used for views added at runtime). */
    fun style(v: View, p: Palette) {
        val d = ZScale.density(v.context)
        fun dp(x: Int) = (x * d).toInt()
        fun box(fill: Int, stroke: Int?, r: Int) = GradientDrawable().apply {
            setColor(fill); cornerRadius = r * d; if (stroke != null) setStroke(dp(1), stroke)
        }
        fun ripple(content: GradientDrawable, onFill: Int) =
            RippleDrawable(ColorStateList.valueOf((onFill and 0x00FFFFFF) or 0x33000000), content, null)
        fun text(t: TextView, color: Int, sp: Float) {
            t.setTextColor(color); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * FONT_SCALE * d * v.resources.configuration.fontScale); t.typeface = ZFont.reg
        }
        when (v.tag as? String) {
            "root" -> v.setBackgroundColor(p.bg)
            "card" -> { v.background = box(p.card, p.line, 18); v.setPadding(dp(18), dp(8), dp(18), dp(12)) }
            "h" -> (v as TextView).apply {
                text(this, p.mut, 15f); typeface = ZFont.bold; isAllCaps = true; letterSpacing = 0.12f; setPadding(0, 0, 0, if (isSelected) 0 else dp(6))
                minHeight = dp(44); gravity = android.view.Gravity.CENTER_VERTICAL
                compoundDrawableTintList = ColorStateList.valueOf(p.mut)
            }
            "t" -> text(v as TextView, p.fg, 18f)
            "m" -> text(v as TextView, p.mut, 15f)
            "accent" -> (v as TextView).apply { text(this, accentText(p), 16f); typeface = ZFont.bold }
            "in" -> (v as EditText).apply {
                text(this, p.fg, 18f); setHintTextColor(p.mut)
                background = box(p.chip, p.line, 12); minHeight = dp(60); setPadding(dp(18), dp(12), dp(18), dp(12))
            }
            "btn", "btn2", "bad", "seg" -> (v as Button).apply {
                val primary = tag == "btn" || (tag == "seg" && isSelected)
                val fill = if (primary) LIME else p.chip
                val fg = when { primary -> ON_LIME; tag == "bad" -> BAD; else -> p.fg }
                text(this, fg, 16f)
                isAllCaps = false; typeface = ZFont.semi
                background = ripple(box(fill, if (primary) null else p.line, 14), fg)
                compoundDrawableTintList = ColorStateList.valueOf(fg)
                minHeight = dp(68); minimumHeight = dp(68); stateListAnimator = null; elevation = 0f
                // buttons with an icon (the half-width Back up / Restore ...) get slim side padding so the label never overflows and sticks to the left
                val px = if (compoundDrawablesRelative.any { it != null }) dp(10) else dp(22)
                setPadding(px, dp(10), px, dp(10))
            }
            "sw" -> (v as SwitchCompat).apply {
                text(this, p.fg, 18f); minHeight = dp(64); setPadding(0, dp(8), 0, dp(8))
                val checked = intArrayOf(android.R.attr.state_checked)
                val off = intArrayOf()
                thumbTintList = ColorStateList(arrayOf(checked, off), intArrayOf(LIME, p.mut))
                trackTintList = ColorStateList(arrayOf(checked, off), intArrayOf((LIME and 0x00FFFFFF) or 0x66000000, p.line))
            }
            "seek" -> (v as SeekBar).apply {
                // the ZDash slider: a 6 dp track in the line colour, filled with the text colour, and a 24 dp round knob of that colour
                thumbTintList = null; progressTintList = null; progressBackgroundTintList = null
                thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.fg); setSize(dp(24), dp(24)) }
                splitTrack = false
                fun bar(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(3).toFloat(); setSize(-1, dp(6)) }
                progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(bar(p.line),
                    android.graphics.drawable.ClipDrawable(bar(p.fg), android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply {
                    setId(0, android.R.id.background); setId(1, android.R.id.progress)
                }
                minimumHeight = dp(56); setPadding(dp(18), dp(10), dp(18), dp(10))
            }
            "chip" -> v.setBackgroundColor(p.chip)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i), p)
    }
}

/**
 * One fixed screen density for the settings UI. The system re-bases this process's density onto the 240 dpi HUD display whenever one of our
 * HUD overlay windows appears, and everything sized from resources.displayMetrics afterwards came out 25 % smaller. The main display's own
 * density does not change, so sizes are computed from that.
 */
object ZScale {
    @Volatile private var pinned = 0f
    /** Called first thing in the settings Activity's onCreate: its own resources are correct at launch, before any HUD window re-bases the process. */
    fun pin(c: android.content.Context) { pinned = c.resources.displayMetrics.density }
    fun density(c: android.content.Context): Float {
        if (pinned <= 0f) pinned = c.resources.displayMetrics.density
        return pinned
    }
}

/** App pop-ups in the Z_Cam look (card, mono type, lime primary button), instead of the stock dialog. */
object ZDialog {
    private fun pal(act: Activity) = ZTheme.palette(ZTheme.Mode.of(act.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).getString("theme", null)))

    private fun open(act: Activity, card: View, widthFrac: Float, cancelable: Boolean): android.app.AlertDialog {
        ZTheme.style(card, pal(act))
        val dlg = android.app.AlertDialog.Builder(act).setView(card).setCancelable(cancelable).create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dlg.show()
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * widthFrac).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dlg
    }

    private fun card(act: Activity, title: String): LinearLayout {
        val d = ZScale.density(act)
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; tag = "card"
            addView(TextView(act).apply { tag = "h"; text = title })
        }
    }

    private fun btn(act: Activity, label: String, tag: String, click: () -> Unit) = Button(act).apply {
        this.tag = tag; text = label; setOnClickListener { click() }
    }

    /** Message + one or two buttons. [onNegative] runs on the negative button only (not on a cancel by tapping outside). */
    fun show(act: Activity, title: String, message: String?, positive: String, negative: String? = "Cancel", cancelable: Boolean = true,
             onNegative: (() -> Unit)? = null, onPositive: () -> Unit) {
        val d = ZScale.density(act); fun dp(x: Int) = (x * d).toInt()
        val c = card(act, title)
        if (message != null) c.addView(TextView(act).apply { tag = "t"; text = message; setPadding(0, 0, 0, dp(14)) })
        var dlg: android.app.AlertDialog? = null
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        if (negative != null) row.addView(btn(act, negative, "btn2") { dlg?.dismiss(); onNegative?.invoke() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        row.addView(btn(act, positive, "btn") { dlg?.dismiss(); onPositive() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(row)
        dlg = open(act, c, 0.5f, cancelable)
    }

    /** A list to pick one entry from; the current one is lime. */
    fun choose(act: Activity, title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val d = ZScale.density(act); fun dp(x: Int) = (x * d).toInt()
        val c = card(act, title)
        var dlg: android.app.AlertDialog? = null
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.forEachIndexed { i, n ->
            col.addView(Button(act).apply {
                tag = "seg"; text = n; isSelected = i == selected; gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                setOnClickListener { dlg?.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        val maxH = (act.resources.displayMetrics.heightPixels * 0.6f).toInt()
        c.addView(android.widget.ScrollView(act).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(maxH, names.size * dp(76))).apply { bottomMargin = dp(6) })
        c.addView(btn(act, "Cancel", "btn2") { dlg?.dismiss() })
        dlg = open(act, c, 0.5f, true)
    }

    /** Any content in the same card, with one Close button. */
    fun custom(act: Activity, content: View, close: String = "Close", widthFrac: Float = 0.6f): android.app.AlertDialog {
        val d = ZScale.density(act); fun dp(x: Int) = (x * d).toInt()
        val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; tag = "card" }
        var dlg: android.app.AlertDialog? = null
        c.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        c.addView(btn(act, close, "btn") { dlg?.dismiss() })
        dlg = open(act, c, widthFrac, true)
        return dlg
    }
}
