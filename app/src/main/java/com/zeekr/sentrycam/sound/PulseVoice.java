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
 *
 *
 */
final class PulseVoice implements Voice {

    private static final int RING = 2048;
    private static final int MASK = RING - 1;
    private static final float MAX_DELAY = RING - 64;
    private static final int PIPE = 1024;
    private static final int PIPE_MASK = PIPE - 1;

    private static final int EXHAUST = 0;
    private static final int EXHAUST_CLOSE = 1;
    private static final int INTAKE_CLOSE = 2;
    private static final int BURN = 3;

    static final float CENTER = 0.7f;
    static final float CENTER_PAN = CENTER * 1.4142135f;
    static final float CYLINDER_NORM = 0.825f;
    static final float SOUND_MPS = 343f;
    static final float CUT_FLOOR = 0.15f;
    static final float CUT_RASP = 0.6f;
    static final float CUT_POP = 0.5f;
    /** Z_Cam: scales the exhaust pops (0 = none, 1 = as ported); set from the Engine sound card. */
    static volatile float crackleScale = 1f;
    static final float CLICK_SEC = 0.004f;
    static final float CLICK_DECAY_SEC = 0.0008f;
    static final float DOWNSHIFT_THUNK = 0.7f;
    static final float RAM_KMH = 200f;
    static final float ROAR_FULL_RPM = 600f;
    static final float TILT_TOP_HZ = 4000f;
    static final float CRANK_LEVEL = 0.15f;
    static final float UNFIRED_LEVEL = 0.35f;
    static final float UNFIRED_FULL_RPM = 300f;
    static final float MISFIRE = 0.3f;
    static final float CATCH_SEC = 0.25f;
    static final float CATCH_ROCK = 1f;
    static final float ROLL_CATCH_ROCK = 0.6f;
    static final float SETTLE_ROCK = 0.35f;
    static final float MOVING_SETTLE_ROCK = 0.15f;
    static final float ROCK_ATTACK_SEC = 0.003f;
    static final float INTAKE_DECAY_DEG = 30f;
    static final float INTAKE_RISE_MS = 1f;
    static final float LIFT_PEAK_SEC = 1.5f;
    static final float LIFT_DROP = 0.25f;
    static final float WHOOSH_DB = -26f;
    static final float WHOOSH_SEC = 0.3f;
    static final float WHOOSH_HZ = 1200f;
    static final float CABIN_TONE_DB = -6f;
    static final float CABIN_TONE_HZ = 3500f;
    static final float WHINE_TOP_HZ = 10000f;
    static final float WHINE_FADE_HZ = 3000f;
    static final double MALE_PHASE = 1.3 / (2.0 * Math.PI);
    static final float JET_FREE = 0.15f;
    static final float INLET_FREE = 0.25f;
    static final float PUFF_MEAN = 0.3125f;
    static final float GEAR_SEC = 0.05f;
    static final float CABIN_LOWPASS_HZ = 200f;
    static final float POP_SPREAD = 20f;
    static final float HISS_RISE_SEC = 0.0005f;
    static final float HISS_REF_RPM = 900f;
    static final float DECEL_REF_RPM = 4500f;
    static final double DECEL_PHASE = 0.4;

    private final PulseProfile p;
    private final float rate;

    private final float[] evAngle;
    private final int[] evKind;
    private final int[] evCyl;
    private int nextEvent;
    private double theta;
    private double cycleBase;
    private double nextAngle;
    private final int[] bankOf;
    private final float[] gapDeg;
    private final float[] headerSamples;
    private final float[] bankSamples;
    private final float lagSamples;
    private final float[] cylGain;
    private final float[] cylDelay;
    private final float[] cylLeft;
    private final float[] cylRight;

    private final float[][] exc = new float[2][RING];
    private final float[][] kick = new float[2][RING];
    private final float[][] pop = new float[2][RING];
    private final float[][] tick = new float[2][RING];
    private final float[][] knock = new float[2][RING];
    private final float[] intake = new float[RING];
    private final float[][] pulsation = new float[2][RING];
    private final float[] click = new float[RING];
    private final float[] hiss = new float[RING];
    private int cursor;

    private float evCombustion;
    private float evSigma;
    private float evMisfire;
    private float evPop;
    private float evRecovery;
    private float evJitter;
    private float evSamplesPerDeg;
    private float evRasp;
    private float evTick;
    private float evKnock;
    private float evIntake;
    private float evPulsation;
    private float evCut;
    private float evHiss;

    private final float[] pulseDecay = new float[2];
    private final float[] pulseRise = new float[2];
    private final float[] popDecay = new float[2];
    private final float[] popRise = new float[2];
    private final float[] envelope = new float[2];
    private final float[] tilt = new float[2];
    private final Biquad[] noiseHighpass = new Biquad[2];
    private final Biquad[] noisePeak = new Biquad[2];
    private final float[][] pipe = new float[2][PIPE];
    private final float[] pipeDelay = new float[2];
    private final float[] pipeLoss = new float[2];
    private int pipeWrite;
    private final Biquad[][] formants = new Biquad[2][];
    private final Biquad[] muffler = new Biquad[2];
    private final Biquad[] radiation = new Biquad[2];
    private final float[] bankOut = new float[2];
    private float tiltK;
    private float tiltNorm;
    private final float pipeK;
    private final float popDecayA;
    private final float popRiseA;

    private float intakeDecay;
    private float intakeRise;
    private final Biquad plenum;
    private final Biquad runner;
    private final Biquad roar;
    private float roarNow;
    private float hissDecay;
    private float hissRise;
    private final float hissDecayA;
    private final float hissRiseA;
    private final float hissNorm;
    private final Biquad hissBand;
    private final Biquad hissBand2;
    private float hissTarget;
    private float hissNow;

    private final boolean supercharged;
    private final float lobeGain;
    private final float[] meshGains;
    private float meshNow;
    private final double[] voicePhase;
    private final float[] voiceNoise;
    private final float voiceK;
    private final float voiceNorm;
    private final float voiceScale;
    private final Biquad[][] pulsationModes;
    private final float[] pulsationGains;
    private final Biquad[] pulsationTop = new Biquad[2];
    private final float[] plenumLeft = new float[2];
    private final float[] plenumRight = new float[2];
    private final float[] voiceLeft;
    private final float[] voiceRight;
    private float whineL;
    private float whineR;
    private float lobeNow;
    private final float steadyWhine;
    private final float widthGain;
    private final float widthK;
    private float widthLp;
    private final float pulsationLagSamples;
    private double lobePhase;
    private double femalePhase;
    private double malePhase;
    private final float[] dischargeGains;
    private float dischargeTarget;
    private float dischargeNow;
    private final Biquad[] intercooler = new Biquad[2];
    private final Biquad[] duct = new Biquad[2];
    private final float[] decelGains;
    private float decelTarget;
    private float decelNow;
    private float decelKick;
    private float growlTarget;
    private float growlNow;
    private final Biquad[] bodyModes;
    private final Biquad bodyTop;
    private final float bodyGain;
    private float pressKick;
    private float pressNow;
    private float boost;
    private float whoosh;
    private float whineNow;
    private float jetNow;
    private float inletNow;
    private float whooshNow;
    private final Biquad[][] jetBands = new Biquad[2][];
    private final float[] jetGains;
    private final Biquad[] airLowpass = new Biquad[2];
    private final Biquad inletBand;
    private final Biquad whooshBand;
    private float loadPeak;
    private final float inletLeft;
    private final float inletRight;
    private final float jetNear;
    private final float jetFar;

