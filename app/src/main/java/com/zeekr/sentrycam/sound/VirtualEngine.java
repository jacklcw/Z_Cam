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
 *
 *
 *
 *
 */
final class VirtualEngine {

    enum RpmMode { CONTINUOUS, SHIFTS }

    enum Gear { P, R, N, D, UNKNOWN }

    enum Flow { DRIVE, REGEN, IDLE, NOT_READY, UNKNOWN }

    enum Phase { STARTING, JOINING, RUNNING, STOPPING }

    static final class Input {
        float speedKmh;
        float throttlePct = Float.NaN;
        float pedalRate;
        float accel;
        boolean braking;
        Gear gear = Gear.UNKNOWN;
        Flow flow = Flow.UNKNOWN;
        RpmMode mode = RpmMode.CONTINUOUS;
        float eager;
        float sportPedal;
        int shiftStep;
    }

    static final float THROTTLE_DEADBAND = 3f;
    static final float THROTTLE_FULL = 85f;
    static final float LOAD_CURVE = 0.8f;
    static final float BRAKE_ON = 3f;
    static final float ACCEL_FULL = 5f;
    static final float ACCEL_LOAD_WEIGHT = 0.8f;
    static final float EFFORT_SMOOTH_SEC = 0.2f;
    static final float SPORT_PEDAL_EXP = 0.75f;
    static final float SURGE_FROM_LOAD = 0.5f;
    static final float SURGE_MAX = 0.8f;

    static final float CAR_MASS_KG = 2400f;
    static final float ROLLING_COEF = 0.011f;
    static final float DRAG_AREA_M2 = 0.67f;
    static final float AIR_DENSITY = 1.2f;
    static final float DRIVELINE_EFF = 0.85f;
    static final float[] TORQUE_RPM = {1000f, 2000f, 3000f, 4800f, 6000f, 6500f};
    static final float[] TORQUE_NM = {600f, 760f, 840f, 881f, 839f, 790f};

    static final float STAB_FROM = 80f;
    static final float STAB_FULL = 500f;
    static final float STAB_DECAY_SEC = 0.5f;
    static final float STAB_ATTACK_CUT = 0.6f;
    static final float STAB_RISE_CUT = 0.4f;
    static final float STAB_FLARE_RPM = 1200f;
    static final float SHIFT_FLARE_SHARE = 0.25f;
    static final float LIFT_FROM = 150f;
    static final float LIFT_FULL = 600f;
    static final float LIFT_DECAY_SEC = 0.8f;
    static final float OVERRUN_MIN_KMH = 15f;

    static final float DECEL_FULL = 6f;
    static final float DECEL_SMOOTH_SEC = 0.2f;
    static final float DECEL_HOLD_RPM = 800f;

    static final float LEVEL_FLOOR_DB = -19f;
    static final float LEVEL_LOAD_DB = 19f;
    static final float LEVEL_LOAD_CURVE = 0.6f;
    static final float LEVEL_SPEED_DB = 4f;
    static final float LEVEL_SURGE_DB = 2f;
    static final float LEVEL_STAB_DB = 2f;
    static final float SPEED_GAIN_DB = 2.5f;
    static final float BRIGHT_STAB_DB = 3f;
    static final float BRIGHT_SURGE_DB = 2f;
    static final float INTAKE_STAB_DB = 6f;

    static final float CRUISE_AFTER_SEC = 4f;
    static final float CRUISE_MAX_DB = 6f;
    static final float CRUISE_SEC = 3f;
    static final float CRUISE_BACK_SEC = 0.3f;
    static final float CRUISE_MOVE_PCT = 10f;
    static final float CRUISE_LOAD = 0.4f;
    static final float CRUISE_MIN_KMH = 10f;
    static final float THROTTLE_REF_SEC = 0.5f;

    static final float FREE_REV_MAX_RPM = 4500f;
    static final float REVERSE_MAX_RPM = 4500f;
    static final float REVERSE_WHINE_HZ_PER_KMH = 55f;
    static final float REVERSE_WHINE_FULL_KMH = 12f;
    static final float SHIFT_RESET_KMH = 3f;
    static final float SHIFT_SETTLE = 3f;
    static final float DOWNSHIFT_BLIP_RPM = 400f;
    static final float DOWNSHIFT_BLIP_LOAD = 0.4f;
    static final float BLIP_DECAY_SEC = 0.3f;
    static final float BLIP_LOAD_DECAY_SEC = 0.15f;
    static final float STAB_DOWNSHIFT_RAISE = 0.25f;
    static final float DECEL_DOWNSHIFT_RAISE = 0.2f;
    static final float SHIFT_GUARD = 0.95f;
    static final float SHIFT_LOAD_HOLD_SEC = 3f;
    static final float KICKDOWN_STAB = 0.5f;
    static final float KICKDOWN_ROOM = 0.8f;
    static final float LIFT_LAND = 1.6f;
    static final float KICKDOWN_PEDAL_PCT = 90f;
    static final float NO_UPSHIFT_DECEL = 0.1f;

    static final float COUPLE_SEC = 0.3f;
    static final float COUPLE_OVER = 0.75f;
    static final float COUPLE_KMH = 60f;

    static final float EAGER_UP_RPM = 600f;
    static final float EAGER_DOWN_RAISE = 0.1f;
    static final float EAGER_SEC = 1f;
    static final float EAGER_HOLD = 0.5f;
    static final float TIP_IN_RATE = 20f;

