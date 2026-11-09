/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

/**
 *
 *
 * <ul>
 * </ul>
 *
 */
final class JetOldVoice implements Voice {

    static final float IDLE_RPM = 900f;
    static final float FULL_RPM = 6200f;
    static final float MAIN_ORDER = 10f;
    static final float IDLE_HZ = MAIN_ORDER * IDLE_RPM / 60f;
    static final float TOP_HZ = MAIN_ORDER * FULL_RPM / 60f;
    static final float MIN_HZ = 20f;
    static final float FREE_CURVE = 2f;
    static final float LAG_UP_SEC = 0.14f;
    static final float LAG_DOWN_SEC = 0.7f;
    static final float DRIVE_LAG_SEC = 0.04f;

    static final float[] LINE_RATIO = {0.36f, 0.42f, 0.49f, 0.56f, 0.64f, 0.69f, 0.74f, 0.81f, 0.88f, 0.95f, 1f,
        1.07f, 1.17f, 1.28f, 1.41f, 1.55f, 1.68f, 1.86f};
    static final float[] LINE_FULL_DB = {-16f, -10f, -14f, -12f, -9f, -7f, -8f, -6f, -9f, -7f, 0f,
        -8f, -9f, -11f, -13f, -12f, -15f, -18f};
    static final float[] LINE_IDLE_DB = {-31.5f, -25.5f, -29.5f, -27.5f, -23.5f, -21.5f, -22.5f, -19.5f, -15.5f, -9.5f, -1.5f,
        -9.5f, -13.5f, -16.5f, -19.5f, -19.5f, -23.5f, -27.5f};
    static final float ENGINE_SPLIT = 0.0007f;
    static final float LINE_JITTER = 0.13f;
    static final float LINE_JITTER_HZ = 35f;
    static final float PAIR = 0.013f;
    static final float LOW_WOBBLE = 0.012f;
    static final float WOBBLE_HZ = 8f;
    static final float WHINE_DB = -6f;
    static final float ENGINE_PAN = 0.3f;

    static final float[] LOW_IDLE_HZ = {88f, 76f, 165f};
    static final float[] LOW_FULL_HZ = {140f, 120f, 215f};
    static final float[] LOW_DB = {-5f, -14f, -17f};
    static final float LOW_IDLE_DB = -1f;

    static final float ROAR_HZ = 90f;
    static final float ROAR_Q = 0.7f;
    static final float ROAR_LOWPASS_HZ = 250f;
    static final float ROAR_FULL_DB = -10f;
    static final float ROAR_IDLE_DB = -22f;
    static final float ROAR_GAIN = 3f;
    static final float ROAR_COMMON = 0.8f;
    static final float ROAR_OWN = 0.6f;
    static final float WHOOMP_SEC = 0.12f;
    static final float WHOOMP_DB = 20f;
    static final float WHOOMP_RELEASE = 0.63f;
    static final float WHOOMP_LAG_SEC = 0.3f;
    static final float WHOOMP_GAIN = 1.5f;

    static final float BOOM_SEC = 0.06f;
    static final float BOOM_DB = -2f;
    static final float BOOM_LOWPASS_HZ = 2000f;
    static final float THUMP_HZ = 45f;
    static final float THUMP_SEC = 0.12f;
    static final float THUMP_DB = -4f;
    static final float SHIFT_WHOOMP_UP = 0.35f;
    static final float SHIFT_WHOOMP_DOWN = 0.2f;
    static final float WHOOSH_HZ = 900f;
    static final float WHOOSH_Q = 1f;
    static final float WHOOSH_DB = -22f;
    static final float WHOOSH_SEC = 0.5f;
    static final float WHOOSH_DROP = 0.15f;
    static final float WHOOSH_FROM = 0.3f;
    static final float CUT_THRUST = 0.7f;
    static final float LEVEL_POW = 0.12f;
    static final float NORM = 0.32f;