    private final Biquad[][] tickModes = new Biquad[2][];
    private final Biquad[][] knockModes = new Biquad[2][];
    private final Biquad cabinLowpass;
    private final Biquad[] cabinModes;
    private final Biquad[] sympathetic;

    private double gearPhase;
    private double diffPhase;
    private double shaftPhase;
    private double reversePhase;
    private double starterPhase;
    private float gearNow;
    private float diffNow;
    private float shaftNow;
    private float reverseNow;
    private float starterNow;

    private final float[] thunkEnv;
    private final double[] thunkPhase;
    private final float[] thunkDecay;
    private final float[] thunkStep;
    private final Biquad clickBand;
    private boolean serialSeen;
    private int lastSerial;
    private float rockEnv;
    private float rockNow;
    private double rockPhase;
    private final float rockDecay;
    private final float rockAttackK;
    private final float rockStep;
    private int lastCatch;

    private final boolean cabin;
    private final float levelPow;
    private final float[] frontK;
    private final float[] rearK;
    private final float[][] frontLp = new float[2][2];
    private final float[][] rearLp = new float[2][2];
    private final Biquad[] cabinLow = new Biquad[2];
    private final float cabinGain;
    private float toneLpL;
    private float toneLp2L;
    private float toneLpR;
    private float toneLp2R;
    private final float toneK;
    private final float cabinToneGain;
    private float chordGain = 1f;

    private final Biquad sideHighpass;
    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private final float ampK;
    private final float gainK;
    private final float gearK;
    private float rpmNow = -1f;
    private float fuel = -1f;
    private float heat = -1f;
    private float levelNow;
    private float gainNow;
    private float catchEnv;
    private int noise = 0x2545F491;
    private int clickNoise = 0x1B873593;
    private int hissNoise = 0x3C6EF372;

    PulseVoice(PulseProfile profile, float sampleRate) {
        this(profile, sampleRate, false);
    }

