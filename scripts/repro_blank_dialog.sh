#!/bin/bash
# Drive the first-run setup the way that actually reproduces issue #5.
#
# The recipe is the reporter's, not a guess: on the language screen pick
# something OTHER than the English it times out to, then take the menu -
# start, then A four times. Reaching the intro movie means the run got
# through; sitting on a dialog with an empty options box means it did not.
#
# Left alone the setup times out to English and usually survives, which is
# why this went so long without a reliable repro.
#
# usage: repro_blank_dialog.sh [runs]
set -uo pipefail
. "$(dirname "$0")/env.sh" 2>/dev/null || true
PKG=com.nakas.skate3
FILES=/sdcard/Android/data/$PKG/files
LOG=$FILES/skate3.log
SHOTS="$(cd "$(dirname "$0")/.." && pwd)/build/repro"
RUNS="${1:-5}"
mkdir -p "$SHOTS"

key() { adb shell input keyevent "$1" >/dev/null 2>&1; sleep "${2:-1}"; }

pass=0; fail=0; lost=0
for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop $PKG >/dev/null 2>&1; sleep 2
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1

  # A cold pipeline cache is the precondition; a save skips the setup flow
  # entirely, so both have to go.
  adb shell "rm -rf $FILES/user/cache/nrhi_shaders $FILES/user/cache/shaders" >/dev/null 2>&1
  for x in $(adb shell "ls -d $FILES/user/*/ 2>/dev/null" | tr -d '\r' | grep -oE '[0-9A-Fa-f]{16}'); do
    adb shell "rm -rf $FILES/user/$x" >/dev/null 2>&1
  done
  adb shell "rm -f $LOG" >/dev/null 2>&1

  adb shell am start -n $PKG/.SetupActivity >/dev/null 2>&1; sleep 4
  COORD=$(adb shell uiautomator dump /sdcard/r.xml >/dev/null 2>&1; adb shell cat /sdcard/r.xml 2>/dev/null | tr -d '\r' | python3 -c "
import sys,re,html
s=sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', s):
    seg=m.group(0)
    t=re.search(r'text=\"([^\"]+)\"',seg); b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',seg)
    if t and b and html.unescape(t.group(1)).strip().upper()=='PLAY':
        print((int(b.group(1))+int(b.group(3)))//2,(int(b.group(2))+int(b.group(4)))//2); break")
  if [ -z "$COORD" ]; then echo "run $i: launcher unreachable"; lost=$((lost+1)); continue; fi
  adb shell input tap $COORD >/dev/null 2>&1

  # Boot to the language screen. Long enough for the XEX patch, the content
  # scan and the first pipeline builds.
  sleep 55

  # Anything but the English it defaults to - that is the half that matters.
  key KEYCODE_DPAD_DOWN 1
  key KEYCODE_BUTTON_A 3
  # Then straight through the setup: start, then confirm four times.
  key KEYCODE_BUTTON_START 2
  for _ in 1 2 3 4; do key KEYCODE_BUTTON_A 2; done

  sleep 25
  adb exec-out screencap -p > "$SHOTS/run$i.png" 2>/dev/null

  VERDICT=$(python3 - "$SHOTS/run$i.png" <<'PY'
import sys
from PIL import Image
import numpy as np
im = Image.open(sys.argv[1]); im.load()
W, H = im.size
g = np.asarray(im.convert("L"), dtype=np.uint8)
def band(l, t, r, b):
    return g[int(t/1080*H):int(b/1080*H), int(l/2340*W):int(r/2340*W)]
# Same discriminator as the probe: masthead lit with menu-dark surroundings
# is the setup dialog; anything else this far in is the movie or the game.
banner = (band(796, 252, 1574, 433) >= 110).mean()
top = band(700, 40, 1700, 200).mean()
bottom = band(700, 860, 1700, 1050).mean()
on_dialog = banner > 0.30 and top < 55 and bottom < 25
if on_dialog:
    options = (band(1090, 570, 1575, 690) >= 110).mean()
    print("STUCK-BLANK" if options < 0.012 else "STUCK-POPULATED")
else:
    print("THROUGH")
PY
)
  SKIPS=$(adb shell "grep -c 'async placeholder draw' $LOG" 2>/dev/null | tr -d '\r')
  SYNC=$(adb shell "grep -c 'shader compilation synchronous' $LOG" 2>/dev/null | tr -d '\r')
  COLD=$(adb shell "grep -c 'cold boot frontend' $LOG" 2>/dev/null | tr -d '\r')
  echo "run $i: $VERDICT  (skipped_frames=${SKIPS:-?} sync_windows=${SYNC:-?} cold_frontend=${COLD:-?})"
  case "$VERDICT" in
    THROUGH) pass=$((pass+1)); mv "$SHOTS/run$i.png" "$SHOTS/run$i-through.png" 2>/dev/null;;
    STUCK-BLANK) fail=$((fail+1)); mv "$SHOTS/run$i.png" "$SHOTS/run$i-BLANK.png" 2>/dev/null;;
    *) lost=$((lost+1));;
  esac
done

echo
echo "--- $RUNS runs: $pass through, $fail blank, $lost inconclusive ---"
echo "shots: $SHOTS"
