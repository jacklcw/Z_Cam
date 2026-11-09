# Engine sound (GPL-3.0)

The files in this folder are ported from **dts88/zeekr-shortcut-car** (https://github.com/dts88/zeekr-shortcut-car,
GPL-3.0), package `com.kooo.evcam.sound` (simulated engine sound, which itself builds on FerixzTanz's and other work credited there).

Changes made here: package renamed, the car-signal layer replaced by `Telemetry.java` (polling the ECARX car API), settings read from
this app's prefs (`Shim.java`), and the engine sound plays in every drive mode instead of only Sport.

Because these files are GPL-3.0, the built Z_Cam app that includes them is distributed under GPL-3.0 (see `LICENSE-GPL-3.0.txt`).
`Shim.java` and `Telemetry.java` are this project's own glue code and are offered under the same terms.

Facts only, no code or recordings used: the upstream authors' notes credit **CarPiano** (https://github.com/jlunnn/CarPiano, MIT) for
how to play to the car's speakers, and **EngineV1** (https://evenginev1.com) as the spectral reference for the V8 voice.

## Additional terms from upstream (GPL-3.0 section 7)

dts88's code carries two extra terms (see upstream NOTICE.md, section 8):

1. Attribution: keep the "Based on Zeekr Shortcut by dts88 (https://github.com/dts88/zeekr-shortcut-car)" line visible in the app's
   credits (Z_Cam shows it at the bottom of the main screen), and keep the copyright header at the top of each ported source file.
2. Modified versions must not pass as the original or as coming from dts88. Z_Cam is a modified, separate app and is not affiliated with Zeekr Shortcut.