    PulseVoice(PulseProfile profile, float sampleRate, boolean cabin) {
        this.p = profile;
        this.rate = sampleRate;
        this.cabin = cabin;
        levelPow = cabin ? profile.cabinLevelPow : profile.levelPow;
        int n = profile.firing.length;
        int cylinders = profile.trim.length;
        bankOf = new int[cylinders];
        gapDeg = new float[cylinders];
        headerSamples = new float[cylinders];
        cylGain = new float[cylinders];
        cylDelay = new float[cylinders];
        cylLeft = new float[cylinders];
        cylRight = new float[cylinders];
        for (int c : profile.firing) {
            bankOf[c - 1] = c % 2 == 1 ? 0 : 1;
            float d = profile.cylinderNearM + (3 - (c - 1) / 2) * profile.cylinderPitchM;
            cylGain[c - 1] = CYLINDER_NORM * profile.cylinderNearM / d;
            cylDelay[c - 1] = (d - profile.cylinderNearM) / SOUND_MPS * sampleRate;
            float x = bankOf[c - 1] == 0 ? -profile.cylinderPan : profile.cylinderPan;
            cylLeft[c - 1] = panLeft(x);
            cylRight[c - 1] = panRight(x);
        }
        lagSamples = profile.exhaustLagMs * 1e-3f * sampleRate;
        for (int k = 0; k < n; k++) {
            int c = profile.firing[k];
            int j = 1;
            while (bankOf[profile.firing[((k - j) % n + n) % n] - 1] != bankOf[c - 1]) {
                j++;
            }
            gapDeg[c - 1] = 720f * j / n;
            headerSamples[c - 1] = profile.headerMs[c - 1] * 1e-3f * sampleRate;
        }
        bankSamples = new float[] {profile.bankMs[0] * 1e-3f * sampleRate, profile.bankMs[1] * 1e-3f * sampleRate};
        int count = 4 * n;
        evAngle = new float[count];
        evKind = new int[count];
        evCyl = new int[count];
        float[] offsets = {profile.exhaustOpenDeg, profile.exhaustCloseDeg, profile.intakeCloseDeg, profile.burnDeg};
        int[] kinds = {EXHAUST, EXHAUST_CLOSE, INTAKE_CLOSE, BURN};
        int e = 0;
        for (int k = 0; k < n; k++) {
            float tdc = 720f * k / n;
            for (int j = 0; j < 4; j++) {
                evAngle[e] = (tdc + offsets[j]) % 720f;
                evKind[e] = kinds[j];
                evCyl[e] = profile.firing[k];
                e++;
            }
        }
        sortEvents();
        nextEvent = 0;
        nextAngle = evAngle[0];

        setTilt(profile.raspTiltHz);
        pipeK = onePole(profile.pipeLossHz);
        popDecayA = perSample(profile.popDecayMs * 1e-3f);
        popRiseA = perSample(profile.popRiseMs * 1e-3f);
        for (int b = 0; b < 2; b++) {
            noiseHighpass[b] = Biquad.highpass(profile.raspHighpassHz, 0.7071f, sampleRate);
            noisePeak[b] = Biquad.peaking(profile.raspPeakHz, profile.raspPeakDb, profile.raspPeakQ, sampleRate);
            pipeDelay[b] = Math.max(1f, Math.min(PIPE - 2, sampleRate / profile.pipeHz[b]));
            int f = profile.formantHz.length;
            formants[b] = new Biquad[f + 1];
            for (int k = 0; k < f; k++) {
                formants[b][k] = Biquad.peaking(profile.formantHz[k], profile.formantDb[k], profile.formantQ[k], sampleRate);
            }
            formants[b][f] = Biquad.peaking(profile.helmholtzHz[b], profile.helmholtzDb, profile.helmholtzQ, sampleRate);
            muffler[b] = Biquad.lowpass(profile.mufflerHz, 0.6f, sampleRate);
            radiation[b] = Biquad.highpass(profile.radiationHz, 0.6f, sampleRate);
            tickModes[b] = bank(profile.tickHz, profile.tickQ);
            knockModes[b] = bank(profile.knockHz, profile.knockQ);
            highpass[b] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[b] = new Biquad();
        }
        limiter = new Limiter(sampleRate);
        plenum = Biquad.bandpass(profile.plenumHz, profile.plenumQ, sampleRate);
        runner = Biquad.bandpass(profile.runnerHz, profile.runnerQ, sampleRate);
        roar = Biquad.bandpass(profile.roarHz, profile.roarQ, sampleRate);
        hissBand = Biquad.bandpass(profile.hissHz, profile.hissQ, sampleRate);
        hissBand2 = Biquad.bandpass(profile.hissHz, profile.hissQ, sampleRate);
        double hd = profile.hissDecayMs * 1e-3;
        double hr = HISS_RISE_SEC;
        double hp = Math.log(hd / hr) * hd * hr / (hd - hr);
        hissNorm = (float) (1.0 / (Math.exp(-hp / hd) - Math.exp(-hp / hr)));
        hissDecayA = perSample((float) hd);
        hissRiseA = perSample(HISS_RISE_SEC);
        supercharged = profile.superchargerOrder > 0f;
        lobeGain = EngineVoice.dbToLin(profile.lobeDb);
        meshGains = new float[profile.meshHarmonicsDb[0].length];
        voicePhase = new double[profile.meshVoiceDetune.length];
        voiceNoise = new float[voicePhase.length];
        voiceK = onePole(profile.meshVoiceWanderHz);
        voiceNorm = (float) (1.0 / Math.sqrt(voiceK / (2.0 - voiceK)));
        voiceScale = (float) (1.0 / Math.sqrt(voicePhase.length));
        pulsationModes = new Biquad[2][profile.pulsationOrders.length];
        for (int b = 0; b < 2; b++) {
            for (int k = 0; k < profile.pulsationOrders.length; k++) {
                pulsationModes[b][k] = new Biquad();
            }
            pulsationTop[b] = Biquad.lowpass(profile.pulsationTopHz, 0.7071f, sampleRate);
            float x = b == 0 ? -profile.plenumPan : profile.plenumPan;
            plenumLeft[b] = CENTER_PAN * panLeft(x);
            plenumRight[b] = CENTER_PAN * panRight(x);
        }
        pulsationGains = new float[profile.pulsationOrders.length];
        voiceLeft = new float[profile.meshVoiceDetune.length];
        voiceRight = new float[voiceLeft.length];
        for (int v = 0; v < voiceLeft.length; v++) {
            float x = v == 0 ? 0f : (v % 2 == 1 ? -1f : 1f) * profile.meshSpread;
            voiceLeft[v] = CENTER_PAN * panLeft(x);
            voiceRight[v] = CENTER_PAN * panRight(x);
        }
        widthGain = EngineVoice.dbToLin(profile.widthDb);
        steadyWhine = EngineVoice.dbToLin(profile.steadyWhineDb);
        widthK = onePole(profile.widthHz);
        pulsationLagSamples = profile.pulsationLagMs * 1e-3f * sampleRate;
        dischargeGains = new float[profile.dischargeHarmonics.length];
        for (int k = 0; k < dischargeGains.length; k++) {
            dischargeGains[k] = EngineVoice.dbToLin(profile.dischargeHarmonicsDb[k]);
        }
        for (int s = 0; s < 2; s++) {
            intercooler[s] = Biquad.lowpass(profile.intercoolerHz, 0.7071f, sampleRate);
            duct[s] = Biquad.peaking(profile.ductHz[s], profile.ductDb, profile.ductQ, sampleRate);
        }
        bodyModes = new Biquad[profile.bodyHz.length];
        for (int k = 0; k < bodyModes.length; k++) {
            bodyModes[k] = Biquad.bandpass(profile.bodyHz[k], profile.bodyQ, sampleRate);
        }
        bodyTop = Biquad.lowpass(profile.bodyTopHz, 0.7071f, sampleRate);
        bodyGain = EngineVoice.dbToLin(profile.bodyDb);
        decelGains = new float[profile.decelHarmonics.length];
        for (int k = 0; k < decelGains.length; k++) {
            decelGains[k] = EngineVoice.dbToLin(profile.decelHarmonicsDb[k]);
        }
        jetGains = new float[profile.jetHz.length];
        for (int k = 0; k < jetGains.length; k++) {
            jetGains[k] = EngineVoice.dbToLin(profile.jetGainDb[k]);
        }
        for (int b = 0; b < 2; b++) {
            jetBands[b] = bank(profile.jetHz, profile.jetQ);
            airLowpass[b] = Biquad.lowpass(profile.airLowpassHz, 0.7071f, sampleRate);
        }
        inletBand = Biquad.bandpass(profile.inletHz, profile.inletQ, sampleRate);
        whooshBand = Biquad.bandpass(WHOOSH_HZ, 0.9f, sampleRate);
        inletLeft = CENTER_PAN * panLeft(profile.inletPan);
        inletRight = CENTER_PAN * panRight(profile.inletPan);
        jetNear = CENTER_PAN * panLeft(-profile.jetPan);
        jetFar = CENTER_PAN * panRight(-profile.jetPan);
        int modes = profile.thunkHz.length;
        thunkEnv = new float[modes];
        thunkPhase = new double[modes];
        thunkDecay = new float[modes];
        thunkStep = new float[modes];
        for (int k = 0; k < modes; k++) {
            thunkDecay[k] = perSample(profile.thunkMs[k] * 1e-3f);
            thunkStep[k] = profile.thunkHz[k] / sampleRate;
        }
        clickBand = Biquad.bandpass(profile.clickHz, profile.clickQ, sampleRate);
        rockDecay = perSample(profile.rockMs * 1e-3f);
        rockAttackK = 1f - perSample(ROCK_ATTACK_SEC);
        rockStep = profile.rockHz / sampleRate;
        frontK =new float[] {onePole(profile.cabinFrontHz[0]), onePole(profile.cabinFrontHz[1])};
        rearK = new float[] {onePole(profile.cabinRearHz[0]), onePole(profile.cabinRearHz[1])};
        for (int b = 0; b < 2; b++) {
            cabinLow[b] = Biquad.lowShelf(profile.cabinLowHz, profile.cabinLowDb, sampleRate);
        }
        cabinGain = EngineVoice.dbToLin(profile.cabinGainDb);
        toneK = onePole(CABIN_TONE_HZ);
        cabinToneGain = EngineVoice.dbToLin(CABIN_TONE_DB);
        cabinLowpass = Biquad.lowpass(CABIN_LOWPASS_HZ, 0.7071f, sampleRate);
        cabinModes = bank(profile.cabinHz, profile.cabinQ);
        sympathetic = bank(profile.sympatheticHz, profile.sympatheticQ);
        sideHighpass = Biquad.highpass(profile.bassMonoHz, 0.7071f, sampleRate);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        gearK = 1f - (float) Math.exp(-1.0 / (GEAR_SEC * sampleRate));
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        float rpmTarget = params.rpm > 0f ? params.rpm : 0f;
        if (rpmNow < 0f) {
            rpmNow = rpmTarget;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            limiter.reset();
            return;
        }
        PulseProfile p = this.p;
        float dt = frames / rate;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float starter = VirtualEngine.clamp01(params.starter);
        boolean cranking = starter > 0.5f;
        boolean unfired = cranking || params.fire < 0.5f;
        boolean moving = params.speedKmh > VirtualEngine.START_LEAVE_KMH;
        if (params.catchSerial != lastCatch) {
            lastCatch = params.catchSerial;
            catchEnv = 1f;
            rock(moving ? ROLL_CATCH_ROCK : CATCH_ROCK);
        }
        if (unfired && !cranking && rpmTarget <= 0f && rpmNow > 0f) {
            rock(moving ? MOVING_SETTLE_ROCK : SETTLE_ROCK);
        }
        catchEnv *= VirtualEngine.decay(dt, CATCH_SEC);
        float rpmCalc = Math.max(rpmTarget, 100f);

        float cut = VirtualEngine.clamp01(params.cut);
        evCut = cut;
        float fuelTarget = 1f - over * (1f - p.pump);
        if (fuel < 0f || fuelTarget >= fuel) {
            fuel = fuelTarget;
        } else {
            fuel += (fuelTarget - fuel) * VirtualEngine.follow(dt, p.fuelOutSec);
        }
        evCombustion = cranking ? CRANK_LEVEL
                : unfired ? UNFIRED_LEVEL * VirtualEngine.clamp01(rpmTarget / UNFIRED_FULL_RPM)
                : fuel * (1f - cut * (1f - CUT_FLOOR));
        evSigma = mix(p.sigmaIdle, p.sigmaLoad, load) + over * p.sigmaOverrun;
        evMisfire = MISFIRE * catchEnv;
        evPop = crackleScale * p.popMax * VirtualEngine.clamp01(0.1f * over + 0.6f * params.burble + CUT_POP * cut);
        evRecovery = mix(p.recoveryIdle, p.recoveryLoad, load);
        evJitter = mix(p.jitterIdleDeg, p.jitterLoadDeg, load);
        evSamplesPerDeg = rate / (6f * Math.max(rpmTarget, 50f));
        float flowDb = raspFlowDb(rpmTarget, over);
        evRasp = EngineVoice.dbToLin(mix(mix(p.raspIdleDb, p.raspLoadDb, load), p.raspOverrunDb, over) + flowDb
                + (cabin ? rpmDb(p.cabinRaspRpm, p.cabinRaspRpmDb, rpmCalc) : 0f));
        evTick = EngineVoice.dbToLin(p.tickDb) * (0.4f + 0.6f * Math.min(rpmTarget / 6000f, 1f));
        evKnock = EngineVoice.dbToLin(p.knockDb) * (0.35f + 0.65f * load) * evCombustion;
        evIntake = EngineVoice.dbToLin(mix(p.intakeIdleDb, p.intakeLoadDb, (float) Math.pow(load, 1.2)));

        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            shift(EngineVoice.dbToLin(p.thunkDb) * (0.3f + 0.7f * load) * (params.shiftUp ? 1f : DOWNSHIFT_THUNK));
        }

