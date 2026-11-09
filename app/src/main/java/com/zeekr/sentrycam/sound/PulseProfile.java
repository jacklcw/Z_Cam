/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.zeekr.sentrycam.sound;

final class PulseProfile {

    final int[] firing;
    final float[] trim;
    final float[] headerMs;
    final float[] bankMs;
    final float exhaustOpenDeg;
    final float exhaustCloseDeg;
    final float intakeCloseDeg;
    final float burnDeg;

    final float riseIdleMs;
    final float riseLoadMs;
    final float decayDeg;
    final float recoveryDeg;
    final float recoveryIdle;
    final float recoveryLoad;
    final float sigmaIdle;
    final float sigmaLoad;
    final float sigmaOverrun;
    final float jitterIdleDeg;
    final float jitterLoadDeg;

    final float raspIdleDb;
    final float raspLoadDb;
    final float raspOverrunDb;
    final float raspFloor;
    final float raspDecayDeg;
    final float raspHighpassHz;
    final float raspTiltHz;
    final float raspTiltRpmPow;
    final float raspAir;
    final float[] raspRpmDb;
    final float raspRefRpm;
    final float raspRpmMaxDb;
    final float raspPeakHz;
    final float raspPeakDb;
    final float raspPeakQ;

    final float[] pipeHz;
    final float pipeFeedback;
    final float pipeLossHz;
    final float[] formantHz;
    final float[] formantDb;
    final float[] formantQ;
    final float[] helmholtzHz;
    final float helmholtzDb;
    final float helmholtzQ;
    final float mufflerHz;
    final float mufflerPow;
    final float mufflerTopHz;
    final float mufflerLoad;
    final float radiationHz;
    final float driveIdle;
    final float driveLoad;
    final float cross;
    final float bassMonoHz;
    final float meshSpread;
    final float plenumPan;
    final float widthDb;
    final float widthHz;
    final float rpmLoudDb;
    final float levelPow;
    final float cabinLevelPow;
    final float cutDipDb;
    final float norm;

    final float intakeIdleDb;
    final float intakeLoadDb;
    final float plenumHz;
    final float plenumQ;
    final float runnerHz;
    final float runnerQ;
    final float roarIdleDb;
    final float roarLoadDb;
    final float roarHz;
    final float roarQ;
    final float hissDb;
    final float hissHz;
    final float hissQ;
    final float hissDecayMs;
    final float hissFloor;

    final float superchargerOrder;
    final float lobeDb;
    final float meshMultiple;
    final float[][] meshHarmonicsDb;
    final float[] rotorLobes;
    final float[] rotorAm;
    final float superchargerDb;
    final float toneRpmPow;
    final float superchargerRpmPow;
    final float[] meshRpm;
    final float[] meshRpmDb;
    final float torsion;
    final float[] meshVoiceDetune;
    final float meshVoiceWander;
    final float meshVoiceWanderHz;
    final float superchargerFree;
    final float superchargerAm;
    final float superchargerSec;
    final float growlFree;
    final float[] bodyHz;
    final float bodyQ;
    final float bodyTopHz;
    final float bodyDb;
    final float steadyWhineDb;
    final float pressBurst;
    final float pressBurstSec;
    final float pulsationDb;
    final float pulsationTopHz;
    final float[] pulsationRpm;
    final float[] pulsationRpmDb;
    final float[] pulsationOrders;
    final float pulsationQ;
    final float pulsationRecovery;
    final float pulsationLagMs;
    final float pulsationFree;
    final float dischargeDb;
    final int[] dischargeHarmonics;
    final float[] dischargeHarmonicsDb;
    final float intercoolerHz;
    final float[] ductHz;
    final float ductDb;
    final float ductQ;
    final float chargeHeat;
    final float jetDb;
    final float[] jetHz;
    final float[] jetQ;
    final float[] jetGainDb;
    final float jetPulse;
    final float airLowpassHz;
    final float inletDb;
    final float inletHz;
    final float inletQ;
    final float inletRam;
    final float decelDb;
    final float decelRpmPow;
    final int[] decelHarmonics;
    final float[] decelHarmonicsDb;
    final float decelBurst;
    final float decelBurstSec;

