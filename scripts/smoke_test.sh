#!/usr/bin/env bash
# Launches Eclipse on an Android emulator like a person would, and records
# everything: screenshots, screen contents, logs, crash reports.
#   smoke_test.sh <apk> <output dir> [abi to install, e.g. arm64-v8a]
# Ends with "SMOKE OK" or "SMOKE FAIL: ..." in <output dir>/result.txt
# (exit code 1 on failure).
set -u
APK=$1
OUT=$2
ABI=${3:-}
PKG=com.eclipse.stream
ACT=$PKG/com.limelight.EclipseActivity
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
RES="$OUT/result.txt"
: > "$RES"
FAIL=""

note() { echo "$*" | tee -a "$RES"; }
fail() { FAIL="$FAIL; $*"; note "FAIL $*"; }
alive() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r'; }
shot() {
  adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1
}
# tap <text>: taps the first thing on screen showing that text
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/tap.xml" > /dev/null 2>&1
  local xy
  xy=$(python3 "$HERE/ui_find.py" "$OUT/tap.xml" "$1") || { note "  (pas trouvé à l'écran : $1)"; return 1; }
  adb shell input tap $xy
  note "  tap « $1 » ($xy)"
}
wait_alive() { # seconds, label
  local i
  for i in $(seq 1 "$1"); do
    sleep 1
    if [ -z "$(alive)" ]; then fail "l'appli s'est fermée ($2, après ${i} s)"; return 1; fi
  done
  note "OK toujours ouverte après $1 s ($2)"
}

adb wait-for-device
note "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')), ABI $(adb shell getprop ro.product.cpu.abilist | tr -d '\r'), page $(adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
adb root > /dev/null 2>&1; sleep 2; adb wait-for-device
adb shell settings put global package_verifier_enable 0 > /dev/null 2>&1

if [ -n "$ABI" ]; then
  adb install -r --abi "$ABI" "$APK" > "$OUT/install.txt" 2>&1
else
  adb install -r "$APK" > "$OUT/install.txt" 2>&1
fi
if ! grep -q Success "$OUT/install.txt"; then
  fail "installation : $(tail -1 "$OUT/install.txt")"
  echo "SMOKE FAIL: $FAIL" >> "$RES"; exit 1
fi
note "installée ($(adb shell dumpsys package $PKG | grep -m1 -o 'primaryCpuAbi=[^ ]*' | tr -d '\r'))"
adb logcat -c; adb logcat -b crash -c 2>/dev/null

# 1. First launch, like tapping the icon
adb shell am start -W -n "$ACT" > /dev/null
wait_alive 25 "premier lancement" && shot 1-launch

# 2. A QR code with a Tailscale address (the PC is not reachable here)
adb shell "am start -W -a android.intent.action.VIEW -d 'eclipse://pair?a=10.0.2.2&id=SMOKE-TEST&n=PC+de+test&p=1234&port=47989&t=abcdefgh&ts=100.101.102.103&tu=test%40example.com&v=1' $PKG" > /dev/null
wait_alive 45 "code QR avec Tailscale" && shot 2-qr

# 3. "Connexion Tailscale" (built-in Tailscale: start + sign-in page)
if tap "Connexion Tailscale"; then
  wait_alive 30 "connexion Tailscale" && shot 3-tunnel
  adb shell am start -W -n "$ACT" > /dev/null   # back from the browser
  wait_alive 10 "retour dans Eclipse" && shot 4-back
fi

# 4. Closed and opened again (Tailscale already set up)
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$ACT" > /dev/null
wait_alive 30 "relance" && shot 5-relaunch

# 5. Background / foreground
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell am start -W -n "$ACT" > /dev/null
wait_alive 15 "retour au premier plan" && shot 6-resume

adb logcat -d > "$OUT/logcat.txt" 2>&1
adb logcat -b crash -d > "$OUT/crash.txt" 2>&1
adb shell "ls /data/tombstones" > "$OUT/tombstones.txt" 2>&1
for t in $(adb shell "ls /data/tombstones" 2>/dev/null | tr -d '\r' | grep -v '\.pb$'); do
  adb shell "cat /data/tombstones/$t" > "$OUT/tombstone-$t.txt" 2>/dev/null
done
grep -E "eclipsenet|GoLog|Eclipse:" "$OUT/logcat.txt" > "$OUT/eclipse-log.txt" 2>/dev/null
if grep -qE "FATAL EXCEPTION|Fatal signal|ANR in $PKG" "$OUT/logcat.txt" "$OUT/crash.txt" 2>/dev/null; then
  fail "plantage dans le journal : $(grep -m1 -E 'FATAL EXCEPTION|Fatal signal|ANR in' "$OUT/logcat.txt" "$OUT/crash.txt")"
fi

if [ -z "$FAIL" ]; then
  echo "SMOKE OK" >> "$RES"
else
  echo "SMOKE FAIL: ${FAIL#; }" >> "$RES"
  exit 1
fi
