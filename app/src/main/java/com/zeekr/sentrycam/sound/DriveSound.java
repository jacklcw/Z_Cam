/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;


import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 *
 * <ul>
 * </ul>
 *
 */
public final class DriveSound {

    private static final String TAG = "DriveSound";
    static final String USER = "drive-sound";
    static final long FAST_MS = 20L;
    private static final Set<Signal> FAST =
            Collections.unmodifiableSet(EnumSet.of(Signal.SPEED, Signal.THROTTLE_DEPTH, Signal.BRAKE_DEPTH));
    static final int BLOCK = 256;
    static final long CHECK_MS = 500L;
    static final long WATCH_MS = 1000L;
    static final long RETRY_AFTER_FAILURE_MS = 3_000L;
    static final float HURRY_FADE_SEC = 0.15f;

    private static final DriveSound INSTANCE = new DriveSound();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Telemetry.Listener listener = readings -> update();
    private Context app;
    private AppConfig config;
    private boolean installed;
    private boolean armed;
    private Run run;
    private Run leaving;
    private long failedAtMs;

    private volatile boolean enabled;
    private volatile boolean standardMode;
    private volatile float userDb = 0f;
    private volatile VirtualEngine.RpmMode rpmMode = VirtualEngine.RpmMode.CONTINUOUS;
    private volatile Gearbox gearbox = Gearbox.AUTO8;
    private volatile SoundStyle style = SoundStyle.AMERICAN;
    private volatile boolean cabinView;
    /** Z_Cam: a short rev when the gear goes from P/N into D/R while stopped. */
    private volatile boolean revBlip = true;
    /** Z_Cam driving profile "Silent": fade the sound out but keep the outputs open. */
    private volatile boolean muted;
    private volatile String outputMode = AppConfig.DRIVE_SOUND_OUTPUT_CABIN;
    private volatile String exteriorRoute = AppConfig.DRIVE_SOUND_ROUTE_NOTIFY;
    private volatile float exteriorDb = 12f;
    private volatile Run latest;

    private DriveSound() {
    }

    public static void install(Context context) {
        INSTANCE.doInstall(context);
    }

    public static boolean styleHasViews(String style) {
        return SoundStyle.of(style).hasViews();
    }

    public static boolean styleChoosesGearbox(String style) {
        return SoundStyle.of(style).choosesGearbox();
    }

    public static void refresh(Context context) {
        INSTANCE.main.post(() -> {
            INSTANCE.doInstall(context);
            INSTANCE.update();
        });
    }

    public static void shift(int step) {
        Run r = INSTANCE.run;
        if (r != null) {
            r.shiftSteps.addAndGet(step);
        }
    }

    private void doInstall(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        app = context.getApplicationContext();
        Shim.app = app;
        config = new AppConfig(app);
        Telemetry.get().addListener(listener);
        update();
    }

    private void update() {
        if (!installed) {
            return;
        }
        readSettings();
        Telemetry telemetry = Telemetry.get();
        telemetry.core(app, enabled);
        Readings r = telemetry.readings();
        DriveSoundRules.Why why = DriveSoundRules.decide(enabled, telemetry.carLinkConnected(),
                r.code(Signal.IGNITION), r.text(Signal.GEAR), r.code(Signal.DRIVE_MODE), standardMode, 0f, 0f, 0L, false);
        if (why.armed != armed) {
            armed = why.armed;
            if (armed) {
                telemetry.acquireFast(USER, FAST, FAST_MS);
            } else {
                telemetry.releaseFast(USER);
            }
        }
        if (run != null && (run.style != style || run.cabin != cabinView || !run.outputMode.equals(outputMode)
                || (!run.exteriorRoute.equals(exteriorRoute) && !AppConfig.DRIVE_SOUND_OUTPUT_CABIN.equals(outputMode)))) {
            // (Z_Cam: fade the old one out fast so two synths never render side by side)
            run.hurry();
            run.stop();
            leaving = run;
            run = null;
        }
        if (why.open && run == null) {
            long now = SystemClock.elapsedRealtime();
            if (failedAtMs != 0L && now - failedAtMs < RETRY_AFTER_FAILURE_MS) {
                return;
            }
            if (leaving != null) {
                leaving.hurry();
                leaving = null;
            }
            run = new Run();
            run.start();
        } else if (!why.open && run != null) {
            run.stop();
            leaving = run;
            run = null;
        }
    }

    private void ended(Run which, boolean failed) {
        main.post(() -> {
            if (leaving == which) {
                leaving = null;
            }
            if (run == which) {
                run = null;
                if (failed) {
                    failedAtMs = SystemClock.elapsedRealtime();
                    // nothing else re-asks while the car is steady, so retry by ourselves
                    main.postDelayed(this::update, RETRY_AFTER_FAILURE_MS + 200L);
                }
            }
        });
    }

