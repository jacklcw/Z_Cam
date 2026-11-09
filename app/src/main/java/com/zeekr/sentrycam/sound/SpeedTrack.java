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
final class SpeedTrack {

    static final float EVERY_START_MS = 200f;
    static final float HOLD_TIMES = 1.5f;
    static final float HOLD_MIN_MS = 250f;
    static final float HOLD_MAX_MS = 1000f;
    static final float WINDOW_MIN_MS = 450f;
    static final float WINDOW_TIMES = 2.2f;
    static final long GAP_MS = 1500L;
    static final float AHEAD_SEC = 0.2f;
    static final float BLEND_SEC = 0.05f;
    static final float ACCEL_SMOOTH_SEC = 0.1f;
    private static final int HISTORY = 16;

    private final long[] at = new long[HISTORY];
    private final float[] kmh = new float[HISTORY];
    private int count;
    private int head = -1;
    private long lastSeenAt = Long.MIN_VALUE;
    private long lastChangeAt = Long.MIN_VALUE;
    private float everyMs = EVERY_START_MS;
    private float slope;
    private float speed = Float.NaN;
    private float accel;

    void sample(float value, long atMs) {
        if (Float.isNaN(value)) {
            count = 0;
            head = -1;
            slope = 0f;
            accel = 0f;
            speed = Float.NaN;
            lastSeenAt = Long.MIN_VALUE;
            lastChangeAt = Long.MIN_VALUE;
            return;
        }
        if (atMs == lastSeenAt) {
            return;
        }
        lastSeenAt = atMs;
        float v = Math.abs(value);
        if (head >= 0) {
            long gap = atMs - at[head];
            if (gap < 0L) {
                return;
            }
            if (gap > GAP_MS) {
                count = 0;
                head = -1;
                slope = 0f;
                lastChangeAt = Long.MIN_VALUE;
            } else if (v == kmh[head]) {
                if (gap < holdMs()) {
                    return;
                }
            } else {
                if (lastChangeAt != Long.MIN_VALUE && atMs - lastChangeAt <= GAP_MS) {
                    everyMs += (atMs - lastChangeAt - everyMs) * 0.2f;
                }
                lastChangeAt = atMs;
            }
        }
        add(v, atMs);
        if (Float.isNaN(speed)) {
            speed = v;
        }
    }

    void step(float dtSec, long nowMs) {
        if (head < 0 || Float.isNaN(speed)) {
            return;
        }
        long since = nowMs - at[head];
        if (since >= holdMs() && since <= GAP_MS) {
            add(kmh[head], nowMs);
            since = 0L;
        }
        float ahead = Math.min(Math.max(0f, since / 1000f), Math.min(AHEAD_SEC, everyMs / 1000f));
        float target = Math.max(0f, kmh[head] + slope * ahead);
        speed += (target - speed) * VirtualEngine.follow(dtSec, BLEND_SEC);
        accel += (slope / 3.6f - accel) * VirtualEngine.follow(dtSec, ACCEL_SMOOTH_SEC);
    }

    float speed() {
        return speed;
    }

    float accel() {
        return accel;
    }

    float everyMs() {
        return everyMs;
    }

    private float holdMs() {
        return VirtualEngine.clamp(HOLD_TIMES * everyMs, HOLD_MIN_MS, HOLD_MAX_MS);
    }

    private void add(float v, long atMs) {
        head = (head + 1) % HISTORY;
        at[head] = atMs;
        kmh[head] = v;
        if (count < HISTORY) {
            count++;
        }
        slope = fit();
    }

    private float fit() {
        long newest = at[head];
        float window = Math.max(WINDOW_MIN_MS, WINDOW_TIMES * everyMs);
        double sumT = 0;
        double sumV = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            long age = newest - at[idx];
            if (age > window) {
                break;
            }
            sumT -= age;
            sumV += kmh[idx];
            n++;
        }
        if (n < 2) {
            return 0f;
        }
        double meanT = sumT / n;
        double meanV = sumV / n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            double t = -(double) (newest - at[idx]) - meanT;
            num += t * (kmh[idx] - meanV);
            den += t * t;
        }
        return den > 0 ? (float) (num / den * 1000.0) : 0f;
    }
}
