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
            .shifts(2400f, 4200f, 0.55f, 0.06f, -4.5f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Nissan GT-R: 3.8 twin-turbo V6 (uneven 90 degree firing, so 1.5 order is strong), deep and burbly, 7000 rpm. */
    static final SoundProfile GTR = new Builder()
            .rpm(750f, 7000f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.11f, 5500f)
            .fall(0.45f, 3800f)
            .load(0.07f, 0.30f, 0.12f)
            .order(3f, 0f, 0f)
            .order(1.5f, -5f, 2f)
            .order(6f, -7f, 4f)
            .order(4.5f, -11f, 3f)
            .order(9f, -13f, 5f)
            .order(1f, -14f, 0f)
            .order(0.5f, -22f, 6f)
            .order(14f, -24f, 8f)
            .intake(28f, 1.4f, -17f)
            .exhaust(-12f)
            .resonance(110f, 5f, 1.0f)
            .resonance(850f, 3f, 1.3f)
            .resonance(2600f, 3f, 2.0f)
            .shifts(2600f, 4000f, 0.58f, 0.05f, -4.5f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Ferrari: flat-plane V8, even firing so almost only whole orders; light, smooth and high, 9000 rpm. */
    static final SoundProfile FERRARI = new Builder()
            .rpm(1000f, 9000f)
            .continuous(0.30f, 0.32f, 75f)
            .rise(0.07f, 9000f)
            .fall(0.30f, 6500f)
            .load(0.04f, 0.18f, 0.10f)
            .order(4f, 0f, 0f)
            .order(8f, -4f, 4f)
            .order(12f, -8f, 6f)
            .order(16f, -12f, 6f)
            .order(20f, -17f, 6f)
            .order(2f, -16f, 0f)
            .order(24f, -23f, 6f)
            .intake(40f, 1.6f, -15f)
            .exhaust(-17f)
            .resonance(180f, 3f, 1.0f)
            .resonance(1800f, 4f, 1.4f)
            .resonance(3200f, 3f, 1.8f)
            .shifts(4200f, 4000f, 0.65f, 0.04f, -4f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Lamborghini: naturally aspirated V10, 5th order, a wide and hard wail, 8500 rpm. */
    static final SoundProfile LAMBO = new Builder()
            .rpm(950f, 8500f)
            .continuous(0.30f, 0.32f, 75f)
            .rise(0.08f, 8500f)
            .fall(0.32f, 6000f)
            .load(0.05f, 0.20f, 0.10f)
            .order(5f, 0f, 0f)
            .order(10f, -4f, 4f)
            .order(15f, -9f, 6f)
            .order(20f, -13f, 6f)
            .order(2.5f, -15f, 2f)
            .order(25f, -20f, 6f)
            .order(1f, -18f, 0f)
            .order(7.5f, -14f, 3f)
            .intake(36f, 1.5f, -15f)
            .exhaust(-14f)
            .resonance(160f, 4f, 1.0f)
            .resonance(1500f, 4f, 1.3f)
            .resonance(3000f, 3f, 1.8f)
            .shifts(3800f, 4000f, 0.64f, 0.04f, -4f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Porsche: naturally aspirated flat-six, 3rd order, a metallic rasp that gets harder with revs, 7800 rpm. */
    static final SoundProfile PORSCHE = new Builder()
            .rpm(800f, 7800f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.08f, 7800f)
            .fall(0.35f, 5000f)
            .load(0.05f, 0.22f, 0.12f)
            .order(3f, 0f, 0f)
            .order(6f, -4f, 3f)
            .order(9f, -9f, 5f)
            .order(12f, -15f, 6f)
            .order(1.5f, -18f, 3f)
            .order(18f, -22f, 7f)
            .order(1f, -20f, 0f)
            .intake(34f, 1.8f, -15f)
            .exhaust(-16f)
            .resonance(140f, 3f, 1.0f)
            .resonance(1400f, 4f, 1.5f)
            .resonance(2800f, 4f, 2.0f)
            .shifts(3400f, 3800f, 0.62f, 0.05f, -4f)
            .loudness(0.17f, 0.8f)
            .build();

    /** Toyota Supra: turbo inline-six, very even and smooth, 3rd order with a deep low end, 7000 rpm. */
    static final SoundProfile SUPRA = new Builder()
            .rpm(750f, 7000f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.10f, 6000f)
            .fall(0.40f, 4000f)
            .load(0.06f, 0.26f, 0.12f)
            .order(3f, 0f, 0f)
            .order(6f, -6f, 3f)
            .order(1.5f, -20f, 3f)
            .order(9f, -12f, 4f)
            .order(12f, -17f, 6f)
            .order(1f, -15f, 0f)
            .order(0.5f, -24f, 6f)
            .intake(30f, 1.4f, -17f)
            .exhaust(-13f)
            .resonance(120f, 4f, 1.0f)
            .resonance(1000f, 3f, 1.2f)
            .shifts(2600f, 4200f, 0.56f, 0.06f, -4.5f)
            .loudness(0.17f, 0.8f)
            .build();

    /**
     * Porsche Taycan Turbo S, Electric Sport Sound: whole-order motor harmonics only (no half orders, so no burble or
     * combustion roughness), a bright airy whoosh, revs that follow speed and rarely shift (the real car sweeps).
     */
    static final SoundProfile TAYCAN = new Builder()
            .rpm(1100f, 9000f)
            .continuous(0.55f, 0.35f, 110f)
            .rise(0.06f, 9000f)
            .fall(0.25f, 7000f)
            .load(0.04f, 0.22f, 0.10f)
            .order(4f, 0f, 0f)
            .order(8f, -3f, 3f)
            .order(12f, -6f, 4f)
            .order(16f, -10f, 5f)
            .order(2f, -8f, 0f)
            .order(6f, -12f, 3f)
            .order(24f, -15f, 6f)
            .order(36f, -22f, 6f)
            .intake(60f, 1.2f, -14f)
            .exhaust(-30f)
            .resonance(900f, 4f, 1.2f)
            .resonance(2400f, 3f, 1.5f)
            .shifts(8200f, 800f, 0.80f, 0.04f, -3f)
            .loudness(0.17f, 0.8f)
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
