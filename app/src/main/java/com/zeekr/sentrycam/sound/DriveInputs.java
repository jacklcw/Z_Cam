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
 */
final class DriveInputs {

    static final long PEDAL_WINDOW_MS = 150L;
    static final long ACCEL_WINDOW_MS = 300L;
    static final float ACCEL_SMOOTH_SEC = 0.15f;
    static final long GAP_MS = 400L;
    private static final int HISTORY = 16;

    private final long[] at = new long[HISTORY];
    private final float[] speed = new float[HISTORY];
    private final float[] throttle = new float[HISTORY];
    private int count;
    private int head = -1;
    private long lastVersion = Long.MIN_VALUE;

    private float speedNow = Float.NaN;
    private float throttleNow = Float.NaN;
    private float brakeNow = Float.NaN;
    private float pedalRate;
    private float accel;

    void offer(long version, long atMs, float speedKmh, float throttlePct, float brakePct) {
        if (version == lastVersion) {
            return;
        }
        lastVersion = version;
        brakeNow = brakePct;
        boolean usable = !Float.isNaN(speedKmh) && !Float.isNaN(throttlePct);
        if (!usable) {
            clearHistory();
            speedNow = Float.isNaN(speedKmh) ? Float.NaN : Math.abs(speedKmh);
            throttleNow = throttlePct;
            return;
        }
        float v = Math.abs(speedKmh);
        long gap = head < 0 ? 0L : atMs - at[head];
        if (head >= 0 && gap == 0L) {
            speed[head] = v;
            throttle[head] = throttlePct;
            speedNow = v;
            throttleNow = throttlePct;
            return;
        }
        if (head >= 0 && (gap < 0L || gap > GAP_MS)) {
            clearHistory();
            gap = 0L;
        }
        head = (head + 1) % HISTORY;
        at[head] = atMs;
        speed[head] = v;
        throttle[head] = throttlePct;
        if (count < HISTORY) {
            count++;
        }
        speedNow = v;
        throttleNow = throttlePct;
        pedalRate = slope(throttle, PEDAL_WINDOW_MS) * 1000f;
        float raw = slope(speed, ACCEL_WINDOW_MS) * (1000f / 3.6f);
        if (count < 2) {
            accel = 0f;
        } else {
            float dt = gap / 1000f;
            float k = dt > 0f ? 1f - (float) Math.exp(-dt / ACCEL_SMOOTH_SEC) : 1f;
            accel += (raw - accel) * k;
        }
    }

    void reset() {
        clearHistory();
        lastVersion = Long.MIN_VALUE;
        speedNow = Float.NaN;
        throttleNow = Float.NaN;
        brakeNow = Float.NaN;
    }

    private void clearHistory() {
        count = 0;
        head = -1;
        pedalRate = 0f;
        accel = 0f;
    }

    private float slope(float[] values, long windowMs) {
        if (count < 2) {
            return 0f;
        }
        long newest = at[head];
        double sumT = 0;
        double sumX = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            long age = newest - at[idx];
            if (age > windowMs) {
                break;
            }
            sumT += -age;
            sumX += values[idx];
            n++;
        }
        if (n < 2) {
            int prev = (head - 1 + HISTORY) % HISTORY;
            long dt = newest - at[prev];
            return dt > 0 ? (values[head] - values[prev]) / dt : 0f;
        }
        double meanT = sumT / n;
        double meanX = sumX / n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            double t = -(double) (newest - at[idx]) - meanT;
            num += t * (values[idx] - meanX);
            den += t * t;
        }
        return den > 0 ? (float) (num / den) : 0f;
    }

    float speed() {
        return speedNow;
    }

    float throttle() {
        return throttleNow;
    }

    float brake() {
        return brakeNow;
    }

    float pedalRate() {
        return pedalRate;
    }

    float accel() {
        return accel;
    }
}
