/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

/**
 *
 * <ul>
 * </ul>
 *
 *
 */
final class JetVoice implements Voice {

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

    static final float[] LINE_RATIO = {0.41f, 0.45f, 0.58f, 0.63f, 0.75f, 1f, 1.07f, 1.13f, 1.18f, 1.36f, 1.42f, 1.58f, 1.68f, 1.89f};
    static final float[] LINE_FULL_DB = {-9f, -14f, -18f, -16f, -15f, 0f, -20f, -24f, -18f, -22f, -17f, -28f, -29f, -27f};
    static final float[] LINE_IDLE_DB = {-24f, -30f, -27f, -25f, -28f, -1.5f, -35f, -35f, -32f, -35f, -5f, -35f, -35f, -35f};
    static final int[] HARMONIC = {2, 3, 4, 6};
    static final float[] HARMONIC_DB = {-30f, -33f, -32f, -34f};
    static final float ENGINE_OFFSET = 0.25f;
    static final float WANDER = 0.003f;
    static final float WANDER_HZ = 1f;

    static final float FLOOR_DB = -5f;
    static final float FLOOR_TOP = 1.1f;
    static final float FLOOR_Q = 3f;
    static final float FLOOR_WIDTH_HZ = 350f;
    static final float FLOOR_LOW_HZ = 100f;
    static final float FLOOR_OWN = 0.9f;
    static final float FLOOR_COMMON = 0.44f;
    static final float WHINE_DB = -6f;
    static final float ENGINE_PAN = 0.3f;

    static final float LOW_IDLE_HZ = 88f;
    static final float LOW_FULL_HZ = 110f;
    static final float LOW_DB = -5f;
    static final float LOW_IDLE_DB = -1f;
    static final float LOW_WOBBLE = 0.012f;
    static final float WOBBLE_HZ = 8f;
    static final float LOW_SECOND_DB = -10f;
    static final float SUB_DB = -23f;

    static final float WHOOMP_SEC = 0.12f;
    static final float WHOOMP_DB = 0f;
    static final float WHOOMP_RELEASE = 0.63f;
    static final float WHOOMP_LAG_SEC = 0.3f;
    static final float WHOOMP_GAIN = 1.5f;

    static final float BOOM_SEC = 0.06f;
    static final float BOOM_DB = -2f;
    static final float BOOM_LOWPASS_HZ = 2000f;
    static final float THUMP_HZ = 45f;
    static final float THUMP_SEC = 0.12f;
    static final float THUMP_DB = -4f;
    static final float SHIFT_WHOOMP_UP = 1f;
    static final float SHIFT_WHOOMP_DOWN = 0.4f;
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
    private static final int CONTROL = 16;
    private static final float SQRT2 = 1.4142135f;

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float driveLagK;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float wanderK;
    private final float wanderNorm;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float gainK;
    private final float[] fanIdleGain = new float[LINES];
    private final float[] fanLnSpan = new float[LINES];
    private final float lowGain;
    private final float lowLnSpan;
    private final float secondGain;
    private final float subGain;
    private final float whoompGain;
    private final int mainLine;
    private final float[] harmonicGain = new float[HARMONIC.length];
    private final float floorGain;
    private final float[] pinkState = new float[9];
    private final Biquad[] floorHighpass = new Biquad[2];
    private final Biquad[] floorPeak = new Biquad[2];
    private final Biquad[] floorLowpass = new Biquad[2];

    private float spool;
    private boolean started;
    private int controlLeft;
    private final double[] linePhase = new double[2 * LINES];
    private final float[] wanderNoise = new float[LINES];
    private final float[] wanderSmooth = new float[LINES];
    private final float[] lineMul = new float[LINES];
    private final float[] fanGain = new float[LINES];
    private double lowPhase;
    private float lowWobble;
    private float lowInc;
    private double subPhase;
    private float thrustNow;
    private float startNow = -1f;
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

