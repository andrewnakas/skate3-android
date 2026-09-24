#!/bin/bash
# One A/B measurement, recorded with enough context that two runs can honestly
# be compared. Wraps the existing tools rather than replacing them.
#
#   scripts/ab.sh <label> [seconds]        # default 300 (five minutes)
#   scripts/ab.sh --compare <dirA> <dirB>
#
# WHY THIS EXISTS. Three confounds have each already cost a session here:
#
#   1. The frame cap. A run with the cap off is not comparable to one with it
#      on, and the cap is not visible in any frame-rate number.
#   2. Thermal state. The GPU clamps from 818 MHz to 317 over a few minutes and
#      the frame rate tracks it down; a cold run and a warm run of the SAME
#      binary differ by a factor of two.
#   3. The present mode. MAILBOX with no cap renders ~125 fps into a 60 Hz
#      panel and throws half of it away, which is pure heat.
#
# So every run records its whole configuration and the comparison refuses to
# pretend two differently-configured runs mean anything.
#
# THE PRIMARY METRIC IS THE GPU CLAMP, NOT AVERAGE FPS. A cold-phone average
# has agreed with the wrong conclusion more than once. What matters is whether
# thermal_pwrlevel is still 0 after fifteen minutes.
#
# WHAT THIS ADDS OVER bench.sh: SurfaceFlinger keeps only the last 128 present
# timestamps per layer, so bench.sh's percentiles describe the last ~2 seconds
# however long you asked it to sample. This polls once a second and
# accumulates, so the percentiles cover the whole run - which is the entire
# point when the thing being measured is decay over minutes.
#
# DO NOT run scripts/thermal_watch.sh at the same time: kgsl's gpubusy node is
# read-and-clear and two readers both under-report.
set -uo pipefail
. "$(dirname "$0")/env.sh"

if [ "${1:-}" = "--compare" ]; then
  A="${2:?usage: ab.sh --compare <dirA> <dirB>}"; B="${3:?}"
  python3 "$(dirname "$0")/ab_compare.py" "$A" "$B"
  exit $?
fi

LABEL="${1:?usage: ab.sh <label> [seconds]   (or: ab.sh --compare <dirA> <dirB>)}"
SECS="${2:-300}"
MAX_START_TEMP_C="${MAX_START_TEMP_C:-33}"
OUT="$APP_DIR/logs/ab/$(date +%Y%m%d-%H%M%S)-$LABEL"
mkdir -p "$OUT"
adbs() { adb -s "$SERIAL" "$@"; }

# ---------------------------------------------------------------- pre-flight
pid=$(adbs shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] || { echo "!! $PKG is not running - start it and reach GAMEPLAY first"; exit 1; }

batt_temp=$(adbs shell dumpsys battery | tr -d '\r' | awk '/^  temperature:/{print $2/10}')
gpu=$(adbs shell 'cd /sys/class/kgsl/kgsl-3d0 && printf "%s %s %s" "$(cat thermal_pwrlevel)" "$(cat max_clock_mhz)" "$(($(cat temp)/1000))"' 2>/dev/null | tr -d '\r')
read -r pwr0 maxclk0 gputemp0 <<< "$gpu"
charging=$(adbs shell dumpsys battery | tr -d '\r' | awk '/USB powered|AC powered/{print $1"="$3}' | paste -sd, -)
level=$(adbs shell dumpsys battery | tr -d '\r' | awk '/^  level:/{print $2}')

echo "== $LABEL   (${SECS}s)"
printf "   battery %s%% %s C  [%s]\n" "$level" "$batt_temp" "$charging"
printf "   gpu     pwrlevel %s  ceiling %s MHz  %s C\n" "$pwr0" "$maxclk0" "$gputemp0"

fail=0
awk -v t="$batt_temp" -v m="$MAX_START_TEMP_C" 'BEGIN{exit !(t+0>m+0)}' && {
  echo "!! battery is ${batt_temp} C, above ${MAX_START_TEMP_C} C - let it cool, this run would measure heat"; fail=1; }
[ "${pwr0:-9}" = "0" ] || { echo "!! GPU already clamped to pwrlevel $pwr0 - let it cool"; fail=1; }
[ "${level:-0}" -ge 25 ] 2>/dev/null || { echo "!! battery ${level}% - low-battery states throttle; charge above 25%"; fail=1; }
if [ "$fail" = 1 ] && [ "${AB_FORCE:-0}" != "1" ]; then
  echo "   (set AB_FORCE=1 to run anyway; the result is then not comparable)"; exit 1
fi

