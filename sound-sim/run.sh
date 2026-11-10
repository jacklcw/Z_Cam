#!/bin/bash
# Starts the desktop test ground. Silent until you press Start in the page.
cd "$(dirname "$0")"
D=../app/src/main/java/com/zeekr/sentrycam/sound
OUT="${TMPDIR:-/tmp}/zcam-sim-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" stub/AppConfig.java SimServer.java $D/{Biquad,Limiter,VirtualEngine,EngineVoice,SoundProfile,Gearbox,Voice,VoiceParams,PulseVoice,PulseProfile,CivicTypeRVoice,JetVoice,JetOldVoice,TurboFourVoice,ElectricVoice,SoundStyle}.java || exit 1
echo "Open http://127.0.0.1:8765/"
exec java -cp "$OUT" com.zeekr.sentrycam.sound.SimServer car-sim.html
