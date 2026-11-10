package com.zeekr.sentrycam.sound;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Desktop test ground: runs the app's real VirtualEngine and the real voice of every engine style, plays through this
 * computer's speakers, and is steered by a web page (pedal, brake, gear). Silent until the page's Start button is
 * pressed: no audio line is opened before that. Listens on 127.0.0.1 only.
 */
public final class SimServer {
    static final int RATE = 48000, BLOCK = 256;
    static volatile String styleKey = "typer";
    static volatile float pitchSt, bassDb, darkHz = 20000f;
    static volatile float pedalIn, brakeIn, depth = 1f, volumeDb = 0f;
    static volatile boolean drive, shifts = true, wantRun, wantReset;
    static volatile float sRpm, sSpeed, sLoad;
    static volatile int sGear = -1;
    static volatile String sPhase = "off";
    static Thread audio;

    static String ups() {
        SoundStyle st = SoundStyle.of(styleKey);
        Gearbox gb = st.gearbox(Gearbox.AUTO8);
        StringBuilder sb = new StringBuilder("[");
        for (int g = 0; g + 1 < gb.rpmPerKmh.length; g++) {
            sb.append(g > 0 ? "," : "").append(String.format("[%.1f,%.1f,%.0f,%.0f]",
                    VirtualEngine.upshiftRpm(st.profile(), 0f) / gb.rpmPerKmh[g],
                    VirtualEngine.upshiftRpm(st.profile(), 1f) / gb.rpmPerKmh[g],
                    gb.rpmPerKmh[g], 0f));
        }
        return sb.append("]").toString();
    }