        float decaySec = p.decayDeg / (6f * rpmCalc);
        float riseSec = mix(p.riseIdleMs, p.riseLoadMs, load) * 1e-3f * (1f + over);
        if (riseSec > decaySec * 0.5f) {
            riseSec = decaySec * 0.5f;
        }
        float ad = perSample(decaySec);
        float ar = perSample(riseSec);
        double tp = Math.log(decaySec / riseSec) * decaySec * riseSec / (decaySec - riseSec);
        float pulseNorm = (float) (1.0 / (Math.exp(-tp / decaySec) - Math.exp(-tp / riseSec)));
        float ae = perSample(p.raspDecayDeg / (6f * rpmCalc));
        float bright = 1f + params.brightDb / 6f;
        float mufflerHz = Math.min(p.mufflerHz * (float) Math.pow(Math.max(rpmTarget, 500f) / 1000f, p.mufflerPow), p.mufflerTopHz)
                * (1f + p.mufflerLoad * load) * bright;
        muffler[0].setLowpass(mufflerHz, 0.6f, rate);
        muffler[1].setLowpass(mufflerHz, 0.6f, rate);
        float heatTarget = unfired ? 0f : VirtualEngine.clamp01(load * rpmTarget / p.heatFullRpm);
        if (heat < 0f) {
            heat = heatTarget;
        }
        heat += (heatTarget - heat) * VirtualEngine.follow(dt, heatTarget > heat ? p.heatUpSec : p.heatDownSec);
        float sound = (float) Math.sqrt((p.gasColdK + (p.gasHotK - p.gasColdK) * heat) / p.gasRefK);
        for (int b = 0; b < 2; b++) {
            pipeDelay[b] = Math.max(1f, Math.min(PIPE - 2, rate / (p.pipeHz[b] * sound)));
            int f = p.formantHz.length;
            for (int k = 0; k < f; k++) {
                formants[b][k].setPeaking(Math.min(p.formantHz[k] * sound, 0.45f * rate), p.formantDb[k], p.formantQ[k], rate);
            }
            formants[b][f].setPeaking(p.helmholtzHz[b] * sound, p.helmholtzDb, p.helmholtzQ, rate);
        }
        setTilt(Math.min(p.raspTiltHz * (float) Math.pow(Math.max(rpmCalc, 300f) / 2000f, p.raspTiltRpmPow), TILT_TOP_HZ));
        float drive = mix(p.driveIdle, p.driveLoad, load);
        float raspLevel = evRasp;
        float floorShare = Math.min(1f, p.raspFloor + 0.4f * over);
        float floor = floorShare * raspLevel * (unfired ? 0.2f : 1f);
        float kickShare = 1f - floorShare;

        float aiD = perSample(INTAKE_DECAY_DEG / (6f * rpmCalc));
        float aiR = perSample(INTAKE_RISE_MS * 1e-3f);
        float wind = Math.min(Math.max(params.speedKmh, 0f) / RAM_KMH, 1.2f);
        float ram = 1f + p.inletRam * wind * wind;
        float roarTarget = EngineVoice.dbToLin(mix(p.roarIdleDb, p.roarLoadDb, (float) Math.pow(load, 1.5))
                + params.intakeBoostDb) * ram * VirtualEngine.clamp01(rpmTarget / ROAR_FULL_RPM);
        float shut = 1f - load;
        hissTarget = EngineVoice.dbToLin(p.hissDb) * shut * shut * VirtualEngine.clamp01(rpmTarget / ROAR_FULL_RPM);
        evHiss = hissTarget * (float) Math.sqrt(HISS_REF_RPM / rpmCalc);

