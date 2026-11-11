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
    private static boolean lost;       // another app (a web video) took the focus from us
    private static boolean seenOther;  // after losing the focus: the other app's player has actually shown up as a running player
    private static boolean watching;
    private static long lostAt;       // when we lost the focus
    private static long quietSince;   // since when nobody but us has been playing
    private static long assertedAt;   // last time the focus was re-asserted
    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            watching = false;
            if (users <= 0 || held == null) {
                return;
            }
            try {
                AudioManager am = appCtx.getSystemService(AudioManager.class);
                long now = android.os.SystemClock.uptimeMillis();
                // somebody else is playing when there are more active players than our own tracks
                boolean other = am.getActivePlaybackConfigurations().size() > SoundOutput.LIVE.get();
                if (other) {
                    quietSince = 0;
                    if (lost) {
                        seenOther = true;   // the video / music is really playing now
                    }
                } else if (quietSince == 0) {
                    quietSince = now;
                }
                if (lost && !other && (seenOther || now - lostAt > 8000)) {
                    // whatever took the focus has stopped (music ended, video paused): take it back, or the car mutes the cabin speaker again.
                    // Never before it was seen playing, unless 8 s passed: a video that is still starting up has no player yet, and grabbing the focus then paused it after ~2 s.
                    int r = am.requestAudioFocus(held);
                    Shim.log("wake: regained audio focus, result " + r);
                    lost = false;
                    seenOther = false;
                    assertedAt = now;
                } else if (!lost && !other && quietSince != 0 && now - quietSince > 6000 && now - assertedAt > 15000) {
                    // the car can hand the focus to its media app without telling us: while nothing else plays, keep asserting ours
                    am.requestAudioFocus(held);
                    assertedAt = now;
                }
            } catch (RuntimeException e) {
                Shim.log("wake: watch " + e);
            }
            watching = true;
            MAIN.postDelayed(this, 1000);
        }
    };
    private static Context appCtx;

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
                        .setOnAudioFocusChangeListener(change -> {
                            Shim.log("wake: focus change " + change);
                            if (change < 0) {
                                lost = true; seenOther = false; lostAt = android.os.SystemClock.uptimeMillis();
                            }
                        })
                        .build();
                int r = am.requestAudioFocus(req);
                Shim.log("wake: holding audio focus, result " + r + " (1 = granted)");
                held = req;
                appCtx = ctx.getApplicationContext();
                lost = false;
                if (!watching) {
                    watching = true;
                    MAIN.postDelayed(WATCH, 1000);
                }
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
                lost = false;
                MAIN.removeCallbacks(WATCH);
                watching = false;
                Shim.log("wake: audio focus released");
            }
        });
    }
}
