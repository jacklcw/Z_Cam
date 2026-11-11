/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;


enum SoundStyle {
    AMERICAN(AppConfig.DRIVE_SOUND_STYLE_AMERICAN),
    PROTOTYPE(AppConfig.DRIVE_SOUND_STYLE_PROTOTYPE),
    TURBO4(AppConfig.DRIVE_SOUND_STYLE_TURBO4),
    /** Z_Cam: Civic Type R flavoured turbo four ({@link CivicTypeRVoice}), manual 6-speed. */
    TYPE_R(AppConfig.DRIVE_SOUND_STYLE_TYPE_R);

    final String key;

    SoundStyle(String key) {
        this.key = key;
    }

    static SoundStyle of(String key) {
        for (SoundStyle s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return TYPE_R;
    }

    boolean cranks() {
        return this == PROTOTYPE || this == TYPE_R;
    }

    boolean hasViews() {
        return this == AMERICAN;
    }

    boolean choosesGearbox() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    /** Z_Cam: the engine model's profile (rev range, shift points); the V8 one unless the style has its own. */
    SoundProfile profile() {
        switch (this) {
            case TYPE_R: return SoundProfile.TYPE_R;
            default: return SoundProfile.V8;
        }
    }

    Gearbox gearbox(Gearbox chosen) {
        if (this == TYPE_R) {
            return Gearbox.MANUAL6;
        }
        return choosesGearbox() ? chosen : Gearbox.AUTO8;
    }

    boolean pseudoGears() {
        return this == AMERICAN || this == PROTOTYPE;
    }

    boolean realNeutral() {
        return this == AMERICAN;
    }

    Voice voice(float sampleRate, boolean cabin) {
        switch (this) {
            case PROTOTYPE:
                return new EngineVoice(SoundProfile.V8, sampleRate);
            case TURBO4:
                return new TurboFourVoice(sampleRate);
            case TYPE_R:
                return new CivicTypeRVoice(sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate, cabin);
        }
    }
}