    static final float PSEUDO_ON_PCT = 80f;
    static final float PSEUDO_OFF_PCT = 55f;

    static final float SHIFT_LIMIT_RPM = 6200f;
    static final float REDLINE_RPM = 5500f;
    static final float REDLINE_REARM_RPM = 5300f;
    static final float LUG_RPM = 1100f;
    static final float DRIVER_HOLD_SEC = 4f;
    static final float DRIVER_HOLD_END = 0.02f;
    static final float SAME_UPSHIFT_SEC = 1f;

    static final float UPSHIFT_HOLD = 0.2f;
    static final float CUT_END = 0.6f;
    static final float CUT_FADE = 0.85f;
    static final float CUT_LOAD_FULL = 0.8f;
    static final float BARK_DB = 3f;
    static final float BARK_SEC = 0.08f;

    static final float START_CRANK_SEC = 0.6f;
    static final float START_CATCH_SEC = 0.3f;
    static final float START_SETTLE_SEC = 1.3f;
    static final float START_CRANK_RPM = 250f;
    static final float START_CRANK_LOAD = 0.12f;
    static final float START_FLARE_RPM = 2000f;
    static final float START_CRANK_TAU = 0.15f;
    static final float START_CATCH_TAU = 0.08f;
    static final float START_SETTLE_TAU = 0.35f;
    static final float STARTER_SEC = 0.04f;
    static final float START_LEAVE_KMH = 2f;
    static final float START_TAKEOVER_LOAD = 0.1f;
    static final float SURGE_GAIN_DB = 2.5f;
    static final float SURGE_BRIGHT_DB = 3f;
    static final float SURGE_INTAKE_DB = 6f;
    static final float SURGE_SEC = 0.3f;

    static final float ROLL_SPIN_SEC = 0.22f;
    static final float ROLL_SPIN_TAU = 0.06f;
    static final float ROLL_FLARE_RPM = 500f;
    static final float ROLL_CATCH_LOAD = 0.6f;
    static final float ROLL_SURGE = 0.6f;
    static final float ROLL_EASING_LOAD = 0.15f;
    static final float ROLL_EASING_SURGE = 0.25f;

    static final float STOP_DECEL = 800f;
    static final float STOP_TAU = 0.9f;
    static final float STOP_MOVING_DECEL = 2500f;
    static final float STOP_MOVING_TAU = 0.6f;
    static final float STOP_LUMP = 0.35f;
    static final float STOP_LUMP_RPM = 300f;
    static final float COMPRESSIONS_PER_REV = 4f;
    static final float STOP_TAIL_SEC = 0.35f;
    static final float TONE_OPEN_HZ = 20000f;

    static final float OVERRUN_LOAD = 0.08f;
    static final float OVERRUN_FROM_RPM = 150f;
    static final float OVERRUN_RPM_SPAN = 500f;
    static final float OVERRUN_SEC = 0.15f;

    static final float NEUTRAL_DECEL = 1000f;
    static final float NEUTRAL_DECEL_TAU = 6.5f;
    static final float NEUTRAL_LAND_SEC = 0.2f;
    static final float NEUTRAL_FUEL_RPM = 2600f;
    static final float NEUTRAL_CUT_SPAN = 900f;
    static final float CATCH_FLARE_RPM = 120f;
    static final float CATCH_RISE_SEC = 0.6f;
    static final float CATCH_FALL_SEC = 0.6f;
    static final float CATCH_LOAD = 0.08f;
    static final float CATCH_ARM_RPM = 600f;
    static final float CATCH_FROM_RPM = 30f;

    static final float MAX_DT = 0.1f;

    private final SoundProfile p;
    private Gearbox gearbox;
    private final boolean crank;
    private Phase phase = Phase.RUNNING;
    private float phaseSec;
    private float rpm;
    private float load;
    private float stabEnv;
    private float liftEnv;
    private float decel;
    private float effortEnv;
    private float blip;
    private float blipLoad;
    private float cruiseCutDb;
    private float steadySec;
    private float throttleRef = Float.NaN;
    private int gearIndex;
    private boolean gearPicked;
    private float shiftLoad;
    private boolean kickdown;
    private float shiftLeftSec;
    private boolean coupling;
    private float coupled;
    private float eager;
    private boolean pseudo;
    private final boolean pseudoAllowed;
    private boolean geared;
    private float upLeftSec;
    private float upFromRpm;
    private float cut;
    private float bark;
    private boolean barkPending;
    private int shiftSerial;
    private boolean shiftUp = true;
    private float starter;
    private float fire = 1f;
    private float surge;
    private int catchSerial;
    private boolean stopMoving;
    private float stopRpm;
    private float lumpPhase;
    private float stopAtSec;
    private boolean done;
    private float overrun;
    private float speedKmh;
    private Gear gear = Gear.UNKNOWN;
    private final boolean realNeutral;
    private boolean catchArmed;
    private float catchSec = -1f;
    private boolean driverOnly;
    private float driverHold;
    private int queuedShift;
    private float sameUpLeftSec;
    private boolean redlineArmed = true;
    private int beepSerial;

    VirtualEngine(SoundProfile profile) {
        this(profile, true);
    }

