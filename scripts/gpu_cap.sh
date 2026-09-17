#!/bin/bash
# Is the GPU's thermal governor taking the clock away?
#
# This is the acceptance test for the sustained-frame-time work. Measured
# 2026-09-16 on the S23 FE: after thirteen minutes of play the Adreno thermal
# governor had clamped the GPU to power level 9 of 13, max_clock_mhz 818 -> 317,
# with gpu_busy_percentage at 94 the whole time. The frame rate tracked the
# ceiling down from 59 fps to 32. Nothing in the process was leaking.
#
#   thermal_pwrlevel  the index the governor has clamped to. 0 = unrestricted.
#   max_clock_mhz     that clamp as a frequency. 818 = unrestricted on this part.
#   temp              GPU junction temperature in millidegrees.
#   busy              how much of the interval the GPU was working.
#
# A PASS is pwrlevel 0 / 818 MHz still holding after fifteen minutes of play.
# Any other reading means the frame is still too expensive to sustain, whatever
# the frame counter happens to say at that moment.
#
#   scripts/gpu_cap.sh            one reading
#   scripts/gpu_cap.sh 5          keep reading every 5 seconds
set -uo pipefail
. "$(dirname "$0")/env.sh"

read_once() {
  adb -s "$SERIAL" shell '
    cd /sys/class/kgsl/kgsl-3d0 2>/dev/null || exit 1
    printf "pwrlevel=%s/%s max=%sMHz cur=%sMHz temp=%sC busy=%s" \
      "$(cat thermal_pwrlevel)" "$(cat num_pwrlevels)" "$(cat max_clock_mhz)" \
      "$(($(cat devfreq/cur_freq)/1000000))" \
      "$(($(cat temp)/1000))" "$(cat gpu_busy_percentage)"
  ' 2>/dev/null | tr -d '\r'
}

if [ $# -eq 0 ]; then
  echo "[$(date '+%H:%M:%S')] $(read_once)"
  exit 0
fi
while :; do
  echo "[$(date '+%H:%M:%S')] $(read_once)"
  sleep "$1"
done
