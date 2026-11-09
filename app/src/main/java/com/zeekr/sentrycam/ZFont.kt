package com.zeekr.sentrycam

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/** The app's one typeface: Inter, the same as the ZDash app. Loaded once at start (see [ZApp]); plain sans-serif until then. */
object ZFont {
    @Volatile var reg: Typeface = Typeface.SANS_SERIF; private set
    @Volatile var semi: Typeface = Typeface.DEFAULT_BOLD; private set
    @Volatile var bold: Typeface = Typeface.DEFAULT_BOLD; private set
    fun init(c: Context) {
        try {
            reg = ResourcesCompat.getFont(c, R.font.inter_regular) ?: reg
            semi = ResourcesCompat.getFont(c, R.font.inter_semibold) ?: semi
            bold = ResourcesCompat.getFont(c, R.font.inter_bold) ?: bold
        } catch (_: Throwable) {}
    }
}

class ZApp : Application() {
    override fun onCreate() { super.onCreate(); ZFont.init(this) }
}
