package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Z_Cam: the car keeps the cabin media speaker muted unless some app holds audio focus (the web page's video does, which is why the cabin
 * engine sound only played while a video was running; a 1.5 s grab was audible for exactly that long). So while the engine sound is running
 * it holds a "may duck" focus, and gives it back when the last engine sound thread ends. The engine sound itself still ignores focus changes.
 */
final class AudioWake {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AudioFocusRequest held;
    private static int users;

    private AudioWake() {
    }

    static void acquire(Context ctx) {
        MAIN.post(() -> {
            users++;
            try {
                if (held != null) {
                    return;
                }
                AudioManager am = ctx.getSystemService(AudioManager.class);
                if (am == null) {
                    return;
                }
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(change -> Shim.log("wake: focus change " + change))
                        .build();
                int r = am.requestAudioFocus(req);
                Shim.log("wake: holding audio focus, result " + r + " (1 = granted)");
                held = req;
            } catch (RuntimeException e) {
                Shim.log("wake: " + e);
            }
        });
    }

    static void release(Context ctx) {
        MAIN.post(() -> {
            if (users > 0) {
                users--;
            }
            if (users == 0 && held != null) {
                try {
                    ctx.getSystemService(AudioManager.class).abandonAudioFocusRequest(held);
                } catch (RuntimeException ignored) {
                    // nothing to give back
                }
                held = null;
                Shim.log("wake: audio focus released");
            }
        });
    }
}
