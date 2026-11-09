package com.zeekr.sentrycam

import android.app.Activity
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** The full-screen web page (see [WebHud.showFull]): a normal activity, so the car's system panels can open above it. */
class FullWebActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val hud = WebHud.instance
        val pad = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { WebHud.instance?.fullSurface(Surface(st)) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { WebHud.instance?.fullSurface(null); return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            setOnTouchListener { v, e -> WebHud.instance?.padTouch(e, v); true }
            addOnLayoutChangeListener { v, l, t, r, bt, _, _, _, _ -> WebHud.instance?.fullArea(r - l, bt - t) }
        }
        setContentView(FrameLayout(this).apply { addView(pad) })
        if (hud == null) finish() else hud.attachFull(this, pad)
    }

    override fun onDestroy() {
        WebHud.instance?.fullGone(this)
        super.onDestroy()
    }
}
