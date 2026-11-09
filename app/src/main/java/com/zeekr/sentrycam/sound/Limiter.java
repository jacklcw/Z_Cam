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
final class Limiter {

    static final int LOOKAHEAD = 64;
    static final float HOLD_SEC = 0.025f;
    static final float RELEASE_SEC = 0.08f;

    private static final int MASK = LOOKAHEAD - 1;
    private static final int WINDOW = LOOKAHEAD + 1;
    private static final int QUEUE = 2 * LOOKAHEAD;
    private static final int QUEUE_MASK = QUEUE - 1;

    private final float attackK;
    private final float releaseK;
    private final int holdSamples;
    private int holdLeft;
    private final float[] delayL = new float[LOOKAHEAD];
    private final float[] delayR = new float[LOOKAHEAD];
    private final float[] queuePeak = new float[QUEUE];
    private final long[] queueAt = new long[QUEUE];
    private int head;
    private int tail;
    private long count;
    private int pos;
    private float gain = 1f;
    float left;
    float right;

    Limiter(float sampleRate) {
        attackK = 1f - (float) Math.exp(-5.0 / LOOKAHEAD);
        releaseK = 1f - (float) Math.exp(-1.0 / (RELEASE_SEC * sampleRate));
        holdSamples = Math.round(HOLD_SEC * sampleRate);
    }

    void push(float l, float r) {
        float peak = Math.max(Math.abs(l), Math.abs(r));
        long now = count++;
        while (tail != head && queuePeak[(tail - 1) & QUEUE_MASK] <= peak) {
            tail = (tail - 1) & QUEUE_MASK;
        }
        queuePeak[tail] = peak;
        queueAt[tail] = now;
        tail = (tail + 1) & QUEUE_MASK;
        while (queueAt[head] <= now - WINDOW) {
            head = (head + 1) & QUEUE_MASK;
        }
        float windowPeak = queuePeak[head];
        float target = windowPeak > EngineVoice.CEILING ? EngineVoice.CEILING / windowPeak : 1f;
        if (target < gain) {
            gain += (target - gain) * attackK;
            holdLeft = holdSamples;
        } else if (holdLeft > 0) {
            holdLeft--;
        } else {
            gain += (target - gain) * releaseK;
        }
        float dl = delayL[pos];
        float dr = delayR[pos];
        delayL[pos] = l;
        delayR[pos] = r;
        pos = (pos + 1) & MASK;
        left = clip(dl * gain);
        right = clip(dr * gain);
    }

    float push(float x) {
        push(x, x);
        return left;
    }

    void reset() {
        for (int i = 0; i < LOOKAHEAD; i++) {
            delayL[i] = 0f;
            delayR[i] = 0f;
        }
        head = 0;
        tail = 0;
        count = 0L;
        pos = 0;
        gain = 1f;
        holdLeft = 0;
        left = 0f;
        right = 0f;
    }

    float gain() {
        return gain;
    }

    private static float clip(float x) {
        return x > EngineVoice.CEILING ? EngineVoice.CEILING : (x < -EngineVoice.CEILING ? -EngineVoice.CEILING : x);
    }
}
