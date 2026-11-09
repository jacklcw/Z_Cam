package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;

/** Z_Cam: key-fob style double chirp on the outside speaker when the car locks, so you can walk away sure it is locked. */
public final class LockSound {
    private static final float EDGE_SEC = 0.006f;
    // note = {startHz, endHz, seconds, gapSeconds, decay (1 = fades out), timbre (0 sine, 1 horn)}
    private static float[][] notes(String style) {
        switch (style) {
            case "single": return new float[][]{{2700, 2700, 0.14f, 0f, 0, 0}};
            case "triple": return new float[][]{{2600, 2600, 0.08f, 0.07f, 0, 0}, {2600, 2600, 0.08f, 0.07f, 0, 0}, {2600, 2600, 0.08f, 0f, 0, 0}};
            case "fob": return new float[][]{{1800, 1800, 0.10f, 0.07f, 0, 0}, {2800, 2800, 0.12f, 0f, 0, 0}};
            case "chime": return new float[][]{{1047, 1047, 0.22f, 0.02f, 1, 0}, {1568, 1568, 0.22f, 0.02f, 1, 0}, {2093, 2093, 0.40f, 0f, 1, 0}};
            case "horn": return new float[][]{{420, 420, 0.12f, 0.10f, 0, 1}, {420, 420, 0.12f, 0f, 0, 1}};
            case "sweep": return new float[][]{{1500, 3500, 0.25f, 0f, 0, 0}};
            default: return new float[][]{{2400, 2400, 0.09f, 0.07f, 0, 0}, {3000, 3000, 0.09f, 0f, 0, 0}};
        }
    }

    private static volatile boolean busy;

    private LockSound() {
    }

    public static void play(Context context) {
        if (busy) {
            return;
        }
        busy = true;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                render(app);
            } catch (Throwable e) {
                Shim.log("locksound: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "locksound").start();
    }

    private static void render(Context app) {
        Shim.app = app;
        for (int i = 0; i < 40 && WelcomeSound.isPlaying(); i++) {   // a welcome engine winds down on lock; chirp after it
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
        AppConfig c = new AppConfig(app);
        SharedPreferences p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        int usage = "0".equals(p.getString("engExt", "1")) ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
        float amp = Math.min(0.9f, 0.15f * EngineVoice.dbToLin(DriveSoundRules.exteriorDb(c.getDriveSoundExteriorVolume()) * 0.5f));
        SoundOutput out = SoundOutput.open(app, CarSpeakers.exteriorAddress(c.getDriveSoundExteriorRoute()), 60, usage, true);
        try {
            int rate = SoundOutput.RATE;
            float[][] ns = notes(p.getString("lockStyle", "chirp"));
            int edge = Math.round(EDGE_SEC * rate);
            int total = rate / 10;
            for (float[] n : ns) total += Math.round((n[2] + n[3]) * rate);
            float[] lr = new float[2 * total];
            int pos = 0;
            for (float[] n : ns) {
                int len = Math.round(n[2] * rate);
                double ph = 0;
                for (int i = 0; i < len; i++, pos++) {
                    float k = (float) i / len;
                    ph += 2.0 * Math.PI * (n[0] + (n[1] - n[0]) * k) / rate;
                    float env = Math.min(1f, (float) Math.min(i, len - 1 - i) / edge);
                    if (n[4] > 0) env *= (float) Math.exp(-4.0 * k);
                    float w = (float) Math.sin(ph);
                    if (n[5] > 0) w = 0.6f * w + 0.3f * (float) Math.sin(2 * ph) + 0.25f * (float) Math.sin(3 * ph);   // horn: harmonics
                    float s = amp * env * w;
                    lr[2 * pos] = s;
                    lr[2 * pos + 1] = s;
                }
                pos += Math.round(n[3] * rate);
            }
            final int block = 256;
            float[] buf = new float[2 * block];
            for (int f = 0; f < total; f += block) {
                int n = Math.min(block, total - f);
                java.util.Arrays.fill(buf, 0f);
                System.arraycopy(lr, 2 * f, buf, 0, 2 * n);
                if (!out.write(buf, block)) {
                    break;
                }
            }
            java.util.Arrays.fill(buf, 0f);
            for (int i = 0; i < 40; i++) {
                if (!out.write(buf, block)) {
                    break;
                }
            }
        } finally {
            out.close();
        }
    }
}
