package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;

/**
 * Z_Cam: a short welcome sound on the outside speaker (engine start, one rev, shut down), in the engine style picked in
 * the Engine sound card ("welcomeStyle"). Rendered live with the same engine models as the driving sound.
 */
public final class WelcomeSound {
    private static final int BLOCK = 256;
    private static final float MAX_SEC = 14f;
    private static volatile boolean busy;
    private static volatile boolean stopRequested;
    private static final float HOLD_MAX_SEC = 120f;   // an engine left idling for this long is shut down whatever happens

    private WelcomeSound() {
    }

    public static boolean isPlaying() {
        return busy;
    }

    /** A held welcome sound shuts the engine down on its next block after this. */
    public static void requestStop() {
        stopRequested = true;
    }

    /** Plays once on its own thread; ignored while one is already playing. */
    public static void play(Context context) {
        play(context, false);
    }

    /** Starts the engine and one rev, then keeps it idling until [requestStop] (or the 2 minute cap), then shuts down. */
    public static void playHeld(Context context) {
        play(context, true);
    }

    private static void play(Context context, boolean hold) {
        final Context app = context.getApplicationContext();
        if (busy) {
            return;
        }
        busy = true;
        stopRequested = false;
        Thread t = new Thread(() -> {
            try {
                render(app, hold);
            } catch (Throwable e) {
                Shim.log("welcome: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "welcomesound");
        t.start();
    }

    private static void render(Context app, boolean hold) {
        Shim.app = app;
        AppConfig c = new AppConfig(app);
        SharedPreferences p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        SoundStyle style = SoundStyle.of(p.getString("welcomeStyle", "american"));
        String route = c.getDriveSoundExteriorRoute();
        int usage = "0".equals(p.getString("engExt", "1")) ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
        float exteriorDb = DriveSoundRules.exteriorDb(c.getDriveSoundExteriorVolume());
        float userDb = DriveSoundRules.volumeDb(c.getDriveSoundVolume());
        SoundOutput out = SoundOutput.open(app, CarSpeakers.exteriorAddress(route), 60, usage, true);
        try {
            VirtualEngine engine = new VirtualEngine(style.profile(), style.gearbox(Gearbox.AUTO8), style.cranks(), style.pseudoGears(),
                    style.realNeutral());
            Voice voice = style.voice(SoundOutput.RATE, false);
            ExteriorMix exterior = new ExteriorMix(SoundOutput.RATE);
            VirtualEngine.Input in = new VirtualEngine.Input();
            in.gear = VirtualEngine.Gear.P;
            in.throttlePct = 0f;
            in.mode = VirtualEngine.RpmMode.CONTINUOUS;
            VoiceParams params = new VoiceParams();
            float[] buffer = new float[2 * BLOCK];
            float[] mixed = new float[2 * BLOCK];
            float dt = BLOCK / (float) SoundOutput.RATE;
            float exteriorGain = EngineVoice.dbToLin(exteriorDb);
            engine.ignite(in);
            float t = 0f;
            float runningAt = -1f;
            boolean stopped = false;
            while (hold || t < MAX_SEC) {
                if (!stopped && engine.phase() == VirtualEngine.Phase.RUNNING && runningAt < 0f) {
                    runningAt = t;
                }
                float since = runningAt < 0f ? -1f : t - runningAt;
                boolean blip = !hold && since >= 0.5f && since < 0.95f;   // the one rev is only for the test button; the welcome just idles
                in.throttlePct = blip ? 45f : 0f;
                in.pedalRate = blip ? 250f : 0f;
                if (!stopped && since >= 1.6f && (!hold || stopRequested || t >= HOLD_MAX_SEC)) {
                    engine.shutdown(in);
                    stopped = true;
                }
                engine.step(dt, in);
                engine.fill(params);
                params.gain = EngineVoice.dbToLin(userDb + engine.outputDb());
                voice.renderStereo(buffer, BLOCK, params);
                exterior.process(buffer, mixed, BLOCK, exteriorGain);
                if (!out.write(mixed, BLOCK)) {
                    break;
                }
                t += dt;
                if (stopped && engine.done()) {
                    break;
                }
            }
            java.util.Arrays.fill(mixed, 0f);
            for (int i = 0; i < 40; i++) {   // let the tail drain before the track is flushed
                if (!out.write(mixed, BLOCK)) {
                    break;
                }
            }
        } finally {
            out.close();
        }
    }
}