        loadPeak = Math.max(load, loadPeak * VirtualEngine.decay(dt, LIFT_PEAK_SEC));
        float drop = loadPeak - load;
        boolean lift = drop > LIFT_DROP || (loadPeak > 0.3f && load < 0.5f * loadPeak);
        if (lift) {
            loadPeak = load;
        }
        float whineTarget = 0f;
        float meshTarget = 0f;
        float jetTarget = 0f;
        float inletTarget = 0f;
        float whooshTarget = 0f;
        if (supercharged) {
            boost += (load - boost) * VirtualEngine.follow(dt, p.superchargerSec);
            if (lift && boost > 0.3f) {
                whoosh = Math.max(whoosh, boost * Math.min(1f, drop * 2f));
                decelKick = Math.max(decelKick, boost);
            }
            whoosh *= VirtualEngine.decay(dt, WHOOSH_SEC);
            decelKick *= VirtualEngine.decay(dt, p.decelBurstSec);
            pressKick = Math.max(pressKick * VirtualEngine.decay(dt, p.pressBurstSec), VirtualEngine.clamp01(2f * (load - boost)));
            growlTarget = p.growlFree + (1f - p.growlFree) * boost;
            float revs = Math.min(rpmTarget / 6000f, 1.2f);
            float speed = (float) Math.pow(revs, p.superchargerRpmPow);
            whineTarget = (float) Math.pow(Math.min(Math.max(rpmTarget, 300f), 7200f) / 3000f, p.toneRpmPow);
            meshTarget = whineTarget * (p.superchargerFree + (1f - p.superchargerFree) * boost)
                    * EngineVoice.dbToLin(meshRpmDb(p, rpmTarget));
            for (int k = 0; k < meshGains.length; k++) {
                meshGains[k] = EngineVoice.dbToLin(p.superchargerDb + meshHarmonicDb(p, rpmTarget, k));
            }
            chordGain = cabin ? EngineVoice.dbToLin(rpmDb(p.cabinChordRpm, p.cabinChordRpmDb, rpmCalc)) : 1f;
            dischargeTarget = EngineVoice.dbToLin(p.dischargeDb) * whineTarget * boost * (float) Math.sqrt(boost);
            float charge = (float) Math.sqrt(1f + (p.chargeHeat - 1f) * boost);
            for (int s = 0; s < 2; s++) {
                duct[s].setPeaking(Math.min(p.ductHz[s] * charge, 0.45f * rate), p.ductDb, p.ductQ, rate);
            }
            evPulsation = EngineVoice.dbToLin(p.pulsationDb + rpmDb(p.pulsationRpm, p.pulsationRpmDb, rpmCalc))
                    * (p.pulsationFree + (1f - p.pulsationFree) * boost * (float) Math.sqrt(boost));
            for (int k = 0; k < pulsationGains.length; k++) {
                float hz = Math.min(p.pulsationOrders[k] * rpmCalc / 60f, rate * 0.45f);
                pulsationModes[0][k].setBandpass(hz, p.pulsationQ, rate);
                pulsationModes[1][k].setBandpass(hz, p.pulsationQ, rate);
                double alpha = Math.sin(2.0 * Math.PI * hz / rate) / (2.0 * p.pulsationQ);
                pulsationGains[k] = (float) ((k % 2 == 0 ? 1.0 : -1.0) * (1.0 + alpha) / (2.0 * alpha)
                        * p.pulsationOrders[k] / p.pulsationOrders[0]);
            }
            jetTarget = EngineVoice.dbToLin(p.jetDb) * (JET_FREE + (1f - JET_FREE) * boost) * speed;
            float flow = revs * (INLET_FREE + (1f - INLET_FREE) * load);
            inletTarget = EngineVoice.dbToLin(p.inletDb) * flow * (float) Math.sqrt(flow) * ram;
            whooshTarget = EngineVoice.dbToLin(WHOOSH_DB) * whoosh;
            decelTarget = EngineVoice.dbToLin(p.decelDb)
                    * (float) Math.pow(Math.min(Math.max(rpmTarget, 300f), 7200f) / DECEL_REF_RPM, p.decelRpmPow)
                    * (shut * shut + p.decelBurst * decelKick);
        }

        boolean driving = params.drive && params.speedKmh > 1f && !unfired;
        float torque = Math.max(load, 0.6f * over);
        float gearOrder = params.gearIndex >= 0
                ? p.gearOrders[Math.min(params.gearIndex, p.gearOrders.length - 1)] : p.cvtOrder;
        float v = Math.max(params.speedKmh, 0f);
        float gearTarget = driving ? EngineVoice.dbToLin(p.gearDb) * torque : 0f;
        float diffTarget = driving ? EngineVoice.dbToLin(p.diffDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 20f) : 0f;
        float shaftTarget = driving ? EngineVoice.dbToLin(p.shaftDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 30f) : 0f;
        float diffStep = p.diffHzPerKmh * v / rate;
        float shaftStep = p.shaftHzPerKmh * v / rate;
        float reverseTarget = EngineVoice.dbToLin(EngineVoice.WHINE_DB) * VirtualEngine.clamp01(params.whineAmp);
        float starterTarget = EngineVoice.dbToLin(EngineVoice.STARTER_DB) * starter;
        float starterStep = EngineVoice.STARTER_WHINE_HZ * (0.8f + 0.2f * starter) / rate;

