/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

import java.util.ArrayList;
import java.util.List;

final class SoundProfile {

    final float idleRpm;
    final float startRpm;
    final float maxRpm;
    final float speedShare;
    final float pedalKick;
    final float speedKneeKmh;

    final float rpmRiseSec;
    final float rpmFallSec;
    final float rpmRisePerSec;
    final float rpmFallPerSec;
    final float loadAttackSec;
    final float loadReleaseSec;
    final float brakeReleaseSec;

    final float[] orders;
    final float[] orderDb;
    final float[] orderLoadDb;

    final float intakeCenterOrder;
    final float intakeQ;
    final float intakeDb;
    final float exhaustDb;

    final float[] resonanceHz;
    final float[] resonanceDb;
    final float[] resonanceQ;

    final float shiftUpBaseRpm;
    final float shiftUpLoadRpm;
    final float shiftDownRatio;
    final float shiftSec;
    final float shiftDipDb;
    final float levelPow;
    final float norm;
    final float coastDb;
    final float idleDb;
    final float blipRpm;
    final float blipLoad;
    final float liftDownRpm;
    final float downSlewSec;
    final float freeRevRpm;

    private SoundProfile(Builder b) {
        idleRpm = b.idleRpm;
        startRpm = b.startRpm;
        maxRpm = b.maxRpm;
        speedShare = b.speedShare;
        pedalKick = b.pedalKick;
        speedKneeKmh = b.speedKneeKmh;
        rpmRiseSec = b.rpmRiseSec;
        rpmFallSec = b.rpmFallSec;
        rpmRisePerSec = b.rpmRisePerSec;
        rpmFallPerSec = b.rpmFallPerSec;
        loadAttackSec = b.loadAttackSec;
        loadReleaseSec = b.loadReleaseSec;
        brakeReleaseSec = b.brakeReleaseSec;
        orders = toArray(b.orders);
        orderDb = toArray(b.orderDb);
        orderLoadDb = toArray(b.orderLoadDb);
        intakeCenterOrder = b.intakeCenterOrder;
        intakeQ = b.intakeQ;
        intakeDb = b.intakeDb;
        exhaustDb = b.exhaustDb;
        resonanceHz = toArray(b.resonanceHz);
        resonanceDb = toArray(b.resonanceDb);
        resonanceQ = toArray(b.resonanceQ);
        shiftUpBaseRpm = b.shiftUpBaseRpm;
        shiftUpLoadRpm = b.shiftUpLoadRpm;
        shiftDownRatio = b.shiftDownRatio;
        shiftSec = b.shiftSec;
        shiftDipDb = b.shiftDipDb;
        levelPow = b.levelPow;
        norm = b.norm;
        coastDb = b.coastDb;
        idleDb = b.idleDb;
        blipRpm = b.blipRpm;
        blipLoad = b.blipLoad;
        liftDownRpm = b.liftDownRpm;
        downSlewSec = b.downSlewSec;
        freeRevRpm = b.freeRevRpm > 0f ? b.freeRevRpm : VirtualEngine.FREE_REV_MAX_RPM;
    }