    final float cylinderPan;
    final float cylinderNearM;
    final float cylinderPitchM;
    final float exhaustLagMs;
    final float jetPan;
    final float inletPan;

    final float[] thunkHz;
    final float[] thunkMs;
    final float[] thunkGainDb;
    final float thunkDb;
    final float clickHz;
    final float clickQ;
    final float clickDb;
    final float rockHz;
    final float rockMs;
    final float rockDb;

    final float[] cabinFrontHz;
    final float[] cabinRearHz;
    final float cabinLowHz;
    final float cabinLowDb;
    final float cabinGainDb;


    final float tickDb;
    final float[] tickHz;
    final float[] tickQ;
    final float knockDb;
    final float[] knockHz;
    final float[] knockQ;
    final float[] cabinHz;
    final float[] cabinQ;
    final float[] cabinGain;
    final float[] sympatheticHz;
    final float[] sympatheticQ;
    final float[] sympatheticGain;

    final float[] gearOrders;
    final float cvtOrder;
    final float gearDb;
    final float diffHzPerKmh;
    final float diffDb;
    final float shaftHzPerKmh;
    final float shaftDb;

    final float pump;
    final float popMax;
    final float popGain;
    final float popRiseMs;
    final float popDecayMs;
    final float overrunLevelDb;
    final float fuelOutSec;
    final float gasColdK;
    final float gasHotK;
    final float gasRefK;
    final float heatFullRpm;
    final float heatUpSec;
    final float heatDownSec;
    final float[] cabinRaspRpm;
    final float[] cabinRaspRpmDb;
    final float[] cabinChordRpm;
    final float[] cabinChordRpmDb;