    private void readSettings() {
        AppConfig c = config;
        if (c == null) {
            return;
        }
        PulseVoice.crackleScale = c.getCrackleScale();
        revBlip = c.isRevBlip();
        muted = c.isMuted();
        enabled = c.isDriveSoundEnabled();
        standardMode = true;
        userDb = DriveSoundRules.volumeDb(c.getDriveSoundVolume());
        rpmMode = AppConfig.DRIVE_SOUND_RPM_SHIFTS.equals(c.getDriveSoundRpm())
                ? VirtualEngine.RpmMode.SHIFTS : VirtualEngine.RpmMode.CONTINUOUS;
        gearbox = AppConfig.DRIVE_SOUND_GEARBOX_MANUAL6.equals(c.getDriveSoundGearbox()) ? Gearbox.MANUAL6 : Gearbox.AUTO8;
        style = SoundStyle.of(c.getDriveSoundStyle());
        cabinView = AppConfig.DRIVE_SOUND_VIEW_CABIN.equals(c.getDriveSoundView());
        String output = c.getDriveSoundOutput();
        outputMode = AppConfig.DRIVE_SOUND_OUTPUT_OUTSIDE.equals(output) ? output : AppConfig.DRIVE_SOUND_OUTPUT_CABIN;   // a saved "both" falls back to cabin
        exteriorRoute = AppConfig.DRIVE_SOUND_ROUTE_PLAYBACK.equals(c.getDriveSoundExteriorRoute())
                ? AppConfig.DRIVE_SOUND_ROUTE_PLAYBACK : AppConfig.DRIVE_SOUND_ROUTE_NOTIFY;
        exteriorDb = DriveSoundRules.exteriorDb(c.getDriveSoundExteriorVolume());
    }

    private static float num(Float value) {
        return value == null ? Float.NaN : value;
    }