    /** Sets the (all-gear) upshift rpm at light and at full pedal on the live profile. */
    static void setUps(float lightRpm, float fullRpm) {
        try {
            SoundProfile p = SoundStyle.of(styleKey).profile();
            java.lang.reflect.Field b = SoundProfile.class.getDeclaredField("shiftUpBaseRpm");
            java.lang.reflect.Field l = SoundProfile.class.getDeclaredField("shiftUpLoadRpm");
            b.setAccessible(true);
            l.setAccessible(true);
            b.setFloat(p, lightRpm);
            l.setFloat(p, Math.max(0f, fullRpm - lightRpm));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] a) throws Exception {
        String html = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8);
        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8765), 0);
        srv.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", html);
            } else {
                send(ex, 404, "text/plain", "no");
            }
        });
        srv.createContext("/state", ex -> send(ex, 200, "application/json",
                String.format("{\"rpm\":%.0f,\"speed\":%.0f,\"gear\":%d,\"load\":%.2f,\"phase\":\"%s\",\"on\":%b,\"ups\":%s}", sRpm, sSpeed, sGear, sLoad, sPhase, audio != null && audio.isAlive(), ups())));
        srv.createContext("/ups", ex -> {
            Map<String, String> m = form(ex);
            setUps(Float.parseFloat(m.get("light")), Float.parseFloat(m.get("full")));
            send(ex, 200, "text/plain", "ok");
        });
        srv.createContext("/ctl", ex -> {
            Map<String, String> m = form(ex);
            if (m.containsKey("pedal")) pedalIn = Float.parseFloat(m.get("pedal"));
            if (m.containsKey("brake")) brakeIn = Float.parseFloat(m.get("brake"));
            if (m.containsKey("depth")) depth = Float.parseFloat(m.get("depth"));
            if (m.containsKey("drive")) drive = m.get("drive").equals("1");
            if (m.containsKey("shifts")) shifts = m.get("shifts").equals("1");
            if (m.containsKey("drive2")) CivicTypeRVoice.tuneDrive = Float.parseFloat(m.get("drive2"));
            if (m.containsKey("rasp")) CivicTypeRVoice.tuneRaspDb = Float.parseFloat(m.get("rasp"));
            if (m.containsKey("sharp")) CivicTypeRVoice.tuneRise = Float.parseFloat(m.get("sharp"));
            if (m.containsKey("fade")) CivicTypeRVoice.tuneRedlineFade = Float.parseFloat(m.get("fade"));
            if (m.containsKey("pitch")) pitchSt = Float.parseFloat(m.get("pitch"));
            if (m.containsKey("bass")) bassDb = Float.parseFloat(m.get("bass"));
            if (m.containsKey("dark")) darkHz = Float.parseFloat(m.get("dark"));
            if (m.containsKey("vol")) volumeDb = Float.parseFloat(m.get("vol"));
            if (m.containsKey("sound") && !m.get("sound").equals(styleKey)) {
                styleKey = m.get("sound");
                wantReset = true;
            }
            send(ex, 200, "text/plain", "ok");
        });
        srv.createContext("/start", ex -> {
            synchronized (SimServer.class) {
                if (audio == null || !audio.isAlive()) {
                    wantRun = true;
                    audio = new Thread(SimServer::loop, "simaudio");
                    audio.start();
                }
            }
            send(ex, 200, "text/plain", "ok");
        });
        srv.createContext("/stop", ex -> {
            wantRun = false;
            send(ex, 200, "text/plain", "ok");
        });
        srv.start();
        System.out.println("Test ground at http://127.0.0.1:8765/  (silent until you press Start in the page)");
    }

    static void loop() {
        SourceDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BLOCK * 4 * 6);
            line.start();
            byte[] pcm = new byte[BLOCK * 4];
            float[] buf = new float[2 * BLOCK];
            float dt = BLOCK / (float) RATE;
            while (wantRun) {
                wantReset = false;
                SoundStyle style = SoundStyle.of(styleKey);
                VirtualEngine e = new VirtualEngine(style.profile(), style.gearbox(Gearbox.AUTO8), style.cranks(), style.pseudoGears(), style.realNeutral());
                Voice v = style.voice(RATE, false);
                VirtualEngine.Input in = new VirtualEngine.Input();
                in.gear = VirtualEngine.Gear.P;
                in.throttlePct = 0f;
                VoiceParams p = new VoiceParams();
                Biquad[] shelf = new Biquad[2], lp = new Biquad[2];
                float setBass = Float.NaN, setDark = Float.NaN;
                float speed = 0f, pedal = 0f, lastPedal = 0f, lastSpeed = 0f;
                boolean stopping = false;
                e.ignite(in);
                while (wantRun && !wantReset) {
                    float target = pedalIn * depth;
                    pedal += (target - pedal) * Math.min(1f, dt / (target > pedal ? 0.35f : 0.25f));
                    in.mode = shifts ? VirtualEngine.RpmMode.SHIFTS : VirtualEngine.RpmMode.CONTINUOUS;
                    in.gear = drive ? VirtualEngine.Gear.D : VirtualEngine.Gear.P;
                    float acc = 0f;
                    if (drive && !stopping) {
                        float thr = pedal * (e.phase() == VirtualEngine.Phase.RUNNING ? 1f : 0f);
                        float a = thr * Math.min(5f, 1000f / (speed + 55f)) - 0.00007f * speed * speed - 0.25f - (brakeIn > 0f ? 9f : 0f) - (pedal < 0.05f ? 0.5f : 0f);
                        speed = Math.max(0f, speed + a * dt * 3.6f);
                        acc = a;
                    } else {
                        speed = Math.max(0f, speed - 20f * dt);
                    }
                    in.speedKmh = speed;
                    in.accel = acc;
                    in.throttlePct = pedal * 100f;
                    in.pedalRate = (pedal - lastPedal) / dt * 100f;
                    in.braking = brakeIn > 0f;
                    lastPedal = pedal;
                    e.step(dt, in);
                    e.fill(p);
                    p.rpm *= (float) Math.pow(2.0, pitchSt / 12.0);
                    p.gain = EngineVoice.dbToLin(volumeDb - 8f + e.outputDb());
                    v.renderStereo(buf, BLOCK, p);
                    float bd = bassDb, dk = darkHz;
                    if (bd != setBass || dk != setDark) {
                        setBass = bd;
                        setDark = dk;
                        for (int c = 0; c < 2; c++) {
                            shelf[c] = Biquad.lowShelf(180f, bd, RATE);
                            lp[c] = Biquad.lowpass(dk, 0.7071f, RATE);
                        }
                    }
                    for (int i = 0; i < BLOCK; i++) {
                        buf[2 * i] = lp[0].process(shelf[0].process(buf[2 * i]));
                        buf[2 * i + 1] = lp[1].process(shelf[1].process(buf[2 * i + 1]));
                        int l = clip(buf[2 * i]), r = clip(buf[2 * i + 1]);
                        pcm[4 * i] = (byte) l; pcm[4 * i + 1] = (byte) (l >> 8);
                        pcm[4 * i + 2] = (byte) r; pcm[4 * i + 3] = (byte) (r >> 8);
                    }
                    line.write(pcm, 0, pcm.length);
                    sRpm = e.rpm(); sSpeed = speed; sLoad = e.load(); sGear = p.gearIndex; sPhase = e.phase().name();
                }
                if (!wantRun || !wantReset) {
                    break;
                }
            }
            line.drain();
        } catch (Throwable t) {
            System.out.println("audio: " + t);
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            sPhase = "off"; sRpm = 0; sSpeed = 0; sLoad = 0;
        }
    }

    static int clip(float x) {
        return Math.max(-32767, Math.min(32767, Math.round(x * 32767f)));
    }

    static Map<String, String> form(HttpExchange ex) throws java.io.IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[1024];
        for (int n; (n = in.read(t)) > 0; ) b.write(t, 0, n);
        Map<String, String> m = new HashMap<>();
        for (String kv : b.toString("UTF-8").split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws java.io.IOException {
        byte[] d = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, d.length);
        ex.getResponseBody().write(d);
        ex.close();
    }
}
