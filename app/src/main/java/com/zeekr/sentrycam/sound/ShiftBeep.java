/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class ShiftBeep {

    static final float HZ = 1760f;
    static final float SEC = 0.09f;
    static final float EDGE_SEC = 0.005f;
    static final float LEVEL = 0.3f;

    private final double radPerSample;
    private final int length;
    private final int edge;
    private int pos;

    ShiftBeep(float sampleRate) {
        radPerSample = 2.0 * Math.PI * HZ / sampleRate;
        length = Math.round(SEC * sampleRate);
        edge = Math.max(1, Math.round(EDGE_SEC * sampleRate));
        pos = length;
    }

    void start() {
        pos = 0;
    }

    void mix(float[] lr, int frames, float gain) {
        for (int i = 0; i < frames && pos < length; i++, pos++) {
            float env = Math.min(1f, (float) Math.min(pos, length - 1 - pos) / edge);
            float level = LEVEL * env * gain;
            float s = level * (float) Math.sin(radPerSample * pos);
            float keep = 1f - level / EngineVoice.CEILING;
            lr[2 * i] = lr[2 * i] * keep + s;
            lr[2 * i + 1] = lr[2 * i + 1] * keep + s;
        }
    }
}
