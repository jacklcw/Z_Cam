/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class ThrottleSources {

    static final long RECENT_MS = 600L;
    private static final int HISTORY = 16;

    private final float[] seenValue = new float[HISTORY];
    private final long[] seenAt = new long[HISTORY];
    private final boolean[] seenByPush = new boolean[HISTORY];
    private final boolean[] matched = new boolean[HISTORY];
    private int head;
    private int count;
    private float last = Float.NaN;
    private long lastPushCount = -1L;
    private long lastPollVersion = Long.MIN_VALUE;

    float sample = Float.NaN;
    long sampleAt;
    int pushFirst;
    int pollFirst;
    float pushLeadMs = -1f;
    float pollLeadMs = -1f;

    boolean offer(long pushCount, float pushValue, long pushAtMs, long pollVersion, float pollValue, long pollAtMs) {
        boolean fresh = false;
        if (pushCount != lastPushCount) {
            lastPushCount = pushCount;
            if (pushCount > 0L && !Float.isNaN(pushValue)) {
                int i = find(pushValue, pushAtMs);
                if (i >= 0 && !seenByPush[i] && !matched[i]) {
                    matched[i] = true;
                    pollFirst++;
                    pollLeadMs = ema(pollLeadMs, pushAtMs - seenAt[i]);
                }
                if (pushValue != last) {
                    fresh = accept(pushValue, pushAtMs, true);
                }
            }
        }
        if (pollVersion != lastPollVersion) {
            lastPollVersion = pollVersion;
            if (!Float.isNaN(pollValue)) {
                int i = find(pollValue, pollAtMs);
                if (i >= 0 && seenByPush[i] && !matched[i]) {
                    matched[i] = true;
                    pushFirst++;
                    pushLeadMs = ema(pushLeadMs, pollAtMs - seenAt[i]);
                }
                if (pollValue != last && i < 0) {
                    fresh |= accept(pollValue, pollAtMs, false);
                }
            } else if (lastPushCount <= 0L && !Float.isNaN(last)) {
                last = Float.NaN;
                sample = Float.NaN;
                sampleAt = pollAtMs;
                fresh = true;
            }
        }
        return fresh;
    }

    private boolean accept(float value, long atMs, boolean byPush) {
        seenValue[head] = value;
        seenAt[head] = atMs;
        seenByPush[head] = byPush;
        matched[head] = false;
        head = (head + 1) % HISTORY;
        count = Math.min(HISTORY, count + 1);
        last = value;
        sample = value;
        sampleAt = atMs;
        return true;
    }

    private int find(float value, long nowMs) {
        for (int k = 1; k <= count; k++) {
            int i = (head - k + HISTORY) % HISTORY;
            if (nowMs - seenAt[i] > RECENT_MS) {
                return -1;
            }
            if (seenValue[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static float ema(float previous, long sampleMs) {
        return previous < 0f ? sampleMs : previous + (sampleMs - previous) * 0.1f;
    }
}