        float post = EngineVoice.dbToLin(p.rpmLoudDb * VirtualEngine.clamp01((rpmTarget - 800f) / 5200f)) * p.norm;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f) * EngineVoice.dbToLin(p.overrunLevelDb * over), levelPow)
                * EngineVoice.dbToLin(p.cutDipDb * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;

        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            advance(6.0 * r / rate);
            int cur = cursor;

            for (int b = 0; b < 2; b++) {
                float x = exc[b][cur];
                exc[b][cur] = 0f;
                pulseDecay[b] = ad * pulseDecay[b] + x;
                pulseRise[b] = ar * pulseRise[b] + x;
                float pulse = (pulseDecay[b] - pulseRise[b]) * pulseNorm;
                float xp = pop[b][cur];
                pop[b][cur] = 0f;
                popDecay[b] = popDecayA * popDecay[b] + xp;
                popRise[b] = popRiseA * popRise[b] + xp;
                float k = kick[b][cur];
                kick[b][cur] = 0f;
                envelope[b] = ae * envelope[b] + k;
                float w = white();
                tilt[b] += tiltK * (w - tilt[b]);
                float nz = noisePeak[b].process(noiseHighpass[b].process(tilt[b] * tiltNorm + p.raspAir * w));
                float u = saturate(pulse + (popDecay[b] - popRise[b]) + (envelope[b] * kickShare + floor) * nz, drive);
                int pd = (int) pipeDelay[b];
                float pf = pipeDelay[b] - pd;
                float p0 = pipe[b][(pipeWrite - pd) & PIPE_MASK];
                float delayed = p0 + (pipe[b][(pipeWrite - pd - 1) & PIPE_MASK] - p0) * pf;
                pipeLoss[b] += pipeK * (delayed - pipeLoss[b]);
                float y = u + p.pipeFeedback * pipeLoss[b];
                pipe[b][pipeWrite] = y;
                for (Biquad f : formants[b]) {
                    y = f.process(y);
                }
                y = muffler[b].process(y);
                bankOut[b] = radiation[b].process(y);
            }
            pipeWrite = (pipeWrite + 1) & PIPE_MASK;
            float left = bankOut[0];
            float right = bankOut[1];

            float xi = intake[cur];
            intake[cur] = 0f;
            intakeDecay = aiD * intakeDecay + xi;
            intakeRise = aiR * intakeRise + xi;
            float ip = intakeDecay - intakeRise;
            roarNow += (roarTarget - roarNow) * ampK;
            float front = plenum.process(ip) * 1.5f + runner.process(ip) + roar.process(white()) * roarNow;
            float xh = hiss[cur];
            hiss[cur] = 0f;
            hissDecay = hissDecayA * hissDecay + xh;
            hissRise = hissRiseA * hissRise + xh;
            hissNow += (hissTarget - hissNow) * ampK;
            front += hissBand2.process(hissBand.process(hissWhite())) * (p.hissFloor * hissNow + (hissDecay - hissRise) * hissNorm);
            float cowlL = 0f;
            float cowlR = 0f;
            float sideL = 0f;
            float sideR = 0f;

            float jetL = 0f;
            float jetR = 0f;
            float inlet = 0f;
            float cabinTonesL = 0f;
            float cabinTonesR = 0f;
            float housing = 0f;
            if (supercharged) {
                float hz = p.superchargerOrder * r / 60f * (1f + p.torsion * EngineVoice.sin(theta / 90.0));
                lobePhase += hz / rate;
                if (lobePhase >= 1.0) {
                    lobePhase -= 1.0;
                }
                femalePhase += hz / p.rotorLobes[0] / rate;
                if (femalePhase >= 1.0) {
                    femalePhase -= 1.0;
                }
                malePhase += hz / p.rotorLobes[1] / rate;
                if (malePhase >= 1.0) {
                    malePhase -= 1.0;
                }
                whineNow += (whineTarget - whineNow) * ampK;
                meshNow += (meshTarget - meshNow) * ampK;
                jetNow += (jetTarget - jetNow) * ampK;
                inletNow += (inletTarget - inletNow) * ampK;
                whooshNow += (whooshTarget - whooshNow) * ampK;
                float c = 0.5f + 0.5f * EngineVoice.sin(lobePhase + 0.25);
                float push = 1f + p.jetPulse * (c * c * c / PUFF_MEAN - 1f);
                jetL = airLowpass[0].process(jets(jetBands[0], white() * push)) * jetNow;
                jetR = airLowpass[1].process(jets(jetBands[1], white() * push)) * jetNow;
                inlet = inletBand.process(white()) * inletNow;
                dischargeNow += (dischargeTarget - dischargeNow) * ampK;
                growlNow += (growlTarget - growlNow) * ampK;
                pressNow += (pressKick - pressNow) * ampK;
                float rotor = 1f + growlNow * (p.rotorAm[0] * EngineVoice.sin(femalePhase)
                        + p.rotorAm[1] * EngineVoice.sin(malePhase + MALE_PHASE));
                float mod = rotor * (1f + growlNow * p.superchargerAm * EngineVoice.sin(theta / 90.0));
                float tone = steadyWhine + (mod - 1f);
                float d = discharge(hz) * dischargeNow * tone;
                float dl = duct[0].process(intercooler[0].process(d));
                float dr = duct[1].process(intercooler[1].process(d));
                whine(hz);
                float loadedL = CENTER * lobeNow * mod + whineL * tone;
                float loadedR = CENTER * lobeNow * mod + whineR * tone;
                float shell = 0f;
                float loaded = lobeNow * mod + (whineL + whineR) * (0.5f / CENTER) * tone + d;
                for (Biquad m : bodyModes) {
                    shell += m.process(loaded);
                }
                housing = bodyTop.process(shell) * bodyGain;
                float pl = pulsation(0, cur) * chordGain;
                float pr = pulsation(1, cur) * chordGain;
                decelNow += (decelTarget - decelNow) * ampK;
                float lifted = decelNow > 1e-6f ? CENTER * steadyWhine * decel(hz) * decelNow : 0f;
                float toneL = loadedL + plenumLeft[0] * pl + plenumLeft[1] * pr + lifted;
                float toneR = loadedR + plenumRight[0] * pl + plenumRight[1] * pr + lifted;
                if (cabin) {
                    cowlL += toneL + 0.5f * (jetNear * dl + jetFar * dr);
                    cowlR += toneR + 0.5f * (jetFar * dl + jetNear * dr);
                } else {
                    sideL += toneL;
                    sideR += toneR;
                    jetL += dl;
                    jetR += dr;
                }
                front += whooshBand.process(white()) * whooshNow;
            }
            if (cabin) {
                toneLpL += toneK * (cowlL - toneLpL);
                toneLp2L += toneK * (toneLpL - toneLp2L);
                toneLpR += toneK * (cowlR - toneLpR);
                toneLp2R += toneK * (toneLpR - toneLp2R);
                cabinTonesL = toneLp2L * cabinToneGain;
                cabinTonesR = toneLp2R * cabinToneGain;
            }

            float both = left + right;
            float low = cabinLowpass.process(both);
            float room = 0f;
            for (int k = 0; k < cabinModes.length; k++) {
                room += p.cabinGain[k] * cabinModes[k].process(low);
            }
            for (int k = 0; k < sympathetic.length; k++) {
                room += p.sympatheticGain[k] * sympathetic[k].process(both);
            }

            float tickL = modes(tickModes[0], tick[0], cur);
            float tickR = modes(tickModes[1], tick[1], cur);
            float knockL = modes(knockModes[0], knock[0], cur);
            float knockR = modes(knockModes[1], knock[1], cur);

            float frontL = CENTER * front + sideL + inletLeft * inlet + jetNear * jetL + jetFar * jetR + tickL + knockL;
            float frontR = CENTER * front + sideR + inletRight * inlet + jetFar * jetL + jetNear * jetR + tickR + knockR;
            float rearL = left + p.cross * right;
            float rearR = right + p.cross * left;
            if (cabin) {
                frontL = lowpass2(frontLp[0], frontK, frontL) + cabinTonesL;
                frontR = lowpass2(frontLp[1], frontK, frontR) + cabinTonesR;
                rearL = lowpass2(rearLp[0], rearK, rearL);
                rearR = lowpass2(rearLp[1], rearK, rearR);
            }
            float l = rearL + frontL + CENTER * (room + housing);
            float rr = rearR + frontR + CENTER * (room + housing);
            float mid = 0.5f * (l + rr);
            float side = sideHighpass.process(0.5f * (l - rr));
            widthLp += widthK * (side - widthLp);
            side += (widthGain - 1f) * (side - widthLp);
            levelNow += (levelTarget - levelNow) * gainK;
            float g = post * levelNow;
            l = (mid + side) * g;
            rr = (mid - side) * g;

            float extra = 0f;
            gearNow += (gearTarget - gearNow) * gearK;
            diffNow += (diffTarget - diffNow) * ampK;
            shaftNow += (shaftTarget - shaftNow) * ampK;
            if (gearNow > 1e-6f) {
                gearPhase += gearOrder * r / 60f / rate;
                if (gearPhase >= 1.0) {
                    gearPhase -= 1.0;
                }
                extra += gearNow * (EngineVoice.sin(gearPhase) + 0.3f * EngineVoice.sin(2.0 * gearPhase));
            }
            if (diffNow > 1e-6f) {
                diffPhase += diffStep;
                if (diffPhase >= 1.0) {
                    diffPhase -= 1.0;
                }
                extra += diffNow * EngineVoice.sin(diffPhase);
            }
            if (shaftNow > 1e-6f) {
                shaftPhase += shaftStep;
                if (shaftPhase >= 1.0) {
                    shaftPhase -= 1.0;
                }
                extra += shaftNow * EngineVoice.sin(shaftPhase);
            }
            reverseNow += (reverseTarget - reverseNow) * ampK;
            if (reverseNow > 1e-5f && params.whineHz > 0f) {
                reversePhase += params.whineHz / rate;
                if (reversePhase >= 1.0) {
                    reversePhase -= 1.0;
                }
                extra += reverseNow * (EngineVoice.sin(reversePhase) + 0.3f * EngineVoice.sin(2.0 * reversePhase));
            }
            starterNow += (starterTarget - starterNow) * ampK;
            if (starterNow > 1e-5f) {
                starterPhase += starterStep;
                if (starterPhase >= 1.0) {
                    starterPhase -= 1.0;
                }
                float compression = 0.55f + 0.45f * EngineVoice.sin(theta / 90.0);
                float buzz = EngineVoice.sin(starterPhase) + 0.35f * EngineVoice.sin(2.0 * starterPhase) + 0.25f * nextNoise();
                extra += starterNow * compression * buzz;
            }
            for (int k = 0; k < thunkEnv.length; k++) {
                if (thunkEnv[k] > 1e-7f) {
                    thunkEnv[k] *= thunkDecay[k];
                    thunkPhase[k] += thunkStep[k];
                    if (thunkPhase[k] >= 1.0) {
                        thunkPhase[k] -= 1.0;
                    }
                    extra += thunkEnv[k] * EngineVoice.sin(thunkPhase[k]);
                }
            }
            float xc = click[cur];
            click[cur] = 0f;
            extra += clickBand.process(xc);
            if (rockEnv > 1e-7f || rockNow > 1e-7f) {
                rockEnv *= rockDecay;
                rockNow += (rockEnv - rockNow) * rockAttackK;
                rockPhase += rockStep;
                if (rockPhase >= 1.0) {
                    rockPhase -= 1.0;
                }
                extra += rockNow * EngineVoice.sin(rockPhase);
            }
            l += extra;
            rr += extra;
            if (cabin) {
                l = cabinLow[0].process(l) * cabinGain;
                rr = cabinLow[1].process(rr) * cabinGain;
            }

            if (toneOn) {
                l = tone[0].process(l);
                rr = tone[1].process(rr);
            }
            l = highpass[0].process(l);
            rr = highpass[1].process(rr);
            gainNow += (params.gain - gainNow) * gainK;
            limiter.push(l * gainNow, rr * gainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
            cursor = (cursor + 1) & MASK;
        }
        rpmNow = rpmTarget;
    }

    float gasScale() {
        return heat < 0f ? 1f : (float) Math.sqrt((p.gasColdK + (p.gasHotK - p.gasColdK) * heat) / p.gasRefK);
    }

    float sameBankGapDeg(int cylinder) {
        return gapDeg[cylinder - 1];
    }

    float[] cylinderPlace(int cylinder) {
        int c = cylinder - 1;
        return new float[] {cylGain[c], cylDelay[c], cylLeft[c], cylRight[c]};
    }

    private void advance(double degrees) {
        if (!(degrees > 0.0)) {
            return;
        }
        double end = theta + degrees;
        while (nextAngle <= end) {
            float at = 1f - (float) ((end - nextAngle) / degrees);
            fire(evKind[nextEvent], evCyl[nextEvent], at);
            nextEvent++;
            if (nextEvent == evAngle.length) {
                nextEvent = 0;
                cycleBase += 720.0;
            }
            nextAngle = cycleBase + evAngle[nextEvent];
        }
        theta = end;
        if (theta >= 720.0) {
            theta -= 720.0;
            cycleBase -= 720.0;
            nextAngle -= 720.0;
        }
    }

    private void fire(int kind, int cylinder, float at) {
        int c = cylinder - 1;
        int b = bankOf[c];
        if (kind == EXHAUST) {
            float variation = Math.max(0f, 1f + evSigma * gauss());
            float level = evCombustion;
            if (evMisfire > 0f && random01() < evMisfire) {
                level = p.pump;
            }
            float recovery = Math.max(0.15f, 1f - evRecovery * (float) Math.exp(-gapDeg[c] / p.recoveryDeg));
            float a = p.trim[c] * recovery * variation * level;
            float delay = headerSamples[c] + bankSamples[b] + lagSamples + evJitter * (3f + gauss()) * evSamplesPerDeg;
            float q = at + Math.max(0f, Math.min(MAX_DELAY, delay));
            add(exc[b], q, a);
            add(kick[b], q, a * evRasp * (0.4f + 0.6f * level) * (1f - CUT_RASP * evCut));
            if (evPop > 0f && random01() < evPop) {
                add(pop[b], q + random01() * POP_SPREAD, p.popGain * (0.6f + 0.8f * random01()));
                add(kick[b], q, 3f * evRasp);
            }
        } else if (kind == EXHAUST_CLOSE || kind == INTAKE_CLOSE) {
            float a = evTick * (0.6f + 0.4f * random01()) * cylGain[c];
            add(tick[0], at + cylDelay[c], a * cylLeft[c]);
            add(tick[1], at + cylDelay[c], a * cylRight[c]);
            if (kind == INTAKE_CLOSE) {
                add(intake, at, evIntake * (0.9f + 0.2f * random01()));
                add(hiss, at, evHiss);
                if (evPulsation > 0f) {
                    float rec = Math.max(0.15f, 1f - p.pulsationRecovery * (float) Math.exp(-gapDeg[c] / p.recoveryDeg));
                    add(pulsation[bankOf[c]], at + pulsationLagSamples * (1f - rec), evPulsation * rec * (0.97f + 0.06f * random01()));
                }
            }
        } else {
            float a = evKnock * (0.8f + 0.4f * random01()) * cylGain[c];
            add(knock[0], at + cylDelay[c], a * cylLeft[c]);
            add(knock[1], at + cylDelay[c], a * cylRight[c]);
        }
    }

    private void shift(float amp) {
        for (int k = 0; k < thunkEnv.length; k++) {
            thunkEnv[k] = amp * EngineVoice.dbToLin(p.thunkGainDb[k]);
            thunkPhase[k] = 0.0;
        }
        int n = Math.min((int) (CLICK_SEC * rate), RING - 2);
        float d = perSample(CLICK_DECAY_SEC);
        float env = amp * EngineVoice.dbToLin(p.clickDb);
        for (int i = 0; i < n; i++) {
            click[(cursor + 1 + i) & MASK] += env * clickWhite();
            env *= d;
        }
    }

    private void rock(float amp) {
        rockEnv = Math.max(rockEnv, amp * EngineVoice.dbToLin(p.rockDb));
        rockPhase = 0.0;
    }

    private void whine(float hz) {
        float lobe = lobeGain * (1f + p.pressBurst * pressNow) * VirtualEngine.clamp01((WHINE_TOP_HZ - hz) / WHINE_FADE_HZ)
                * EngineVoice.sin(lobePhase) * whineNow;
        lobeNow = lobe;
        float wl = 0f;
        float wr = 0f;
        for (int v = 0; v < voicePhase.length; v++) {
            voiceNoise[v] += voiceK * (white() - voiceNoise[v]);
            voicePhase[v] += hz * (p.meshVoiceDetune[v] + p.meshVoiceWander * voiceNorm * voiceNoise[v]) / rate;
            voicePhase[v] -= Math.floor(voicePhase[v]);
        }
        for (int k = 0; k < meshGains.length; k++) {
            float m = p.meshMultiple * (k + 1);
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            float g = meshGains[k] * Math.min(fade, 1f) * voiceScale * meshNow;
            for (int v = 0; v < voicePhase.length; v++) {
                float s = g * EngineVoice.sin(m * (lobePhase + voicePhase[v]));
                wl += voiceLeft[v] * s;
                wr += voiceRight[v] * s;
            }
        }
        whineL = wl;
        whineR = wr;
    }

    private float discharge(float hz) {
        float d = 0f;
        for (int k = 0; k < dischargeGains.length; k++) {
            int m = p.dischargeHarmonics[k];
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            d += dischargeGains[k] * Math.min(fade, 1f) * EngineVoice.sin(m * lobePhase + 0.25 * k);
        }
        return d;
    }

    private float decel(float hz) {
        float d = 0f;
        for (int k = 0; k < decelGains.length; k++) {
            int m = p.decelHarmonics[k];
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            d += decelGains[k] * Math.min(fade, 1f) * EngineVoice.sin(m * lobePhase + DECEL_PHASE * k);
        }
        return d;
    }

    private float pulsation(int bank, int cur) {
        float xs = pulsation[bank][cur];
        pulsation[bank][cur] = 0f;
        float puls = 0f;
        Biquad[] modes = pulsationModes[bank];
        for (int k = 0; k < modes.length; k++) {
            puls += pulsationGains[k] * modes[k].process(xs);
        }
        return pulsationTop[bank].process(puls);
    }

    static float meshRpmDb(PulseProfile p, float rpm) {
        return rpmDb(p.meshRpm, p.meshRpmDb, rpm);
    }

    static float rpmDb(float[] at, float[] db, float rpm) {
        double f = rpmPosition(at, rpm);
        int i = (int) f;
        return i >= db.length - 1 ? db[db.length - 1] : (float) (db[i] + (db[i + 1] - db[i]) * (f - i));
    }

    static float meshHarmonicDb(PulseProfile p, float rpm, int k) {
        float[][] db = p.meshHarmonicsDb;
        double f = rpmPosition(p.meshRpm, rpm);
        int i = (int) f;
        return i >= db.length - 1 ? db[db.length - 1][k] : (float) (db[i][k] + (db[i + 1][k] - db[i][k]) * (f - i));
    }

    static double rpmPosition(float[] at, float rpm) {
        if (rpm <= at[0]) {
            return 0.0;
        }
        for (int i = 1; i < at.length; i++) {
            if (rpm <= at[i]) {
                return i - 1 + Math.log(rpm / at[i - 1]) / Math.log(at[i] / at[i - 1]);
            }
        }
        return at.length - 1;
    }

    private float raspFlowDb(float rpm, float over) {
        double octaves = Math.log(Math.max(rpm, 300f) / p.raspRefRpm) / Math.log(2.0);
        float db = (float) (mix(p.raspRpmDb[0], p.raspRpmDb[1], over) * octaves);
        return VirtualEngine.clamp(db, -24f, p.raspRpmMaxDb);
    }

    private void setTilt(float hz) {
        tiltK = onePole(hz);
        tiltNorm = (float) (1.0 / Math.sqrt(tiltK / (2.0 - tiltK)));
    }

    private static float lowpass2(float[] state, float[] k, float x) {
        state[0] += k[0] * (x - state[0]);
        state[1] += k[1] * (state[0] - state[1]);
        return state[1];
    }

    static float panLeft(float x) {
        return (float) Math.cos(VirtualEngine.clamp01((x + 1f) / 2f) * Math.PI / 2.0);
    }

    static float panRight(float x) {
        return (float) Math.sin(VirtualEngine.clamp01((x + 1f) / 2f) * Math.PI / 2.0);
    }

    private void add(float[] ring, float q, float amp) {
        int i = (int) q;
        float w = q - i;
        ring[(cursor + i) & MASK] += amp * (1f - w);
        ring[(cursor + i + 1) & MASK] += amp * w;
    }

    private float jets(Biquad[] bands, float x) {
        float y = 0f;
        for (int k = 0; k < bands.length; k++) {
            y += jetGains[k] * bands[k].process(x);
        }
        return y;
    }

    private static float modes(Biquad[] bank, float[] ring, int cur) {
        float x = ring[cur];
        ring[cur] = 0f;
        float y = 0f;
        for (Biquad m : bank) {
            y += m.process(x);
        }
        return y;
    }

    private Biquad[] bank(float[] hz, float[] q) {
        Biquad[] out = new Biquad[hz.length];
        for (int k = 0; k < hz.length; k++) {
            out[k] = Biquad.bandpass(hz[k], q[k], rate);
        }
        return out;
    }

    private void sortEvents() {
        for (int i = 1; i < evAngle.length; i++) {
            float a = evAngle[i];
            int k = evKind[i];
            int c = evCyl[i];
            int j = i - 1;
            while (j >= 0 && evAngle[j] > a) {
                evAngle[j + 1] = evAngle[j];
                evKind[j + 1] = evKind[j];
                evCyl[j + 1] = evCyl[j];
                j--;
            }
            evAngle[j + 1] = a;
            evKind[j + 1] = k;
            evCyl[j + 1] = c;
        }
    }

    static float saturate(float x, float drive) {
        if (drive <= 1e-3f) {
            return x;
        }
        float y = x * drive;
        float t;
        if (y >= 3f) {
            t = 1f;
        } else if (y <= -3f) {
            t = -1f;
        } else {
            float y2 = y * y;
            t = y * (27f + y2) / (27f + 9f * y2);
        }
        return t / drive;
    }

    private float perSample(float tauSec) {
        return (float) Math.exp(-1.0 / (Math.max(tauSec, 1e-6f) * rate));
    }

    private float onePole(float hz) {
        return 1f - (float) Math.exp(-2.0 * Math.PI * hz / rate);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float white() {
        return nextNoise() * 1.7320508f;
    }

    private float clickWhite() {
        int x = clickNoise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        clickNoise = x;
        return x * (1.7320508f / 2147483648f);
    }

    private float hissWhite() {
        int x = hissNoise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        hissNoise = x;
        return x * (1.7320508f / 2147483648f);
    }

    private float gauss() {
        return (nextNoise() + nextNoise() + nextNoise() + nextNoise()) * 0.8660254f;
    }

    private float random01() {
        return (nextNoise() + 1f) * 0.5f;
    }

    private float nextNoise() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1f / 2147483648f);
    }
}
