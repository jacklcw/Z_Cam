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
final class TurboFourVoice implements Voice {

    private static final int RING = 2048;
    private static final int MASK = RING - 1;
    private static final float MAX_DELAY = RING - 64;
    private static final int PIPE = 1024;
    private static final int PIPE_MASK = PIPE - 1;
    private static final int TIP = 64;
    private static final int TIP_MASK = TIP - 1;

    static final int[] FIRING = {1, 3, 4, 2};
    static final float[] TRIM = {1.08f, 0.93f, 1.04f, 0.96f};
    static final float[] HEADER_MS = {0.10f, 0.34f, 0.16f, 0.28f};
    static final float SCROLL_MS = 0.05f;
    static final float EXHAUST_OPEN_DEG = 130f;
    static final float RISE_IDLE_MS = 0.45f;
    static final float RISE_LOAD_MS = 0.12f;
    static final float DECAY_DEG = 12f;
    static final float SIGMA_IDLE = 0.07f;
    static final float SIGMA_LOAD = 0.04f;
    static final float SIGMA_OVERRUN = 0.25f;
    static final float JITTER_IDLE_DEG = 1.2f;
    static final float JITTER_LOAD_DEG = 0.5f;
    static final float PUMP = 0.28f;

    static final float SPOOL_FROM_RPM = 1300f;
    static final float SPOOL_FULL_RPM = 2600f;
    static final float BOOST_UP_LOW_SEC = 0.9f;
    static final float BOOST_UP_HIGH_SEC = 0.25f;
    static final float BOOST_DOWN_SEC = 0.15f;
    static final float SHAFT_FREE = 0.3f;
    static final float SHAFT_UP_SEC = 0.35f;
    static final float SHAFT_DOWN_SEC = 1.6f;
    static final float TURBINE_ABSORB = 0.3f;
    static final float TURBINE_SMOOTH = 0.5f;
    static final float WHISTLE_LOW_HZ = 1800f;
    static final float WHISTLE_HIGH_HZ = 6200f;
    static final float WHISTLE_DB = -34f;
    static final float WHISTLE_SECOND_DB = -14f;
    static final float WHOOSH_HZ = 1400f;
    static final float WHOOSH_Q = 0.8f;
    static final float WHOOSH_DB = -30f;
    static final float WHOOSH_SEC = 0.3f;
    static final float LIFT_DROP = 0.25f;
    static final float LIFT_PEAK_SEC = 1.5f;
    static final float WHOOSH_FROM = 0.3f;

    static final float RASP_IDLE_DB = -24f;
    static final float RASP_LOAD_DB = -14f;
    static final float RASP_OVERRUN_DB = -28f;
    static final float RASP_DECAY_DEG = 40f;
    static final float RASP_FLOOR = 0.2f;
    static final float RASP_HIGHPASS_HZ = 150f;
    static final float RASP_TILT_HZ = 900f;
    static final float RASP_TILT_TOP_HZ = 4000f;

    static final float GAS_COLD_K = 500f;
    static final float GAS_HOT_K = 900f;
    static final float GAS_REF_K = 700f;
    static final float HEAT_LOAD = 0.6f;
    static final float HEAT_UP_SEC = 1.5f;
    static final float HEAT_DOWN_SEC = 3f;
    static final float PIPE_HZ = 118f;
    static final float PIPE_FEEDBACK = 0.45f;
    static final float PIPE_LOSS_HZ = 1400f;
    static final float[] FORMANT_HZ = {380f, 650f, 1150f};
    static final float[] FORMANT_DB = {-3f, 8f, -3f};
    static final float[] FORMANT_Q = {1.0f, 1.3f, 1.0f};
    static final float MUFFLER_HZ = 480f;
    static final float MUFFLER_POW = 0.6f;
    static final float MUFFLER_LOAD = 0.3f;
    static final float MUFFLER_TOP_HZ = 1600f;
    static final float RADIATION_HZ = 70f;
    static final float DRIVE_IDLE = 0.3f;
    static final float DRIVE_LOAD = 1.2f;
    static final float[] TIP_MS = {0f, 0.08f};
    static final float[] TIP_HZ = {1900f, 2200f};
    static final float TIP_DB = 2.5f;
    static final float TIP_Q = 3f;
    static final float CROSS = 0.25f;
    static final float BASS_MONO_HZ = 150f;
    static final float EXIT_DB = -14f;
    static final float EXIT_HIGHPASS_HZ = 400f;
    static final float EXIT_TILT_HZ = 1500f;
    static final float EXIT_IDLE_FLOW = 0.3f;

