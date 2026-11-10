package com.zeekr.sentrycam.sound;

import android.content.Context;
import android.content.SharedPreferences;

import com.zeekr.sentrycam.Logger;

/** Glue between the ported engine-sound code (from zeekr-shortcut-car) and this app: settings, log. */
final class Shim {
    static volatile Context app;
    private Shim() {}
    static void log(String m) { Context c = app; if (c != null) Logger.INSTANCE.log(c, "enginesound: " + m); }
}

/** Replaces zeekr-shortcut-car's AppConfig for the engine sound: reads this app's "cfg" prefs. */
final class AppConfig {
    static final String DRIVE_SOUND_RPM_CONTINUOUS = "continuous";
    static final String DRIVE_SOUND_RPM_SHIFTS = "shifts";
    static final String DRIVE_SOUND_GEARBOX_AUTO8 = "auto8";
    static final String DRIVE_SOUND_GEARBOX_MANUAL6 = "manual6";
    static final String DRIVE_SOUND_STYLE_AMERICAN = "american";
    static final String DRIVE_SOUND_STYLE_PROTOTYPE = "prototype";
    static final String DRIVE_SOUND_STYLE_JET = "jet";
    static final String DRIVE_SOUND_STYLE_JET_OLD = "jet_old";
    static final String DRIVE_SOUND_STYLE_TURBO4 = "turbo4";
    static final String DRIVE_SOUND_STYLE_ELECTRIC = "electric";
    static final String DRIVE_SOUND_STYLE_TYPE_R = "typer";
    static final String DRIVE_SOUND_VIEW_OUTSIDE = "outside";
    static final String DRIVE_SOUND_VIEW_CABIN = "cabin";
    static final String DRIVE_SOUND_OUTPUT_CABIN = "cabin";
    static final String DRIVE_SOUND_OUTPUT_OUTSIDE = "outside";
    static final String DRIVE_SOUND_OUTPUT_BOTH = "both";
    static final String DRIVE_SOUND_ROUTE_NOTIFY = "bus12";
    static final String DRIVE_SOUND_ROUTE_PLAYBACK = "bus11";

    private final SharedPreferences p;
    AppConfig(Context c) { p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    boolean isDriveSoundEnabled() { return p.getBoolean("engOn", false); }
    String getDriveSoundVolume() { return String.valueOf(p.getInt("engVol", 0)); }
    String getDriveSoundRpm() { return p.getString("engRpm", DRIVE_SOUND_RPM_CONTINUOUS); }
    String getDriveSoundGearbox() { return p.getString("engGearbox", DRIVE_SOUND_GEARBOX_AUTO8); }
    String getDriveSoundStyle() { return p.getString("engStyle", DRIVE_SOUND_STYLE_AMERICAN); }
    String getDriveSoundView() { return p.getString("engView", DRIVE_SOUND_VIEW_CABIN); }
    String getDriveSoundOutput() { return p.getString("engOut", DRIVE_SOUND_OUTPUT_CABIN); }
    String getDriveSoundExteriorRoute() { return p.getString("engRoute", DRIVE_SOUND_ROUTE_NOTIFY); }
    boolean isMuted() { return p.getBoolean("pfMute", false); }
    boolean isRevBlip() { return !"off".equals(p.getString("engBlip", "on")); }
    float getCrackleScale() { String v = p.getString("engCrackle", "normal"); return "off".equals(v) ? 0f : "low".equals(v) ? 0.4f : 1f; }
    String getDriveSoundExteriorVolume() { return String.valueOf(p.getInt("engExtVol", 18)); }
}

final class AppLog {
    private AppLog() {}
    static void i(String tag, String m) { Shim.log(tag + ": " + m); }
    static void w(String tag, String m) { Shim.log(tag + ": " + m); }
    static void e(String tag, String m) { Shim.log(tag + ": " + m); }
    static void e(String tag, String m, Throwable t) { Shim.log(tag + ": " + m + " " + t); }
}

final class BlackBox {
    private BlackBox() {}
    static void note(String m) { Shim.log(m); }
}
