# Session 1 runbook

Device time is the scarce resource, so this is ordered to get the most out of
one charge. Everything here is prepared and staged; you play, I drive.

## Before you start

Phone on the charger until **>=30%** and **GPU junction <42 C**
(`scripts/gpu_cap.sh` — the temp field). Fast charging heats the SoC, so it
needs a few minutes off the fast charger before a run, or the first
measurement is a measurement of the charger.

`scripts/ab.sh` refuses to run outside those bounds and says why. That is
deliberate: three separate sessions here have been lost to a confound
(frame cap, thermal state, present mode), and a run that is not comparable is
worse than no run because it looks like evidence.

## Step 0 — is the console reachable? (60 seconds, free)

    adb shell input keyevent KEYCODE_GRAVE

If a console overlay appears, every cvar becomes settable from the host with no
relaunch. If not, `skate3_live_cvars` (below) covers it anyway. Worth knowing
either way.

## Step 1 — symbol-ordering capture (~10 min of play)

The single cheapest big win available: the lld `--symbol-ordering-file`
plumbing has existed the whole time and has never been fed
(`CMakeLists.txt:547-558`, `REXGLUE_SYMBOL_ORDER_FILE` empty). 67 MB of
recompiled `.text` against a 32-64 KB L1I, with 50% of samples in ~115
functions and 80% in ~600. Applying the result is a **relink**, measured at
5-10 seconds — not a rebuild.

    scripts/push_args.sh android_args/profile-order.txt
    adb shell am force-stop com.nakas.skate3
    adb shell am start -n com.nakas.skate3/.SetupActivity

Then reach gameplay and **play ten minutes of dense world** — not menus, not a
loading screen, not standing still. The profile is only as good as the session:
ten minutes of standing still produces a layout optimised for standing still.

Two traps this preset already handles, both of which would silently waste the
session: the order file is truncated at the end of *every* report window (so
the default 30 s would leave a profile of a pause menu — it is set to 600), and
the profiler's reports were `REXLOG_INFO`, which is invisible at the shipped
`log_level=warn` (promoted to WARN).

Afterwards:

    adb pull /sdcard/Android/data/com.nakas.skate3/files/order.txt
    # expect ~4,000 lines; check the "skate3 profile spread:" line in the log

This run also settles, for free, **why the shipped 60 fps cap was not in
force** — the preset sets it explicitly, so the `[pace]` line either honours it
or proves something erases it.

## Step 2 — baseline (5 min of play)

    scripts/push_args.sh --clear
    adb shell am force-stop com.nakas.skate3
    # relaunch, reach gameplay, then:
    scripts/ab.sh baseline-tuned 300

`ab.sh` records the full configuration (cap, present mode, settings.toml, args,
build id, start/end junction temperature) and accumulates SurfaceFlinger frame
data once a second. That last part matters: `bench.sh` reads `--latency` once
at the end and SurfaceFlinger only keeps the last **128** frames, so its
percentiles describe the last ~2 seconds however long you asked it to sample —
useless for something that decays over minutes.

**The primary metric is the GPU clamp, not average fps.** `ab.sh` reports
whether `thermal_pwrlevel` held at 0 and when it first moved. Average fps on a
cold phone has agreed with the wrong conclusion more than once here.

## Step 3 — flip levers without relaunching

New this session: `skate3_live_cvars`. The console is bound to the Backtick key
and has no gamepad chord, so on a phone no arbitrary cvar could be set at
runtime at all. Now:

    # once, in the args:  skate3_live_cvars=true
    adb shell "echo skate3_guest_spin_yield=1 > \
      /sdcard/Android/data/com.nakas.skate3/files/user/live_cvars.txt"

Applied within a second, mid-run, confirmed in the log at WARN. Levers worth
flipping this way, in order:

| cvar | try | why |
|---|---|---|
| `skate3_guest_spin_yield` | 1, then 2, then 3 | `sub_82B76080` is the guest's spin-wait, measured at **35% of the render thread**, with its calling loop at another 13%. The console paced it with `cctpl`/`db16cyc`/`cctpm`, none of which survive recompilation, so it spins flat out — burning a core and the memory bandwidth the threads it is *waiting for* need. The fix is written and tested and ships disabled. |
| `vulkan_keep_render_pass` | 0 vs 1 | The render-pass early-out added this session. **Watch the shadows** — if they are wrong at 1 and right at 0, my pending-clear reasoning was wrong. |
| `gpu_op_timing` | leave 0 | Was shipping *enabled*: 2 clock reads + 2 atomics on every PM4 packet, ~1.7M vDSO calls/sec on the busiest thread. |
| `skate3_native_render_lw_refresh` | 2 | The crowd sim runs every frame on any phone with >=4 GB, because its low-end gate is a *memory* test used for a *CPU* throttle. |

Pair the spin-wait test with `skate3_guest_spin_measure=true` — as its own
description puts it, "a profiler share is not a duration; this is."

## Step 4 — relink with the order file, measure again

    cmake -S /Users/nakas/skate3/skate3recomp-dev \
          -B /Users/nakas/skate3/skate3recomp-dev/out/build/android-arm64-release \
          -DREXGLUE_SYMBOL_ORDER_FILE=/abs/path/to/order.txt
    # confirm "skate3: ordering text by ..." appears, then relink + reinstall
    scripts/ab.sh ordered-tuned 300
    scripts/ab.sh --compare logs/ab/<baseline> logs/ab/<ordered>

`--compare` refuses two runs whose cap, present mode, settings or starting
temperature differ, and says which. The `EXISTS()` check on the order file runs
at **configure** time, so configuring before the file exists silently skips it
and the build still "succeeds" — check for the `skate3: ordering text by` line.