    private static boolean inCall(AudioManager audio) {
        if (audio == null) {
            return false;
        }
        int mode = audio.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    public static String diagnostics() {
        DriveSound s = INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "  enabled=%s standard=%s style=%s view=%s speakers=%s exterior=%s %+.0f dB volume=%.0f dB rpm=%s gearbox=%s "
                        + "armed=%s fastUsers=%s%n",
                s.enabled, s.standardMode, s.style.key, s.cabinView ? "cabin" : "outside", s.outputMode, s.exteriorRoute, s.exteriorDb, s.userDb,
                s.rpmMode, s.gearbox, s.armed, Telemetry.get().fastUsers()));
        FastReadings fast = Telemetry.get().fast();
        long now = SystemClock.elapsedRealtime();
        sb.append(String.format(Locale.US, "  fast: age=%d ms speed=%s throttle=%s brake=%s%n", fast.ageMs(now),
                fast.number(Signal.SPEED), fast.number(Signal.THROTTLE_DEPTH), fast.number(Signal.BRAKE_DEPTH)));
        String pushStatus = Telemetry.get().pushStatus();
        sb.append("  push: ").append(pushStatus.isEmpty() ? "not tried" : pushStatus).append('\n');
        String formats = OutputProbe.result();
        sb.append("  output formats: ").append(formats.isEmpty() ? "not tried" : formats).append('\n');
        sb.append("  pushed: ").append(pushed(Signal.SPEED, "speed", now)).append(", ")
                .append(pushed(Signal.THROTTLE_DEPTH, "throttle", now)).append(", ")
                .append(pushed(Signal.BRAKE_DEPTH, "brake", now)).append('\n');
        sb.append("  throttle listeners: ").append(pushed(Telemetry.get().throttleListener(false), "fastest", now)).append(", ")
                .append(pushed(Telemetry.get().throttleListener(true), "RATE_DR", now)).append('\n');
        Run r = s.latest;
        sb.append("  ").append(r == null ? "no run since the process started" : r.describe()).append('\n');
        return sb.toString();
    }

    private static String pushed(Signal signal, String name, long now) {
        return pushed(Telemetry.get().pushed(signal), name, now);
    }

    private static String pushed(PushedValue p, String name, long now) {
        if (p.count == 0L) {
            return name + " -";
        }
        return String.format(Locale.US, "%s %s (%d ms ago, %d pushes, every %s)", name, p.value, now - p.atMs, p.count,
                p.everyMs < 0f ? "?" : String.format(Locale.US, "%.0f ms", p.everyMs));
    }

    private final class Run implements Runnable {
        final SoundStyle style = DriveSound.this.style;
        final boolean cabin = DriveSound.this.cabinView;
        final String outputMode = DriveSound.this.outputMode;
        final String exteriorRoute = DriveSound.this.exteriorRoute;
        final AtomicInteger shiftSteps = new AtomicInteger();
        private volatile int mediaFirst = Integer.MIN_VALUE;
        private volatile int mediaIndex = -1;
        private volatile int mediaMax = -1;
        private volatile boolean mediaFixed;
        private volatile boolean mediaTracked;
        private volatile boolean stopping;
        private volatile boolean hurry;
        private volatile String state = "opening";
        private volatile DriveSoundRules.Why why = DriveSoundRules.Why.STALE;
        private volatile String ignitedAs = "";
        private volatile String endedAs = "";
        private volatile String output = "";
        private volatile String trouble = "";
        private volatile float rpm;
        private volatile float load;
        private volatile float levelDb;
        private volatile int gear;
        private volatile boolean pseudo;
        private volatile float eager;
        private volatile float coupled;
        private volatile boolean driverOnly;
        private volatile float driverHold;
        private volatile int beeps;
        private volatile float gate;
        private volatile float pedalRate;
        private volatile float accel;
        private volatile long blocks;
        private volatile float renderMs;
        private volatile float mixerEveryMs = -1f;
        private volatile float burstBlocks = -1f;
        private final long askedAt = SystemClock.elapsedRealtime();
        private final long modeAgeWhenAsked = SignalAges.ageMs(Signal.DRIVE_MODE, askedAt);
        private volatile long openMs = -1L;
        private volatile long askedToSoundMs = -1L;
        private volatile long modeToSoundMs = -1L;
        private volatile float fastEveryMs = -1f;
        private volatile long fastGapMaxMs = -1L;
        private volatile float throttleEveryMs = -1f;
        private volatile int throttleSteps;
        private volatile float speedEveryMs = -1f;
        private volatile float pushEveryMs = -1f;
        private volatile long pushes;
        private volatile int pushFirst;
        private volatile int pollFirst;
        private volatile float pushLeadMs = -1f;
        private volatile float pollLeadMs = -1f;
        private volatile float mainEveryMs = -1f;
        private volatile long mainPushes;
        private volatile float drEveryMs = -1f;
        private volatile long drPushes;
        private volatile float inputAgeMs = -1f;
        private volatile float outputLatencyMs = -1f;

        void start() {
            latest = this;
            Thread thread = new Thread(this, "DriveSound");
            thread.start();
        }

        void stop() {
            stopping = true;
        }

        void hurry() {
            hurry = true;
        }

        /** Which outputs were asked for; a failed one is dropped and reopened every few seconds while the other keeps playing. */
        private boolean wantIn;
        private boolean wantOut;
        /** Z_Cam: starting buffer (ms); bigger when both speakers are fed from this one thread. */
        private int startMs = SoundOutput.START_BUFFER_MS;
        /** Exterior stream usage: its own (sonification) unless the user picked "same as cabin". */
        private int extUsage() {
            return "0".equals(app.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).getString("engExt", "1"))
                    ? android.media.AudioAttributes.USAGE_MEDIA : android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
        }
        private final java.util.List<SoundOutput> reopened = new java.util.ArrayList<>();

        @Override
        public void run() {
            raisePriority();
            boolean toCabin = !AppConfig.DRIVE_SOUND_OUTPUT_OUTSIDE.equals(outputMode);
            boolean toOutside = AppConfig.DRIVE_SOUND_OUTPUT_OUTSIDE.equals(outputMode) || AppConfig.DRIVE_SOUND_OUTPUT_BOTH.equals(outputMode);
            startMs = toCabin && toOutside ? 60 : SoundOutput.START_BUFFER_MS;
            SoundOutput inside = null;
            SoundOutput outside = null;
            if (toCabin) {
                AudioWake.acquire(app);   // the car mutes the cabin speaker unless something holds audio focus
            }
            long openStart = SystemClock.elapsedRealtime();
            final boolean pair = toCabin && toOutside;   // Z_Cam: build both first, then start both together
            if (toCabin) {
                try {
                    inside = SoundOutput.open(app, CarSpeakers.CABIN_MEDIA, startMs, android.media.AudioAttributes.USAGE_MEDIA, !pair);
                } catch (RuntimeException e) {
                    trouble = "cabin: " + e.getMessage();
                    AppLog.w(TAG, "cabin output: " + e.getMessage());
                    BlackBox.note("模拟声浪：开不了车内喇叭（" + e.getMessage() + "）");
                }
            }
            if (toOutside) {
                String address = CarSpeakers.exteriorAddress(exteriorRoute);
                try {
                    outside = SoundOutput.open(app, address, startMs, extUsage(), !pair);
                } catch (RuntimeException e) {
                    trouble = (trouble.isEmpty() ? "" : trouble + "; ") + "exterior: " + e.getMessage();
                    AppLog.w(TAG, "exterior output: " + e.getMessage());
                    BlackBox.note("模拟声浪：开不了车外喇叭（" + e.getMessage() + "）");
                }
            }
            if (pair) {
                try { if (inside != null) inside.start(); } catch (RuntimeException e) { inside = null; }
                try { if (outside != null) outside.start(); } catch (RuntimeException e) { outside = null; }
            }
            wantIn = toCabin;
            wantOut = toOutside;
            if (inside == null && outside == null) {
                state = "failed";
                if (toCabin) {
                    AudioWake.release(app);
                }
                ended(this, true);
                return;
            }
            openMs = SystemClock.elapsedRealtime() - openStart;
            output = describeOutputs(inside, outside);
            BlackBox.note("模拟声浪：开始（" + output + "）");
            if (inside != null) {
                OutputProbe.runOnce(app, CarSpeakers.CABIN_MEDIA);
            }
            boolean failed = false;
            try {
                failed = loop(inside, outside);
            } catch (RuntimeException e) {
                failed = true;
                trouble = e.toString();
                AppLog.w(TAG, "drive sound stopped: " + e);
            } finally {
                if (inside != null) {
                    inside.close();
                }
                if (outside != null) {
                    outside.close();
                }
                for (SoundOutput o : reopened) {
                    o.close();
                }
                if (toCabin) {
                    AudioWake.release(app);
                }
                state = failed ? "failed" : "stopped";
                output = describeOutputs(inside, outside);
                BlackBox.note("模拟声浪：停止（" + (failed ? "出错 " + trouble + "；" : "") + output + "）");
                ended(this, failed);
            }
        }

        private void readMedia(AudioManager audio) {
            if (audio == null) {
                return;
            }
            try {
                int index = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                mediaFixed = audio.isVolumeFixed();
                mediaMax = max;
                mediaIndex = index;
                if (mediaFirst == Integer.MIN_VALUE) {
                    mediaFirst = index;
                } else if (!mediaTracked && index != mediaFirst) {
                    mediaTracked = true;
                    BlackBox.note("模拟声浪：系统报的媒体音量变了（" + mediaFirst + " → " + index + " / " + max + "），车外跟着它");
                }
            } catch (RuntimeException e) {
            }
        }

        private String describeOutputs(SoundOutput inside, SoundOutput outside) {
            if (inside == null && outside == null) {
                return "none";
            }
            if (outside == null) {
                return inside.describe();
            }
            return (inside == null ? "" : inside.describe() + "; ") + "exterior " + outside.describe();
        }

        private void raisePriority() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (RuntimeException urgentRefused) {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                } catch (RuntimeException audioRefused) {
                    AppLog.w(TAG, "audio thread priority refused: " + audioRefused);
                }
            }
        }

        private boolean loop(SoundOutput inside, SoundOutput outside) {
            Telemetry telemetry = Telemetry.get();
            AudioManager audio = app.getSystemService(AudioManager.class);
            DriveInputs inputs = new DriveInputs();
            VirtualEngine engine = new VirtualEngine(style.profile(), style.gearbox(gearbox), style.cranks(), style.pseudoGears(),
                    style.realNeutral());
            Voice voice = wantIn ? style.voice(SoundOutput.RATE, cabin) : null;
            Voice voiceOut = null;
            if (wantOut) {
                voiceOut = voice != null && (!cabin || !style.hasViews()) ? voice : style.voice(SoundOutput.RATE, false);
            }
            ExteriorMix exterior = wantOut ? new ExteriorMix(SoundOutput.RATE) : null;
            long nextReopen = 0L;
            VirtualEngine.Gear prevGear = VirtualEngine.Gear.UNKNOWN;
            long blipUntil = 0L;
            ShiftBeep beep = new ShiftBeep(SoundOutput.RATE);
            int beepSeen = 0;
            VirtualEngine.Input in = new VirtualEngine.Input();
            VoiceParams params = new VoiceParams();
            float[] buffer = new float[2 * BLOCK];
            float[] outsideBuffer = new float[2 * BLOCK];
            float[] exteriorBuffer = new float[2 * BLOCK];
            float dt = BLOCK / (float) SoundOutput.RATE;
            boolean ignited = false;
            boolean windingDown = false;
            boolean inCall = false;
            boolean link = false;
            float g = 0f;
            long nextCheck = 0L;
            long nextWatch = 0L;
            long lastFastVersion = Long.MIN_VALUE;
            long lastFastAt = -1L;
            long gapMax = 0L;
            long gapWindowEnd = 0L;
            float lastThrottle = Float.NaN;
            long lastThrottleAt = -1L;
            long lastWakeNs = 0L;
            int blocksSinceWake = 0;
            long seenFastVersion = Long.MIN_VALUE;
            long seenPushCount = -1L;
            long inputVersion = 0L;
            ThrottleSources sources = new ThrottleSources();
            PedalTrack pedal = new PedalTrack();
            SpeedTrack speedTrack = new SpeedTrack();
            long lastPushCount = 0L;
            float usedThrottle = Float.NaN;
            state = "running";
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCheck) {
                    nextCheck = now + CHECK_MS;
                    readSettings();
                    inCall = inCall(audio);
                    link = telemetry.carLinkConnected();
                    readMedia(audio);
                }
                FastReadings fast = telemetry.fast();
                PushedValue push = telemetry.pushed(Signal.THROTTLE_DEPTH);
                PushedValue pushSpeed = telemetry.pushed(Signal.SPEED);
                PushedValue pushBrake = telemetry.pushed(Signal.BRAKE_DEPTH);
                float polled = num(fast.number(Signal.THROTTLE_DEPTH));
                float polledSpeed = num(fast.number(Signal.SPEED));
                float polledBrake = num(fast.number(Signal.BRAKE_DEPTH));
                boolean freshThrottle = sources.offer(push.count, push.value, push.atMs, fast.version, polled, fast.atMs);
                float throttle = sources.sample;
                long throttleAt = sources.sampleAt;
                boolean speedPushed = DriveSoundRules.usePush(pushSpeed.count, pushSpeed.atMs, pushSpeed.value, polledSpeed, now);
                float speed = speedPushed ? pushSpeed.value : polledSpeed;
                long speedAt = speedPushed ? pushSpeed.atMs : fast.atMs;
                float brake = DriveSoundRules.usePush(pushBrake.count, pushBrake.atMs, pushBrake.value, polledBrake, now)
                        ? pushBrake.value : polledBrake;
                long pushCount = push.count + pushSpeed.count + pushBrake.count;
                if (fast.version != seenFastVersion || pushCount != seenPushCount) {
                    seenFastVersion = fast.version;
                    seenPushCount = pushCount;
                    inputVersion++;
                }
                long inputAt = Math.max(Math.max(fast.atMs, push.atMs), Math.max(pushSpeed.atMs, pushBrake.atMs));
                inputs.offer(inputVersion, inputAt, speed, throttle, brake);
                if (freshThrottle) {
                    pedal.sample(sources.sample, sources.sampleAt, now);
                }
                pedal.step(dt, now);
                speedTrack.sample(speed, speedAt);
                speedTrack.step(dt, now);
                speedEveryMs = speedTrack.everyMs();
                PushedValue mainListener = telemetry.throttleListener(false);
                PushedValue drListener = telemetry.throttleListener(true);
                if (mainListener.count > 0L) {
                    mainEveryMs = mainListener.everyMs;
                    mainPushes = mainListener.count;
                }
                if (drListener.count > 0L) {
                    drEveryMs = drListener.everyMs;
                    drPushes = drListener.count;
                }
                pushFirst = sources.pushFirst;
                pollFirst = sources.pollFirst;
                pushLeadMs = sources.pushLeadMs;
                pollLeadMs = sources.pollLeadMs;
                if (!Float.isNaN(throttle) && (Float.isNaN(usedThrottle) || Math.abs(throttle - usedThrottle) >= 1f)) {
                    if (!Float.isNaN(usedThrottle) && throttleAt > 0L) {
                        long age = now - throttleAt;
                        inputAgeMs = inputAgeMs < 0f ? age : inputAgeMs + (age - inputAgeMs) * 0.1f;
                    }
                    usedThrottle = throttle;
                }
                if (push.count != lastPushCount && push.count > 0L) {
                    pushEveryMs = push.everyMs;
                    pushes = push.count;
                    lastPushCount = push.count;
                }
                if (fast.version != lastFastVersion && fast.atMs > 0L) {
                    lastFastVersion = fast.version;
                    if (lastFastAt > 0L) {
                        long gap = fast.atMs - lastFastAt;
                        fastEveryMs = fastEveryMs < 0f ? gap : fastEveryMs + (gap - fastEveryMs) * 0.05f;
                        gapMax = Math.max(gapMax, gap);
                    }
                    lastFastAt = fast.atMs;
                    if (now >= gapWindowEnd) {
                        gapWindowEnd = now + 10_000L;
                        fastGapMaxMs = gapMax;
                        gapMax = 0L;
                    }
                    float t = polled;
                    if (!Float.isNaN(t) && t != lastThrottle) {
                        if (!Float.isNaN(lastThrottle) && lastThrottleAt > 0L && fast.atMs - lastThrottleAt < 500L) {
                            long step = fast.atMs - lastThrottleAt;
                            throttleSteps++;
                            throttleEveryMs = throttleEveryMs < 0f ? step : throttleEveryMs + (step - throttleEveryMs) * 0.1f;
                        }
                        lastThrottle = t;
                        lastThrottleAt = fast.atMs;
                    }
                }
                Readings r = telemetry.readings();
                String gearText = r.text(Signal.GEAR);
                DriveSoundRules.Why asked = DriveSoundRules.decide(enabled, link, r.code(Signal.IGNITION), gearText,
                        r.code(Signal.DRIVE_MODE), standardMode, inputs.speed(), inputs.throttle(), fast.ageMs(now), inCall);
                DriveSoundRules.Why w = stopping ? DriveSoundRules.Why.OFF : asked;
                why = w;
                boolean speedTracked = !Float.isNaN(speedTrack.speed());
                in.speedKmh = speedTracked ? speedTrack.speed() : inputs.speed();
                in.accel = speedTracked ? speedTrack.accel() : inputs.accel();
                boolean tracked = !Float.isNaN(pedal.value());
                in.throttlePct = tracked ? pedal.value() : inputs.throttle();
                in.pedalRate = tracked ? pedal.rate(now) : inputs.pedalRate();
                in.braking = inputs.brake() > VirtualEngine.BRAKE_ON || Boolean.TRUE.equals(r.bool(Signal.BRAKE_PEDAL));
                in.gear = DriveSoundRules.gearOf(gearText);
                in.flow = DriveSoundRules.flowOf(r.code(Signal.POWER_FLOW));
                in.mode = rpmMode;
                in.eager = DriveSoundRules.eagerOf(r.code(Signal.DRIVE_MODE));
                in.sportPedal = DriveSoundRules.sportPedalOf(r.code(Signal.DRIVE_MODE));
                // P/N into D/R while stopped: a short rev, as if the engine picks up the drive
                if (revBlip && ignited && (prevGear == VirtualEngine.Gear.P || prevGear == VirtualEngine.Gear.N)
                        && (in.gear == VirtualEngine.Gear.D || in.gear == VirtualEngine.Gear.R) && in.speedKmh < 3f) {
                    blipUntil = now + 450L;
                }
                prevGear = in.gear;
                if (now < blipUntil) {
                    in.throttlePct = Math.max(in.throttlePct, 45f);
                    in.pedalRate = Math.max(in.pedalRate, 250f);
                    in.braking = false;
                }
                in.shiftStep = shiftSteps.getAndSet(0);
                engine.setGearbox(style.gearbox(gearbox));
                if (!ignited && w == DriveSoundRules.Why.RUNNING) {
                    engine.ignite(in);
                    ignited = true;
                    g = 1f;
                    askedToSoundMs = now - askedAt;
                    long modeAge = SignalAges.ageMs(Signal.DRIVE_MODE, now);
                    modeToSoundMs = modeAge >= 0L && modeAge < 10_000L ? modeAge : -1L;
                    boolean starting = engine.phase() == VirtualEngine.Phase.STARTING;
                    ignitedAs = starting ? "start" : String.format(Locale.US, "join at %.0f km/h", in.speedKmh);
                    BlackBox.note("模拟声浪：" + (starting ? "启动（车停着）" : "接入（行驶中 " + Math.round(in.speedKmh) + " km/h）"));
                }
                if (stopping && !windingDown && !hurry && ignited && g > 0.5f && asked.shutsDown) {
                    engine.shutdown(in);
                    windingDown = true;
                    boolean moving = in.speedKmh >= VirtualEngine.START_LEAVE_KMH;
                    endedAs = moving ? String.format(Locale.US, "shutdown at %.0f km/h", in.speedKmh) : "shutdown";
                    BlackBox.note("模拟声浪：熄火（" + (moving ? "行驶中 " + Math.round(in.speedKmh) + " km/h" : "车停着") + "）");
                } else if (stopping && !windingDown && endedAs.isEmpty()) {
                    endedAs = asked == DriveSoundRules.Why.RUNNING ? "fade (style or view changed)" : "fade (" + asked + ")";
                } else if (windingDown && hurry && !endedAs.endsWith("cut short")) {
                    endedAs += ", cut short";
                }
                if (ignited) {
                    engine.step(dt, in);
                }
                float target;
                float fadeSec;
                if (windingDown && !hurry) {
                    if (engine.done()) {
                        return false;
                    }
                    target = g;
                    fadeSec = w.fadeSec;
                } else {
                    target = w.audible && !muted ? 1f : 0f;
                    fadeSec = hurry ? HURRY_FADE_SEC : w.fadeSec;
                }
                float stepSize = dt / Math.max(fadeSec, dt);
                g = target > g ? Math.min(target, g + stepSize) : Math.max(target, g - stepSize);
                gate = g;
                if (stopping && g <= 0f) {
                    return false;
                }
                if (ignited) {
                    engine.fill(params);
                    params.gain = g * EngineVoice.dbToLin(userDb + engine.outputDb());
                    rpm = engine.rpm();
                    load = engine.load();
                    levelDb = engine.levelDb();
                    gear = engine.geared() ? engine.gearIndex() + 1 : 0;
                    pseudo = engine.pseudoGears();
                    eager = engine.eager();
                    coupled = engine.coupled();
                    driverOnly = engine.driverOnly();
                    driverHold = engine.driverHold();
                    beeps = engine.beepSerial();
                } else {
                    params.gain = 0f;
                }
                pedalRate = in.pedalRate;
                accel = in.accel;
                long t0 = System.nanoTime();
                if (voice != null) {
                    voice.renderStereo(buffer, BLOCK, params);
                }
                if (voiceOut != null && voiceOut != voice) {
                    voiceOut.renderStereo(outsideBuffer, BLOCK, params);
                }
                if (exterior != null) {
                    float follow = mediaTracked ? DriveSoundRules.mediaFollowDb(mediaIndex, mediaMax) : 0f;
                    float exteriorGain = follow == Float.NEGATIVE_INFINITY ? 0f : EngineVoice.dbToLin(exteriorDb + follow);
                    exterior.process(voiceOut == voice ? buffer : outsideBuffer, exteriorBuffer, BLOCK, exteriorGain);
                }
                if (voice != null) {
                    if (engine.beepSerial() != beepSeen) {
                        beepSeen = engine.beepSerial();
                        beep.start();
                    }
                    beep.mix(buffer, BLOCK, g * EngineVoice.dbToLin(Math.min(userDb, 0f)));
                }
                renderMs += ((System.nanoTime() - t0) / 1_000_000f - renderMs) * 0.01f;
                long w0 = System.nanoTime();
                // both outputs are fed without blocking, so neither waits for the other's clock; a dead one is dropped after the block
                boolean inDone = inside == null, outDone = outside == null, inBad = false, outBad = false;
                for (long waitFrom = System.nanoTime(); !(inDone && outDone); ) {
                    if (!inDone) {
                        int ri = inside.offer(buffer, BLOCK);
                        if (ri != 0) { inDone = true; inBad = ri < 0; }
                    }
                    if (!outDone) {
                        int ro = outside.offer(exteriorBuffer, BLOCK);
                        if (ro != 0) { outDone = true; outBad = ro < 0; }
                    }
                    if (inDone && outDone) {
                        break;
                    }
                    if (System.nanoTime() - waitFrom > 1_000_000_000L) {   // a track that takes nothing for a second is as good as dead
                        inBad |= !inDone; outBad |= !outDone;
                        break;
                    }
                    SystemClock.sleep(1L);
                }
                if (inBad) {
                    // one speaker dropping out must not silence the other: close it and try again below
                    trouble = "write failed (cabin)";
                    BlackBox.note("模拟声浪：车内喇叭写失败，稍后重开 (code " + inside.lastError() + ", open " + inside.ageMs() + " ms, other " + (outside == null ? "-" : outside.ageMs() + " ms") + ")");
                    inside.close();
                    inside = null;
                    nextReopen = wantIn && wantOut ? now + 300L : 0L;   // Z_Cam: reopen on the next block (both speakers: after 0.3 s, restarted together)
                }
                if (outBad) {
                    trouble = "write failed (exterior)";
                    BlackBox.note("模拟声浪：车外喇叭写失败，稍后重开 (code " + outside.lastError() + ", open " + outside.ageMs() + " ms, other " + (inside == null ? "-" : inside.ageMs() + " ms") + ")");
                    outside.close();
                    outside = null;
                    nextReopen = wantIn && wantOut ? now + 300L : 0L;
                }
                if ((wantIn && inside == null || wantOut && outside == null) && now >= nextReopen) {
                    nextReopen = now + 2_000L;
                    if (wantIn && wantOut) {
                        // Z_Cam: with both speakers, one reopening used to knock the other out (it then reopened and knocked the first out ...).
                        // So restart both in one go: build both tracks first, then start them together.
                        if (inside != null) { inside.close(); inside = null; }
                        if (outside != null) { outside.close(); outside = null; }
                        SoundOutput a = null;
                        SoundOutput b = null;
                        try {
                            a = SoundOutput.open(app, CarSpeakers.CABIN_MEDIA, startMs, android.media.AudioAttributes.USAGE_MEDIA, false);
                            b = SoundOutput.open(app, CarSpeakers.exteriorAddress(exteriorRoute), startMs, extUsage(), false);
                            a.start();
                            b.start();
                            inside = a; outside = b;
                            reopened.add(a); reopened.add(b);
                            BlackBox.note("模拟声浪：车内、车外喇叭一起重新打开");
                        } catch (RuntimeException ignored) {
                            if (a != null && inside != a) { a.close(); }
                            if (b != null && outside != b) { b.close(); }
                            // try again in 2 s
                        }
                    } else {
                        if (wantIn && inside == null) {
                            try {
                                inside = SoundOutput.open(app, CarSpeakers.CABIN_MEDIA, startMs);
                                reopened.add(inside);
                                BlackBox.note("模拟声浪：车内喇叭重新打开");
                            } catch (RuntimeException ignored) {
                                // try again in 2 s
                            }
                        }
                        if (wantOut && outside == null) {
                            try {
                                outside = SoundOutput.open(app, CarSpeakers.exteriorAddress(exteriorRoute), startMs, extUsage());
                                reopened.add(outside);
                                BlackBox.note("模拟声浪：车外喇叭重新打开");
                            } catch (RuntimeException ignored) {
                                // try again in 2 s
                            }
                        }
                    }
                }
                if (inside == null && outside == null) {
                    SystemClock.sleep(5L);   // nothing to block on while both are away
                }
                long w1 = System.nanoTime();
                blocks++;
                blocksSinceWake++;
                if (w1 - w0 > 1_000_000L) {
                    if (lastWakeNs > 0L) {
                        float every = (w1 - lastWakeNs) / 1_000_000f;
                        mixerEveryMs = mixerEveryMs < 0f ? every : mixerEveryMs + (every - mixerEveryMs) * 0.05f;
                        burstBlocks = burstBlocks < 0f ? blocksSinceWake : burstBlocks + (blocksSinceWake - burstBlocks) * 0.05f;
                    }
                    lastWakeNs = w1;
                    blocksSinceWake = 0;
                }
                if (now >= nextWatch) {
                    nextWatch = now + WATCH_MS;
                    boolean grown = inside != null && inside.watch();
                    grown |= outside != null && outside.watch();
                    output = describeOutputs(inside, outside);
                    SoundOutput lat = inside != null ? inside : outside;
                    if (lat != null) {
                        outputLatencyMs = lat.latencyMs();
                    }
                    if (grown) {
                        BlackBox.note("模拟声浪：欠载，缓冲加大（" + output + "）");
                    }
                }
            }
        }

        String describe() {
            return String.format(Locale.US,
                    "run: %s style=%s view=%s speakers=%s why=%s ignited=%s ended=%s rpm=%.0f gear=%d%s eager=%.1f coupled=%.2f "
                            + "buttons=%s beeps=%d load=%.2f level=%.1f dB gate=%.2f "
                            + "pedal=%.0f %%/s accel=%.2f m/s2 blocks=%d render=%.3f ms per %.1f ms block%s%n  output: %s%n"
                            + "  exterior: route %s, volume %+.0f dB, media volume %s%n"
                            + "  mixer: takes a period every %s (%s blocks each)%n"
                            + "  start: drive mode changed %s before the run was asked for, output opened in %s, "
                            + "first sound %s after the ask (%s after the mode change)%n"
                            + "  inputs: fast reads every %s (longest gap in 10 s %s), throttle changed every %s (%d changes), "
                            + "speed changed every %s%n"
                            + "  throttle push: every %s (%d pushes), a new value came first by push %d times (ahead by %s), "
                            + "by the fast read %d times (ahead by %s); fastest listener every %s (%d), RATE_DR listener every %s (%d)%n"
                            + "  pedal to speaker (the part measurable here): new throttle used %s after it arrived "
                            + "+ output %s (HAL timestamp; on the car's head unit it reads about 5.1 s whatever the buffer, so it is "
                            + "not real; + the head unit's own signal and amplifier delays, not measurable here)",
                    state, style.key, cabin ? "cabin" : "outside", outputMode, why, ignitedAs.isEmpty() ? "-" : ignitedAs,
                    endedAs.isEmpty() ? "-" : endedAs, rpm, gear,
                    pseudo ? " (pseudo)" : "", eager, coupled,
                    driverOnly ? "only" : String.format(Locale.US, "%.2f", driverHold), beeps, load, levelDb, gate, pedalRate, accel,
                    blocks, renderMs, BLOCK * 1000f / SoundOutput.RATE,
                    trouble.isEmpty() ? "" : " trouble=" + trouble, output.isEmpty() ? "-" : output,
                    exteriorRoute, DriveSound.this.exteriorDb, mediaText(),
                    ms(mixerEveryMs), burstBlocks < 0f ? "?" : String.format(Locale.US, "%.1f", burstBlocks),
                    ms(modeAgeWhenAsked), ms(openMs), ms(askedToSoundMs), ms(modeToSoundMs),
                    ms(fastEveryMs), ms(fastGapMaxMs), ms(throttleEveryMs), throttleSteps, ms(speedEveryMs),
                    ms(pushEveryMs), pushes, pushFirst, ms(pushLeadMs), pollFirst, ms(pollLeadMs),
                    ms(mainEveryMs), mainPushes, ms(drEveryMs), drPushes,
                    ms(inputAgeMs), ms(outputLatencyMs));
        }

        private String mediaText() {
            if (mediaMax < 0) {
                return "not read";
            }
            float follow = DriveSoundRules.mediaFollowDb(mediaIndex, mediaMax);
            return String.format(Locale.US, "%d / %d (first %d, fixed=%s, %s)", mediaIndex, mediaMax, mediaFirst, mediaFixed,
                    mediaTracked ? (follow == Float.NEGATIVE_INFINITY ? "followed: muted"
                            : String.format(Locale.US, "followed: %.0f dB", follow)) : "never changed, not followed");
        }

        private String ms(float value) {
            return value < 0f ? "?" : String.format(Locale.US, "%.0f ms", value);
        }
    }
}
