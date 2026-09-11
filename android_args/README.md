# android_args presets

Copy one onto the phone and relaunch; no rebuild needed:

    scripts/push_args.sh android_args/<file>
    adb shell am force-stop com.nakas.skate3
    adb shell am start -n com.nakas.skate3/.SetupActivity

One argument per line, `#` comments allowed. Any key here **replaces** the
built-in entry from `BuildAndroidArguments`, so a line left behind after an
experiment silently beats a later code change. Delete an experiment line the
moment the experiment is over — that exact mistake cost an evening on iOS.

A duplicated key inside one file is collapsed (last wins) before the arguments
are parsed, because the parser rejects a scalar option it sees twice and that
rejection is not local: it discards every compiled-in default too, and the game
comes up on stock desktop settings with no visible error.

## What is deliberately absent

Roughly half the iOS tuning was about MoltenVK — `vulkan_mvk_synchronous_queue_submits`,
`vulkan_mvk_present_with_command_buffer`, the ProMotion workarounds. None of it
applies here: Android talks to Vulkan directly. Copying those lines across would
do nothing at best.

Two more iOS lines are wrong here rather than merely useless:
`vulkan_allow_present_mode_immediate=false` and `..._mailbox=false` existed
because a CAMetalLayer is always display-synced whatever it claims. Adreno
really does implement both, so leave them alone unless measuring tearing.

## Files

- `baseline.txt` — the compiled-in defaults written out, for reference. Pushing
  it changes nothing; edit a copy.
- `diagnostics.txt` — the defaults plus the instruments. Start here when
  something needs explaining: it is what produces the `[pace]` line.
- `cap30.txt` — half rate. The fallback if 60 cannot be held once the phone is
  warm; a locked 30 reads better than an unstable 45.
- `quality.txt` — spends the headroom an 8 Gen 1 has over the phones this was
  tuned on: shadows, ambient occlusion, bloom, 2x MSAA.
- `shadow-ab.txt` — one experiment per block, commented out, for bisecting the
  shadow artefacts reported on Adreno 725 and 740. Read the file: the order of
  the blocks is the argument, and running them out of order wastes the runs.
  Everything in it is hot-reloadable, so it works on a build you already have.

## streaming.txt / v0114-ab-old.txt

`streaming.txt` is `diagnostics.txt` with `skate3_native_render_scene_perf_interval=120`,
so the per-window `native-scene perf:` line (which carries `guest_dt_max`, the
build and render costs and the prewarm commit cost) lands every two seconds
instead of every ten. A streaming hitch lasts one frame; at the shipped
600-frame window it is averaged away.

It deliberately does NOT set `skate3_draw_distance_debug`. That cvar's
`stream gather` and `stream probe` lines fire per frame per focus, and writing
them to FUSE-backed storage perturbs the very frame times being measured. Use a
separate run when you want the load/unload correlation.

`v0114-ab-old.txt` reverts v0.1.14's three timing changes - the frame pacer's
spin window, the command processor's idle poll, and the guest critical-section
spin cap - so the same build can be measured both ways in one session. Read
`[pace] 30s:` max across at least four windows in each.
