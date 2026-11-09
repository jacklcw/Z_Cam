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
 */
final class ElectricVoice implements Voice {

    static final float IDLE_RPM = 900f;
    static final float MAIN_ORDER = 2.6f;
    static final float MIN_HZ = 20f;
    static final float LAG_SEC = 0.03f;

    static final float[] PARTIAL = {0.5f, 1f, 1.5f, 2f, 3f, 4f, 7.45f};
    static final float[] LIGHT_DB = {-7f, 0f, -24f, -8f, -20f, -16f, -24f};
    static final float[] FULL_DB = {-5f, 0f, -16f, -5f, -12f, -2f, -7f};
    static final float[] SPREAD = {0f, 0f, 0.08f, 0.1f, 0.15f, 0.18f, 0.22f};
    static final float MAIN_DB = -8f;
    static final float FADE_FROM_HZ = 6000f;
    static final float FADE_TOP_HZ = 9000f;

    static final float FLOOR_LIGHT_DB = -12f;
    static final float FLOOR_FULL_DB = 0f;
    static final float FLOOR_LOW_HZ = 180f;
    static final float FLOOR_TILT_HZ = 1200f;
    static final float FLOOR_TOP_HZ = 7000f;
    static final float FLOOR_OWN = 0.8f;
    static final float FLOOR_COMMON = 0.5f;

    static final float ZAP_FROM_HZ = 3200f;
    static final float ZAP_TO_HZ = 900f;
    static final float ZAP_SEC = 0.06f;
    static final float ZAP_DECAY_SEC = 0.025f;
    static final float ZAP_ATTACK_SEC = 0.001f;
    static final float ZAP_DB = -16f;
    static final float ZAP_DOWN = 0.5f;
    static final float CUT_DIP_DB = -6f;

    static final float REGEN_SHARE = 0.35f;

    static final float LEVEL_POW = 0.25f;
    static final float NORM = 0.5f;

    private static final int PARTIALS = PARTIAL.length;

    private final float rate;
    private final float lagK;
    private final float ampK;
    private final float gainK;
    private final float mainGain;
    private final float[] lightGain = new float[PARTIALS];
    private final float[] lnSpan = new float[PARTIALS];
    private final float[] gainNow = new float[PARTIALS];
    private final float[] gainTarget = new float[PARTIALS];
    private final double[] phase = new double[PARTIALS];
    private float hz = -1f;
    private float startNow = -1f;

    private final float[] pinkState = new float[9];
    private final Biquad[] floorHighpass = new Biquad[2];
    private final Biquad[] floorLowpass = new Biquad[2];
    private final float[] floorTilt = new float[2];
    private final float tiltK;
    private float floorNow;

    private boolean serialSeen;
    private int lastSerial;
    private int zapPos = -1;
    private final int zapLength;
    private final int zapAttack;
    private float zapAmp;
    private double zapPhase;
    private final float zapDecay;
    private float zapEnv;
    private final double zapRatio;

    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private float levelNow;
    private float outGainNow;
    private int noise = 0x1F83D9AB;

