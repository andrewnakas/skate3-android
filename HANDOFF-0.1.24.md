# Handoff: cutting v0.1.24

Written 2026-09-17 for whoever releases next. Everything below is either
committed or sitting in the working tree of this repo and `~/skate3/skate3recomp-dev`.

**The short version.** The engine now carries the 66-commit `switch` branch
merged into `android`. That brought a working sub-60 frame cap, which is the
answer to the performance complaints — *and* it brought three custom-map/DLC
fixes that may already be the bug you are working on. **Read §2 before you
write any DLC code.**

---

## 1. The performance work: what was wrong, and what fixes it

The reported symptom was "60 fps in University, drops to 30 after a few
minutes, comes back if I restart the game."

### It is GPU thermal throttling. It is not a leak.

Measured on the S23 FE (`logs/sustained-run1/`). Read straight off
`/sys/class/kgsl/kgsl-3d0` while the phone was hot:

```
thermal_pwrlevel   9        (of 13 power levels)
max_clock_mhz      317      (the CEILING, down from 818)
gpu_busy_percentage 94 %
temp               56500    (56.5 C junction)
```

The GPU ceiling tracks the frame rate the whole way down: 599 MHz → 59.3 fps,
492 → 51.5, 350 → 46.1, 317 → 32.2.

A second instrument agrees independently. Across the same session CPU-side
accounted work moved **7.5 ms → 7.8 ms** while frame time moved
**16.9 ms → 27.0 ms**. Ten milliseconds went somewhere no CPU stage can see.

**The restart "fix" is a duty cycle, not a state reset.** Adreno clamps on GPU
*junction* temperature, whose time constant is seconds. A relaunch spends
30–60 s on a load screen with the GPU idle, the junction drops, the clamp lifts,
and you get 60 fps again until it heats back up.

### The frame was never too expensive

Uncapped with vsync off, from a cold phone, the GPU held 818 MHz and the game
ran at **83–130 fps (7.7–12.0 ms per frame)** against a 16.6 ms budget. The
arithmetic then predicts everything we observed:

| GPU clock | frame time | result |
|---|---|---|
| 818 MHz | ~10 ms | 60 fps easily |
| 492 MHz | ~16.6 ms | borderline |
| 350 MHz | ~23 ms | 30 fps |
| 317 MHz | ~26 ms | 30 fps |

### Three levers that do NOT work — do not re-try these

All three reduce work *per frame*, and work per frame was never the problem:

| tried | measured | still clamped? |
|---|---|---|
| `skate3_native_render_scene_scale=0.60` (36% of the pixels) | ~84% of the GPU work — the frame is **not fill-bound** | yes, pwrlevel 9 in 3.5 min |
| `skate3_draw_distance_scale=0.25` | items ~1000 → ~500, draws ~2000 → ~1150 | yes, pwrlevel 9 in 7 min |
| a light custom map (404–686 draws/frame vs ~2000) | — | yes, pwrlevel 8 at 350 MHz |

### What DOES work: the 30 fps cap. Verified on hardware.

`logs/cap30-verify/`, 15 minutes on device:

- **38 windows, every one exactly 30.0 fps.** Frame time 33.3 ms, worst 33.4.
  Zero deviation, including dense areas at ~1000 draws/frame.
- The GPU settles at **220 MHz** — the second-lowest of its 13 power levels —
  against the 818 MHz it needs for 60.
- **The thermal clamp releases while you play**: pwrlevel 8 → 7 → 6 → 5 → 3 →
  2 → 1 → **0**, junction temperature falling 57 °C → 46 °C *during gameplay*.

The user's verdict playing it: "30fps feels ok, not great but ok." A locked 30
reads as smooth-but-slower because every frame is held for exactly two
refreshes; the 31–45 fps it used to wander through is what reads as judder.

### The cap only works because of the merge — this matters

`ca9dab1 Report the guest display at whatever the frame rate is capped to` is a
`switch` commit. It added `MatchGuestRefreshToFrameCap`
(`src/skate3_app_common.cpp:1191`, on by default via
`skate3_match_guest_refresh_to_cap`), which reports the guest video mode as
30 Hz to match the cap.

**This title advances one *reported refresh period* of simulation per rendered
frame**, so before that commit a 30 fps cap ran the game in slow motion. The
`android_args/cap30.txt` that shipped in this repo was therefore a trap, and
anyone who found that setting got a half-speed game. It is fixed and the file
now documents why.

The settings menu gained the rows to match:

| | before | after |
|---|---|---|
| Frame cap options | Unlimited, 60, 90, 120, 144, 165, 240 | **20, 24, 30**, 60, 90, … |

Reachable on Android: `UseGuestFrameCap()` gates on `skate3_native_render`,
which defaults true and nothing overrides it.

### What to actually ship

The 30 fps row is already in the Video page's frame cap control, so the option
exists with no further work. **Do not make it the default** — it is a large
behavioural change and 60 is correct on a cold phone and in light scenes. It is
worth a line in the release notes telling players that a phone that drops to
the 30s under load will be *smoother* pinned at 30 than left to wander.

---

## 2. ⚠️ READ THIS BEFORE WRITING ANY DLC/MAP-PACK CODE

The merge brought custom-map fixes that were not on `android` before. There is a
real chance one of them is already the bug being worked on. All are in
`src/skate3_app_common.cpp`:

