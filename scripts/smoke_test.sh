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
# launch: opens Eclipse like the icon does (tries again if Android
# does not start it: seen on Android 8 emulators right after a force-stop)
launch() {
  local try i
  for try in 1 2 3; do
    # after the first try, a fresh task (a "open with" chooser left in
    # Eclipse's task on Android 8 would stay in front otherwise)
    if [ $try = 1 ]; then adb shell am start -W -n "$ACT" > /dev/null 2>&1
    else adb shell am start -W --activity-clear-task -n "$ACT" > /dev/null 2>&1; fi
    for i in $(seq 1 8); do
      [ -n "$(pid_of $PKG)" ] && return 0
      sleep 1
    done
    adb shell input keyevent KEYCODE_HOME
    sleep 2
  done
  return 1
}
# clear_dialogs: closes "Eclipse has stopped" dialogs left by part 2's
# deliberate crashes (Android 8 shows them even with hide_error_dialogs)
clear_dialogs() {
  local i xy
  for i in 1 2 3 4 5 6; do
    dump dlg
    xy=$(python3 - "$OUT/dlg.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if (n.get("resource-id") or "") in ("android:id/aerr_close", "android:id/aerr_wait"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2); sys.exit(0)
sys.exit(1)
PY
) || return 0
    adb shell input tap $xy
    note "  (fenêtre d'erreur système fermée)"
    sleep 1
  done
}
# back to Eclipse's home screen, nothing open over it
home_screen() {
  adb shell am force-stop "$PKG"
  sleep 1
  launch
  sleep 3
  clear_dialogs
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
if [ $TUNNEL = 1 ] && [ -n "$(pid_of $PKG:tunnel)" ]; then
  note "  (moteur Tailscale démarré alors que rien ne le demande)"
fi

open_qr
if [ $TUNNEL = 1 ]; then
  # The PC is not on this Wi-Fi and the phone is not signed in to Tailscale:
  # the sign-in page opens by itself (it comes from Tailscale's servers: the
  # engine works), and Eclipse waits to try again through Tailscale.
  ok=0
  for i in $(seq 1 60); do
    sleep 1
    if grep -q "sign-in page ready: login.tailscale.com" "$OUT/logcat.txt" 2>/dev/null; then ok=1; break; fi
  done
  if [ $ok = 1 ]; then note "OK page de connexion Tailscale ouverte toute seule (après $i s)"
  else fail "la page de connexion Tailscale ne s'est pas ouverte"; fi
  sleep 3
  shot 3-signin
  if [ -n "$(pid_of $PKG:tunnel)" ]; then note "OK le moteur Tailscale tourne dans son propre processus"
  else fail "le processus du moteur Tailscale n'a pas démarré"; fi
  adb shell input keyevent KEYCODE_BACK   # the person leaves the browser (or the "open with" chooser)
  sleep 1
  launch   # back in Eclipse
  wait_alive 5 "retour dans Eclipse" && shot 4-back
  if screen_has 4-back "Une seule fois"; then note "OK Eclipse attend la connexion à Tailscale"
  else note "  (écran d'attente de connexion non vu)"; fi
else
  wait_text "Rescanner" 45
  wait_alive 3 "code QR" && shot 2-qr
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
  wait_alive 50 "pannes du moteur"
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
  if tap "Réglages" && wait_text "Jouer partout" 10 && tap "Jouer partout"; then
    if wait_text "ne fonctionne pas sur ce téléphone" 10; then note "OK Eclipse explique que le Tailscale intégré est arrêté"
    else fail "pas d'explication après les pannes"; fi
    shot 9-remote
  fi
  wait_alive 3 "fin"
  adb shell "rm -f $D/test-crash" > /dev/null 2>&1
  clear_dialogs
fi

# ---------------------------------------------------------------- part 3
# A PC reachable only through Tailscale (a private test network run on the
# machine hosting the emulator, see tunnel/emulator_pc_test.go): the app
# joins it, finds the PC and talks to it through its relay.
if [ $TUNNEL = 1 ] && [ -n "${FAKE_PC:-}" ] && [ -s "$FAKE_PC" ]; then
  note "== 3. Un PC joignable seulement par Tailscale (réseau de test)"
  P3_FROM=$(wc -l < "$OUT/logcat.txt")
  read -r CTRL PCIP < "$FAKE_PC"
  adb shell pm clear $PKG > /dev/null 2>&1
  CTX=$(adb shell stat -c %C /data/data/$PKG | tr -d '\r')
  OWN=$(adb shell stat -c %u:%g /data/data/$PKG | tr -d '\r')
  D=/data/data/$PKG/files/tailscale
  adb shell "mkdir -p $D && echo '$CTRL' > $D/test-control-url && chown -R $OWN /data/data/$PKG/files && chcon -R $CTX /data/data/$PKG/files; restorecon -R /data/data/$PKG/files"
  launch
  clear_dialogs
  wait_alive 4 "lancement"
  QR3="eclipse://pair?a=192.168.77.77&id=DE7509DC-6D80-5E2F-7700-BF75D0205FCB&n=Lenovo+500w+Gen+5&p=1234&port=47989&t=abcdefgh&tl=1&ts=$PCIP&tu=test%40example.com&v=1"
  adb shell "am start -W -a android.intent.action.VIEW -d '$QR3' $PKG" > /dev/null 2>&1
  ok=0
  for i in $(seq 1 90); do
    sleep 1
    if grep -q "PC: GET /pair" "${FAKE_PC_LOG:-/dev/null}" 2>/dev/null; then ok=1; break; fi
  done
  if [ $ok = 1 ]; then note "OK le téléphone a joint le PC par Tailscale et lui a demandé la connexion (après $i s)"
  else fail "le téléphone n'a pas joint le PC par Tailscale"; fi
  wait_alive 5 "connexion par Tailscale"
  clear_dialogs
  shot 10-tailnet-qr
  # The relay of this PC listens on 127.0.0.2:47989 (0200007F:BB75 in /proc).
  if adb shell cat /proc/net/tcp | grep -qi "0200007F:BB75 00000000:0000 0A"; then note "OK relais du PC sur 127.0.0.2"
  else fail "pas de relais sur 127.0.0.2"; fi
  # The test PC pairs for real (same protocol as Sunshine, PIN of the QR code).
  PAIRED=0
  for i in $(seq 1 60); do
    if grep -q 'paired$' "${FAKE_PC_LOG:-/dev/null}" 2>/dev/null; then PAIRED=1; break; fi
    sleep 1
  done
  if [ $PAIRED = 1 ]; then note "OK le téléphone est connecté au PC par Tailscale (après $i s)"
  else fail "le téléphone ne s'est pas connecté au PC par Tailscale"; fi
  home_screen
  if wait_text "Lenovo 500w Gen 5" 30; then note "OK le PC trouvé par Tailscale est dans la liste"
  else fail "le PC trouvé par Tailscale n'est pas dans la liste"; fi
  shot 11-tailnet-list
  tail -n +"$((P3_FROM + 1))" "$OUT/logcat.txt" > "$OUT/logcat-part3.txt"
  if eclipse_crashes "$OUT/logcat-part3.txt" > "$OUT/part3-crashes.txt"; then
    fail "plantage avec Tailscale : $(head -1 "$OUT/part3-crashes.txt")"
  else
    note "OK aucun plantage avec Tailscale"
  fi
fi


# ---------------------------------------------------------------- part 5
# File sharing with Eclipse for PC (the real one, running on the machine
# hosting the emulator, reached through the test PC on Tailscale like from
# away): the phone browses and downloads the PC's files; the PC sees the
# phone's files, sends one and downloads one.
if [ $TUNNEL = 1 ] && [ "${PAIRED:-0}" = 1 ] && [ -n "${PC_URL_FILE:-}" ] && [ -s "$PC_URL_FILE" ]; then
  note "== 5. Partage de fichiers"
  P5_FROM=$(wc -l < "$OUT/logcat.txt")
  PCURL=$(grep -o 'http://127.0.0.1:[0-9]*/?t=[0-9a-f]*' "$PC_URL_FILE" | head -1)
  PCBASE=${PCURL%%/?t=*}
  PCTOKEN=${PCURL##*t=}
  pcapi() { curl -s -m 90 -H "X-Token: $PCTOKEN" "$@"; }
  [ "$API" -lt 29 ] && adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE > /dev/null 2>&1
  home_screen
  if tap "=Fichiers" && wait_text "Dossier personnel" 60; then
    note "OK le téléphone voit les dossiers du PC"
    shot 14-files-roots
    if tap "=Documents" && wait_text "rapport-eclipse.txt" 40; then
      note "OK dossier du PC ouvert"
      tap "rapport-eclipse.txt" && wait_text "=Télécharger" 10 && tap "=Télécharger"
      ok=0
      for i in $(seq 1 40); do
        sleep 1
        if adb shell cat /sdcard/Download/Eclipse/rapport-eclipse.txt 2>/dev/null | grep -q "Rapport de test Eclipse"; then ok=1; break; fi
      done
      shot 15-files-download
      if [ $ok = 1 ]; then note "OK fichier du PC téléchargé sur le téléphone (après $i s)"
      else fail "le fichier du PC n'a pas été téléchargé sur le téléphone"; fi
    else
      fail "le dossier Documents du PC ne s'ouvre pas"; shot 15-files-download
    fi
  else
    fail "le téléphone ne voit pas les fichiers du PC"; shot 14-files-roots
  fi

  # This phone's files, seen from the PC.
  if [ "$API" -ge 30 ]; then
    adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow > /dev/null 2>&1
  else
    adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE > /dev/null 2>&1
    adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE > /dev/null 2>&1
  fi
  [ "$API" -ge 33 ] && adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS > /dev/null 2>&1
  adb shell "mkdir -p /sdcard/Download && echo 'Photo du telephone' > /sdcard/Download/eclipse-phone.txt"
  tap "Partager les fichiers" > /dev/null
  PHID=""
  for i in $(seq 1 90); do
    sleep 1
    PHID=$(pcapi "$PCBASE/api/x/devices" | python3 -c 'import json,sys
d=[x for x in json.load(sys.stdin).get("devices",[]) if x.get("online")]
print(d[0]["id"] if d else "")' 2>/dev/null)
    [ -n "$PHID" ] && break
  done
  shot 16-files-share
  if [ -n "$PHID" ]; then
    note "OK le PC voit le téléphone (après $i s)"
    if pcapi "$PCBASE/api/x/$PHID/list?path=/storage/emulated/0/Download" | grep -q eclipse-phone.txt; then
      note "OK le PC liste les fichiers du téléphone"
    else
      fail "le PC ne liste pas les fichiers du téléphone"
    fi
    echo "Envoye depuis le PC" > "$OUT/depuis-le-pc.txt"
    pcapi -X POST --data-binary @"$OUT/depuis-le-pc.txt" "$PCBASE/api/x/$PHID/put?dir=/storage/emulated/0/Download&name=depuis-le-pc.txt" > "$OUT/put.json"
    if adb shell cat /sdcard/Download/depuis-le-pc.txt 2>/dev/null | grep -q "Envoye depuis le PC"; then
      note "OK fichier envoyé du PC au téléphone"
    else
      fail "le fichier envoyé par le PC n'est pas sur le téléphone ($(head -c 200 "$OUT/put.json"))"
    fi
    pcapi -X POST -H "Content-Type: application/json" -d '{"path":"/storage/emulated/0/Download/eclipse-phone.txt"}' "$PCBASE/api/x/$PHID/fetch" > "$OUT/fetch.json"
    if grep -q "Photo du telephone" "${PC_DOWNLOADS:-/nonexistent}/eclipse-phone.txt" 2>/dev/null; then
      note "OK fichier du téléphone téléchargé sur le PC"
    else
      fail "le fichier du téléphone n'a pas été téléchargé sur le PC ($(head -c 200 "$OUT/fetch.json"))"
    fi
    if adb shell dumpsys notification 2>/dev/null | grep -q "Fichiers partagés"; then note "OK notification du partage affichée"
    else note "  (notification du partage non vue)"; fi
  else
    fail "le PC ne voit pas le téléphone"
    adb shell dumpsys activity services $PKG > "$OUT/services.txt" 2>&1
  fi
  wait_alive 3 "partage de fichiers"
  tail -n +"$((P5_FROM + 1))" "$OUT/logcat.txt" > "$OUT/logcat-part5.txt"
  if eclipse_crashes "$OUT/logcat-part5.txt" > "$OUT/part5-crashes.txt"; then
    fail "plantage pendant le partage de fichiers : $(head -1 "$OUT/part5-crashes.txt")"
  fi
  # Sharing off again for the next part.
  adb shell am stopservice -n $PKG/com.limelight.EclipseShare > /dev/null 2>&1
fi

# ---------------------------------------------------------------- part 4
# Eclipse updates itself when it opens: a "newer version" published on a
# local test server (same APK) is downloaded, checked and handed to
# Android's installer; the update is confirmed like a person would.
note "== 4. Mise à jour automatique de l'appli"
P4_FROM=$(wc -l < "$OUT/logcat.txt")
SRV=$(mktemp -d)
cp "$APK" "$SRV/Eclipse.apk"
SUM=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
printf '{"tag_name":"v99.0.0","assets":[{"name":"Eclipse.apk","size":%s,"browser_download_url":"http://10.0.2.2:48300/Eclipse.apk","digest":"sha256:%s"}]}' "$SIZE" "$SUM" > "$SRV/latest"
(cd "$SRV" && python3 -m http.server 48300 > /dev/null 2>&1) &
HTTPD=$!
sleep 1
BEFORE=$(adb shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d '\r')
launch; sleep 2
CTX=$(adb shell stat -c %C /data/data/$PKG | tr -d '\r')
OWN=$(adb shell stat -c %u:%g /data/data/$PKG | tr -d '\r')
adb shell "mkdir -p /data/data/$PKG/files && echo 'http://10.0.2.2:48300/latest' > /data/data/$PKG/files/test-update-url && chown -R $OWN /data/data/$PKG/files && chcon -R $CTX /data/data/$PKG/files; restorecon -R /data/data/$PKG/files"
# The permission a person gives once ("install unknown apps": Eclipse).
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow > /dev/null 2>&1
adb shell am force-stop "$PKG"
sleep 1
clear_dialogs
launch
ok=0
for i in $(seq 1 90); do
  sleep 1
  if grep -q "update handed to the installer" "$OUT/logcat.txt"; then ok=1; break; fi
done
if [ $ok = 1 ]; then note "OK nouvelle version téléchargée, vérifiée et confiée à l'installateur (après $i s)"
else fail "la mise à jour n'a pas été téléchargée"; fi
sleep 3
clear_dialogs
shot 12-update-confirm
for b in "=Update" "=Install" "=Mettre à jour" "=Installer"; do tap "$b" > /dev/null 2>&1 && break; done
ok=0
for i in $(seq 1 60); do
  sleep 1
  AFTER=$(adb shell dumpsys package $PKG | grep -m1 lastUpdateTime | tr -d '\r')
  if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then ok=1; break; fi
done
if [ $ok = 1 ]; then note "OK l'appli s'est mise à jour ($AFTER)"
else fail "l'appli ne s'est pas mise à jour ($BEFORE)"; fi
kill $HTTPD 2>/dev/null
adb shell "rm -f /data/data/$PKG/files/test-update-url"
sleep 15   # Android finishes the update (it may restart the app once)
launch
wait_alive 10 "après la mise à jour" && shot 13-after-update
tail -n +"$((P4_FROM + 1))" "$OUT/logcat.txt" > "$OUT/logcat-part4.txt"
if eclipse_crashes "$OUT/logcat-part4.txt" > "$OUT/part4-crashes.txt"; then
  fail "plantage pendant la mise à jour : $(head -1 "$OUT/part4-crashes.txt")"
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
