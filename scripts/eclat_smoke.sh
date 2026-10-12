#!/usr/bin/env bash
# Uses Éclat on an Android emulator like a person would:
#  1. first launch (photos screen), with a test photo and a test video;
#  2. RECEIVING a file sent "by NFC": the computer plays the sending phone
#     (tests/ShareHost.java, the same server code as on the phone) and
#     Android opens Éclat's island the way an NFC tag read does; the test
#     taps "Accepter" and checks that the photo arrived;
#  3. the VLC player opens a video and plays it.
# Ends with "SMOKE OK" or "SMOKE FAIL: ..." in <out>/result.txt.
#   eclat_smoke.sh <apk> <out dir> <dir with ShareTest/ShareHost classes>
set -u
APK=$1
OUT=$2
CLASSES=$3
PKG=com.eclipse.eclat
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
RES="$OUT/result.txt"
: > "$RES"
FAIL=""

note() { echo "$*" | tee -a "$RES"; }
fail() { FAIL="$FAIL; $*"; note "FAIL $*"; }
pid_of() { # asks twice: adb can hiccup
  local p
  p=$(adb shell pidof "$1" 2>/dev/null | tr -d '\r')
  [ -z "$p" ] && { sleep 1; p=$(adb shell pidof "$1" 2>/dev/null | tr -d '\r'); }
  echo "$p"
}
dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; dump "$1"; }
# A busy emulator shows "X isn't responding" for system apps: answer Wait.
clear_anr() {
  local f=$1 xy
  grep -qE "isn.t responding|ne répond pas" "$f" 2>/dev/null || return 1
  xy=$(python3 "$HERE/ui_find.py" "$f" "Wait" exact) || xy=$(python3 "$HERE/ui_find.py" "$f" "Attendre" exact) || return 1
  adb shell input tap $xy
  note "  (fenêtre « ne répond pas » du système fermée)"
  sleep 2
  adb shell am start -n $PKG/.MainActivity > /dev/null 2>&1
  sleep 2
  return 0
}
wait_text() { # <text> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    dump wait
    clear_anr "$OUT/wait.xml" && continue
    python3 "$HERE/ui_find.py" "$OUT/wait.xml" "$1" > /dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
tap() {
  local xy
  dump tap
  clear_anr "$OUT/tap.xml" && { sleep 2; dump tap; }
  xy=$(python3 "$HERE/ui_find.py" "$OUT/tap.xml" "$1" exact) || { note "  (pas trouvé à l'écran : $1)"; return 1; }
  adb shell input tap $xy
  note "  tap « $1 »"
}
crashes() { grep -E "FATAL EXCEPTION|Process: $PKG|ANR in $PKG|Fatal signal" "$OUT/logcat.txt" 2>/dev/null | grep -B2 -A12 -E "$PKG|libvlc" | head -60; }

adb wait-for-device
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
note "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $API)"
for i in $(seq 1 90); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { sleep 2; continue; }
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1 | cut -d. -f1)
  [ "${load:-99}" -lt 4 ] && break
  sleep 2
done
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1
adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
adb shell wm dismiss-keyguard > /dev/null 2>&1
adb logcat -c

adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
if ! grep -q Success "$OUT/install.txt"; then
  fail "installation : $(tail -1 "$OUT/install.txt")"
  echo "SMOKE FAIL: ${FAIL#; }" >> "$RES"; exit 1
fi
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow > /dev/null 2>&1

# test media
python3 - "$OUT/test.jpg" <<'PY'
import sys, zlib, struct
# a tiny valid JPEG is complex to write by hand: use PIL if present, else a PNG renamed is not OK -> fall back to ffmpeg below
try:
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (640, 480), (246, 165, 26)); d = ImageDraw.Draw(im)
    d.ellipse([200, 120, 440, 360], fill=(26, 24, 46)); im.save(sys.argv[1], quality=90)
except Exception:
    sys.exit(1)
PY
[ -s "$OUT/test.jpg" ] || ffmpeg -loglevel error -y -f lavfi -i "color=c=orange:s=640x480" -frames:v 1 "$OUT/test.jpg"
ffmpeg -loglevel error -y -f lavfi -i testsrc=duration=6:size=640x360:rate=25 -f lavfi -i sine=frequency=440:duration=6 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$OUT/test.mp4" 2>/dev/null || note "  (pas de ffmpeg : pas de vidéo de test)"
adb shell mkdir -p /sdcard/DCIM/Camera /sdcard/Movies
adb push "$OUT/test.jpg" /sdcard/DCIM/Camera/IMG_TEST.jpg > /dev/null
[ -s "$OUT/test.mp4" ] && adb push "$OUT/test.mp4" /sdcard/Movies/VID_TEST.mp4 > /dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/Camera/IMG_TEST.jpg > /dev/null 2>&1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/VID_TEST.mp4 > /dev/null 2>&1
adb shell content call --uri content://media/external/file --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 3

