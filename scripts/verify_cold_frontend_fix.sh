#!/bin/bash
# Did the cold-boot frontend stop skipping draws that were still compiling?
#
# The blank difficulty dialog (issue #5) is one-shot frontend renders losing
# draws whose pipeline had not finished building. Reproducing it directly is a
# coin flip - the reporter put it near one in ten - so this measures the CAUSE
# instead, which is deterministic: on a cold shader cache the engine either
# skips placeholder draws during the frontend or it does not.
#
# Run it against a build without the fix and then one with it. What should
# change: "cold boot frontend - shader compilation synchronous" appears, and
# the placeholder-skip count drops to zero.
#
# usage: verify_cold_frontend_fix.sh [label]
set -uo pipefail
. "$(dirname "$0")/env.sh"
PKG=com.nakas.skate3
FILES=/sdcard/Android/data/$PKG/files
LOG=$FILES/skate3.log
LABEL="${1:-run}"

adb shell am force-stop $PKG >/dev/null 2>&1
sleep 2
# Anything of ours is gone; a system activity we opened earlier is not, and it
# swallows the launcher.
for _ in 1 2 3; do adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; done
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1

# The two things that make a boot "first ever": no compiled pipelines and no
# save. Only these are removed - the game data is untouched.
adb shell "rm -rf $FILES/user/cache/nrhi_shaders $FILES/user/cache/shaders" >/dev/null 2>&1
for x in $(adb shell "ls -d $FILES/user/*/ 2>/dev/null" | tr -d '\r' | grep -oE '[0-9A-Fa-f]{16}'); do
  adb shell "rm -rf $FILES/user/$x" >/dev/null 2>&1
done
adb shell "rm -f $LOG" >/dev/null 2>&1

adb shell am start -n $PKG/.SetupActivity >/dev/null 2>&1
sleep 4
COORD=$(adb shell uiautomator dump /sdcard/v.xml >/dev/null 2>&1; adb shell cat /sdcard/v.xml 2>/dev/null | tr -d '\r' | python3 -c "
import sys,re,html
s=sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', s):
    seg=m.group(0)
    t=re.search(r'text=\"([^\"]+)\"',seg); b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',seg)
    if t and b and html.unescape(t.group(1)).strip().upper()=='PLAY':
        print((int(b.group(1))+int(b.group(3)))//2,(int(b.group(2))+int(b.group(4)))//2); break")
if [ -z "$COORD" ]; then
  echo "launcher not reachable (phone locked, or another app is on top)"; exit 2
fi
adb shell input tap $COORD >/dev/null 2>&1

echo "booting with a cold shader cache; sampling for 90s..."
sleep 90

SYNC=$(adb shell "grep -c 'shader compilation synchronous' $LOG" 2>/dev/null | tr -d '\r')
COLDLINE=$(adb shell "grep -c 'cold boot frontend' $LOG" 2>/dev/null | tr -d '\r')
SKIPS=$(adb shell "grep -c 'async placeholder draw' $LOG" 2>/dev/null | tr -d '\r')
COMPILES=$(adb shell "grep -c 'cold shader cache' $LOG" 2>/dev/null | tr -d '\r')

echo
echo "--- $LABEL ---"
echo "cold-cache pipeline compiles : ${COMPILES:-0}   (0 means the cache was not actually cold - the test proves nothing)"
echo "sync-compile window entered  : ${SYNC:-0}"
echo "  of which 'cold boot frontend': ${COLDLINE:-0}   (the fix; 0 on a build without it)"
echo "frames with draws SKIPPED     : ${SKIPS:-0}   (this is the defect; want 0)"