    VirtualEngine(SoundProfile profile, boolean crank) {
        this(profile, Gearbox.AUTO8, crank);
    }

    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank) {
        this(profile, gearbox, crank, true);
    }

    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank, boolean pseudoGears) {
        this(profile, gearbox, crank, pseudoGears, false);
    }

    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank, boolean pseudoGears, boolean realNeutral) {
        this.p = profile;
        this.gearbox = gearbox;
        this.crank = crank;
        this.pseudoAllowed = pseudoGears;
        this.realNeutral = realNeutral;
        this.rpm = profile.idleRpm;
    }

    void setGearbox(Gearbox g) {
        if (g == gearbox) {
            return;
        }
        gearbox = g;
        gearPicked = false;
        gearIndex = Math.min(gearIndex, g.rpmPerKmh.length - 1);
        shiftLeftSec = 0f;
        upLeftSec = 0f;
        cut = 0f;
        clearDriver();
    }

    private void clearDriver() {
        driverOnly = false;
        driverHold = 0f;
        queuedShift = 0;
        sameUpLeftSec = 0f;
        redlineArmed = true;
    }

    void ignite(Input in) {
        stabEnv = 0f;
        liftEnv = 0f;
        decel = 0f;
        effortEnv = 0f;
        blip = 0f;
        blipLoad = 0f;
        cruiseCutDb = 0f;
        steadySec = 0f;
        throttleRef = Float.NaN;
        shiftLeftSec = 0f;
        shiftLoad = 0f;
        pseudo = false;
        geared = false;
        upLeftSec = 0f;
        cut = 0f;
        bark = 0f;
        phaseSec = 0f;
        starter = 0f;
        overrun = 0f;
        surge = 0f;
        done = false;
        catchArmed = false;
        catchSec = -1f;
        clearDriver();
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        gearIndex = 0;
        gearPicked = true;
        eager = clamp01(in.eager);
        coupling = v >= COUPLE_KMH;
        coupled = coupling ? 1f : 0f;
        rpm = 0f;
        load = 0f;
        fire = 0f;
        if (v < START_LEAVE_KMH) {
            phase = Phase.STARTING;
            if (!crank) {
                phaseSec = START_CRANK_SEC;
                rpm = START_CRANK_RPM;
                fire = 1f;
                surge = 1f;
                catchSerial++;
            }
            return;
        }
        phase = Phase.JOINING;
        shiftLoad = loadTarget(in);
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (in.gear == Gear.D && in.mode == RpmMode.SHIFTS) {
            gearIndex = pickGear(p, gearbox, v, shiftLoad, SHIFT_GUARD, eager);
            if (!easing && gearIndex > 0
                    && v * gearbox.rpmPerKmh[gearIndex - 1] < upshiftRpm(p, 1f, eager) * SHIFT_GUARD) {
                gearIndex--;
                shiftLoad = 1f;
            }
        }
    }

    void shutdown(Input in) {
        if (phase == Phase.STOPPING) {
            return;
        }
        phase = Phase.STOPPING;
        phaseSec = 0f;
        stopMoving = speedOf(in) >= START_LEAVE_KMH;
        stopRpm = rpm;
        stopAtSec = 0f;
        lumpPhase = 0f;
        done = false;
        fire = 0f;
        starter = 0f;
        surge = 0f;
        cut = 0f;
        upLeftSec = 0f;
        shiftLeftSec = 0f;
        barkPending = false;
        overrun = 0f;
        clearDriver();
    }

    void step(float dt, Input in) {
        if (!(dt > 0f)) {
            return;
        }
        if (dt > MAX_DT) {
            dt = MAX_DT;
        }
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        phaseSec += dt;

        float stab = clamp01((in.pedalRate - STAB_FROM) / (STAB_FULL - STAB_FROM));
        stabEnv = Math.max(stab, stabEnv * decay(dt, STAB_DECAY_SEC));
        boolean overrunSpeed = in.gear == Gear.D && v >= OVERRUN_MIN_KMH;
        float lift = overrunSpeed ? clamp01((-in.pedalRate - LIFT_FROM) / (LIFT_FULL - LIFT_FROM)) : 0f;
        liftEnv = Math.max(lift, liftEnv * decay(dt, LIFT_DECAY_SEC));
        decel += (clamp01(-in.accel / DECEL_FULL) - decel) * follow(dt, DECEL_SMOOTH_SEC);
        eager += (clamp01(in.eager) - eager) * follow(dt, EAGER_SEC);
        effortEnv += (push(in) - effortEnv) * follow(dt, EFFORT_SMOOTH_SEC);
        blip *= decay(dt, BLIP_DECAY_SEC);
        blipLoad *= decay(dt, BLIP_LOAD_DECAY_SEC);
        bark *= decay(dt, BARK_SEC);
        surge *= decay(dt, SURGE_SEC);

        if (phase == Phase.STOPPING) {
            stepStop(dt);
            return;
        }
        if (phase == Phase.STARTING) {
            stepStart(dt, v, in);
            return;
        }
        if (phase == Phase.JOINING) {
            stepJoin(dt, v, in);
            return;
        }

        float target = loadTarget(in);
        if (in.gear == Gear.D && !in.braking && in.flow != Flow.REGEN && (target > 0f || in.flow == Flow.DRIVE)) {
            target = Math.max(target, cruiseLoad(v, rpm));
        }
        float tau = target > load ? p.loadAttackSec * (1f - STAB_ATTACK_CUT * stabEnv)
                : (in.braking ? p.brakeReleaseSec : p.loadReleaseSec);
        load += (target - load) * follow(dt, tau);
        shiftLoad = Math.max(load, shiftLoad * decay(dt, SHIFT_LOAD_HOLD_SEC));
        kickdown = stabEnv >= KICKDOWN_STAB || in.throttlePct >= KICKDOWN_PEDAL_PCT;
        if (kickdown) {
            shiftLoad = Math.max(shiftLoad, target);
        }
        updateCruise(dt, in.throttlePct, v);

        updatePseudo(in);
        takeButton(dt, in);
        geared = in.gear == Gear.D && byGears(in);
        boolean shifting = false;
        if (geared) {
            updateGear(dt, v, in);
            shifting = shiftLeftSec > 0f;
            updateCoupling(dt, v);
        } else {
            gearIndex = 0;
            gearPicked = false;
            shiftLeftSec = 0f;
            upLeftSec = 0f;
            coupling = false;
            coupled = 0f;
        }
        updateCatch(dt, target, in.gear);
        float goal = rpmTarget(v, in);
        if (upLeftSec > 0f) {
            stepUpshift(dt, goal, in.gear);
        } else {
            cut = 0f;
            moveRpm(dt, goal, shifting, in.gear);
        }
        float overrunTarget = target >= OVERRUN_LOAD ? 0f
                : neutral(in.gear) ? clamp01((rpm - NEUTRAL_FUEL_RPM) / NEUTRAL_CUT_SPAN)
                : clamp01((rpm - p.idleRpm - OVERRUN_FROM_RPM) / OVERRUN_RPM_SPAN);
        overrun += (overrunTarget - overrun) * follow(dt, OVERRUN_SEC);
    }

    private void updateCoupling(float dt, float v) {
        if (v < SHIFT_RESET_KMH) {
            coupling = false;
            coupled = 0f;
            return;
        }
        if (!coupling && (v * gearbox.rpmPerKmh[gearIndex] * COUPLE_OVER >= launchRpm(load) || v >= COUPLE_KMH)) {
            coupling = true;
        }
        if (coupling) {
            coupled += (1f - coupled) * follow(dt, COUPLE_SEC);
        }
    }

    private void updateCatch(float dt, float target, Gear g) {
        if (!neutral(g) || target >= OVERRUN_LOAD) {
            catchArmed = false;
            catchSec = -1f;
            return;
        }
        if (catchSec >= 0f) {
            catchSec += dt;
            if (catchSec > CATCH_RISE_SEC + 8f * CATCH_FALL_SEC) {
                catchSec = -1f;
            }
        }
        if (rpm > p.idleRpm + CATCH_ARM_RPM) {
            catchArmed = true;
        } else if (catchArmed && rpm < p.idleRpm + CATCH_FROM_RPM) {
            catchArmed = false;
            catchSec = 0f;
        }
    }

    private float catchShape() {
        if (catchSec < 0f) {
            return 0f;
        }
        return catchSec < CATCH_RISE_SEC ? smooth(catchSec / CATCH_RISE_SEC)
                : (float) Math.exp(-(catchSec - CATCH_RISE_SEC) / CATCH_FALL_SEC);
    }

    private boolean neutral(Gear g) {
        return realNeutral && g != Gear.D && g != Gear.R;
    }

    private void takeButton(float dt, Input in) {
        if (in.gear != Gear.D) {
            driverHold = 0f;
            queuedShift = 0;
            return;
        }
        if (in.shiftStep != 0) {
            if (!geared) {
                gearPicked = false;
            }
            queuedShift += in.shiftStep;
            driverOnly |= gearbox.manual();
        }
        if (load < CRUISE_LOAD) {
            driverHold *= decay(dt, DRIVER_HOLD_SEC);
            if (driverHold < DRIVER_HOLD_END) {
                driverHold = 0f;
            }
        }
    }

    private boolean driverGear() {
        return driverOnly || driverHold > 0f || queuedShift != 0;
    }

    private boolean byGears(Input in) {
        return in.mode == RpmMode.SHIFTS || pseudo || driverGear();
    }

    private void updatePseudo(Input in) {
        if (!pseudoAllowed || in.gear != Gear.D || in.mode == RpmMode.SHIFTS) {
            pseudo = false;
            return;
        }
        float t = in.throttlePct > 0f ? in.throttlePct : 0f;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (!pseudo) {
            if (!easing && (t >= PSEUDO_ON_PCT || (kickdown && t >= PSEUDO_OFF_PCT))) {
                pseudo = true;
                gearPicked = gearPicked && driverGear();
            }
        } else if (easing || t < PSEUDO_OFF_PCT) {
            pseudo = false;
        }
    }

    private void stepUpshift(float dt, float target, Gear g) {
        upLeftSec = Math.max(0f, upLeftSec - dt);
        float f = 1f - upLeftSec / gearbox.upshiftSec;
        float u = smooth(clamp01((f - UPSHIFT_HOLD) / (1f - UPSHIFT_HOLD)));
        target = clamp(target, p.idleRpm, capFor(g));
        rpm = upFromRpm + (target - upFromRpm) * u;
        float c = f < CUT_END ? 1f : 1f - smooth(clamp01((f - CUT_END) / (CUT_FADE - CUT_END)));
        float strength = clamp01(load / CUT_LOAD_FULL);
        if (f >= CUT_END && barkPending) {
            barkPending = false;
            bark = Math.max(bark, strength);
        }
        cut = upLeftSec > 0f ? c * strength : 0f;
    }

    private void stepStart(float dt, float v, Input in) {
        float crankEnd = p.startRpm > 0f ? 0.52f : START_CRANK_SEC;
        float catchEnd = crankEnd + (p.startRpm > 0f ? 1.2f : START_CATCH_SEC);
        float end = catchEnd + (p.startRpm > 0f ? 6f : START_SETTLE_SEC);
        float rpmTarget;
        float loadTarget;
        float starterTarget;
        float tau;
        if (phaseSec < crankEnd) {
            rpmTarget = p.startRpm > 0f ? 400f : START_CRANK_RPM;
            loadTarget = START_CRANK_LOAD;
            starterTarget = 1f;
            tau = START_CRANK_TAU;
        } else if (phaseSec < catchEnd) {
            rpmTarget = p.startRpm > 0f ? p.startRpm * 1.06f : START_FLARE_RPM;
            loadTarget = p.startRpm > 0f ? 0.35f : 1f;
            starterTarget = 0f;
            tau = START_CATCH_TAU;
            if (fire < 0.5f) {
                catchSerial++;
            }
            fire = 1f;
            surge = 1f;
        } else {
            rpmTarget = p.startRpm > 0f ? p.startRpm : p.idleRpm;
            loadTarget = 0f;
            starterTarget = 0f;
            tau = START_SETTLE_TAU;
        }
        starter += (starterTarget - starter) * follow(dt, STARTER_SEC);
        load += (loadTarget - load) * follow(dt, loadTarget > load ? p.loadAttackSec : p.loadReleaseSec);
        rpm += (rpmTarget - rpm) * follow(dt, tau);
        rpm = clamp(rpm, 0f, p.maxRpm);
        boolean pressed = demand(in.throttlePct) > START_TAKEOVER_LOAD;
        boolean leaving = v >= START_LEAVE_KMH;
        if (phaseSec >= end || (phaseSec >= catchEnd && (pressed || leaving))) {
            phase = Phase.RUNNING;
            starter = 0f;
            fire = 1f;
            gearPicked = false;
            if (rpm < p.idleRpm) {
                rpm = p.idleRpm;
            }
        }
    }

    private void stepJoin(float dt, float v, Input in) {
        float want = loadTarget(in);
        load += (want - load) * follow(dt, want > load ? p.loadAttackSec : p.loadReleaseSec);
        float target = clamp(rpmTarget(v, in), p.idleRpm, capFor(in.gear));
        rpm += (target - rpm) * follow(dt, ROLL_SPIN_TAU);
        if (phaseSec < ROLL_SPIN_SEC) {
            return;
        }
        phase = Phase.RUNNING;
        fire = 1f;
        catchSerial++;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        surge = Math.max(surge, easing ? ROLL_EASING_SURGE : ROLL_SURGE);
        blipLoad = Math.max(blipLoad, easing ? ROLL_EASING_LOAD : ROLL_CATCH_LOAD);
        if (!easing) {
            blip = Math.max(blip, ROLL_FLARE_RPM);
        }
    }

    private void stepStop(float dt) {
        load += (0f - load) * follow(dt, p.loadReleaseSec);
        if (stopRpm > 0f) {
            float decel = stopMoving ? STOP_MOVING_DECEL : STOP_DECEL;
            float tau = stopMoving ? STOP_MOVING_TAU : STOP_TAU;
            stopRpm = Math.max(0f, stopRpm - (decel + stopRpm / tau) * dt);
            lumpPhase += COMPRESSIONS_PER_REV * stopRpm / 60f * dt;
            lumpPhase -= (float) Math.floor(lumpPhase);
            float lump = STOP_LUMP * clamp01(1f - stopRpm / STOP_LUMP_RPM);
            rpm = stopRpm * (1f + lump * (float) Math.sin(2.0 * Math.PI * lumpPhase));
            if (stopRpm <= 0f) {
                rpm = 0f;
                stopAtSec = phaseSec;
            }
        } else if (phaseSec - stopAtSec >= STOP_TAIL_SEC) {
            rpm = 0f;
            done = true;
        }
    }

    private void updateGear(float dt, float v, Input in) {
        if (shiftLeftSec > 0f) {
            shiftLeftSec = Math.max(0f, shiftLeftSec - dt);
        }
        if (sameUpLeftSec > 0f) {
            sameUpLeftSec = Math.max(0f, sameUpLeftSec - dt);
        }
        if (v < SHIFT_RESET_KMH) {
            gearIndex = 0;
            gearPicked = true;
            driverHold = 0f;
            redlineArmed = true;
            if (queuedShift != 0) {
                refuseShift();
            }
            return;
        }
        if (!gearPicked) {
            gearIndex = pickGear(p, gearbox, v, shiftLoad, SHIFT_GUARD, eager);
            gearPicked = true;
            if (queuedShift == 0) {
                return;
            }
        }
        float inGear = v * gearbox.rpmPerKmh[gearIndex];
        updateRedline(inGear);
        if (shiftLeftSec > 0f) {
            return;
        }
        int last = gearbox.rpmPerKmh.length - 1;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (queuedShift != 0) {
            takeShift(v, easing);
            return;
        }
        if ((driverOnly || driverHold > 0f) && gearIndex < last && inGear >= SHIFT_LIMIT_RPM) {
            autoUpshift();
            return;
        }
        if (driverOnly) {
            return;
        }
        if (kickdown && !easing) {
            int to = pickGear(p, gearbox, v, shiftLoad, KICKDOWN_ROOM, eager);
            if (to < gearIndex) {
                downshiftTo(to, false);
                driverHold = 0f;
                return;
            }
        }
        float up = upshiftRpm(p, shiftLoad, eager);
        up += (SHIFT_LIMIT_RPM - up) * driverHold;
        float downRatio = p.shiftDownRatio + EAGER_DOWN_RAISE * eager + STAB_DOWNSHIFT_RAISE * stabEnv
                + (easing ? DECEL_DOWNSHIFT_RAISE * decel : 0f);
        float down = Math.max(upshiftRpm(p, easing ? shiftLoad : load, eager) * downRatio * (1f - driverHold), LUG_RPM);
        float landLimit = up * (easing ? SHIFT_GUARD : KICKDOWN_ROOM);
        if (p.liftDownRpm > 0f && loadTarget(in) < OVERRUN_LOAD) {
            down = Math.max(down, p.liftDownRpm);
            landLimit = Math.max(landLimit, p.liftDownRpm * LIFT_LAND);
        }
        boolean slowing = easing && decel > NO_UPSHIFT_DECEL;
        boolean holding = loadTarget(in) < OVERRUN_LOAD && (eager > EAGER_HOLD || gearbox.manual())
                && inGear < upshiftRpm(p, 1f, eager) * SHIFT_GUARD;
        boolean pressing = in.pedalRate > TIP_IN_RATE;
        if (gearIndex < last && inGear >= up && !slowing && !holding && !pressing) {
            autoUpshift();
            driverHold = 0f;
        } else if (gearIndex > 0 && inGear < down
                && v * gearbox.rpmPerKmh[gearIndex - 1] < landLimit) {
            downshiftTo(gearIndex - 1, easing);
            driverHold = 0f;
        }
    }

    private void takeShift(float v, boolean easing) {
        int step = Integer.signum(queuedShift);
        queuedShift -= step;
        int to = gearIndex + step;
        if (to < 0 || to >= gearbox.rpmPerKmh.length) {
            refuseShift();
            return;
        }
        float after = v * gearbox.rpmPerKmh[to];
        if (step > 0) {
            if (sameUpLeftSec > 0f) {
                return;
            }
            if (after < LUG_RPM && !gearbox.manual()) {
                refuseShift();
                return;
            }
            upshift();
        } else {
            if (after > SHIFT_LIMIT_RPM) {
                refuseShift();
                return;
            }
            downshiftTo(to, easing);
        }
        if (!gearbox.manual()) {
            driverHold = 1f;
        }
    }

    private void refuseShift() {
        queuedShift = 0;
        beepSerial++;
    }

    private void updateRedline(float inGear) {
        if (inGear < REDLINE_REARM_RPM) {
            redlineArmed = true;
        } else if (redlineArmed && inGear >= REDLINE_RPM && (driverOnly || driverHold > 0f)) {
            redlineArmed = false;
            beepSerial++;
        }
    }

    private void autoUpshift() {
        upshift();
        sameUpLeftSec = SAME_UPSHIFT_SEC;
    }

    private void upshift() {
        gearIndex++;
        shiftLeftSec = gearbox.upshiftSec;
        upLeftSec = gearbox.upshiftSec;
        upFromRpm = rpm;
        barkPending = true;
        shiftSerial++;
        shiftUp = true;
    }

    private void downshiftTo(int to, boolean easing) {
        gearIndex = to;
        shiftLeftSec = p.shiftSec * SHIFT_SETTLE;
        upLeftSec = 0f;
        shiftSerial++;
        shiftUp = false;
        blip = Math.max(blip, p.blipRpm);
        blipLoad = Math.max(blipLoad, easing ? p.blipLoad : p.blipLoad * 0.5f);
    }

    private float rpmTarget(float v, Input in) {
        float base;
        switch (in.gear) {
            case R:
                base = Math.min(gearedRpm(launchRpm(load), v * gearbox.reverseRpmPerKmh), REVERSE_MAX_RPM);
                break;
            case D:
                if (byGears(in)) {
                    float inGear = v * gearbox.rpmPerKmh[gearIndex];
                    float slipping = slipRpm(launchRpm(load), inGear)
                            + STAB_FLARE_RPM * SHIFT_FLARE_SHARE * stabEnv * (1f - speedCurve(p, v));
                    base = slipping + (inGear - slipping) * coupled;
                } else {
                    boolean easing = in.braking || in.flow == Flow.REGEN;
                    base = continuousRpm(p, v, load)
                            + STAB_FLARE_RPM * stabEnv * (1f - 0.5f * speedCurve(p, v))
                            + (easing ? DECEL_HOLD_RPM * decel * speedCurve(p, v) : 0f);
                }
                break;
            default:
                base = p.idleRpm + (p.freeRevRpm - p.idleRpm) * load + CATCH_FLARE_RPM * catchShape();
                break;
        }
        return base + blip;
    }

    private void moveRpm(float dt, float target, boolean shifting, Gear g) {
        float cap = capFor(g);
        target = clamp(target, p.idleRpm, cap);
        boolean free = neutral(g);
        float riseTau = p.rpmRiseSec * (1f - STAB_RISE_CUT * stabEnv);
        float tau = shifting ? (!shiftUp && p.downSlewSec > 0f ? p.downSlewSec : p.shiftSec) : (target > rpm ? riseTau : (free ? NEUTRAL_LAND_SEC : p.rpmFallSec));
        float next = rpm + (target - rpm) * follow(dt, tau);
        if (!shifting) {
            float up = p.rpmRisePerSec * (1f + stabEnv) * dt;
            float down = (free ? NEUTRAL_DECEL + rpm / NEUTRAL_DECEL_TAU : p.rpmFallPerSec) * dt;
            next = clamp(next, rpm - down, rpm + up);
        }
        rpm = clamp(next, p.idleRpm, cap);
    }

    private float capFor(Gear g) {
        if (g == Gear.D) {
            return p.maxRpm;
        }
        return g == Gear.R ? REVERSE_MAX_RPM : p.freeRevRpm;
    }

    private float launchRpm(float l) {
        return p.idleRpm + (p.maxRpm - p.idleRpm) * p.pedalKick * l;
    }

    private void updateCruise(float dt, float throttlePct, float v) {
        float t = throttlePct > 0f ? throttlePct : 0f;
        if (Float.isNaN(throttleRef)) {
            throttleRef = t;
        }
        throttleRef += (t - throttleRef) * follow(dt, THROTTLE_REF_SEC);
        if (Math.abs(t - throttleRef) > CRUISE_MOVE_PCT || v < CRUISE_MIN_KMH) {
            steadySec = 0f;
        } else {
            steadySec += dt;
        }
        boolean cruising = steadySec >= CRUISE_AFTER_SEC && load < CRUISE_LOAD;
        float targetCut = cruising ? CRUISE_MAX_DB : 0f;
        cruiseCutDb += (targetCut - cruiseCutDb) * follow(dt, cruising ? CRUISE_SEC : CRUISE_BACK_SEC);
    }


    static float speedCurve(SoundProfile p, float speedKmh) {
        return speedKmh > 0f ? 1f - (float) Math.exp(-speedKmh / p.speedKneeKmh) : 0f;
    }

    static float demand(float throttlePct) {
        if (!(throttlePct > THROTTLE_DEADBAND)) {
            return 0f;
        }
        float x = (throttlePct - THROTTLE_DEADBAND) / (THROTTLE_FULL - THROTTLE_DEADBAND);
        return (float) Math.pow(clamp01(x), LOAD_CURVE);
    }

    static float demand(float throttlePct, float sport) {
        if (!(throttlePct > THROTTLE_DEADBAND)) {
            return 0f;
        }
        float p = Math.min(throttlePct, 100f);
        float deeper = 100f * (float) Math.pow(p / 100f, SPORT_PEDAL_EXP);
        return demand(p + (deeper - p) * clamp01(sport));
    }

    static float effort(Input in) {
        if (in.braking || in.flow == Flow.REGEN || (in.gear != Gear.D && in.gear != Gear.R) || demand(in.throttlePct) > 0f) {
            return 0f;
        }
        return in.flow == Flow.DRIVE ? clamp01(in.accel / ACCEL_FULL) : 0f;
    }

    static float loadTarget(Input in) {
        if (in.braking || in.flow == Flow.REGEN) {
            return 0f;
        }
        if (in.gear != Gear.D && in.gear != Gear.R) {
            return demand(in.throttlePct);
        }
        return Math.max(demand(in.throttlePct, in.sportPedal), ACCEL_LOAD_WEIGHT * effort(in));
    }

    static float push(Input in) {
        if (in.braking || in.flow == Flow.REGEN || (in.gear != Gear.D && in.gear != Gear.R)) {
            return 0f;
        }
        float d = demand(in.throttlePct, in.sportPedal);
        return Math.max(effort(in), SURGE_MAX * clamp01((d - SURGE_FROM_LOAD) / (1f - SURGE_FROM_LOAD)));
    }

    static float cruiseLoad(float speedKmh, float rpm) {
        float v = Math.max(speedKmh, 0f) / 3.6f;
        float force = ROLLING_COEF * CAR_MASS_KG * 9.81f + 0.5f * AIR_DENSITY * DRAG_AREA_M2 * v * v;
        float available = DRIVELINE_EFF * maxTorque(rpm) * rpm * (float) (2.0 * Math.PI / 60.0);
        return available > 0f ? clamp01(force * v / available) : 0f;
    }

    static float maxTorque(float rpm) {
        if (rpm <= TORQUE_RPM[0]) {
            return TORQUE_NM[0];
        }
        for (int k = 1; k < TORQUE_RPM.length; k++) {
            if (rpm <= TORQUE_RPM[k]) {
                float f = (rpm - TORQUE_RPM[k - 1]) / (TORQUE_RPM[k] - TORQUE_RPM[k - 1]);
                return TORQUE_NM[k - 1] + (TORQUE_NM[k] - TORQUE_NM[k - 1]) * f;
            }
        }
        return TORQUE_NM[TORQUE_NM.length - 1];
    }

    static float continuousRpm(SoundProfile p, float speedKmh, float load) {
        float s = speedCurve(p, speedKmh);
        float kick = p.pedalKick * load;
        float x = kick + (1f - kick) * s * (p.speedShare + (1f - p.speedShare) * load);
        return p.idleRpm + (p.maxRpm - p.idleRpm) * clamp01(x);
    }

    static float gearedRpm(float launch, float inGear) {
        double a = Math.max(0f, launch);
        double b = Math.max(0f, inGear);
        return (float) Math.cbrt(a * a * a + b * b * b);
    }

    static float slipRpm(float launch, float inGear) {
        double a = Math.max(0f, launch);
        double b = Math.max(0f, inGear);
        double a2 = a * a;
        double b2 = b * b;
        double a8 = a2 * a2 * a2 * a2;
        double b8 = b2 * b2 * b2 * b2;
        return (float) Math.pow(a8 + b8, 0.125);
    }

    static float upshiftRpm(SoundProfile p, float load) {
        return upshiftRpm(p, load, 0f);
    }

    static float upshiftRpm(SoundProfile p, float load, float eager) {
        float l = clamp01(load);
        float l2 = l * l;
        return Math.min(p.shiftUpBaseRpm + EAGER_UP_RPM * clamp01(eager) * (1f - l2) + p.shiftUpLoadRpm * l2, p.maxRpm - 300f);
    }

    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load) {
        return pickGear(p, gearbox, speedKmh, load, SHIFT_GUARD);
    }

    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load, float room) {
        return pickGear(p, gearbox, speedKmh, load, room, 0f);
    }

    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load, float room, float eager) {
        float up = upshiftRpm(p, load, eager) * room;
        for (int g = 0; g < gearbox.rpmPerKmh.length; g++) {
            if (speedKmh * gearbox.rpmPerKmh[g] < up) {
                return g;
            }
        }
        return gearbox.rpmPerKmh.length - 1;
    }

    static float follow(float dt, float tau) {
        return tau > 0f ? 1f - (float) Math.exp(-dt / tau) : 1f;
    }

    static float decay(float dt, float tau) {
        return tau > 0f ? (float) Math.exp(-dt / tau) : 0f;
    }

    static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    private static float speedOf(Input in) {
        return in.speedKmh > 0f ? in.speedKmh : 0f;
    }


    Phase phase() {
        return phase;
    }

    float rpm() {
        return rpm;
    }

    float load() {
        return load;
    }

    float voiceLoad() {
        return phase == Phase.JOINING ? 0f : Math.max(Math.max(load, blipLoad), CATCH_LOAD * catchShape());
    }

    float levelDb() {
        float s = speedCurve(p, speedKmh);
        float l = voiceLoad();
        return LEVEL_FLOOR_DB + LEVEL_LOAD_DB * (float) Math.pow(l, LEVEL_LOAD_CURVE) + LEVEL_SPEED_DB * s
                + LEVEL_SURGE_DB * effortEnv + LEVEL_STAB_DB * stabEnv - cruiseCutDb + p.coastDb * s * (1f - Math.min(l, 1f));
    }

    float outputDb() {
        return SPEED_GAIN_DB * speedCurve(p, speedKmh) + SURGE_GAIN_DB * surge;
    }

    float starter() {
        return starter;
    }

    float fire() {
        return fire;
    }

    int catchSerial() {
        return catchSerial;
    }

    boolean done() {
        return done;
    }

    float brightDb() {
        return BRIGHT_STAB_DB * stabEnv + BRIGHT_SURGE_DB * effortEnv + BARK_DB * bark + SURGE_BRIGHT_DB * surge;
    }

    float cut() {
        return cut;
    }

    int shiftSerial() {
        return shiftSerial;
    }

    boolean shiftUp() {
        return shiftUp;
    }

    boolean geared() {
        return geared;
    }

    boolean pseudoGears() {
        return pseudo;
    }

    float intakeBoostDb() {
        return INTAKE_STAB_DB * stabEnv + SURGE_INTAKE_DB * surge;
    }

    float burble() {
        return liftEnv;
    }

    float overrun() {
        return overrun;
    }

    float speedKmh() {
        return speedKmh;
    }

    float whineHz() {
        return gear == Gear.R ? REVERSE_WHINE_HZ_PER_KMH * speedKmh : 0f;
    }

    float whineAmp() {
        return gear == Gear.R ? clamp01(speedKmh / REVERSE_WHINE_FULL_KMH) : 0f;
    }

    float eager() {
        return eager;
    }

    float coupled() {
        return coupled;
    }

    int gearIndex() {
        return gearIndex;
    }

    boolean driverOnly() {
        return driverOnly;
    }

    float driverHold() {
        return driverHold;
    }

    int beepSerial() {
        return beepSerial;
    }

    float cruiseCutDb() {
        return cruiseCutDb;
    }

    void fill(VoiceParams out) {
        out.rpm = rpm;
        out.load = voiceLoad();
        out.level = EngineVoice.dbToLin(levelDb());
        out.brightDb = brightDb();
        out.intakeBoostDb = intakeBoostDb();
        out.burble = burble();
        out.starter = starter;
        out.fire = fire;
        out.catchSerial = catchSerial;
        out.whineHz = whineHz();
        out.whineAmp = whineAmp();
        out.overrun = overrun;
        out.speedKmh = speedKmh;
        out.drive = gear == Gear.D;
        out.gearIndex = geared ? gearIndex : -1;
        out.cut = cut;
        out.shiftSerial = shiftSerial;
        out.shiftUp = shiftUp;
    }
}