    private static final int LINES = LINE_RATIO.length;
    private static final int LOWS = LOW_DB.length;
    private static final int CONTROL = 16;
    private static final float LN2 = 0.6931472f;
    private static final float SQRT2 = 1.4142135f;
    private static final float[] MEMBER = {1f - 0.5f * PAIR, 1f + 0.5f * PAIR};
    private static final float[] ENGINE = {1f - ENGINE_SPLIT, 1f + ENGINE_SPLIT};

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float driveLagK;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float jitterK;
    private final float jitterNorm;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float gainK;
    private final float[] fanIdleGain = new float[LINES];
    private final float[] fanLnSpan = new float[LINES];
    private final float[] lowGain = new float[LOWS];
    private final float[] lowLnSpan = new float[LOWS];

    private float spool;
    private boolean started;
    private int controlLeft;
    private final double[] linePhase = new double[2 * LINES];
    private final float[] jitter = new float[2 * LINES];
    private final float[] jitterStep = new float[2 * LINES];
    private final float[] jitterNoise = new float[2 * LINES];
    private final float[] jitterSmooth = new float[2 * LINES];
    private final float[] fanGain = new float[LINES];
    private final double[] lowPhase = new double[2 * LOWS * 2];
    private final float[] lowWobble = new float[2 * LOWS * 2];
    private final float[] lowInc = new float[2 * LOWS * 2];
    private final Biquad[] roarBand = new Biquad[3];
    private final Biquad[] roarLowpass = new Biquad[3];
    private final Biquad whooshBand;
    private final Biquad boomLowpass;
    private float whoomp;
    private float whoosh;
    private float loadPrev;
    private float loadSlow;
    private int boomPos = -1;
    private final int boomLength;
    private float boomAmp;
    private float thump;
    private double thumpPhase;
    private boolean serialSeen;
    private int lastSerial;
    private final float engineNear;
    private final float engineFar;

    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private float levelNow;
    private float gainNow;
    private int noise = 0x6D2B79F5;

    JetOldVoice(float sampleRate) {
        this.rate = sampleRate;
        lagUpK = 1f - (float) Math.exp(-1.0 / (LAG_UP_SEC * sampleRate));
        lagDownK = 1f - (float) Math.exp(-1.0 / (LAG_DOWN_SEC * sampleRate));
        driveLagK = 1f - (float) Math.exp(-1.0 / (DRIVE_LAG_SEC * sampleRate));
        wobbleK = 1f - (float) Math.exp(-2.0 * Math.PI * WOBBLE_HZ * CONTROL / sampleRate);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        jitterK = 1f - (float) Math.exp(-2.0 * Math.PI * LINE_JITTER_HZ * CONTROL / sampleRate);
        jitterNorm = twoPoleNorm(jitterK);
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < LINES; k++) {
            fanIdleGain[k] = EngineVoice.dbToLin(LINE_IDLE_DB[k] + WHINE_DB);
            fanLnSpan[k] = (LINE_FULL_DB[k] - LINE_IDLE_DB[k]) * dbToLn;
        }
        for (int k = 0; k < LOWS; k++) {
            lowGain[k] = 0.5f * EngineVoice.dbToLin(LOW_DB[k]);
            lowLnSpan[k] = (float) Math.log(LOW_FULL_HZ[k] / LOW_IDLE_HZ[k]);
        }
        for (int c = 0; c < 3; c++) {
            roarBand[c] = Biquad.bandpass(ROAR_HZ, ROAR_Q, sampleRate);
            roarLowpass[c] = Biquad.lowpass(ROAR_LOWPASS_HZ, 0.7071f, sampleRate);
        }
        for (int c = 0; c < 2; c++) {
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        whooshBand = Biquad.bandpass(WHOOSH_HZ, WHOOSH_Q, sampleRate);
        boomLowpass = Biquad.lowpass(BOOM_LOWPASS_HZ, 0.7071f, sampleRate);
        boomLength = Math.max(1, Math.round(BOOM_SEC * sampleRate));
        limiter = new Limiter(sampleRate);
        engineNear = SQRT2 * PulseVoice.panLeft(-ENGINE_PAN);
        engineFar = SQRT2 * PulseVoice.panRight(-ENGINE_PAN);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            limiter.reset();
            return;
        }
        float dt = frames / rate;
        float rpm = params.rpm > 0f ? params.rpm : 0f;
        float load = VirtualEngine.clamp01(params.load);
        float thrust = load * (1f - CUT_THRUST * VirtualEngine.clamp01(params.cut));
        float target = spoolTarget(rpm, load, params.drive);
        if (!started) {
            spool = target;
            started = true;
        }
        float lagK = params.drive || rpm < IDLE_RPM ? driveLagK : target > spool ? lagUpK : lagDownK;
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;

