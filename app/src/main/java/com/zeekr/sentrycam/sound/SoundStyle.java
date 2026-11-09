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
    JET(AppConfig.DRIVE_SOUND_STYLE_JET),
    JET_OLD(AppConfig.DRIVE_SOUND_STYLE_JET_OLD),
    TURBO4(AppConfig.DRIVE_SOUND_STYLE_TURBO4),
    ELECTRIC(AppConfig.DRIVE_SOUND_STYLE_ELECTRIC),
    /** Z_Cam: Civic Type R flavoured turbo four ({@link CivicTypeRVoice}), manual 6-speed. */
    TYPE_R(AppConfig.DRIVE_SOUND_STYLE_TYPE_R),
    /** Z_Cam: twin-turbo V6 (GT-R), flat-plane V8 (Ferrari), V10 (Lamborghini), flat-six (Porsche), turbo inline-six (Supra): {@link EngineVoice} with their own {@link SoundProfile}. */
    GTR(AppConfig.DRIVE_SOUND_STYLE_GTR),
    FERRARI(AppConfig.DRIVE_SOUND_STYLE_FERRARI),
    LAMBO(AppConfig.DRIVE_SOUND_STYLE_LAMBO),
    PORSCHE(AppConfig.DRIVE_SOUND_STYLE_PORSCHE),
    SUPRA(AppConfig.DRIVE_SOUND_STYLE_SUPRA),
    /** Porsche Taycan Turbo S with Electric Sport Sound: a smooth gliding chord of motor harmonics, no combustion pulses. */
    TAYCAN(AppConfig.DRIVE_SOUND_STYLE_TAYCAN);

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
        return AMERICAN;
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
            case GTR: return SoundProfile.GTR;
            case FERRARI: return SoundProfile.FERRARI;
            case LAMBO: return SoundProfile.LAMBO;
            case PORSCHE: return SoundProfile.PORSCHE;
            case SUPRA: return SoundProfile.SUPRA;
            case TAYCAN: return SoundProfile.TAYCAN;
            default: return SoundProfile.V8;
        }
    }

    Gearbox gearbox(Gearbox chosen) {
        if (this == TYPE_R || this == SUPRA) {
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
            case JET:
                return new JetVoice(sampleRate);
            case JET_OLD:
                return new JetOldVoice(sampleRate);
            case TURBO4:
                return new TurboFourVoice(sampleRate);
            case ELECTRIC:
                return new ElectricVoice(sampleRate);
            case TYPE_R:
                return new CivicTypeRVoice(sampleRate);
            case GTR:
            case FERRARI:
            case LAMBO:
            case PORSCHE:
            case SUPRA:
            case TAYCAN:
                return new EngineVoice(profile(), sampleRate);
            default:
                return new PulseVoice(PulseProfile.SUPERCHARGED_V8, sampleRate, cabin);
        }
    }
}
