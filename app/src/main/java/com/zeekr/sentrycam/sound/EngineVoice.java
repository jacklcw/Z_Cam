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
 */
final class EngineVoice implements Voice {

    static final float CEILING = 0.891f;
    static final float BAND_FADE_HZ = 4500f;
    static final float BAND_TOP_HZ = 6000f;
    static final float HIGHPASS_HZ = 30f;
    static final float STARTER_DB = -16f;
    static final float STARTER_WHINE_HZ = 1050f;
    static final float WHINE_DB = -20f;
    static final float UNFIRED_LEVEL = 0.25f;
    static final float BURBLE_HALF_ORDER_DB = 8f;
    static final float BURBLE_DB = -10f;
    static final float BURBLE_RATE_HZ = 22f;
    static final float EXHAUST_LOWPASS_HZ = 300f;
    static final float TONE_BYPASS_HZ = 16000f;
    static final float AMP_SMOOTH_SEC = 0.005f;
    static final float GAIN_SMOOTH_SEC = 0.010f;
    static final float INTAKE_NOISE_NORM = 4f;
    static final float EXHAUST_NOISE_NORM = 8f;

    private static final int TABLE = 4096;
    private static final float[] SINE = sineTable();

    private final SoundProfile p;
    private final float rate;
    private final int n;
    private final float[] baseAmp;
    private final float[] orderPhase;
    private final boolean[] halfOrder;
    private final boolean[] highOrder;
    private final float[] amp;
    private final float[] ampNow;
    private final float intakeUnit;
    private final float exhaustUnit;
    private final float ampK;
    private final float gainK;

    private double crank;
    private float rpmNow = -1f;
    private float levelNow;
    private float gainNow;
    private float intakeNow;
    private float exhaustNow;
    private float burbleNow;
    private float starterNow;
    private float whineNow;
    private double starterPhase;
    private double whinePhase;
    private int noise = 0x2545F491;
    private float in1;
    private float in2;
    private float ex1;
    private float ex2;
    private final float exA1;
    private final float exA2;
    private final float exA3;
    private float gate;
    private float gateTarget;
    private int gateLeft;
    private final int gateSamples;
    private final float gateK;
    private final Biquad[] resonances;
    private final Biquad highpass;
    private final Biquad tone = new Biquad();
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private float[] mono = new float[0];

