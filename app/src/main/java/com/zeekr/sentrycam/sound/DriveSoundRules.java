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
final class DriveSoundRules {

    static final long STALE_MS = 500L;

    enum Why {
        OFF(false, false, false, 0.6f, false),
        NO_LINK(false, false, false, 0.3f, false),
        POWER_OFF(false, false, false, 0.6f, true),
        OTHER_MODE(false, false, false, 0.6f, true),
        STALE(true, true, false, 0.3f, false),
        CALL(true, true, false, 0.15f, false),
        RUNNING(true, true, true, 0.3f, false);

        final boolean armed;
        final boolean open;
        final boolean audible;
        final float fadeSec;
        final boolean shutsDown;

        Why(boolean armed, boolean open, boolean audible, float fadeSec, boolean shutsDown) {
            this.armed = armed;
            this.open = open;
            this.audible = audible;
            this.fadeSec = fadeSec;
            this.shutsDown = shutsDown;
        }
    }

    private DriveSoundRules() {
    }

    static Why decide(boolean enabled, boolean linkConnected, Integer ignition, String gear, Integer driveMode,
                      boolean standard, float speedKmh, float throttlePct, long fastAgeMs, boolean inCall) {
        if (!enabled) {
            return Why.OFF;
        }
        if (!linkConnected) {
            return Why.NO_LINK;
        }
        if (!poweredOn(ignition, gear)) {
            return Why.POWER_OFF;
        }
        if (!soundMode(driveMode, standard)) {
            return Why.OTHER_MODE;
        }
        if (Float.isNaN(speedKmh) || Float.isNaN(throttlePct) || fastAgeMs < 0L || fastAgeMs > STALE_MS) {
            return Why.STALE;
        }
        if (inCall) {
            return Why.CALL;
        }
        return Why.RUNNING;
    }

    static boolean soundMode(Integer driveMode, boolean standard) {
        return true;   // Z_Cam: sounds in every drive mode (zeekr-shortcut-car only did Sport, optionally Standard)
    }

    static float eagerOf(Integer driveMode) {
        return driveMode != null && driveMode == Signal.DRIVE_MODE_SPORT ? 1f : 0f;
    }

    static float sportPedalOf(Integer driveMode) {
        return driveMode != null && driveMode == Signal.DRIVE_MODE_SPORT ? 1f : 0f;
    }

    static final long PUSH_QUIET_MS = 1000L;

    static boolean usePush(long pushCount, long pushAtMs, float pushValue, float polled, long nowMs) {
        if (pushCount <= 0L || Float.isNaN(pushValue)) {
            return false;
        }
        boolean quiet = nowMs - pushAtMs > PUSH_QUIET_MS;
        boolean differs = !Float.isNaN(polled) && Math.abs(polled - pushValue) > 0.5f;
        return !(quiet && differs);
    }

    static float volumeDb(String stored) {
        try {
            float db = Float.parseFloat(stored);
            return Math.min(db, 12f);
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    static float exteriorDb(String stored) {
        try {
            float db = Float.parseFloat(stored);
            return Math.max(0f, Math.min(36f, db));   // Z_Cam: was 0-18 dB
        } catch (RuntimeException e) {
            return 18f;
        }
    }

    static float mediaFollowDb(int index, int max) {
        if (max <= 0) {
            return 0f;
        }
        if (index <= 0) {
            return Float.NEGATIVE_INFINITY;
        }
        return -30f * (1f - Math.min(index, max) / (float) max);
    }

    static boolean poweredOn(Integer ignition, String gear) {
        if (ignition != null) {
            return ignition == Signal.IGNITION_ON || ignition == Signal.IGNITION_DRIVING;
        }
        return "R".equals(gear) || "N".equals(gear) || "D".equals(gear);
    }

    static VirtualEngine.Gear gearOf(String gear) {
        if (gear == null) {
            return VirtualEngine.Gear.UNKNOWN;
        }
        switch (gear) {
            case "P":
                return VirtualEngine.Gear.P;
            case "R":
                return VirtualEngine.Gear.R;
            case "N":
                return VirtualEngine.Gear.N;
            case "D":
                return VirtualEngine.Gear.D;
            default:
                return VirtualEngine.Gear.UNKNOWN;
        }
    }

    static VirtualEngine.Flow flowOf(Integer code) {
        if (code == null) {
            return VirtualEngine.Flow.UNKNOWN;
        }
        switch (code) {
            case Signal.POWER_FLOW_DRIVE:
                return VirtualEngine.Flow.DRIVE;
            case Signal.POWER_FLOW_REGEN:
                return VirtualEngine.Flow.REGEN;
            case Signal.POWER_FLOW_IDLE:
                return VirtualEngine.Flow.IDLE;
            case Signal.POWER_FLOW_NOT_READY:
                return VirtualEngine.Flow.NOT_READY;
            default:
                return VirtualEngine.Flow.UNKNOWN;
        }
    }
}
