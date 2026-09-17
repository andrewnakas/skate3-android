#!/bin/bash
# Sample the phone's thermal and clock state while a measurement run is going on.
#
# Nothing in the app reads thermal state today, so a shipped log cannot tell a
# throttling SoC from a leaking renderer - which is the whole question behind
# the sustained-frame-time work. Until the app can say it itself (plan phase
# 2d), this runs alongside the session and writes a line the engine log can be
# read against by wall clock.
#
# Columns:
#   status   Android's own thermal severity, 0-6. By 3 (severe) the governor is
#            already cutting clocks; 0 throughout a decaying session is strong
#            evidence the decay is not thermal.
#   ap/skin  SoC and skin temperature in C, from the thermal HAL.
#   cpu      per-core scaling_cur_freq in MHz, little cores first. A big core
#            pinned low while the game is busy is throttling made visible.
#   gpu      Adreno clock in MHz, and its busy percentage since the last
#            sample. A GPU that is busy AND slow is throttled; one that is idle
#            while frames are late is waiting on the CPU.
#   cpuss/gpuss/ddr  the on-die sensors behind those clocks, in C.
#
# The phone answers all of this over adb without root; that was checked before
# this script was written, and both kgsl nodes were confirmed readable.
#
#   scripts/thermal_watch.sh [interval_seconds] | tee logs/<run>/thermal.log
set -uo pipefail
. "$(dirname "$0")/env.sh"
INTERVAL="${1:-10}"

# Resolve the handful of zones worth printing to their indices once, by name.
# There are seventy of them on this phone and printing all seventy is how a
# sampler becomes unreadable.
echo "# resolving thermal zones..." >&2
ZONE_MAP=$(adb -s "$SERIAL" shell '
  for t in /sys/class/thermal/thermal_zone*/type; do
    d=$(dirname "$t"); echo "$(basename "$d") $(cat "$t")"
  done' 2>/dev/null | tr -d '\r')
idx_of() { echo "$ZONE_MAP" | awk -v n="$1" '$2==n{gsub(/thermal_zone/,"",$1); print $1; exit}'; }
CPUSS=$(idx_of cpuss-0); GPUSS=$(idx_of gpuss-0); DDR=$(idx_of ddr)
echo "# zones: cpuss-0=$CPUSS gpuss-0=$GPUSS ddr=$DDR   interval ${INTERVAL}s   started $(date '+%Y-%m-%d %H:%M:%S')"

while :; do
  # One round trip, and every byte parsed on the Mac. Toybox's sed and grep are
  # not the ones this was written against, and a parse that silently yields "?"
  # for two hours is worse than no sampler at all.
  OUT=$(adb -s "$SERIAL" shell "
    dumpsys thermalservice
    echo ---CPU---
    cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null
    echo ---GPUF---
    cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null
    echo ---GPUB---
    cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null
    echo ---ZONES---
    cat /sys/class/thermal/thermal_zone$CPUSS/temp /sys/class/thermal/thermal_zone$GPUSS/temp /sys/class/thermal/thermal_zone$DDR/temp 2>/dev/null
  " 2>/dev/null | tr -d '\r')

  status=$(printf '%s' "$OUT" | awk -F': ' '/Thermal Status/{print $2; exit}')
  # Take the HAL block, not the cached one above it: the cached values can be
  # minutes old, which is exactly the resolution this is trying to beat.
  temps=$(printf '%s' "$OUT" | awk '/Current temperatures from HAL/{f=1} f')
  t_of() { printf '%s' "$temps" | awk -v n="mName=$1" -F'[={,]' '$0 ~ n {for(i=1;i<=NF;i++) if($i=="mValue"){printf "%.1f", $(i+1); exit}}'; }
  ap=$(t_of AP); skin=$(t_of SKIN); bat=$(t_of BAT)

  sect() { printf '%s' "$OUT" | awk -v a="---$1---" '$0==a{f=1;next} /^---/{f=0} f'; }
  cpu=$(sect CPU | awk '{printf "%d ", $1/1000}')
  gpuf=$(sect GPUF | awk 'NR==1{printf "%d", $1/1000000}')
  read -r busy total <<< "$(sect GPUB | awk 'NR==1{print $1, $2}')"
  read -r zc zg zd <<< "$(sect ZONES | awk '{printf "%d ", $1/1000}')"

  # kgsl's gpubusy is READ-AND-CLEAR: it reports busy and total since the last
  # time anything read it, so it is already an interval and must not be
  # differenced. Checked on this phone - one read returned "55236 1011494" and
  # the next, with the GPU idle, returned "0 0". Differencing free-running
  # counters here would have produced a plausible-looking wrong number, which
  # is the worst kind.
  #
  # It follows that NOTHING ELSE may read this node during a run, or the two
  # readers split the interval between them and both under-report.
  gpupct='--'
  if [ "${total:-0}" -gt 0 ] 2>/dev/null; then
    gpupct=$(( 100 * busy / total ))
  elif [ "${total:-}" = "0" ]; then
    gpupct=0   # the node answered, and the GPU did nothing at all
  fi

  echo "[$(date '+%H:%M:%S')] status=${status:-?} ap=${ap:-?}C skin=${skin:-?}C bat=${bat:-?}C" \
       "gpu=${gpuf:-?}MHz busy=${gpupct}% cpuss=${zc:-?}C gpuss=${zg:-?}C ddr=${zd:-?}C" \
       "cpu=[ ${cpu}]"
  sleep "$INTERVAL"
done
