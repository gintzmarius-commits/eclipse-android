#!/usr/bin/env bash
# Uses Eclipse on an Android emulator like a person would, and records
# everything: screenshots, screen contents, logs, crash reports.
#   smoke_test.sh <apk> <output dir> [abi to install, e.g. arm64-v8a]
# Ends with "SMOKE OK" or "SMOKE FAIL: ..." in <output dir>/result.txt
# (exit code 1 on failure).
#
# Part 1, normal use: first launch, a QR code with a Tailscale address
# (the PC is not reachable), sign-in to the built-in Tailscale (its sign-in
# page must come from Tailscale's servers), relaunch, background, settings,
# technical report. Nothing may crash.
# Part 2 (when the APK has the built-in Tailscale for this ABI): the network
# engine is made to fail on purpose. Eclipse must stay open, switch the
# built-in Tailscale off after repeated failures, and show the failure in
# its technical report.
set -u
APK=$1
OUT=$2
ABI=${3:-}
PKG=com.eclipse.stream
ACT=$PKG/com.limelight.EclipseActivity
QR="eclipse://pair?a=10.0.2.2&id=SMOKE-TEST&n=PC+de+test&p=1234&port=47989&t=abcdefgh&ts=100.101.102.103&tu=test%40example.com&v=1"
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
RES="$OUT/result.txt"
: > "$RES"
FAIL=""

note() { echo "$*" | tee -a "$RES"; }
fail() { FAIL="$FAIL; $*"; note "FAIL $*"; }
pid_of() { adb shell pidof "$1" 2>/dev/null | tr -d '\r'; }
alive() { # pid of Eclipse's main process (asks twice: adb can hiccup)
  local p
  p=$(pid_of "$PKG")
  [ -z "$p" ] && { sleep 1; p=$(pid_of "$PKG"); }
  echo "$p"
}
dump() { # screen contents -> $OUT/$1.xml
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1
}
shot() {
  adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null
  dump "$1"
}
# tap <text>: taps the first thing on screen showing that text
tap() {
  dump tap
  local xy
  xy=$(python3 "$HERE/ui_find.py" "$OUT/tap.xml" "$1") || { note "  (pas trouvé à l'écran : $1)"; return 1; }
  adb shell input tap $xy
  note "  tap « $1 »"
}
# wait_text <text> <seconds>: until that text is on screen
wait_text() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    dump wait
    python3 "$HERE/ui_find.py" "$OUT/wait.xml" "$1" > /dev/null && return 0
    sleep 1
  done
  return 1
}
screen_has() { python3 "$HERE/ui_find.py" "$OUT/$1.xml" "$2" > /dev/null; }
wait_alive() { # seconds, label
  local i
  for i in $(seq 1 "$1"); do
    sleep 1
    if [ -z "$(alive)" ]; then fail "l'appli s'est fermée ($2, après ${i} s)"; return 1; fi
  done
  note "OK toujours ouverte après $1 s ($2)"
}
launch() { adb shell am start -W -n "$ACT" > /dev/null 2>&1; }
# back to Eclipse's home screen (closes sheets; reopens Eclipse if needed)
home_screen() {
  local i
  for i in 1 2 3; do
    dump home
    screen_has home "Réglages" && ! screen_has home "Fermer" && ! screen_has home "Rescanner" && return 0
    [ -z "$(alive)" ] && { launch; sleep 3; continue; }
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  done
  [ -z "$(alive)" ] && launch
  sleep 2
}
# crashes of Eclipse's own processes in a log file
eclipse_crashes() {
  grep -E "ANR in $PKG|pid [0-9]+ \((\.eclipse\.stream|[a-z.]*stream:tunnel)\)|GoLog.*(panic:|fatal error:)|Process: $PKG" "$@" 2>/dev/null | grep -v "test crash"
}
open_qr() { adb shell "am start -W -a android.intent.action.VIEW -d '$QR' $PKG" > /dev/null 2>&1; }

adb wait-for-device
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
note "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $API), ABI $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb shell settings put global package_verifier_enable 0 > /dev/null 2>&1
# System "isn't responding" dialogs of other apps would cover the screen.
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1
# A freshly started emulator is very busy for a while: let it settle.
for i in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { sleep 2; continue; }
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1 | cut -d. -f1)
  [ "${load:-99}" -lt 4 ] && break
  sleep 2
done
note "système prêt (charge $(adb shell cat /proc/loadavg | cut -d' ' -f1))"
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
adb shell wm dismiss-keyguard > /dev/null 2>&1

if [ -n "$ABI" ]; then
  adb install -r --abi "$ABI" "$APK" > "$OUT/install.txt" 2>&1
else
  adb install -r "$APK" > "$OUT/install.txt" 2>&1
fi
if ! grep -q Success "$OUT/install.txt"; then
  fail "installation : $(tail -1 "$OUT/install.txt")"
  echo "SMOKE FAIL: ${FAIL#; }" >> "$RES"; exit 1
fi
CPU=$(adb shell dumpsys package $PKG | grep -m1 -o 'primaryCpuAbi=[^ ]*' | tr -d '\r' | cut -d= -f2)
TUNNEL=0
unzip -l "$APK" "lib/$CPU/libgojni.so" > /dev/null 2>&1 && TUNNEL=1
note "installée ($CPU, Tailscale intégré : $([ $TUNNEL = 1 ] && echo oui || echo non))"
adb logcat -G 16M > /dev/null 2>&1
adb logcat -c; adb logcat -b crash -c 2>/dev/null
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

