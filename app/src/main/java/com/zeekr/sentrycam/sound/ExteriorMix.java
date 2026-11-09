/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class ExteriorMix {

    static final float HIGHPASS_HZ = 150f;

    private final Biquad highpass;
    private final Limiter limiter;
    private final float gainK;
    private float gain = -1f;

    ExteriorMix(float sampleRate) {
        highpass = Biquad.highpass(HIGHPASS_HZ, 0.7071f, sampleRate);
        limiter = new Limiter(sampleRate);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
    }

    void process(float[] in, float[] out, int frames, float targetGain) {
        if (gain < 0f) {
            gain = targetGain;
        }
        for (int i = 0; i < frames; i++) {
            gain += (targetGain - gain) * gainK;
            float m = highpass.process(0.5f * (in[2 * i] + in[2 * i + 1])) * gain;
            limiter.push(m, m);
            out[2 * i] = limiter.left;
            out[2 * i + 1] = limiter.right;
        }
    }
}