    private PulseProfile(Builder b) {
        firing = b.firing.clone();
        trim = b.trim.clone();
        headerMs = b.headerMs.clone();
        bankMs = b.bankMs.clone();
        exhaustOpenDeg = b.exhaustOpenDeg;
        exhaustCloseDeg = b.exhaustCloseDeg;
        intakeCloseDeg = b.intakeCloseDeg;
        burnDeg = b.burnDeg;
        riseIdleMs = b.riseIdleMs;
        riseLoadMs = b.riseLoadMs;
        decayDeg = b.decayDeg;
        recoveryDeg = b.recoveryDeg;
        recoveryIdle = b.recoveryIdle;
        recoveryLoad = b.recoveryLoad;
        sigmaIdle = b.sigmaIdle;
        sigmaLoad = b.sigmaLoad;
        sigmaOverrun = b.sigmaOverrun;
        jitterIdleDeg = b.jitterIdleDeg;
        jitterLoadDeg = b.jitterLoadDeg;
        raspIdleDb = b.raspIdleDb;
        raspLoadDb = b.raspLoadDb;
        raspOverrunDb = b.raspOverrunDb;
        raspFloor = b.raspFloor;
        raspDecayDeg = b.raspDecayDeg;
        raspHighpassHz = b.raspHighpassHz;
        raspTiltHz = b.raspTiltHz;
        raspTiltRpmPow = b.raspTiltRpmPow;
        raspAir = b.raspAir;
        raspRpmDb = b.raspRpmDb.clone();
        raspRefRpm = b.raspRefRpm;
        raspRpmMaxDb = b.raspRpmMaxDb;
        raspPeakHz = b.raspPeakHz;
        raspPeakDb = b.raspPeakDb;
        raspPeakQ = b.raspPeakQ;
        pipeHz = b.pipeHz.clone();
        pipeFeedback = b.pipeFeedback;
        pipeLossHz = b.pipeLossHz;
        formantHz = b.formantHz.clone();
        formantDb = b.formantDb.clone();
        formantQ = b.formantQ.clone();
        helmholtzHz = b.helmholtzHz.clone();
        helmholtzDb = b.helmholtzDb;
        helmholtzQ = b.helmholtzQ;
        mufflerHz = b.mufflerHz;
        mufflerPow = b.mufflerPow;
        mufflerTopHz = b.mufflerTopHz;
        mufflerLoad = b.mufflerLoad;
        radiationHz = b.radiationHz;
        driveIdle = b.driveIdle;
        driveLoad = b.driveLoad;
        cross = b.cross;
        meshSpread = b.meshSpread;
        plenumPan = b.plenumPan;
        widthDb = b.widthDb;
        widthHz = b.widthHz;
        bassMonoHz = b.bassMonoHz;
        rpmLoudDb = b.rpmLoudDb;
        levelPow = b.levelPow;
        cabinLevelPow = b.cabinLevelPow;
        cutDipDb = b.cutDipDb;
        norm = b.norm;
        intakeIdleDb = b.intakeIdleDb;
        intakeLoadDb = b.intakeLoadDb;
        plenumHz = b.plenumHz;
        plenumQ = b.plenumQ;
        runnerHz = b.runnerHz;
        runnerQ = b.runnerQ;
        roarIdleDb = b.roarIdleDb;
        roarLoadDb = b.roarLoadDb;
        roarHz = b.roarHz;
        roarQ = b.roarQ;
        hissDb = b.hissDb;
        hissHz = b.hissHz;
        hissQ = b.hissQ;
        hissDecayMs = b.hissDecayMs;
        hissFloor = b.hissFloor;
        superchargerOrder = b.superchargerOrder;
        lobeDb = b.lobeDb;
        meshMultiple = b.meshMultiple;
        meshHarmonicsDb = new float[b.meshHarmonicsDb.length][];
        for (int i = 0; i < meshHarmonicsDb.length; i++) {
            meshHarmonicsDb[i] = b.meshHarmonicsDb[i].clone();
        }
        rotorLobes = b.rotorLobes.clone();
        rotorAm = b.rotorAm.clone();
        growlFree = b.growlFree;
        bodyHz = b.bodyHz.clone();
        bodyQ = b.bodyQ;
        bodyTopHz = b.bodyTopHz;
        bodyDb = b.bodyDb;
        steadyWhineDb = b.steadyWhineDb;
        pressBurst = b.pressBurst;
        pressBurstSec = b.pressBurstSec;
        superchargerDb = b.superchargerDb;
        toneRpmPow = b.toneRpmPow;
        superchargerRpmPow = b.superchargerRpmPow;
        meshRpm = b.meshRpm.clone();
        meshRpmDb = b.meshRpmDb.clone();
        torsion = b.torsion;
        meshVoiceDetune = b.meshVoiceDetune.clone();
        meshVoiceWander = b.meshVoiceWander;
        meshVoiceWanderHz = b.meshVoiceWanderHz;
        superchargerFree = b.superchargerFree;
        superchargerAm = b.superchargerAm;
        superchargerSec = b.superchargerSec;
        pulsationDb = b.pulsationDb;
        pulsationTopHz = b.pulsationTopHz;
        pulsationRpm = b.pulsationRpm.clone();
        pulsationRpmDb = b.pulsationRpmDb.clone();
        pulsationOrders = b.pulsationOrders.clone();
        pulsationQ = b.pulsationQ;
        pulsationRecovery = b.pulsationRecovery;
        pulsationLagMs = b.pulsationLagMs;
        pulsationFree = b.pulsationFree;
        dischargeDb = b.dischargeDb;
        dischargeHarmonics = b.dischargeHarmonics.clone();
        dischargeHarmonicsDb = b.dischargeHarmonicsDb.clone();
        intercoolerHz = b.intercoolerHz;
        ductHz = b.ductHz.clone();
        ductDb = b.ductDb;
        ductQ = b.ductQ;
        chargeHeat = b.chargeHeat;
        jetDb = b.jetDb;
        jetHz = b.jetHz.clone();
        jetQ = b.jetQ.clone();
        jetGainDb = b.jetGainDb.clone();
        jetPulse = b.jetPulse;
        airLowpassHz = b.airLowpassHz;
        inletDb = b.inletDb;
        inletHz = b.inletHz;
        inletQ = b.inletQ;
        inletRam = b.inletRam;
        decelDb = b.decelDb;
        decelRpmPow = b.decelRpmPow;
        decelHarmonics = b.decelHarmonics.clone();
        decelHarmonicsDb = b.decelHarmonicsDb.clone();
        decelBurst = b.decelBurst;
        decelBurstSec = b.decelBurstSec;
        cylinderPan = b.cylinderPan;
        cylinderNearM = b.cylinderNearM;
        cylinderPitchM = b.cylinderPitchM;
        exhaustLagMs = b.exhaustLagMs;
        jetPan = b.jetPan;
        inletPan = b.inletPan;
        thunkHz = b.thunkHz.clone();
        thunkMs = b.thunkMs.clone();
        thunkGainDb = b.thunkGainDb.clone();
        thunkDb = b.thunkDb;
        clickHz = b.clickHz;
        clickQ = b.clickQ;
        clickDb = b.clickDb;
        rockHz = b.rockHz;
        rockMs = b.rockMs;
        rockDb = b.rockDb;
        cabinFrontHz = b.cabinFrontHz.clone();
        cabinRearHz = b.cabinRearHz.clone();
        cabinLowHz = b.cabinLowHz;
        cabinLowDb = b.cabinLowDb;
        cabinGainDb = b.cabinGainDb;
        tickDb = b.tickDb;
        tickHz = b.tickHz.clone();
        tickQ = b.tickQ.clone();
        knockDb = b.knockDb;
        knockHz = b.knockHz.clone();
        knockQ = b.knockQ.clone();
        cabinHz = b.cabinHz.clone();
        cabinQ = b.cabinQ.clone();
        cabinGain = b.cabinGain.clone();
        sympatheticHz = b.sympatheticHz.clone();
        sympatheticQ = b.sympatheticQ.clone();
        sympatheticGain = b.sympatheticGain.clone();
        gearOrders = b.gearOrders.clone();
        cvtOrder = b.cvtOrder;
        gearDb = b.gearDb;
        diffHzPerKmh = b.diffHzPerKmh;
        diffDb = b.diffDb;
        shaftHzPerKmh = b.shaftHzPerKmh;
        shaftDb = b.shaftDb;
        pump = b.pump;
        popMax = b.popMax;
        popGain = b.popGain;
        popRiseMs = b.popRiseMs;
        popDecayMs = b.popDecayMs;
        overrunLevelDb = b.overrunLevelDb;
        fuelOutSec = b.fuelOutSec;
        gasColdK = b.gasColdK;
        gasHotK = b.gasHotK;
        gasRefK = b.gasRefK;
        heatFullRpm = b.heatFullRpm;
        heatUpSec = b.heatUpSec;
        heatDownSec = b.heatDownSec;
        cabinRaspRpm = b.cabinRaspRpm.clone();
        cabinRaspRpmDb = b.cabinRaspRpmDb.clone();
        cabinChordRpm = b.cabinChordRpm.clone();
        cabinChordRpmDb = b.cabinChordRpmDb.clone();
    }

