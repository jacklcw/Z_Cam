/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class VoiceParams {
    float rpm;
    float load;
    float level;
    float gain;
    float brightDb;
    float intakeBoostDb;
    float burble;
    float starter;
    float fire = 1f;
    int catchSerial;
    float whineHz;
    float whineAmp;
    float toneHz = VirtualEngine.TONE_OPEN_HZ;
    float overrun;
    float speedKmh;
    int gearIndex = -1;
    boolean drive;
    float cut;
    int shiftSerial;
    boolean shiftUp = true;
}