    EngineVoice(SoundProfile profile, float sampleRate) {
        this.p = profile;
        this.rate = sampleRate;
        this.n = profile.orders.length;
        baseAmp = new float[n];
        orderPhase = new float[n];
        halfOrder = new boolean[n];
        highOrder = new boolean[n];
        amp = new float[n];
        ampNow = new float[n];
        float peak = 0f;
        for (int k = 0; k < n; k++) {
            peak += dbToLin(profile.orderDb[k] + profile.orderLoadDb[k]);
        }
        float unit = peak > 0f ? 1f / peak : 1f;
        for (int k = 0; k < n; k++) {
            baseAmp[k] = dbToLin(profile.orderDb[k]) * unit;
            orderPhase[k] = (float) ((k * 0.6180339887) % 1.0);
            float order = profile.orders[k];
            halfOrder[k] = Math.abs(order - Math.floor(order) - 0.5) < 1e-3;
            highOrder[k] = order >= 6f;
        }
        intakeUnit = dbToLin(profile.intakeDb) * unit * INTAKE_NOISE_NORM;
        exhaustUnit = dbToLin(profile.exhaustDb) * unit * EXHAUST_NOISE_NORM;
        ampK = 1f - (float) Math.exp(-1.0 / (AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (GAIN_SMOOTH_SEC * sampleRate));
        float g = (float) Math.tan(Math.PI * EXHAUST_LOWPASS_HZ / sampleRate);
        float k = 1.4142135f;
        exA1 = 1f / (1f + g * (g + k));
        exA2 = g * exA1;
        exA3 = g * exA2;
        gateSamples = Math.max(1, Math.round(sampleRate / BURBLE_RATE_HZ));
        gateK = 1f - (float) Math.exp(-3.0 / gateSamples);
        resonances = new Biquad[profile.resonanceHz.length];
        for (int i = 0; i < resonances.length; i++) {
            resonances[i] = Biquad.peaking(profile.resonanceHz[i], profile.resonanceDb[i], profile.resonanceQ[i], sampleRate);
        }
        highpass = Biquad.highpass(HIGHPASS_HZ, 0.7071f, sampleRate);
        limiter = new Limiter(sampleRate);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (mono.length < frames) {
            mono = new float[frames];
        }
        render(mono, 0, frames, params);
        for (int i = 0; i < frames; i++) {
            lr[2 * i] = mono[i];
            lr[2 * i + 1] = mono[i];
        }
    }

    void render(float[] out, int offset, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        float rpmTarget = params.rpm > 0f ? params.rpm : 0f;
        if (rpmNow < 0f) {
            rpmNow = rpmTarget;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            for (int i = 0; i < frames; i++) {
                out[offset + i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            limiter.reset();
            return;
        }
        float load = VirtualEngine.clamp01(params.load);
        for (int k = 0; k < n; k++) {
            float order = p.orders[k];
            float db = p.orderLoadDb[k] * load;
            if (highOrder[k]) {
                db += params.brightDb;
            }
            if (halfOrder[k]) {
                db += BURBLE_HALF_ORDER_DB * params.burble;
            }
            amp[k] = baseAmp[k] * dbToLin(db) * bandWeight(order * rpmTarget / 60f);
        }
        float center = VirtualEngine.clamp(p.intakeCenterOrder * rpmTarget / 60f, 200f, 6000f);
        float ig = (float) Math.tan(Math.PI * center / rate);
        float ik = 1f / p.intakeQ;
        float inA1 = 1f / (1f + ig * (ig + ik));
        float inA2 = ig * inA1;
        float inA3 = ig * inA2;
        float intakeTarget = intakeUnit * (float) Math.pow(load, 1.5) * dbToLin(params.intakeBoostDb);
        float exhaustTarget = exhaustUnit * load * load;
        float burbleTarget = PulseVoice.crackleScale * exhaustUnit * dbToLin(BURBLE_DB - p.exhaustDb) * VirtualEngine.clamp01(params.burble);
        float starterTarget = dbToLin(STARTER_DB) * VirtualEngine.clamp01(params.starter);
        float whineTarget = dbToLin(WHINE_DB) * VirtualEngine.clamp01(params.whineAmp);
        boolean toneOn = params.toneHz < TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone.setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;
        float unfired = params.fire < 0.5f && params.starter <= 0.5f
                ? UNFIRED_LEVEL * VirtualEngine.clamp01(rpmTarget / PulseVoice.UNFIRED_FULL_RPM) : 1f;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), p.levelPow)
                * dbToLin(p.shiftDipDb * VirtualEngine.clamp01(params.cut)) * unfired;
        float gainTarget = params.gain * p.norm;
        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            crank += r / 60.0 / rate;
            if (crank >= 2.0) {
                crank -= 2.0;
            }
            float engine = 0f;
            for (int k = 0; k < n; k++) {
                ampNow[k] += (amp[k] - ampNow[k]) * ampK;
                engine += ampNow[k] * sin(p.orders[k] * crank + orderPhase[k]);
            }
            float w = nextNoise();
            float v3 = w - in2;
            float v1 = inA1 * in1 + inA2 * v3;
            float v2 = in2 + inA2 * in1 + inA3 * v3;
            in1 = 2f * v1 - in1;
            in2 = 2f * v2 - in2;
            intakeNow += (intakeTarget - intakeNow) * ampK;
            engine += intakeNow * ik * v1;
            float w2 = nextNoise();
            float e3 = w2 - ex2;
            float e1 = exA1 * ex1 + exA2 * e3;
            float e2 = ex2 + exA2 * ex1 + exA3 * e3;
            ex1 = 2f * e1 - ex1;
            ex2 = 2f * e2 - ex2;
            if (--gateLeft <= 0) {
                gateLeft = gateSamples;
                gateTarget = (nextNoise() + 1f) * 0.5f;
                gateTarget *= gateTarget;
            }
            gate += (gateTarget - gate) * gateK;
            exhaustNow += (exhaustTarget - exhaustNow) * ampK;
            burbleNow += (burbleTarget - burbleNow) * ampK;
            engine += e2 * (exhaustNow + burbleNow * gate);
            levelNow += (levelTarget - levelNow) * gainK;
            float s = engine * levelNow;
            starterNow += (starterTarget - starterNow) * ampK;
            if (starterNow > 1e-5f) {
                starterPhase += STARTER_WHINE_HZ * (0.8 + 0.2 * starterNow) / rate;
                if (starterPhase >= 1.0) {
                    starterPhase -= 1.0;
                }
                float pulse = 0.55f + 0.45f * sin(4.0 * crank);
                float buzz = sin(starterPhase) + 0.35f * sin(2.0 * starterPhase) + 0.25f * nextNoise();
                s += starterNow * pulse * buzz;
            }
            whineNow += (whineTarget - whineNow) * ampK;
            if (whineNow > 1e-5f && params.whineHz > 0f) {
                whinePhase += params.whineHz / rate;
                if (whinePhase >= 1.0) {
                    whinePhase -= 1.0;
                }
                s += whineNow * (sin(whinePhase) + 0.3f * sin(2.0 * whinePhase));
            }
            for (Biquad b : resonances) {
                s = b.process(s);
            }
            s = highpass.process(s);
            if (toneOn) {
                s = tone.process(s);
            }
            gainNow += (gainTarget - gainNow) * gainK;
            out[offset + i] = limiter.push(s * gainNow);
        }
        rpmNow = rpmTarget;
    }

    static float bandWeight(float hz) {
        if (hz <= BAND_FADE_HZ) {
            return 1f;
        }
        if (hz >= BAND_TOP_HZ) {
            return 0f;
        }
        return (BAND_TOP_HZ - hz) / (BAND_TOP_HZ - BAND_FADE_HZ);
    }

    static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    static float sin(double cycles) {
        double x = cycles - Math.floor(cycles);
        float pos = (float) (x * TABLE);
        int i = (int) pos;
        if (i >= TABLE) {
            i = TABLE - 1;
        }
        float frac = pos - i;
        return SINE[i] + (SINE[i + 1] - SINE[i]) * frac;
    }

    private static float[] sineTable() {
        float[] t = new float[TABLE + 1];
        for (int i = 0; i <= TABLE; i++) {
            t[i] = (float) Math.sin(2.0 * Math.PI * i / TABLE);
        }
        return t;
    }

    private float nextNoise() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1f / 2147483648f);
    }
}