# ------------------------------------------------------- record the config
{
  echo "label=$LABEL"; echo "seconds=$SECS"; echo "when=$(date -Iseconds)"
  echo "battery_pct=$level"; echo "battery_temp_c=$batt_temp"; echo "charging=$charging"
  echo "gpu_pwrlevel_start=$pwr0"; echo "gpu_ceiling_start_mhz=$maxclk0"; echo "gpu_temp_start_c=$gputemp0"
  echo "so_build_id=$(adbs shell md5sum /data/app/*/$PKG-*/lib/arm64/libmain.so 2>/dev/null | tr -d '\r' | awk '{print $1}' | head -1)"
  echo "app_version=$(adbs shell dumpsys package $PKG | tr -d '\r' | awk -F= '/versionName=/{print $2; exit}')"
} > "$OUT/config.txt"
adbs shell "cat $FILES/user/android_args.txt" 2>/dev/null | tr -d '\r' > "$OUT/android_args.txt"
adbs shell "cat $FILES/user/settings.toml"    2>/dev/null | tr -d '\r' > "$OUT/settings.toml"
# The two lines that decide whether this run is comparable to any other.
adbs shell "grep -a -e '\[pace\]' -e 'presentation mode' $FILES/skate3.log | tail -5" 2>/dev/null | tr -d '\r' > "$OUT/pace_and_present.txt"
echo "   cap/present:"; sed 's/^/     /' "$OUT/pace_and_present.txt"

# --------------------------------------------------------------- the layer
all=$(adbs shell dumpsys SurfaceFlinger --list 2>/dev/null | tr -d '\r' | grep -i "$PKG" | grep -v 'Background for')
raw=$(printf '%s\n' "$all" | grep -F '(BLAST)' | tail -1)
[ -n "$raw" ] || raw=$(printf '%s\n' "$all" | grep -i 'SurfaceView' | tail -1)
[ -n "$raw" ] || { echo "!! no SurfaceFlinger layer for $PKG"; exit 1; }
layer=$(printf '%s' "$raw" | sed -E 's/^RequestedLayerState\{//; s/\}$//; s/ parentId=[0-9]+.*$//')
echo "   layer: $layer"
echo
echo "   >>> PLAY NOW - same route every time, keep moving, stay in the world <<<"

# ------------------------------------------------------------------ sample
start_cpu=$(adbs shell cat /proc/$pid/stat 2>/dev/null | awk '{print $14+$15}')
adbs shell "dumpsys SurfaceFlinger --latency-clear '$layer'" >/dev/null 2>&1
: > "$OUT/frames.raw"; : > "$OUT/gpu.txt"; : > "$OUT/power.txt"
end=$(( $(date +%s) + SECS ))
while [ "$(date +%s)" -lt "$end" ]; do
  adbs shell "dumpsys SurfaceFlinger --latency '$layer'" 2>/dev/null | tr -d '\r' \
    | awk 'NF>=3 && $2 ~ /^[0-9]+$/ {print $2}' >> "$OUT/frames.raw"
  adbs shell 'cd /sys/class/kgsl/kgsl-3d0 && printf "%s %s %s %s\n" \
      "$(cat thermal_pwrlevel)" "$(cat max_clock_mhz)" "$(($(cat devfreq/cur_freq)/1000000))" "$(($(cat temp)/1000))" "$(cat gpu_busy_percentage 2>/dev/null | tr -dc 0-9)"' \
    2>/dev/null | tr -d '\r' | sed "s/^/$(date +%s) /" >> "$OUT/gpu.txt"
  # Energy. The declared currency of this whole effort is joules per second,
  # and until now nothing measured it -- every "this lowers sustained power"
  # claim was judged by fps and thermal_pwrlevel, which are downstream of power
  # with minutes of lag and a thermal-mass confound.
  # /sys/class/power_supply is permission-denied on this phone, but dumpsys
  # battery exposes a real coulomb counter (uAh) and the millivolts to turn it
  # into energy, plus Samsung's current_avg in mA.
  adb -s "$SERIAL" shell dumpsys battery 2>/dev/null | tr -d '\r' \
    | awk -v ts="$(date +%s)" '
        /Charge counter:/ {cc=$3}
        /^  voltage:/     {mv=$2}
        /current_avg:/    {for(i=1;i<=NF;i++) if($i ~ /^current_avg:/){split($i,a,":"); ca=a[2]}}
        END {if (cc != "") print ts, cc, mv, ca+0}' >> "$OUT/power.txt"
  sleep 1
done
end_cpu=$(adbs shell cat /proc/$pid/stat 2>/dev/null | awk '{print $14+$15}')
ticks=$(adbs shell getconf CLK_TCK 2>/dev/null | tr -d '\r'); ticks=${ticks:-100}
adbs shell dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | grep -E 'TOTAL PSS' | head -1 > "$OUT/meminfo.txt"
adbs shell "grep -a -e '\[cp-swap\]' -e '\[cp-op\]' -e '\[cp-sum\]' -e 'benchmark:' $FILES/skate3.log | tail -12" 2>/dev/null | tr -d '\r' > "$OUT/cp.txt"

python3 "$(dirname "$0")/ab_report.py" "$OUT" "$SECS" "$start_cpu" "$end_cpu" "$ticks" | tee "$OUT/summary.txt"
echo
echo "   saved: $OUT"