    static final PulseProfile SUPERCHARGED_V8 = new Builder()
            .supercharger(12.03f, -20.7f)
            .loudness(0.15f, 0.42f, 0.5f, 0.56f)
            .build();

    static final class Builder {
        private int[] firing = {1, 8, 4, 3, 6, 5, 7, 2};
        private float[] trim = {1.03f, 0.97f, 1.00f, 0.98f, 1.02f, 0.96f, 1.04f, 0.99f};
        private float[] headerMs = {0.10f, 0.29f, 0.24f, 0.14f, 0.17f, 0.26f, 0.31f, 0.11f};
        private float[] bankMs = {0f, 0.35f};
        private float exhaustOpenDeg = 130f;
        private float exhaustCloseDeg = 370f;
        private float intakeCloseDeg = 590f;
        private float burnDeg = 8f;
        private float riseIdleMs = 0.40f;
        private float riseLoadMs = 0.15f;
        private float decayDeg = 18f;
        private float recoveryDeg = 107f;
        private float recoveryIdle = 1.37f;
        private float recoveryLoad = 1.37f;
        private float sigmaIdle = 0.08f;
        private float sigmaLoad = 0.05f;
        private float sigmaOverrun = 0.30f;
        private float jitterIdleDeg = 1f;
        private float jitterLoadDeg = 0.5f;
        private float raspIdleDb = -16f;
        private float raspLoadDb = -1.5f;
        private float raspOverrunDb = -21f;
        private float raspFloor = 0.15f;
        private float raspDecayDeg = 45f;
        private float raspHighpassHz = 90f;
        private float raspTiltHz = 600f;
        private float raspTiltRpmPow = 0.5f;
        private float raspAir = 0.12f;
        private float[] raspRpmDb = {0f, 12f};
        private float raspRefRpm = 2850f;
        private float raspRpmMaxDb = 2f;
        private float raspPeakHz = 1200f;
        private float raspPeakDb = 3f;
        private float raspPeakQ = 0.7f;
        private float[] pipeHz = {172f, 164f};
        private float pipeFeedback = 0.5f;
        private float pipeLossHz = 1500f;
        private float[] formantHz = {150f, 330f, 680f};
        private float[] formantDb = {8f, 2f, 2f};
        private float[] formantQ = {1.4f, 2f, 2.5f};
        private float[] helmholtzHz = {56f, 61f};
        private float helmholtzDb = 10f;
        private float helmholtzQ = 3.5f;
        private float mufflerHz = 2100f;
        private float mufflerPow = 0.9f;
        private float mufflerTopHz = 12000f;
        private float mufflerLoad = 0.35f;
        private float radiationHz = 45f;
        private float driveIdle = 0.3f;
        private float driveLoad = 1.5f;
        private float cross = 0.25f;
        private float meshSpread = 0.6f;
        private float plenumPan = 0.35f;
        private float widthDb = 3f;
        private float widthHz = 700f;
        private float bassMonoHz = 120f;
        private float rpmLoudDb = 5f;
        private float levelPow = 0.5f;
        private float cabinLevelPow = 0.5f;
        private float cutDipDb = -4.5f;
        private float norm = 0.79f;
        private float intakeIdleDb = -30f;
        private float intakeLoadDb = -12f;
        private float plenumHz = 95f;
        private float plenumQ = 2f;
        private float runnerHz = 420f;
        private float runnerQ = 4f;
        private float roarIdleDb = -40f;
        private float roarLoadDb = -14f;
        private float roarHz = 1800f;
        private float roarQ = 0.7f;
        private float hissDb = -22f;
        private float hissHz = 2000f;
        private float hissQ = 1f;
        private float hissDecayMs = 4f;
        private float hissFloor = 0f;
        private float superchargerOrder;
        private float lobeDb = -35f;
        private float meshMultiple = 2f;
        private float[][] meshHarmonicsDb = {
                {1.5f, 0f, -9.5f, -9.5f, -12f},
                {-0.5f, 0f, -6f, -9f, -12f},
                {-2.5f, 0f, -1.5f, -9.5f, -10f},
                {-3.5f, 0f, -3f, -14f, -19f},
                {-3.5f, 0f, -3f, -14f, -19f}};
        private float toneRpmPow = 0.8f;
        private float[] rotorLobes = {5f, 3f};
        private float[] rotorAm = {0.7f, 0.45f};
        private float growlFree = 0.15f;
        private float[] bodyHz = {550f, 950f};
        private float bodyQ = 2.5f;
        private float bodyTopHz = 1400f;
        private float bodyDb = 0f;
        private float steadyWhineDb = -4.2f;
        private float pressBurst = 4f;
        private float pressBurstSec = 0.25f;
        private float superchargerDb = -100f;
        private float superchargerRpmPow = 1.7f;
        private float[] meshRpm = {1300f, 1900f, 2350f, 2850f, 4000f};
        private float[] meshRpmDb = {9f, 14f, 8f, 0f, -4f};
        private float torsion = 0.003f;
        private float[] meshVoiceDetune = {0f, -0.0018f, 0.0022f};
        private float meshVoiceWander = 0.0045f;
        private float meshVoiceWanderHz = 2.5f;
        private float superchargerFree = 0.05f;
        private float superchargerAm = 0.6f;
        private float superchargerSec = 0.12f;
        private float pulsationDb = 1f;
        private float pulsationTopHz = 1000f;
        private float[] pulsationRpm = {2850f, 4000f, 5000f};
        private float[] pulsationRpmDb = {0f, -2f, -3f};
        private float[] pulsationOrders = {12f, 15.5f, 19.5f, 24.5f};
        private float pulsationQ = 4f;
        private float pulsationRecovery = 1.37f;
        private float pulsationLagMs = 2f;
        private float pulsationFree = 0.15f;
        private float dischargeDb = -23f;
        private int[] dischargeHarmonics = {1, 3, 5};
        private float[] dischargeHarmonicsDb = {-14f, 0f, -9f};
        private float intercoolerHz = 2600f;
        private float[] ductHz = {820f, 960f};
        private float ductDb = 6f;
        private float ductQ = 2f;
        private float chargeHeat = 1.27f;
        private float jetDb = -17f;
        private float[] jetHz = {1800f, 3200f, 5500f};
        private float[] jetQ = {1.2f, 1.4f, 1.6f};
        private float[] jetGainDb = {0f, -1f, -6f};
        private float jetPulse = 0.8f;
        private float airLowpassHz = 7000f;
        private float inletDb = -17f;
        private float inletHz = 1600f;
        private float inletQ = 0.7f;
        private float inletRam = 3f;
        private float decelDb = -21f;
        private float decelRpmPow = 2.4f;
        private int[] decelHarmonics = {2, 3, 4};
        private float[] decelHarmonicsDb = {0f, -7f, -9f};
        private float decelBurst = 1.5f;
        private float decelBurstSec = 0.15f;
        private float cylinderPan = 0.6f;
        private float cylinderNearM = 1.7f;
        private float cylinderPitchM = 0.12f;
        private float exhaustLagMs = 10f;
        private float jetPan = 0.7f;
        private float inletPan = -0.45f;
        private float[] thunkHz = {55f, 140f};
        private float[] thunkMs = {35f, 12f};
        private float[] thunkGainDb = {0f, -8f};
        private float thunkDb = -11f;
        private float rockHz = 40f;
        private float rockMs = 90f;
        private float rockDb = -13f;
        private float clickHz = 2200f;
        private float clickQ = 1.5f;
        private float clickDb = -14f;
        private float[] cabinFrontHz = {600f, 3500f};
        private float[] cabinRearHz = {350f, 2000f};
        private float cabinLowHz = 100f;
        private float cabinLowDb = 4f;
        private float cabinGainDb = 3.5f;
        private float tickDb = -34f;
        private float[] tickHz = {3100f, 4800f};
        private float[] tickQ = {14f, 10f};
        private float knockDb = -26f;
        private float[] knockHz = {820f, 1450f, 2300f};
        private float[] knockQ = {6f, 7f, 8f};
        private float[] cabinHz = {42f, 63f, 96f, 135f};
        private float[] cabinQ = {5f, 6f, 5f, 4f};
        private float[] cabinGain = {0.8f, 0.7f, 0.5f, 0.4f};
        private float[] sympatheticHz = {333f};
        private float[] sympatheticQ = {30f};
        private float[] sympatheticGain = {0.15f};
        private float[] gearOrders = {35.3f, 30.2f, 27.4f, 25.1f, 23.3f, 21.8f, 20.6f, 19.7f};
        private float cvtOrder = 26.3f;
        private float gearDb = -36f;
        private float diffHzPerKmh = 4.16f;
        private float diffDb = -40f;
        private float shaftHzPerKmh = 0.69f;
        private float shaftDb = -36f;
        private float pump = 0.45f;
        private float popMax = 0.05f;
        private float popGain = 1.2f;
        private float popRiseMs = 0.3f;
        private float popDecayMs = 5f;
        private float overrunLevelDb = 14f;
        private float fuelOutSec = 0.35f;
        private float gasColdK = 450f;
        private float gasHotK = 1000f;
        private float gasRefK = 495f;
        private float heatFullRpm = 6500f;
        private float heatUpSec = 3f;
        private float heatDownSec = 5f;
        private float[] cabinRaspRpm = {3000f, 5500f};
        private float[] cabinRaspRpmDb = {0f, -8f};
        private float[] cabinChordRpm = {2500f, 3500f, 6000f};
        private float[] cabinChordRpmDb = {3f, 0f, -8f};