    static final float BURBLE_SEC = 1.2f;
    static final float BURBLE_FROM_RPM = 2500f;
    static final float BURBLE_FULL_RPM = 5000f;
    static final float POP_CHANCE = 0.035f;
    static final float POP_ON_THROTTLE = 0.2f;
    static final float POP_DB = -2f;
    static final float POP_RISE_MS = 0.15f;
    static final float POP_DECAY_MS = 3.5f;
    static final float POP_LAG_MS = 2f;
    static final float POP_SPREAD_MS = 6f;
    static final float POP_PAN = 0.35f;
    static final float POP_LOWPASS_HZ = 4500f;
    static final float CRACKLE_DB = -10f;
    static final float CRACKLE_MS = 5f;
    static final float DOWNSHIFT_BURBLE = 0.7f;
    static final float UPSHIFT_BANG = 1.2f;

    static final float CUT_FLOOR = 0.55f;
    static final float CUT_DIP_DB = -2f;

    static final float MISFIRE = 0.3f;
    static final float CATCH_SEC = 0.25f;
    static final float UNFIRED_LEVEL = 0.35f;
    static final float UNFIRED_FULL_RPM = 300f;
    static final float ROCK_HZ = 38f;
    static final float ROCK_MS = 85f;
    static final float ROCK_DB = -14f;
    static final float ROCK_ATTACK_SEC = 0.003f;
    static final float ROLL_ROCK = 0.6f;
    static final float SETTLE_ROCK = 0.35f;

    static final float LEVEL_POW = 0.2f;
    static final float RPM_LOUD_DB = 2f;
    static final float NORM = 0.75f;

    private final float rate;

    private final float[] evAngle = new float[FIRING.length];
    private final int[] evCyl = new int[FIRING.length];
    private int nextEvent;
    private double theta;
    private double cycleBase;
    private double nextAngle;
    private final float[] headerSamples = new float[TRIM.length];

    private final float[] exc = new float[RING];
    private final float[] kick = new float[RING];
    private final float[][] pop = new float[2][RING];
    private final float[] crackle = new float[RING];
    private int cursor;

    private float evCombustion;
    private float evSigma;
    private float evMisfire;
    private float evPop;
    private float evRasp;
    private float evJitter;
    private float evSamplesPerDeg;
    private float evAbsorb;

    private float pulseDecay;
    private float pulseRise;
    private float envelope;
    private float crackleEnv;
    private float tilt;
    private float tiltK;
    private float tiltNorm;
    private final Biquad raspHighpass;
    private final float[] pipe = new float[PIPE];
    private float pipeDelay;
    private final float pipeK;
    private float heat = -1f;
    private float pipeLoss;
    private int pipeWrite;
    private final Biquad[] formants = new Biquad[FORMANT_HZ.length];
    private final Biquad muffler;
    private final Biquad muffler2;
    private final Biquad radiation;
    private final float[] tipLine = new float[TIP];
    private int tipWrite;
    private final float[] tipDelay = new float[2];
    private final Biquad[] tipPeak = new Biquad[2];
    private final Biquad[] exitHighpass = new Biquad[2];
    private final float[][] exitTilt = new float[2][2];
    private final float exitK;
    private float exitNow;
    private final float[] popDecay = new float[2];
    private final float[] popRise = new float[2];
    private final float popDecayA;
    private final float popRiseA;
    private final Biquad[] popLowpass = new Biquad[2];
    private final float crackleDecay;

    private float boost;
    private float shaft;
    private float loadPeak;
    private float whoosh;
    private float whooshNow;
    private final Biquad whooshBand;
    private double whistlePhase;
    private float whistleNow;
    private float whistleHzNow;
    private float burble;

    private boolean serialSeen;
    private int lastSerial;
    private int lastCatch;
    private float catchEnv;
    private float rockEnv;
    private float rockNow;
    private double rockPhase;
    private final float rockDecay;
    private final float rockAttackK;
    private final float rockStep;

    private final Biquad sideHighpass;
    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private final float ampK;
    private final float gainK;
    private float rpmNow = -1f;
    private float levelNow;
    private float gainNow;
    private int noise = 0x3C6EF372;

