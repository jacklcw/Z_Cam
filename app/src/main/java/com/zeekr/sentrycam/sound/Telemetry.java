package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The few car signals the engine sound needs, read from the ECARX car API by reflection (same ids as zeekr-shortcut-car's Signal table).
 * Polling only (no push subscriptions): speed, throttle and brake depth every 20 ms while the engine sound is running; gear, drive mode,
 * ignition, power flow and brake pedal every 250 ms while it is switched on.
 */
enum Signal {
    SPEED(true, 0x00100100), THROTTLE_DEPTH(true, 0x00101400), BRAKE_DEPTH(true, 0x00101300),
    GEAR(false, 0x00200200), DRIVE_MODE(false, 0x22010100), POWER_FLOW(false, 0x24010100),
    IGNITION(false, 0x20259000), BRAKE_PEDAL(false, 0x20317A00);

    static final float BRAKE_FULL = 44f;
    static final int DRIVE_MODE_COMFORT = 0x22010101;
    static final int DRIVE_MODE_STANDARD = 0x22010102;
    static final int DRIVE_MODE_SPORT = 0x22010103;
    static final int POWER_FLOW_DRIVE = 0x24010113;
    static final int POWER_FLOW_IDLE = 0x24010114;
    static final int POWER_FLOW_REGEN = 0x24010115;
    static final int POWER_FLOW_NOT_READY = 0;
    static final int IGNITION_ACC = 0x00200104;
    static final int IGNITION_ON = 0x00200105;
    static final int IGNITION_DRIVING = 0x00200107;

    final boolean sensorValue;   // float sensor (speed, pedals); gear is a sensor event; the rest are functions
    final int id;
    Signal(boolean sensorValue, int id) { this.sensorValue = sensorValue; this.id = id; }

    /** Raw reading to the value the engine code expects (speed km/h, pedals %, gear letter, codes); null = no data. */
    Object decode(Object raw) {
        if (!(raw instanceof Number)) return null;
        Number n = (Number) raw;
        if (this == SPEED || this == THROTTLE_DEPTH || this == BRAKE_DEPTH) {
            float f = n.floatValue();
            if (Float.isNaN(f) || f == 255f || f == -65535f || (f != 0f && Math.abs(f) < 1e-6f)) return null;
            if (this == SPEED) return f * 3.6f;
            if (this == BRAKE_DEPTH) return f / BRAKE_FULL * 100f;
            return f;
        }
        int v = n.intValue();
        if (v == 255 || v == 254 || v == 253 || v == -1 || v == -65535) return null;
        if (this == GEAR) {
            switch (v) { case 0x00200230: return "P"; case 0x00200240: return "R"; case 0x00200210: return "N"; case 0x00200220: return "D"; default: return null; }
        }
        if (this == BRAKE_PEDAL) return v == 0 ? Boolean.FALSE : v == 1 ? Boolean.TRUE : null;
        return v;
    }
}

final class Readings {
    private final Map<Signal, Object> v;
    Readings(Map<Signal, Object> v) { this.v = v; }
    Integer code(Signal s) { Object o = v.get(s); return o instanceof Integer ? (Integer) o : null; }
    String text(Signal s) { Object o = v.get(s); return o instanceof String ? (String) o : null; }
    Boolean bool(Signal s) { Object o = v.get(s); return o instanceof Boolean ? (Boolean) o : null; }
}

final class FastReadings {
    private final Map<Signal, Float> values;
    final long atMs;
    final long version;
    FastReadings(Map<Signal, Float> values, long atMs, long version) { this.values = values; this.atMs = atMs; this.version = version; }
    Float number(Signal s) { return values.get(s); }
    long ageMs(long nowMs) { return atMs <= 0L ? -1L : Math.max(0L, nowMs - atMs); }
}

/** No push subscriptions in this port: always empty, so the engine code falls back to the polled values. */
final class PushedValue {
    private static final PushedValue EMPTY = new PushedValue();
    final float value = Float.NaN;
    final long atMs = 0L;
    final long count = 0L;
    final float everyMs = -1f;
    static PushedValue empty() { return EMPTY; }
}

final class SignalAges {
    static final Map<Signal, Long> CHANGED_AT = new ConcurrentHashMap<>();
    private SignalAges() {}
    static long ageMs(Signal s, long nowMs) { Long at = CHANGED_AT.get(s); return at == null ? -1 : Math.max(0, nowMs - at); }
}

final class Telemetry {
    interface Listener { void onReadingsChanged(Readings readings); }

    private static final Telemetry INSTANCE = new Telemetry();
    static Telemetry get() { return INSTANCE; }

    private static final String CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car";
    private static final long SLOW_MS = 250L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Set<String> fastUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private HandlerThread thread;
    private Handler bg;
    private boolean core;
    private volatile boolean fastOn;
    private long fastMs = 20L;

    private Object car, function, sensor;
    private Method getFunctionValue, getSensorEvent, getSensorLatestValue;
    private volatile boolean connected;
    private long lastTry;

    private volatile Readings readings = new Readings(new EnumMap<>(Signal.class));
    private volatile FastReadings fast = new FastReadings(new EnumMap<>(Signal.class), 0L, 0L);
    private long fastVersion;

    void addListener(Listener l) { synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); } }

    /** Run the slow (gear, mode, ignition) poll: only while the engine sound is switched on. */
    synchronized void core(Context ctx, boolean on) {
        if (on == core) return;
        core = on;
        if (on) { ensureThread(); bg.post(slow); } else maybeStop();
    }

    synchronized void acquireFast(String who, Set<Signal> signals, long intervalMs) {
        fastUsers.add(who); fastMs = Math.max(10L, intervalMs); fastOn = true;
        ensureThread(); bg.removeCallbacks(fastPoll); bg.post(fastPoll);
    }

    synchronized void releaseFast(String who) {
        fastUsers.remove(who);
        if (fastUsers.isEmpty()) { fastOn = false; fast = new FastReadings(new EnumMap<>(Signal.class), 0L, 0L); maybeStop(); }
    }

    private void ensureThread() {
        if (thread == null) { thread = new HandlerThread("EngineSignals"); thread.start(); bg = new Handler(thread.getLooper()); }
    }

    private void maybeStop() {
        if (!core && !fastOn && thread != null) { thread.quitSafely(); thread = null; bg = null; connected = false; car = null; function = null; sensor = null; }
    }

    Readings readings() { return readings; }
    FastReadings fast() { return fast; }
    PushedValue pushed(Signal s) { return PushedValue.empty(); }
    PushedValue throttleListener(boolean dr) { return PushedValue.empty(); }
    String pushStatus() { return "polling only"; }
    List<String> fastUsers() { return new ArrayList<>(fastUsers); }
    boolean carLinkConnected() { return connected; }

    private boolean connect() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTry < 5000L) return false;
        lastTry = now;
        try {
            Context c = Shim.app;
            Class<?> cc = Class.forName(CAR_CLASS);
            car = cc.getMethod("create", Context.class).invoke(null, c);
            function = car.getClass().getMethod("getICarFunction").invoke(car);
            sensor = car.getClass().getMethod("getSensorManager").invoke(car);
            getFunctionValue = function.getClass().getMethod("getFunctionValue", int.class);
            getSensorEvent = sensor.getClass().getMethod("getSensorEvent", int.class);
            getSensorLatestValue = sensor.getClass().getMethod("getSensorLatestValue", int.class);
            connected = true;
            Shim.log("car api connected");
        } catch (Throwable t) {
            connected = false;
            Shim.log("car api unavailable " + t.getClass().getSimpleName());
        }
        return connected;
    }

    private Object read(Signal s) {
        try {
            if (s.sensorValue) return getSensorLatestValue.invoke(sensor, s.id);
            if (s == Signal.GEAR) return getSensorEvent.invoke(sensor, s.id);
            return getFunctionValue.invoke(function, s.id);
        } catch (Throwable t) { return null; }
    }

    private int fastCycle;
    private long pollStatT0;
    private long pollStatN;
    private long pollStatSum;
    private long pollStatMax;
    private long pollStatGapMax;
    private long pollLastStart;

    /**
     * Z_Cam: the throttle is read every cycle, speed and brake only every 3rd (they change slowly; three car-API reads per cycle made the
     * pedal reach the sound late). Every 10 s the cycle cost is logged so a slow car API shows up in the log.
     */
    private final Runnable fastPoll = new Runnable() {
        @Override public void run() {
            if (!fastOn || bg == null) return;
            long t0 = SystemClock.elapsedRealtime();
            if (connected || connect()) {
                Map<Signal, Float> m = new EnumMap<>(Signal.class);
                FastReadings prev = fast;
                boolean full = (fastCycle++ % 3) == 0;
                for (Signal s : new Signal[]{Signal.THROTTLE_DEPTH, Signal.SPEED, Signal.BRAKE_DEPTH}) {
                    if (s == Signal.THROTTLE_DEPTH || full) {
                        Object d = s.decode(read(s));
                        if (d instanceof Float) m.put(s, (Float) d);
                    } else {
                        Float old = prev == null ? null : prev.number(s);
                        if (old != null) m.put(s, old);
                    }
                }
                fast = new FastReadings(m, SystemClock.elapsedRealtime(), ++fastVersion);
            }
            long t1 = SystemClock.elapsedRealtime();
            pollStatN++;
            pollStatSum += t1 - t0;
            pollStatMax = Math.max(pollStatMax, t1 - t0);
            if (pollLastStart > 0) pollStatGapMax = Math.max(pollStatGapMax, t0 - pollLastStart);
            pollLastStart = t0;
            if (pollStatT0 == 0) pollStatT0 = t1;
            if (t1 - pollStatT0 >= 10_000L && pollStatN > 0) {
                Shim.log("latency: car poll " + pollStatN + " cycles in 10 s, cycle cost avg " + (pollStatSum / pollStatN) + " ms max " + pollStatMax
                        + " ms, longest gap between cycles " + pollStatGapMax + " ms");
                pollStatT0 = t1; pollStatN = 0; pollStatSum = 0; pollStatMax = 0; pollStatGapMax = 0;
            }
            Handler h = bg;
            if (h != null) h.postDelayed(this, fastMs);
        }
    };

    private final Runnable slow = new Runnable() {
        @Override public void run() {
            if (!core || bg == null) return;
            if (connected || connect()) {
                Map<Signal, Object> m = new EnumMap<>(Signal.class);
                long now = SystemClock.elapsedRealtime();
                boolean changed = false;
                for (Signal s : new Signal[]{Signal.GEAR, Signal.DRIVE_MODE, Signal.POWER_FLOW, Signal.IGNITION, Signal.BRAKE_PEDAL}) {
                    Object d = s.decode(read(s));
                    if (d != null) m.put(s, d);
                    Object before = readings.code(s) != null ? readings.code(s) : readings.text(s) != null ? readings.text(s) : readings.bool(s);
                    if (before == null ? d != null : !before.equals(d)) {
                        changed = true;
                        if (before != null && d != null) SignalAges.CHANGED_AT.put(s, now);
                    }
                }
                readings = new Readings(m);
                if (changed) {
                    final Readings r = readings;
                    main.post(() -> { synchronized (listeners) { for (Listener l : new ArrayList<>(listeners)) l.onReadingsChanged(r); } });
                }
            }
            Handler h = bg;
            if (h != null) h.postDelayed(this, SLOW_MS);
        }
    };
}
