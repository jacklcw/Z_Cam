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
final class PedalTrack {

    static final float RAMP_SEC = 0.09f;
    static final float RAMP_FAST_SEC = 0.035f;
    static final float SLOW_RATE = 100f;
    static final float FAST_RATE = 500f;
    static final float LEAD_SEC = 0.04f;
    static final float LEAD_MAX = 8f;
    static final float HOLD_SEC = 0.25f;
    static final float SETTLE_SEC = 0.12f;
    static final float MIN_GAP_SEC = 0.05f;
    static final float MAX_GAP_SEC = 0.25f;
    static final float RATE_HOLD_SEC = 0.15f;
    static final float RATE_DECAY_SEC = 0.1f;

    private float value = Float.NaN;
    private float sample = Float.NaN;
    private long sampleAtMs = -1L;
    private float from;
    private float to;
    private long rampStartMs;
    private float rampSec = RAMP_SEC;
    private boolean ramping;
    private float rate;
    private long rateAtMs;

    void sample(float pct, long atMs, long nowMs) {
        if (Float.isNaN(pct)) {
            value = Float.NaN;
            sample = Float.NaN;
            ramping = false;
            rate = 0f;
            return;
        }
        if (Float.isNaN(sample) || Float.isNaN(value)) {
            value = pct;
            sample = pct;
            sampleAtMs = atMs;
            ramping = false;
            rate = 0f;
            rateAtMs = nowMs;
            return;
        }
        float gap = Math.min(MAX_GAP_SEC, Math.max(MIN_GAP_SEC, (atMs - sampleAtMs) / 1000f));
        float slope = (pct - sample) / gap;
        float lead = VirtualEngine.clamp(slope * LEAD_SEC, -LEAD_MAX, LEAD_MAX);
        from = value;
        to = VirtualEngine.clamp(pct + lead, 0f, 100f);
        float quick = VirtualEngine.clamp01((Math.abs(slope) - SLOW_RATE) / (FAST_RATE - SLOW_RATE));
        rampSec = RAMP_SEC + (RAMP_FAST_SEC - RAMP_SEC) * quick;
        rampStartMs = nowMs;
        ramping = true;
        sample = pct;
        sampleAtMs = atMs;
        rate = slope;
        rateAtMs = nowMs;
    }

    void step(float dtSec, long nowMs) {
        if (Float.isNaN(value)) {
            return;
        }
        if (ramping) {
            float f = (nowMs - rampStartMs) / (rampSec * 1000f);
            if (f >= 1f) {
                value = to;
                ramping = false;
            } else {
                value = from + (to - from) * VirtualEngine.smooth(VirtualEngine.clamp01(f));
            }
        } else if ((nowMs - sampleAtMs) / 1000f > HOLD_SEC) {
            value += (sample - value) * VirtualEngine.follow(dtSec, SETTLE_SEC);
        }
    }

    float value() {
        return value;
    }

    float rate(long nowMs) {
        float age = (nowMs - rateAtMs) / 1000f;
        if (age <= RATE_HOLD_SEC) {
            return rate;
        }
        return rate * VirtualEngine.decay(age - RATE_HOLD_SEC, RATE_DECAY_SEC);
    }
}