# 1) first launch
adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1
if wait_text "Photos" 25; then note "OK écran Photos"; else fail "l'écran Photos ne s'affiche pas"; fi
sleep 3
shot 1-photos
[ -n "$(pid_of $PKG)" ] && note "OK Éclat ouvert" || fail "Éclat s'est fermé au lancement"
tap "Vidéos" && sleep 3 && shot 2-videos
if [ -z "$(pid_of $PKG)" ]; then
  SYS=$(adb logcat -d 2>/dev/null | grep -iE "Fatal signal|renderer gone|Render process|Killing [0-9]+:$PKG|Process $PKG .*(died|has died)|Abort message" | tail -8 | cut -c1-230)
  adb shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1; sleep 5
  if echo "$SYS" | grep -q "in dying proc com.google.android.gms"; then
    # Android closes every app using Google Play services' font service
    # when Play services restarts (common on a freshly started emulator).
    note "  (Éclat fermé par Android : les services Google Play ont redémarré ; on relance)"
    tap "Vidéos" && sleep 3 && shot 2-videos
    [ -n "$(pid_of $PKG)" ] || fail "Éclat s'est fermé sur l'onglet Vidéos (deux fois)"
  else
    fail "Éclat s'est fermé sur l'onglet Vidéos"
    note "  système : $(echo "$SYS" | tr '\n' '|')"
  fi
fi
tap "Musique" && sleep 2 && shot 3-music
tap "Fichiers" && sleep 2 && shot 4-files
if wait_text "Download" 8 || wait_text "DCIM" 4; then note "OK dossiers affichés"; else
  fail "les dossiers ne s'affichent pas"
  note "  à l'écran : $(grep -o 'text="[^"]*"' "$OUT/wait.xml" | head -40 | tr '\n' ' ')"
  note "  erreurs de la page : $(adb logcat -d -s EclatJS:W | tail -5 | tr '\n' ' ')"
fi

# 2) receiving a file "by NFC"
java -cp "$CLASSES" ShareHost "$OUT/test.jpg" image/jpeg 120 10.0.2.2 > "$OUT/sender.txt" 2>&1 &
SENDER=$!
for i in $(seq 1 20); do [ -s "$OUT/sender.txt" ] && break; sleep 0.5; done
LINK=$(head -1 "$OUT/sender.txt")
note "lien NFC simulé : $LINK"
adb shell input keyevent KEYCODE_HOME
sleep 1
nfc_read() { adb shell "am start -W -n $PKG/.share.ReceiveActivity -a android.nfc.action.NDEF_DISCOVERED -d '$LINK'" > /dev/null 2>&1; }
nfc_read
# a busy emulator can miss the first one; a phone held against the other reads the tag again too
if ! wait_text "Accepter" 20; then shot 5-retry; note "  (île pas vue en 20 s : deuxième lecture)"; nfc_read; fi
if wait_text "Accepter" 25; then
  note "OK l'île propose le fichier"
  sleep 1; shot 5-island
  grep -q "ASKED" "$OUT/sender.txt" && note "OK l'envoyeur voit la demande ($(grep ASKED "$OUT/sender.txt" | head -1))" || fail "l'envoyeur n'a pas reçu la demande"
  tap "Accepter"
  if wait_text "Reçu" 30; then note "OK fichier reçu (île)"; shot 6-received; else shot 6-not-received; fail "l'île n'affiche pas « Reçu »"; fi
  sleep 1
  grep -q "^SENT" "$OUT/sender.txt" && note "OK l'envoyeur sait que c'est envoyé" || fail "l'envoyeur ne voit pas l'envoi terminé"
  if adb shell ls "/sdcard/Pictures/Éclat/" 2>/dev/null | tr -d '\r' | grep -q "Photo de test"; then
    note "OK photo enregistrée dans Pictures/Éclat"
    adb pull "/sdcard/Pictures/Éclat/Photo de test é.jpg" "$OUT/received.jpg" > /dev/null 2>&1
    cmp -s "$OUT/received.jpg" "$OUT/test.jpg" && note "OK photo identique à l'envoi" || fail "la photo reçue est différente"
  else
    fail "photo absente de Pictures/Éclat ($(adb shell ls /sdcard/Pictures/ 2>&1 | tr '\r\n' '  '))"
  fi
else
  shot 5-no-island
  fail "l'île de réception ne s'affiche pas"
fi
kill $SENDER 2>/dev/null

# 3) the VLC player
if [ -s "$OUT/test.mp4" ]; then
  adb shell "am start -W -n $PKG/.PlayerActivity -a android.intent.action.VIEW -d file:///sdcard/Movies/VID_TEST.mp4 -t video/mp4" > /dev/null 2>&1
  sleep 4
  shot 7-player
  if [ -n "$(pid_of $PKG)" ]; then note "OK lecteur VLC ouvert"; else fail "le lecteur s'est fermé"; fi
  sleep 4
  adb logcat -d > "$OUT/logcat.txt" 2>&1
  if grep -qiE "VLC/.*(main decoder|avcodec|mediacodec)|libvlc" "$OUT/logcat.txt"; then note "OK moteur VLC actif"; fi
  adb shell input keyevent KEYCODE_BACK
  sleep 1
fi

adb logcat -d > "$OUT/logcat.txt" 2>&1
adb logcat -d -b crash > "$OUT/crash-buffer.txt" 2>&1
C=$(crashes)
if [ -n "$C" ] || grep -q "$PKG" "$OUT/crash-buffer.txt"; then
  echo "$C" > "$OUT/crash.txt"
  fail "plantage"
  note "  journal des plantages : $(grep -A25 "$PKG" "$OUT/crash-buffer.txt" | head -40 | cut -c1-220 | tr '\n' '|')"
  note "  logcat : $(grep -E -A6 "FATAL EXCEPTION|Fatal signal|renderer|Render process|ANR in" "$OUT/logcat.txt" | head -30 | cut -c1-220 | tr '\n' '|')"
fi

if [ -z "$FAIL" ]; then echo "SMOKE OK" >> "$RES"; exit 0; fi
echo "SMOKE FAIL: ${FAIL#; }" >> "$RES"
exit 1
