/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

enum Gearbox {
    AUTO8(2.62f, 3.30f, 0.22f, 4.71f, 3.14f, 2.10f, 1.67f, 1.29f, 1.00f, 0.84f, 0.67f),
    MANUAL6(4.11f, 2.90f, 0.35f, 3.63f, 2.12f, 1.53f, 1.13f, 0.91f, 0.74f);

    static final float ROLLING_M = 2.22f;

    final float[] rpmPerKmh;
    final float reverseRpmPerKmh;
    final float upshiftSec;

    Gearbox(float finalDrive, float reverseRatio, float upshiftSec, float... ratios) {
        float perRatio = rpmPerKmh(1f, finalDrive);
        rpmPerKmh = new float[ratios.length];
        for (int g = 0; g < ratios.length; g++) {
            rpmPerKmh[g] = ratios[g] * perRatio;
        }
        reverseRpmPerKmh = reverseRatio * perRatio;
        this.upshiftSec = upshiftSec;
    }

    boolean manual() {
        return this == MANUAL6;
    }

    static float rpmPerKmh(float ratio, float finalDrive) {
        return ratio * finalDrive * 1000f / 60f / ROLLING_M;
    }
}