        Builder variation(float sigmaIdle, float sigmaLoad, float sigmaOverrun, float jitterIdleDeg, float jitterLoadDeg) {
            this.sigmaIdle = sigmaIdle;
            this.sigmaLoad = sigmaLoad;
            this.sigmaOverrun = sigmaOverrun;
            this.jitterIdleDeg = jitterIdleDeg;
            this.jitterLoadDeg = jitterLoadDeg;
            return this;
        }

        Builder recovery(float deg, float idle, float load) {
            recoveryDeg = deg;
            recoveryIdle = idle;
            recoveryLoad = load;
            return this;
        }

        Builder loudness(float levelPow, float cabinLevelPow, float rpmLoudDb, float norm) {
            this.levelPow = levelPow;
            this.cabinLevelPow = cabinLevelPow;
            this.rpmLoudDb = rpmLoudDb;
            this.norm = norm;
            return this;
        }

        Builder supercharger(float order, float db) {
            superchargerOrder = order;
            superchargerDb = db;
            return this;
        }

        Builder discharge(float db) {
            dischargeDb = db;
            return this;
        }

        Builder decel(float db) {
            decelDb = db;
            return this;
        }

        Builder hiss(float db) {
            hissDb = db;
            return this;
        }

        Builder body(float db) {
            bodyDb = db;
            return this;
        }