    JetVoice(float sampleRate) {
        this.rate = sampleRate;
        lagUpK = 1f - (float) Math.exp(-1.0 / (LAG_UP_SEC * sampleRate));
        lagDownK = 1f - (float) Math.exp(-1.0 / (LAG_DOWN_SEC * sampleRate));
        driveLagK = 1f - (float) Math.exp(-1.0 / (DRIVE_LAG_SEC * sampleRate));
        wobbleK = 1f - (float) Math.exp(-2.0 * Math.PI * WOBBLE_HZ * CONTROL / sampleRate);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        wanderK = 1f - (float) Math.exp(-2.0 * Math.PI * WANDER_HZ * CONTROL / sampleRate);
        wanderNorm = twoPoleNorm(wanderK);
        java.util.Arrays.fill(lineMul, 1f);
        for (int k = 0; k < LINES; k++) {
            linePhase[LINES + k] = (ENGINE_OFFSET + 0.618034 * k) % 1.0;
        }
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < LINES; k++) {
            fanIdleGain[k] = EngineVoice.dbToLin(LINE_IDLE_DB[k] + WHINE_DB);
            fanLnSpan[k] = (LINE_FULL_DB[k] - LINE_IDLE_DB[k]) * dbToLn;
        }
        lowGain = EngineVoice.dbToLin(LOW_DB);
        lowLnSpan = (float) Math.log(LOW_FULL_HZ / LOW_IDLE_HZ);
        secondGain = EngineVoice.dbToLin(LOW_SECOND_DB);
        subGain = EngineVoice.dbToLin(SUB_DB);
        whoompGain = EngineVoice.dbToLin(WHOOMP_DB);
        int main = 0;
        for (int k = 0; k < LINES; k++) {
            if (LINE_RATIO[k] == 1f) {
                main = k;
            }
        }
        mainLine = main;
        for (int h = 0; h < HARMONIC.length; h++) {
            harmonicGain[h] = EngineVoice.dbToLin(HARMONIC_DB[h]);
        }
        floorGain = EngineVoice.dbToLin(FLOOR_DB);
        for (int c = 0; c < 2; c++) {
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
            floorHighpass[c] = Biquad.highpass(FLOOR_LOW_HZ, 0.7071f, sampleRate);
            floorPeak[c] = Biquad.lowpass(IDLE_HZ * FLOOR_TOP, FLOOR_Q, sampleRate);
            floorLowpass[c] = Biquad.lowpass(IDLE_HZ * FLOOR_TOP, 0.7071f, sampleRate);
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
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;
        if (!started) {
            spool = target;
            thrustNow = thrust;
            startNow = start;
            started = true;
        }
        float lagK = params.drive || rpm < IDLE_RPM ? driveLagK : target > spool ? lagUpK : lagDownK;

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
        float whooshGain = EngineVoice.dbToLin(WHOOSH_DB);
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
            thrustNow += (thrust - thrustNow) * gainK;
            startNow += (start - startNow) * gainK;
            if (--controlLeft < 0) {
                controlLeft = CONTROL - 1;
                control(s);
            }
            float inc = mainHz(spool) / rate;

            float fanLevel = (0.7f + 0.3f * thrustNow) * startNow;
            float e0 = lines(0, inc) * fanLevel;
            float e1 = lines(1, inc) * fanLevel;

            float common = pink(2, white());
            float floorAmp = floorGain * fanGain[mainLine] * fanLevel;
            float floorL = floorLowpass[0].process(floorPeak[0].process(floorHighpass[0].process(
                    FLOOR_OWN * pink(0, white()) + FLOOR_COMMON * common))) * floorAmp;
            float floorR = floorLowpass[1].process(floorPeak[1].process(floorHighpass[1].process(
                    FLOOR_OWN * pink(1, white()) + FLOOR_COMMON * common))) * floorAmp;

            double lp = lowPhase + lowInc;
            if (lp >= 1.0) {
                lp -= 1.0;
            }
            lowPhase = lp;
            double sp = subPhase + 0.5f * lowInc;
            if (sp >= 1.0) {
                sp -= 1.0;
            }
            subPhase = sp;
            whoomp *= whoompDecay;
            float lowAmp = lowGain * (lowIdle + (1f - lowIdle) * s) * startNow;
            float low = lowAmp * (EngineVoice.sin(lp) + secondGain * (0.3f + 0.7f * s) * EngineVoice.sin(2.0 * lp))
                    + (subGain * thrustNow * (0.4f + 0.6f * s) + whoompGain * whoomp) * startNow * EngineVoice.sin(sp);

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

            float l = engineNear * e0 + engineFar * e1 + floorL + low + PulseVoice.CENTER * middle;
            float r = engineFar * e0 + engineNear * e1 + floorR + low + PulseVoice.CENTER * middle;
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
            wanderNoise[k] += wanderK * (white() - wanderNoise[k]);
            wanderSmooth[k] += wanderK * (wanderNoise[k] - wanderSmooth[k]);
            lineMul[k] = 1f + WANDER * wanderNorm * wanderSmooth[k];
        }
        float top = Math.min(0.45f * rate, mainHz(spool) * FLOOR_TOP);
        for (int c = 0; c < 2; c++) {
            floorPeak[c].setLowpass(top, Math.max(0.7071f, Math.min(FLOOR_Q, top / FLOOR_WIDTH_HZ)), rate);
            floorLowpass[c].setLowpass(top, 0.7071f, rate);
        }
        lowWobble += wobbleK * (white() - lowWobble);
        lowInc = LOW_IDLE_HZ * (float) Math.exp(s * lowLnSpan) * (1f + LOW_WOBBLE * wobbleNorm * lowWobble) / rate;
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
        float step = inc;
        float sum = 0f;
        for (int k = 0; k < LINES; k++) {
            int o = e * LINES + k;
            double ph = linePhase[o] + step * LINE_RATIO[k] * lineMul[k];
            if (ph >= 1.0) {
                ph -= 1.0;
            }
            linePhase[o] = ph;
            sum += fanGain[k] * EngineVoice.sin(ph);
            if (k == mainLine) {
                float hs = 0f;
                for (int h = 0; h < HARMONIC.length; h++) {
                    hs += harmonicGain[h] * EngineVoice.sin(HARMONIC[h] * ph);
                }
                sum += fanGain[k] * hs;
            }
        }
        return sum;
    }

    float mainHz() {
        return mainHz(spool);
    }

    private float pink(int i, float w) {
        int o = 3 * i;
        pinkState[o] = 0.99765f * pinkState[o] + w * 0.0990460f;
        pinkState[o + 1] = 0.96300f * pinkState[o + 1] + w * 0.2965164f;
        pinkState[o + 2] = 0.57000f * pinkState[o + 2] + w * 1.0526913f;
        return pinkState[o] + pinkState[o + 1] + pinkState[o + 2] + w * 0.1848f;
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
