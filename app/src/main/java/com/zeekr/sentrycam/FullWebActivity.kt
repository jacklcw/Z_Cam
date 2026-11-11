package com.zeekr.sentrycam

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** The full-screen web page (see [WebHud.showFull]): a normal activity holding its own WebView, so the car's system panels can open above it. */
class FullWebActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hud = WebHud.instance
        val frame = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        setContentView(frame)
        if (hud == null) finish() else hud.attachFull(this, frame)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (WebHud.instance?.fullBack() != true) super.onBackPressed() }

    override fun onDestroy() {
        WebHud.instance?.fullGone(this)
        super.onDestroy()
    }
}