# ---------------------------------------------------------------- part 1
note "== 1. Utilisation normale"
launch
wait_alive 20 "premier lancement" && shot 1-launch
if [ $TUNNEL = 1 ]; then
  if [ -n "$(pid_of $PKG:tunnel)" ]; then note "OK le moteur Tailscale tourne dans son propre processus"
  else fail "le processus du moteur Tailscale n'a pas démarré"; fi
fi

open_qr
if [ $TUNNEL = 1 ]; then
  if wait_text "Connexion Tailscale" 45; then note "OK QR code : propose la connexion Tailscale"
  else fail "QR code : pas de bouton « Connexion Tailscale »"; fi
else
  wait_text "Rescanner" 45
fi
wait_alive 3 "code QR avec Tailscale" && shot 2-qr

if [ $TUNNEL = 1 ] && tap "Connexion Tailscale"; then
  # The sign-in page comes from Tailscale's servers: the engine works.
  ok=0
  for i in $(seq 1 40); do
    sleep 1
    if grep -q "login.tailscale.com" "$OUT/logcat.txt" 2>/dev/null; then ok=1; break; fi
  done
  if [ $ok = 1 ]; then note "OK page de connexion Tailscale ouverte (après $i s)"
  else fail "la page de connexion Tailscale ne s'est pas ouverte"; fi
  sleep 3
  shot 3-signin
  launch   # back from the browser
  wait_alive 8 "retour dans Eclipse" && shot 4-back
fi

adb shell am force-stop "$PKG"
sleep 2
launch
wait_alive 20 "relance" && shot 5-relaunch

adb shell input keyevent KEYCODE_HOME
sleep 4
launch
wait_alive 8 "retour au premier plan"

home_screen
if tap "Réglages" && wait_text "Rapport technique" 10 && tap "Rapport technique"; then
  if wait_text "Tailscale intégré" 20; then
    shot 6-report
    note "OK rapport technique affiché"
  else
    fail "rapport technique vide"
  fi
fi
home_screen
wait_alive 3 "réglages"

PART1_LINES=$(wc -l < "$OUT/logcat.txt")
adb logcat -b crash -d > "$OUT/crash-part1.txt" 2>&1
head -n "$PART1_LINES" "$OUT/logcat.txt" > "$OUT/logcat-part1.txt"
if eclipse_crashes "$OUT/logcat-part1.txt" "$OUT/crash-part1.txt" > "$OUT/part1-crashes.txt"; then
  fail "plantage pendant l'utilisation normale : $(head -1 "$OUT/part1-crashes.txt")"
else
  note "OK aucun plantage pendant l'utilisation normale"
fi

# ---------------------------------------------------------------- part 2
if [ $TUNNEL = 1 ]; then
  note "== 2. Panne volontaire du moteur Tailscale"
  D=/data/data/$PKG/files/tailscale
  adb shell "touch $D/test-crash && chown \$(stat -c %u:%g $D) $D/test-crash && chmod 600 $D/test-crash; restorecon $D/test-crash" > /dev/null 2>&1
  adb shell am force-stop "$PKG"
  sleep 2
  launch
  wait_alive 3 "lancement"
  open_qr
  wait_text "Connexion Tailscale" 45 && tap "Connexion Tailscale"
  wait_alive 45 "pannes du moteur"
  DEATHS=$(grep -c "Process $PKG:tunnel (pid [0-9]*) has died\|$PKG:tunnel.*exited\|Process $PKG:tunnel .*died" "$OUT/logcat.txt")
  note "le moteur s'est arrêté $DEATHS fois"
  [ "$DEATHS" -ge 1 ] || fail "la panne volontaire n'a pas eu lieu"
  shot 7-after-crashes
  home_screen
  if tap "Réglages" && wait_text "Rapport technique" 10 && tap "Rapport technique"; then
    if wait_text "test crash" 20; then note "OK la panne est dans le rapport technique"
    else fail "la panne n'apparaît pas dans le rapport technique"; fi
    shot 8-report
  fi
  home_screen
  if tap "Réglages" && wait_text "Jouer hors de chez toi" 10 && tap "Jouer hors de chez toi"; then
    if wait_text "ne fonctionne pas sur ce téléphone" 10; then note "OK Eclipse explique que le Tailscale intégré est arrêté"
    else fail "pas d'explication après les pannes"; fi
    shot 9-remote
  fi
  wait_alive 3 "fin"
  adb shell "rm -f $D/test-crash" > /dev/null 2>&1
fi

sleep 2
kill $LOGCAT 2>/dev/null
adb logcat -b crash -d > "$OUT/crash.txt" 2>&1
adb shell "ls /data/tombstones" > "$OUT/tombstones.txt" 2>&1
adb pull /data/tombstones "$OUT/tombstones" > /dev/null 2>&1
[ "$API" -ge 30 ] && adb shell dumpsys activity exit-info $PKG > "$OUT/exit-info.txt" 2>&1
grep -E "eclipsenet|GoLog|Go(Stdout|Stderr)|Eclipse:|$PKG:tunnel" "$OUT/logcat.txt" > "$OUT/eclipse-log.txt" 2>/dev/null

if [ -z "$FAIL" ]; then
  echo "SMOKE OK" >> "$RES"
else
  echo "SMOKE FAIL: ${FAIL#; }" >> "$RES"
  exit 1
fi