        Builder press(float burst) {
            pressBurst = burst;
            return this;
        }

        Builder steadyWhine(float db) {
            steadyWhineDb = db;
            return this;
        }

        Builder width(float db) {
            widthDb = db;
            return this;
        }

        PulseProfile build() {
            if (firing.length == 0 || trim.length < firing.length || headerMs.length < firing.length
                    || pipeHz.length != 2 || helmholtzHz.length != 2 || bankMs.length != 2
                    || rotorLobes.length != 2 || rotorAm.length != 2
                    || jetQ.length != jetHz.length || jetGainDb.length != jetHz.length || raspRpmDb.length != 2
                    || thunkMs.length != thunkHz.length || thunkGainDb.length != thunkHz.length
                    || cabinFrontHz.length != 2 || cabinRearHz.length != 2
                    || meshHarmonicsDb.length != meshRpm.length || meshRpmDb.length != meshRpm.length || meshVoiceDetune.length == 0
                    || pulsationRpmDb.length != pulsationRpm.length || cabinChordRpmDb.length != cabinChordRpm.length
                    || cabinRaspRpmDb.length != cabinRaspRpm.length
                    || dischargeHarmonicsDb.length != dischargeHarmonics.length || ductHz.length != 2
                    || decelHarmonicsDb.length != decelHarmonics.length) {
                throw new IllegalStateException("pulse profile incomplete");
            }
            for (int c : firing) {
                if (c < 1 || c > trim.length) {
                    throw new IllegalStateException("cylinder " + c);
                }
            }
            return new PulseProfile(this);
        }
    }
}