    ElectricVoice(float sampleRate) {
        this.rate = sampleRate;
        lagK = 1f - (float) Math.exp(-1.0 / (LAG_SEC * sampleRate));
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        mainGain = EngineVoice.dbToLin(MAIN_DB);
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < PARTIALS; k++) {
            lightGain[k] = EngineVoice.dbToLin(LIGHT_DB[k]) * mainGain;
            lnSpan[k] = (FULL_DB[k] - LIGHT_DB[k]) * dbToLn;
            phase[k] = 0.0;
        }
        for (int c = 0; c < 2; c++) {
            floorHighpass[c] = Biquad.highpass(FLOOR_LOW_HZ, 0.7071f, sampleRate);
            floorLowpass[c] = Biquad.lowpass(Math.min(FLOOR_TOP_HZ, 0.45f * sampleRate), 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        tiltK = 1f - (float) Math.exp(-2.0 * Math.PI * FLOOR_TILT_HZ / sampleRate);
        zapLength = Math.max(1, Math.round(ZAP_SEC * sampleRate));
        zapAttack = Math.max(1, Math.round(ZAP_ATTACK_SEC * sampleRate));
        zapDecay = (float) Math.exp(-1.0 / (ZAP_DECAY_SEC * sampleRate));
        zapRatio = Math.log(ZAP_TO_HZ / ZAP_FROM_HZ) / zapLength;
        limiter = new Limiter(sampleRate);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        if (params.gain <= 0f && outGainNow < 1e-5f) {
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            outGainNow = 0f;
            limiter.reset();
            return;
        }
        float rpm = params.rpm > 0f ? params.rpm : 0f;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float cut = VirtualEngine.clamp01(params.cut);
        float torque = Math.max(load, REGEN_SHARE * over);
        float target = Math.max(MIN_HZ, MAIN_ORDER * rpm / 60f);
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;
        if (hz < 0f) {
            hz = target;
            startNow = start;
        }

        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            zapPos = 0;
            zapPhase = 0.0;
            zapEnv = 1f;
            zapAmp = EngineVoice.dbToLin(ZAP_DB) * (0.3f + 0.7f * load) * (params.shiftUp ? 1f : ZAP_DOWN);
        }

        float bright = EngineVoice.dbToLin(Math.max(0f, params.brightDb));
        for (int k = 0; k < PARTIALS; k++) {
            float f = target * PARTIAL[k];
            float fade = VirtualEngine.clamp01((FADE_TOP_HZ - f) / (FADE_TOP_HZ - FADE_FROM_HZ));
            float g = lightGain[k] * (float) Math.exp(VirtualEngine.clamp01(torque) * lnSpan[k]) * fade;
            gainTarget[k] = PARTIAL[k] > 2f ? g * bright : g;
        }
        float floorTarget = mainGain * EngineVoice.dbToLin(FLOOR_LIGHT_DB + (FLOOR_FULL_DB - FLOOR_LIGHT_DB) * VirtualEngine.clamp01(torque));
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * NORM * EngineVoice.dbToLin(CUT_DIP_DB * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }

        for (int i = 0; i < frames; i++) {
            hz += (target - hz) * lagK;
            startNow += (start - startNow) * gainK;
            float inc = hz / rate;

            float l = 0f;
            float r = 0f;
            for (int k = 0; k < PARTIALS; k++) {
                gainNow[k] += (gainTarget[k] - gainNow[k]) * ampK;
                double ph = phase[k] + inc * PARTIAL[k];
                ph -= Math.floor(ph);
                phase[k] = ph;
                float g = gainNow[k];
                if (g > 1e-7f) {
                    l += g * EngineVoice.sin(ph);
                    r += g * EngineVoice.sin(ph + SPREAD[k]);
                }
            }

            floorNow += (floorTarget - floorNow) * ampK;
            float common = FLOOR_COMMON * pink(2, white());
            float fl = FLOOR_OWN * pink(0, white()) + common;
            float fr = FLOOR_OWN * pink(1, white()) + common;
            fl = floorHighpass[0].process(fl);
            fr = floorHighpass[1].process(fr);
            floorTilt[0] += tiltK * (fl - floorTilt[0]);
            floorTilt[1] += tiltK * (fr - floorTilt[1]);
            l += floorLowpass[0].process(0.5f * (fl + floorTilt[0])) * floorNow;
            r += floorLowpass[1].process(0.5f * (fr + floorTilt[1])) * floorNow;

            if (zapPos >= 0) {
                double f = ZAP_FROM_HZ * Math.exp(zapRatio * Math.min(zapPos, zapLength));
                zapPhase += f / rate;
                if (zapPhase >= 1.0) {
                    zapPhase -= 1.0;
                }
                zapEnv *= zapDecay;
                float z = zapAmp * zapEnv * Math.min(1f, (zapPos + 1) / (float) zapAttack) * EngineVoice.sin(zapPhase);
                l += PulseVoice.CENTER * z;
                r += PulseVoice.CENTER * z;
                zapPos++;
                if (zapPos >= 2 * zapLength) {
                    zapPos = -1;
                }
            }

            levelNow += (levelTarget - levelNow) * gainK;
            float g = levelNow * startNow;
            l *= g;
            r *= g;
            if (toneOn) {
                l = tone[0].process(l);
                r = tone[1].process(r);
            }
            l = highpass[0].process(l);
            r = highpass[1].process(r);
            outGainNow += (params.gain - outGainNow) * gainK;
            limiter.push(l * outGainNow, r * outGainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
        }
    }

    float mainHz() {
        return hz;
    }

    private float pink(int i, float w) {
        int o = 3 * i;
        pinkState[o] = 0.99765f * pinkState[o] + w * 0.0990460f;
        pinkState[o + 1] = 0.96300f * pinkState[o + 1] + w * 0.2965164f;
        pinkState[o + 2] = 0.57000f * pinkState[o + 2] + w * 1.0526913f;
        return pinkState[o] + pinkState[o + 1] + pinkState[o + 2] + w * 0.1848f;
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
