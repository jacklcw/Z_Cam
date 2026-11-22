package com.zeekr.sentrycam

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import org.json.JSONObject

/** Empty on purpose: being an enabled notification listener is what lets Z_Cam read the car's media sessions ([NowPlaying]). */
class NowPlayingListener : NotificationListenerService()

/**
 * What is playing, from the car's active media sessions (the Bluetooth music link carries the phone's track via AVRCP, so this is the phone's
 * Google Maps / Spotify / YouTube Music etc. track). Works standalone, no relay. Needs "Notification access" for Z_Cam (see [enabled]).
 */
object NowPlaying {
    private fun listed(ctx: Context) =
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().contains(ctx.packageName)

    /** The car's settings have no notification-access page, so when the WRITE_SECURE_SETTINGS permission was granted over adb the app switches its own listener on. */
    fun enabled(ctx: Context): Boolean {
        if (listed(ctx)) return true
        try {
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val me = ComponentName(ctx, NowPlayingListener::class.java).flattenToString()
                val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
                android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", if (cur.isEmpty()) me else "$cur:$me")
            }
        } catch (_: Throwable) {}
        return listed(ctx)
    }

    private fun best(ctx: Context): android.media.session.MediaController? {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val list = msm.getActiveSessions(ComponentName(ctx, NowPlayingListener::class.java))
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
            ?: list.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
    }

    /** cmd: -1 previous, 0 play/pause, 1 next. Goes to the phone over Bluetooth (AVRCP). */
    fun control(ctx: Context, cmd: Int) {
        try {
            val c = best(ctx) ?: return
            val t = c.transportControls
            when (cmd) { -1 -> t.skipToPrevious(); 1 -> t.skipToNext(); else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play() }
        } catch (_: Throwable) {}
    }

    /** {title, artist, playing} of the best session (a playing one first), or null when nothing is known. */
    fun read(ctx: Context): JSONObject? = try {
        if (!enabled(ctx)) JSONObject().put("title", "Media access off: needs adb grant (see Z_Cam README)").put("artist", "").put("playing", false) else {
            val best = best(ctx)
            val m = best?.metadata
            val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (best == null || title.isNullOrBlank()) null else JSONObject()
                .put("title", title)
                .put("artist", (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty())
                .put("playing", best.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
    } catch (_: Throwable) { null }
}