    static final SoundProfile V8 = new Builder()
            .rpm(900f, 7000f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.10f, 6000f)
            .fall(0.40f, 4000f)
            .load(0.06f, 0.25f, 0.12f)
            .order(4f, 0f, 0f)
            .order(2f, -4f, 0f)
            .order(8f, -8f, 4f)
            .order(6f, -12f, 3f)
            .order(3f, -14f, 0f)
            .order(1f, -16f, 0f)
            .order(0.5f, -22f, 6f)
            .order(1.5f, -20f, 6f)
            .order(2.5f, -20f, 6f)
            .order(3.5f, -22f, 6f)
            .order(12f, -18f, 6f)
            .order(16f, -24f, 6f)
            .intake(32f, 1.5f, -18f)
            .exhaust(-14f)
            .resonance(150f, 4f, 1.0f)
            .resonance(1200f, 3f, 1.2f)
            .shifts(2000f, 4000f, 0.55f, 0.06f, -4.5f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Civic Type R: a free-revving 2.0 turbo four with a 7000 rpm redline; same shift model as the V8 profile. */
    static final SoundProfile TYPE_R = new Builder()
            .rpm(800f, 7000f)
            .startRpm(1500f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.02f, 20000f)
            .fall(0.28f, 5500f)
            .load(0.012f, 0.15f, 0.08f)
            .order(2f, 0f, 0f)
            .order(4f, -6f, 4f)
            .order(1f, -10f, 0f)
            .intake(32f, 1.5f, -18f)
            .exhaust(-14f)
            .resonance(150f, 4f, 1.0f)
            .resonance(1200f, 3f, 1.2f)
            .shifts(2400f, 2080f, 0.55f, 0.06f, -4.5f)
            .loudness(0.17f, 0.8f)
            .coast(16f)
            .idle(-6f)
            .freeRev(3200f)
            .build();

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    static final class Builder {
        private float idleRpm = 900f;
        private float startRpm;
        private float maxRpm = 6500f;
        private float speedShare = 0.3f;
        private float pedalKick = 0.3f;
        private float speedKneeKmh = 70f;
        private float rpmRiseSec = 0.18f;
        private float rpmFallSec = 0.5f;
        private float rpmRisePerSec = 5000f;
        private float rpmFallPerSec = 3500f;
        private float loadAttackSec = 0.08f;
        private float loadReleaseSec = 0.3f;
        private float brakeReleaseSec = 0.12f;
        private final List<Float> orders = new ArrayList<>();
        private final List<Float> orderDb = new ArrayList<>();
        private final List<Float> orderLoadDb = new ArrayList<>();
        private float intakeCenterOrder = 32f;
        private float intakeQ = 1.5f;
        private float intakeDb = -18f;
        private float exhaustDb = -14f;
        private final List<Float> resonanceHz = new ArrayList<>();
        private final List<Float> resonanceDb = new ArrayList<>();
        private final List<Float> resonanceQ = new ArrayList<>();
        private float shiftUpBaseRpm = 2500f;
        private float shiftUpLoadRpm = 3500f;
        private float shiftDownRatio = 0.55f;
        private float shiftSec = 0.06f;
        private float shiftDipDb = -3f;
        private float levelPow = 1f;
        private float norm = 1f;
        private float coastDb;
        private float idleDb;
        private float liftDownRpm;
        private float downSlewSec;
        private float freeRevRpm;
        private float blipRpm = VirtualEngine.DOWNSHIFT_BLIP_RPM;
        private float blipLoad = VirtualEngine.DOWNSHIFT_BLIP_LOAD;

        Builder rpm(float idle, float max) {
            idleRpm = idle;
            maxRpm = max;
            return this;
        }

        /** Cold-start rpm: the catch and settle hold here (0 = flare to 2000 and drop to idle). */
        Builder startRpm(float rpm) {
            startRpm = rpm;
            return this;
        }

        Builder continuous(float speedShare, float pedalKick, float speedKneeKmh) {
            this.speedShare = speedShare;
            this.pedalKick = pedalKick;
            this.speedKneeKmh = speedKneeKmh;
            return this;
        }

        Builder rise(float sec, float perSec) {
            rpmRiseSec = sec;
            rpmRisePerSec = perSec;
            return this;
        }

        Builder fall(float sec, float perSec) {
            rpmFallSec = sec;
            rpmFallPerSec = perSec;
            return this;
        }

        Builder load(float attackSec, float releaseSec, float brakeReleaseSec) {
            loadAttackSec = attackSec;
            loadReleaseSec = releaseSec;
            this.brakeReleaseSec = brakeReleaseSec;
            return this;
        }

        Builder order(float order, float db, float loadDb) {
            orders.add(order);
            orderDb.add(db);
            orderLoadDb.add(loadDb);
            return this;
        }

        Builder intake(float centerOrder, float q, float db) {
            intakeCenterOrder = centerOrder;
            intakeQ = q;
            intakeDb = db;
            return this;
        }

        Builder exhaust(float db) {
            exhaustDb = db;
            return this;
        }

        Builder resonance(float hz, float db, float q) {
            resonanceHz.add(hz);
            resonanceDb.add(db);
            resonanceQ.add(q);
            return this;
        }

        Builder shifts(float upBase, float upLoad, float downRatio, float sec, float dipDb) {
            shiftUpBaseRpm = upBase;
            shiftUpLoadRpm = upLoad;
            shiftDownRatio = downRatio;
            shiftSec = sec;
            shiftDipDb = dipDb;
            return this;
        }

        /** Level change (dB) at idle and during the start-up only; driving is untouched. */
        Builder idle(float db) {
            idleDb = db;
            return this;
        }

        /** Extra level (dB, scaled by speed) while off the pedal at speed, so lift-off and downshifts stay audible. */
        Builder coast(float db) {
            coastDb = db;
            return this;
        }

        /** Off the pedal at speed the box drops a gear once the engine falls below this rpm (0 = normal rule). */
        Builder liftDown(float rpm) {
            liftDownRpm = rpm;
            return this;
        }

        /** Top of the revs in P/N with the pedal flat (default: the shared 4500). */
        Builder freeRev(float rpm) {
            freeRevRpm = rpm;
            return this;
        }

        Builder downSlew(float sec) {
            downSlewSec = sec;
            return this;
        }

        /** Downshift rev blip: extra rpm and the engine load it is voiced with. */
        Builder downshiftBlip(float rpm, float load) {
            blipRpm = rpm;
            blipLoad = load;
            return this;
        }

        Builder loudness(float levelPow, float norm) {
            this.levelPow = levelPow;
            this.norm = norm;
            return this;
        }

        SoundProfile build() {
            if (orders.isEmpty() || !(maxRpm > idleRpm)) {
                throw new IllegalStateException("sound profile incomplete");
            }
            return new SoundProfile(this);
        }
    }
}