        loadSlow += (load - loadSlow) * VirtualEngine.follow(dt, WHOOMP_LAG_SEC);
        float dev = load - loadSlow;
        float hit = dev > 0f ? VirtualEngine.clamp01(dev * WHOOMP_GAIN)
                : VirtualEngine.clamp01(-dev * WHOOMP_GAIN) * WHOOMP_RELEASE;
        whoomp = Math.max(whoomp, hit);
        float nowSpool = VirtualEngine.clamp01(spool);
        if (load - loadPrev < -WHOOSH_DROP && nowSpool > WHOOSH_FROM) {
            whoosh = Math.max(whoosh, nowSpool);
        }
        loadPrev = load;

        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            float amp = 0.3f + 0.7f * load;
            if (params.shiftUp) {
                boomPos = 0;
                boomAmp = EngineVoice.dbToLin(BOOM_DB) * amp;
                thump = EngineVoice.dbToLin(THUMP_DB) * amp;
                thumpPhase = 0.0;
            }
            whoomp = Math.max(whoomp, (params.shiftUp ? SHIFT_WHOOMP_UP : SHIFT_WHOOMP_DOWN) * amp);
        }

        float lowIdle = EngineVoice.dbToLin(LOW_IDLE_DB);
        float roarFull = EngineVoice.dbToLin(ROAR_FULL_DB);
        float roarIdle = EngineVoice.dbToLin(ROAR_IDLE_DB);
        float burstGain = EngineVoice.dbToLin(WHOOMP_DB) * ROAR_GAIN * start;
        float whooshGain = EngineVoice.dbToLin(WHOOSH_DB);
        float fanLevel = (0.7f + 0.3f * thrust) * start;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * NORM;
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }

        for (int i = 0; i < frames; i++) {
            spool += (target - spool) * lagK;
            float s = VirtualEngine.clamp01(spool);
            if (--controlLeft < 0) {
                controlLeft = CONTROL - 1;
                control(s);
            }
            float inc = mainHz(spool) / rate;

            float e0 = lines(0, inc) * fanLevel;
            float e1 = lines(1, inc) * fanLevel;

            float l0 = 0f;
            float l1 = 0f;
            for (int k = 0; k < LOWS; k++) {
                int o = 2 * k;
                int o1 = o + 2 * LOWS;
                l0 += lowGain[k] * (low(o) + low(o + 1));
                l1 += lowGain[k] * (low(o1) + low(o1 + 1));
            }
            float lowLevel = (lowIdle + (1f - lowIdle) * s) * start;
            l0 *= lowLevel;
            l1 *= lowLevel;

            whoomp *= whoompDecay;
            float roarLevel = (roarIdle + (roarFull - roarIdle) * thrust * (0.4f + 0.6f * s)) * start * ROAR_GAIN;
            float common = roarLowpass[2].process(roarBand[2].process(white()));
            float burst = common * burstGain * whoomp;
            float roarL = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[0].process(roarBand[0].process(white()))) * roarLevel
                    + burst;
            float roarR = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[1].process(roarBand[1].process(white()))) * roarLevel
                    + burst;

            whoosh *= whooshDecay;
            float middle = whooshBand.process(white()) * whooshGain * whoosh;
            float n = 0f;
            if (boomPos >= 0) {
                n = boomAmp * (1f - 2f * boomPos / (float) boomLength);
                boomPos++;
                if (boomPos >= boomLength) {
                    boomPos = -1;
                }
            }
            middle += boomLowpass.process(n);
            if (thump > 1e-7f) {
                thump *= thumpDecay;
                thumpPhase += THUMP_HZ / rate;
                if (thumpPhase >= 1.0) {
                    thumpPhase -= 1.0;
                }
                middle += thump * EngineVoice.sin(thumpPhase);
            }

            float a = e0 + l0;
            float b = e1 + l1;
            float l = engineNear * a + engineFar * b + roarL + PulseVoice.CENTER * middle;
            float r = engineFar * a + engineNear * b + roarR + PulseVoice.CENTER * middle;
            levelNow += (levelTarget - levelNow) * gainK;
            l *= levelNow;
            r *= levelNow;
            if (toneOn) {
                l = tone[0].process(l);
                r = tone[1].process(r);
            }
            l = highpass[0].process(l);
            r = highpass[1].process(r);
            gainNow += (params.gain - gainNow) * gainK;
            limiter.push(l * gainNow, r * gainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
        }
    }

    private void control(float s) {
        for (int k = 0; k < LINES; k++) {
            fanGain[k] = fanIdleGain[k] * (float) Math.exp(s * fanLnSpan[k]);
            for (int e = 0; e < 2; e++) {
                int o = e * LINES + k;
                jitterNoise[o] += jitterK * (white() - jitterNoise[o]);
                jitterSmooth[o] += jitterK * (jitterNoise[o] - jitterSmooth[o]);
                jitterStep[o] = (LINE_JITTER * jitterNorm * jitterSmooth[o] - jitter[o]) / CONTROL;
            }
        }
        for (int k = 0; k < LOWS; k++) {
            float hz = LOW_IDLE_HZ[k] * (float) Math.exp(s * lowLnSpan[k]);
            for (int e = 0; e < 2; e++) {
                for (int m = 0; m < 2; m++) {
                    int o = e * 2 * LOWS + 2 * k + m;
                    lowWobble[o] += wobbleK * (white() - lowWobble[o]);
                    lowInc[o] = hz * MEMBER[m] * (1f + LOW_WOBBLE * wobbleNorm * lowWobble[o]) / rate;
                }
            }
        }
    }

    private static float spoolTarget(float rpm, float load, boolean drive) {
        if (rpm < IDLE_RPM || drive) {
            return Math.min(1.2f, (rpm - IDLE_RPM) / (FULL_RPM - IDLE_RPM));
        }
        return (float) Math.pow(load, FREE_CURVE);
    }

    private static float mainHz(float s) {
        return Math.max(MIN_HZ, MAIN_ORDER * (IDLE_RPM + (FULL_RPM - IDLE_RPM) * s) / 60f);
    }

    private float lines(int e, float inc) {
        float step = inc * ENGINE[e];
        float sum = 0f;
        for (int k = 0; k < LINES; k++) {
            int o = e * LINES + k;
            double ph = linePhase[o] + step * LINE_RATIO[k];
            if (ph >= 1.0) {
                ph -= 1.0;
            }
            linePhase[o] = ph;
            jitter[o] += jitterStep[o];
            sum += fanGain[k] * EngineVoice.sin(ph + jitter[o]);
        }
        return sum;
    }

    private float low(int o) {
        double ph = lowPhase[o] + lowInc[o];
        if (ph >= 1.0) {
            ph -= 1.0;
        }
        lowPhase[o] = ph;
        return EngineVoice.sin(ph);
    }

    float mainHz() {
        return mainHz(spool);
    }

    private static float twoPoleNorm(float k) {
        double r = (1.0 - k) * (1.0 - k);
        return (float) (1.0 / Math.sqrt(Math.pow(k, 4) * (1.0 + r) / Math.pow(1.0 - r, 3)));
    }

    private float perSample(float tauSec) {
        return (float) Math.exp(-1.0 / (Math.max(tauSec, 1e-6f) * rate));
    }

    private float white() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1.7320508f / 2147483648f);
    }
}