    TurboFourVoice(float sampleRate) {
        this.rate = sampleRate;
        int n = FIRING.length;
        for (int k = 0; k < n; k++) {
            int c = FIRING[k];
            evAngle[k] = (720f * k / n + EXHAUST_OPEN_DEG) % 720f;
            evCyl[k] = c;
        }
        sortEvents();
        nextAngle = evAngle[0];
        for (int c = 1; c <= TRIM.length; c++) {
            float scroll = c == 1 || c == 4 ? 0f : SCROLL_MS;
            headerSamples[c - 1] = (HEADER_MS[c - 1] + scroll) * 1e-3f * sampleRate;
        }
        setTilt(RASP_TILT_HZ);
        raspHighpass = Biquad.highpass(RASP_HIGHPASS_HZ, 0.7071f, sampleRate);
        pipeK = onePole(PIPE_LOSS_HZ);
        for (int k = 0; k < FORMANT_HZ.length; k++) {
            formants[k] = Biquad.peaking(FORMANT_HZ[k], FORMANT_DB[k], FORMANT_Q[k], sampleRate);
        }
        muffler = Biquad.lowpass(MUFFLER_HZ, 0.6f, sampleRate);
        muffler2 = Biquad.lowpass(MUFFLER_HZ, 0.6f, sampleRate);
        radiation = Biquad.highpass(RADIATION_HZ, 0.6f, sampleRate);
        for (int c = 0; c < 2; c++) {
            tipDelay[c] = Math.min(TIP - 2, TIP_MS[c] * 1e-3f * sampleRate);
            tipPeak[c] = Biquad.peaking(TIP_HZ[c], TIP_DB, TIP_Q, sampleRate);
            popLowpass[c] = Biquad.lowpass(POP_LOWPASS_HZ, 0.7071f, sampleRate);
            exitHighpass[c] = Biquad.highpass(EXIT_HIGHPASS_HZ, 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        exitK = onePole(EXIT_TILT_HZ);
        popDecayA = perSample(POP_DECAY_MS * 1e-3f);
        popRiseA = perSample(POP_RISE_MS * 1e-3f);
        crackleDecay = perSample(CRACKLE_MS * 1e-3f);
        whooshBand = Biquad.bandpass(WHOOSH_HZ, WHOOSH_Q, sampleRate);
        whistleHzNow = WHISTLE_LOW_HZ;
        rockDecay = perSample(ROCK_MS * 1e-3f);
        rockAttackK = 1f - perSample(ROCK_ATTACK_SEC);
        rockStep = ROCK_HZ / sampleRate;
        sideHighpass = Biquad.highpass(BASS_MONO_HZ, 0.7071f, sampleRate);
        limiter = new Limiter(sampleRate);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
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
        float dt = frames / rate;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float cut = VirtualEngine.clamp01(params.cut);
        boolean cranking = params.starter > 0.5f;
        boolean unfired = cranking || params.fire < 0.5f;
        boolean moving = params.speedKmh > VirtualEngine.START_LEAVE_KMH;
        if (params.catchSerial != lastCatch) {
            lastCatch = params.catchSerial;
            catchEnv = 1f;
            rock(moving ? ROLL_ROCK : 1f);
        }
        if (unfired && !cranking && rpmTarget <= 0f && rpmNow > 0f) {
            rock(SETTLE_ROCK);
        }
        catchEnv *= VirtualEngine.decay(dt, CATCH_SEC);
        float rpmCalc = Math.max(rpmTarget, 100f);

        float flow = VirtualEngine.clamp01((rpmTarget - SPOOL_FROM_RPM) / (SPOOL_FULL_RPM - SPOOL_FROM_RPM));
        float boostTarget = unfired ? 0f : load * flow;
        float upSec = BOOST_UP_LOW_SEC + (BOOST_UP_HIGH_SEC - BOOST_UP_LOW_SEC) * VirtualEngine.clamp01(rpmTarget / 6000f);
        boost += (boostTarget - boost) * VirtualEngine.follow(dt, boostTarget > boost ? upSec : BOOST_DOWN_SEC);
        float shaftTarget = SHAFT_FREE * VirtualEngine.clamp01(rpmTarget / 7000f) + (1f - SHAFT_FREE) * boost;
        shaft += (shaftTarget - shaft) * VirtualEngine.follow(dt, shaftTarget > shaft ? SHAFT_UP_SEC : SHAFT_DOWN_SEC);
        loadPeak = Math.max(load, loadPeak * VirtualEngine.decay(dt, LIFT_PEAK_SEC));
        float drop = loadPeak - load;
        boolean lift = drop > LIFT_DROP;
        float revs = VirtualEngine.clamp01((rpmTarget - BURBLE_FROM_RPM) / (BURBLE_FULL_RPM - BURBLE_FROM_RPM));
        if (lift) {
            if (boost > WHOOSH_FROM) {
                whoosh = Math.max(whoosh, boost);
            }
            burble = Math.max(burble, revs * Math.min(1f, loadPeak + 0.3f));
            loadPeak = load;
        }
        burble = Math.max(burble, revs * VirtualEngine.clamp01(params.burble));
        whoosh *= VirtualEngine.decay(dt, WHOOSH_SEC);

        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            if (params.shiftUp) {
                bang(UPSHIFT_BANG * (0.4f + 0.6f * load));
            } else {
                burble = Math.max(burble, DOWNSHIFT_BURBLE * Math.max(revs, 0.3f));
            }
        }

        evCombustion = cranking ? 0.15f
                : unfired ? UNFIRED_LEVEL * VirtualEngine.clamp01(rpmTarget / UNFIRED_FULL_RPM)
                : (1f - over * (1f - PUMP)) * (1f - cut * (1f - CUT_FLOOR));
        evSigma = mix(SIGMA_IDLE, SIGMA_LOAD, load) + over * SIGMA_OVERRUN;
        evMisfire = MISFIRE * catchEnv;
        float onThrottle = load > 0.15f && over < 0.3f ? POP_ON_THROTTLE : 1f;
        evPop = unfired ? 0f : POP_CHANCE * burble * onThrottle;
        evRasp = EngineVoice.dbToLin(mix(mix(RASP_IDLE_DB, RASP_LOAD_DB, load), RASP_OVERRUN_DB, over));
        evJitter = mix(JITTER_IDLE_DEG, JITTER_LOAD_DEG, load);
        evSamplesPerDeg = rate / (6f * Math.max(rpmTarget, 50f));
        evAbsorb = 1f - TURBINE_ABSORB * boost;
        burble *= VirtualEngine.decay(dt, BURBLE_SEC);

        float decaySec = DECAY_DEG / (6f * rpmCalc);
        float riseSec = mix(RISE_IDLE_MS, RISE_LOAD_MS, load) * 1e-3f * (1f + TURBINE_SMOOTH * boost) * (1f + over);
        if (riseSec > decaySec * 0.5f) {
            riseSec = decaySec * 0.5f;
        }
        float ad = perSample(decaySec);
        float ar = perSample(riseSec);
        double tp = Math.log(decaySec / riseSec) * decaySec * riseSec / (decaySec - riseSec);
        float pulseNorm = (float) (1.0 / (Math.exp(-tp / decaySec) - Math.exp(-tp / riseSec)));
        float ae = perSample(RASP_DECAY_DEG / (6f * rpmCalc));
        float bright = 1f + params.brightDb / 6f;
        float mufflerHz = Math.min(MUFFLER_HZ * (float) Math.pow(Math.max(rpmTarget, 500f) / 2000f, MUFFLER_POW), MUFFLER_TOP_HZ)
                * (1f + MUFFLER_LOAD * load) * bright;
        muffler.setLowpass(Math.min(mufflerHz, 0.45f * rate), 0.6f, rate);
        muffler2.setLowpass(Math.min(mufflerHz, 0.45f * rate), 0.6f, rate);
        float heatTarget = VirtualEngine.clamp01(HEAT_LOAD * load + (1f - HEAT_LOAD) * rpmTarget / 6500f);
        if (heat < 0f) {
            heat = heatTarget;
        }
        heat += (heatTarget - heat) * VirtualEngine.follow(dt, heatTarget > heat ? HEAT_UP_SEC : HEAT_DOWN_SEC);
        float sound = (float) Math.sqrt((GAS_COLD_K + (GAS_HOT_K - GAS_COLD_K) * heat) / GAS_REF_K);
        for (int k = 0; k < FORMANT_HZ.length; k++) {
            formants[k].setPeaking(Math.min(FORMANT_HZ[k] * sound, 0.45f * rate), FORMANT_DB[k], FORMANT_Q[k], rate);
        }
        pipeDelay = Math.max(1f, Math.min(PIPE - 2, rate / (PIPE_HZ * sound)));
        setTilt(Math.min(RASP_TILT_HZ * (float) Math.sqrt(Math.max(rpmCalc, 300f) / 2000f), RASP_TILT_TOP_HZ));
        float drive = mix(DRIVE_IDLE, DRIVE_LOAD, load);
        float floor = RASP_FLOOR * evRasp * (unfired ? 0.2f : 1f);
        float kickShare = 1f - RASP_FLOOR;
        float popGain = EngineVoice.dbToLin(POP_DB);
        float exitFlow = VirtualEngine.clamp01(rpmTarget / 6500f) * (EXIT_IDLE_FLOW + (1f - EXIT_IDLE_FLOW) * load) * evCombustion;
        float exitTarget = EngineVoice.dbToLin(EXIT_DB) * exitFlow * exitFlow;

        float whistleTarget = EngineVoice.dbToLin(WHISTLE_DB) * shaft * shaft * (0.4f + 0.6f * boost) * (unfired ? 0f : 1f);
        float whistleHz = WHISTLE_LOW_HZ + (WHISTLE_HIGH_HZ - WHISTLE_LOW_HZ) * shaft;
        float whistleSecond = EngineVoice.dbToLin(WHISTLE_SECOND_DB);
        float whooshTarget = EngineVoice.dbToLin(WHOOSH_DB) * whoosh;

        float post = EngineVoice.dbToLin(RPM_LOUD_DB * VirtualEngine.clamp01((rpmTarget - 800f) / 5200f)) * NORM;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * EngineVoice.dbToLin(CUT_DIP_DB * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;
        float rockGain = EngineVoice.dbToLin(ROCK_DB);

        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            advance(6.0 * r / rate);
            int cur = cursor;

            float x = exc[cur];
            exc[cur] = 0f;
            pulseDecay = ad * pulseDecay + x;
            pulseRise = ar * pulseRise + x;
            float pulse = (pulseDecay - pulseRise) * pulseNorm;
            float k = kick[cur];
            kick[cur] = 0f;
            envelope = ae * envelope + k;
            float w = white();
            tilt += tiltK * (w - tilt);
            float nz = raspHighpass.process(tilt * tiltNorm + 0.03f * w);
            float u = PulseVoice.saturate(pulse + (envelope * kickShare + floor) * nz, drive);
            int pd = (int) pipeDelay;
            float pf = pipeDelay - pd;
            float p0 = pipe[(pipeWrite - pd) & PIPE_MASK];
            float delayed = p0 + (pipe[(pipeWrite - pd - 1) & PIPE_MASK] - p0) * pf;
            pipeLoss += pipeK * (delayed - pipeLoss);
            float y = u + PIPE_FEEDBACK * pipeLoss;
            pipe[pipeWrite] = y;
            pipeWrite = (pipeWrite + 1) & PIPE_MASK;
            for (Biquad f : formants) {
                y = f.process(y);
            }
            y = radiation.process(muffler2.process(muffler.process(y)));

            tipLine[tipWrite] = y;
            float left = tipPeak[0].process(tap(tipDelay[0]));
            float right = tipPeak[1].process(tap(tipDelay[1]));
            tipWrite = (tipWrite + 1) & TIP_MASK;
            exitNow += (exitTarget - exitNow) * ampK;
            for (int c = 0; c < 2; c++) {
                float[] e = exitTilt[c];
                e[0] += exitK * (exitHighpass[c].process(white()) - e[0]);
                e[1] += exitK * (e[0] - e[1]);
            }
            left += exitTilt[0][1] * exitNow;
            right += exitTilt[1][1] * exitNow;

            for (int c = 0; c < 2; c++) {
                float xp = pop[c][cur];
                pop[c][cur] = 0f;
                popDecay[c] = popDecayA * popDecay[c] + xp;
                popRise[c] = popRiseA * popRise[c] + xp;
            }
            float xc = crackle[cur];
            crackle[cur] = 0f;
            crackleEnv = crackleDecay * crackleEnv + xc;
            float crack = crackleEnv * white() * popGain;
            left += popLowpass[0].process((popDecay[0] - popRise[0]) * popGain + crack);
            right += popLowpass[1].process((popDecay[1] - popRise[1]) * popGain + crack);
            float l = left + CROSS * right;
            float rr = right + CROSS * left;

            whistleNow += (whistleTarget - whistleNow) * ampK;
            whistleHzNow += (whistleHz - whistleHzNow) * ampK;
            whooshNow += (whooshTarget - whooshNow) * ampK;
            float front = whooshBand.process(white()) * whooshNow;
            if (whistleNow > 1e-6f) {
                whistlePhase += whistleHzNow / rate;
                if (whistlePhase >= 1.0) {
                    whistlePhase -= 1.0;
                }
                front += whistleNow * (EngineVoice.sin(whistlePhase) + whistleSecond * EngineVoice.sin(2.0 * whistlePhase));
            }
            l += PulseVoice.CENTER * front;
            rr += PulseVoice.CENTER * front;

            float mid = 0.5f * (l + rr);
            float side = sideHighpass.process(0.5f * (l - rr));
            levelNow += (levelTarget - levelNow) * gainK;
            float g = post * levelNow;
            l = (mid + side) * g;
            rr = (mid - side) * g;

            if (rockEnv > 1e-7f || rockNow > 1e-7f) {
                rockEnv *= rockDecay;
                rockNow += (rockEnv - rockNow) * rockAttackK;
                rockPhase += rockStep;
                if (rockPhase >= 1.0) {
                    rockPhase -= 1.0;
                }
                float s = rockGain * rockNow * EngineVoice.sin(rockPhase);
                l += s;
                rr += s;
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

    float boost() {
        return boost;
    }

    float shaft() {
        return shaft;
    }

    float burble() {
        return burble;
    }

    private void advance(double degrees) {
        if (!(degrees > 0.0)) {
            return;
        }
        double end = theta + degrees;
        while (nextAngle <= end) {
            float at = 1f - (float) ((end - nextAngle) / degrees);
            exhaust(evCyl[nextEvent], at);
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

    private void exhaust(int cylinder, float at) {
        int c = cylinder - 1;
        float variation = Math.max(0f, 1f + evSigma * gauss());
        float level = evCombustion;
        if (evMisfire > 0f && random01() < evMisfire) {
            level = PUMP;
        }
        float a = TRIM[c] * variation * level * evAbsorb;
        float delay = headerSamples[c] + evJitter * (3f + gauss()) * evSamplesPerDeg;
        float q = at + Math.max(0f, Math.min(MAX_DELAY, delay));
        add(exc, q, a);
        add(kick, q, a * evRasp * (0.4f + 0.6f * level));
        if (evPop > 0f && random01() < evPop) {
            float lag = (POP_LAG_MS + POP_SPREAD_MS * random01()) * 1e-3f * rate;
            afterfire(Math.min(MAX_DELAY, q + lag), 0.6f + 0.8f * random01());
        }
    }

    private void afterfire(float q, float amp) {
        float x = POP_PAN * (2f * random01() - 1f);
        add(pop[0], q, amp * PulseVoice.CENTER_PAN * PulseVoice.panLeft(x));
        add(pop[1], q, amp * PulseVoice.CENTER_PAN * PulseVoice.panRight(x));
        add(crackle, q, amp * EngineVoice.dbToLin(CRACKLE_DB));
    }

    private void bang(float amp) {
        afterfire(1f + POP_LAG_MS * 1e-3f * rate, amp);
    }

    private void rock(float amp) {
        rockEnv = Math.max(rockEnv, amp);
        rockPhase = 0.0;
    }

    private float tap(float d) {
        int i = (int) d;
        float f = d - i;
        float a = tipLine[(tipWrite - i) & TIP_MASK];
        float b = tipLine[(tipWrite - i - 1) & TIP_MASK];
        return a + (b - a) * f;
    }

    private void sortEvents() {
        for (int i = 1; i < evAngle.length; i++) {
            float a = evAngle[i];
            int c = evCyl[i];
            int j = i - 1;
            while (j >= 0 && evAngle[j] > a) {
                evAngle[j + 1] = evAngle[j];
                evCyl[j + 1] = evCyl[j];
                j--;
            }
            evAngle[j + 1] = a;
            evCyl[j + 1] = c;
        }
    }

    private void add(float[] ring, float q, float amp) {
        int i = (int) q;
        float w = q - i;
        ring[(cursor + i) & MASK] += amp * (1f - w);
        ring[(cursor + i + 1) & MASK] += amp * w;
    }

    private void setTilt(float hz) {
        tiltK = onePole(hz);
        tiltNorm = (float) (1.0 / Math.sqrt(tiltK / (2.0 - tiltK)));
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