1. **A deterministic crash on leaving a custom map.** Packs from the PS3→360
   importer register their world with a `dlc` suffix the shipped files do not
   carry — DM Jumpline registers `dmjumplinedlc` but ships `DMJumpline` — so
   exiting opens `dist_dmjumplinedlc_Sim.xml`, which does not exist. The title
   uses the failed `NtCreateFile` return **as a pointer**. Fixed generically in
   `ApplyContentPackWorkarounds` by deriving a `vfs_path_alias`; the alias is
   only tried after a resolve has already failed, so a pack without the defect
   never matches it.
2. **Packs that enumerate and are then refused.** `XamContentGetLicenseMask`
   answers from a single global `license_mask`, and 0 means "nothing was
   purchased". Now set to `0xFFFFFFFF` when a pack is staged.
3. **d2s3-made packs silently absent.** That tool — what everyone builds custom
   maps with — writes `xuid`/`title_id` *packed* at 0x134/0x13C where the
   engine reads them 8-aligned at 0x138/0x140, so `title_id` reads as 0 and the
   content manager looks for a package belonging to title 0. Staging now
   rewrites the descriptor (`StageContentPackHeader`).
4. `CopyFileBytes` replaces `std::filesystem::copy_file` for staging. Needed on
   Horizon (ENOSYS, and it left a zero-byte destination); harmless on Android.

---

## 3. The other change in this handoff

**The "Map Packs…" row is off the System page**, as asked. SDK commit
`b39a972`. `PushLevelPickerRow` and `open_level_picker_` are deliberately left
wired up — restoring the row is putting one call back.

Note the implication: choosing a pack now happens through the **startup
chooser** (`skate3_content_pack_menu`, default on, its own row on the Controls
page), which only appears when two or more packs are installed. A player with
exactly one pack installed no longer has an in-game way to switch to or from it.
If that matters, the row comes back with one line.

---

## 4. Repo state

**App** (`~/Documents/skate3android`, branch `main`, at `edeb840`) — nothing
committed yet. Working tree:

```
 M android_args/cap30.txt          repaired + documented (see note below)
?? android_args/draw-distance.txt  experiment profiles, worth keeping as record
?? android_args/scene-scale.txt
?? android_args/scene-scale-sweep.txt
?? android_args/sustained.txt
?? android_args/uncapped.txt
?? scripts/gpu_cap.sh              NEW - the acceptance test for this class of bug
?? scripts/sustained.py            NEW - fps/store/backlog timeline from a log
?? scripts/thermal_watch.sh        NEW - thermal + clock sampler
?? HANDOFF-0.1.24.md               this file
   logs/*                          gitignored; the evidence lives here
```

`android_args/cap30.txt` shows as modified because it already existed and was
overwritten during this work. All eleven of its original keys and its original
rationale are restored; the new findings are appended.

**Engine** (`~/skate3/skate3recomp-dev`, branch `android`), 67 commits ahead of
the `v2.6.2` tag that built v0.1.23:

- `a12f846` Merge branch 'switch' into android
- `3341a5e` Point at the SDK without the System-page map pack row

**SDK submodule** at `b39a972`.

### To cut the release

1. `app/build.gradle.kts:40-41` — `versionCode = 25`, `versionName = "0.1.24"`.
2. `scripts/build_native.sh` then `scripts/build_apk.sh`.
3. `adb install -r` — **never `adb uninstall`**, it wipes ~6 GB of game data.
4. Tag the engine `v2.6.3` and the app `v0.1.24`.
5. `scripts/push_args.sh --clear` on any test device — a left-behind args file
   silently beats the settings menu for every key it names.

---

## 5. Verified vs not

**Verified on hardware today**, all on the S23 FE (SM-S711U) with the merged build:

- 30 fps cap: locked 30.0 across 38 windows, thermal clamp releasing during play
- Boot, gameplay, a custom map, the settings menu, map loads — roughly 60
  minutes across six sessions, no crashes
- The build gates: `SDL_main`/`JNI_OnLoad` exported, every LOAD segment 16 KB
  aligned, lint clean

**NOT verified:**

- **The System page with the map pack row gone has not been opened on device.**
  The APK is built and installed; somebody should open Settings → System and
  confirm the page renders with Content gone and Interface/Diagnostics/Session
  intact.
- Any device other than the S23 FE. The merge touches the shadow atlas sizing,
  allocation paths and job waits; `adreno-pipeline-layout-limit` is the standing
  reminder that the dev phone is an outlier (`maxBoundDescriptorSets` 7 vs 4).
- The DLC fixes in §2 — they are merged and compile, but no custom-map pack has
  been staged from scratch on this build to watch them work.

## 6. One real bug found and deliberately not fixed

The **mesh store grows all session and never evicts once**: 70 MB / 4599 meshes
→ 184 MB / 13149 over thirteen minutes, because it stays under both its 224 MB
byte budget and its 24576-entry cap. It did not cause the frame rate decay —
that was thermal — but it is real and worth its own change, particularly for
memory-constrained devices. `PickAndroidStoreBudgets`
(`third_party/rexglue-sdk/src/ui/windowed_app_main_sdl.cpp:693`) also hands an
8 GB phone the same 288/224 MB as a 5 GB one, and that function's own comment
asks for exactly that to be revisited once the eviction rate was measured.
