# Skate 3 on Android

Download the release apk here https://github.com/andrewnakas/skate3-android/releases

The Android shell for the Skate 3 native recompilation. The game itself — the
recompiled Xbox 360 executable, the rexglue runtime, and the native Vulkan
scene renderer — lives in the engine tree at `~/skate3/skate3recomp-dev` on the
`android` branch. This repository holds only what Android needs: an activity to
host SDL, a setup screen for the game data, and the scripts that build, deploy
and measure.

**No game content is here and none is downloaded.** You supply your own Skate 3
Xbox 360 disc image and Title Update 3.

## What this is

Not an emulator. The game's PowerPC code was translated ahead of time into C++
and compiled for ARM64, so the skating, physics and career mode are the retail
game's own code running natively. The Xbox 360 GPU is not emulated either
during gameplay: a native renderer reads the game's scene state and draws it
through Vulkan directly.

## Layout

| Path | What |
|---|---|
| `app/src/main/java/com/nakas/skate3/` | Setup, the game activity, the restart helper, map packs, GPU drivers, and where the files live |
| `app/src/main/jniLibs/arm64-v8a/libmain.so` | Built by `scripts/build_native.sh`, not by Gradle. Not in version control |
| `native/` | The Vulkan driver proxy and vendored libadrenotools, built by `scripts/build_driver_proxy.sh`. See `native/PROVENANCE.md` |
| `tests/driver-manager/` | Standalone instrumentation project for the driver importer. Needs a device |
| `android_args/` | Tuning profiles pushed to the phone without rebuilding |
| `scripts/` | Toolchain setup, build, install, logs, performance |
| `logs/` | Pulled logs, one directory per run |

Gradle never runs CMake; it only packages what the scripts have already built.
The engine is 7.7 million lines of recompiled PowerPC and takes hours, and
hiding that inside an APK build would bypass the memory throttling it needs on a
laptop. The driver proxy is kept out for consistency rather than cost - it takes
seconds. So: `build_native.sh` once, `build_driver_proxy.sh` when `native/`
changes, `build_apk.sh` as often as you like.

## Building

```sh
scripts/setup_sdk.sh           # once: command-line tools, platform, build tools, NDK
scripts/build_native.sh        # hours. Safe to leave; it pauses when memory is short
scripts/build_driver_proxy.sh  # minutes. Only needs redoing when native/ changes
scripts/build_apk.sh
scripts/install.sh
```

`local.properties` names the SDK and the engine tree. Both are machine-specific
and neither is in version control.

## GPU drivers

Most of the hard bugs in this port have been Qualcomm driver bugs rather than
engine bugs, and until now there was no way to take the driver out of the
picture. The launcher can now run a Mesa **Turnip** build instead of the one
baked into the phone: **GPU driver…**, pick one, **Apply and restart**.

MrPurple's T30 ships in the APK. Other Android ARM64 Turnip builds can be
imported as `.adpkg` ZIPs, left compressed; the importer checks the archive
structure, the metadata and the ELF before anything is allowed to load. A
selection is fixed for the life of a process, which is why changing it restarts
the app.

The default is the device's own driver, so nothing changes for anyone who does
not go looking. When a device misbehaves, switching to Turnip and back is now a
one-minute experiment rather than a rebuild.

This is why native libraries are extracted at install time rather than mapped
from the APK: libadrenotools needs real files on disk. The download is smaller
as a result and the installed footprint is larger. See `native/PROVENANCE.md`.

The driver manager and the proxy come from Alan Constantino's
[skate3-pocket](https://github.com/AlanConstantino/skate3-pocket), a fork of
this app; the licences are in the APK, under **Driver licences**.

## Getting the game onto the phone

Two routes.

**From a disc image, on the phone.** Launch the app, choose *Install from a
disc image*, and pick your own image and then the title update. The engine
reads them through the system document picker and extracts about 6 GB into its
own directory. Nothing needs a storage permission.

**From this machine, over the cable.** If the disc is already extracted here:

```sh
scripts/push_game_data.sh
```

That copies roughly 6 GB to `/sdcard/Android/data/com.nakas.skate3/files/game`.
It is slower than extracting on the phone, but it skips the picker entirely,
which is what you want when reinstalling the app repeatedly.

## Tuning and measuring

The engine reads `files/user/android_args.txt` at startup. One argument per
line; any key there beats the compiled-in default with no rebuild.

```sh
scripts/push_args.sh android_args/diagnostics.txt
scripts/perf.sh          # frame pacing, memory, thermal state
scripts/logs.sh          # follow the live log
scripts/logs.sh --pull   # collect the log files after a run
```

`android_args/README.md` explains the profiles and, more usefully, which of the
iOS settings deliberately did not come across.

When a native crash appears in logcat, `scripts/symbolize.sh` turns the
addresses into function names using the unstripped library kept in the build
directory.

## Target

Built and measured on a Galaxy S23 FE: Snapdragon 8 Gen 1, Adreno 730, 8 GB,
Android 16. arm64 only, Vulkan only. The `android-arm64-release` preset tunes
the guest code for that CPU; `android-arm64-generic` builds the same thing for
any 64-bit phone from about 2018 onward and is what a shared APK should use.

## Credits

Almost none of the hard parts started here.

**The recompilation.** The engine is **Alex McHugh's**
[Skate 3 recompilation](https://github.com/mchughalex/skate3recomp), which
translates the Xbox 360 executable into C++ ahead of time, built on the
[**ReXGlue SDK**](https://github.com/rexglue/rexglue-sdk) — the Xbox 360
recompilation runtime and toolkit — which is itself derived from the **Xenia**
project's Xbox 360 research (Ben Vanik and contributors). **portingpete** did
early Skate 3 bring-up on ReXGlue in
[skate3-recomp](https://github.com/portingpete/skate3-recomp).

**Skate 3 on ARM64.** **Buku313**
([Skate3-Mobile](https://github.com/Buku313/Skate3-Mobile)) and **darchap**
([Skate3-Port](https://github.com/darchap/Skate3-Port)) have both been bringing
this game to ARM64. Ported from darchap's work: stopping ambient crowds and
props at the spawn instead of hiding them at the draw, and the foreground
service that keeps a backgrounded session resident.

**The GPU driver manager** — the Vulkan driver proxy, the driver importer and
its verification, and the selection UI — is **Alan Constantino's** work, from
[skate3-pocket](https://github.com/AlanConstantino/skate3-pocket), his
handheld-focused fork of this app where it was written and tested on a Retroid
Pocket 6. `native/driver_proxy.cpp` is kept byte-identical to his copy so that a
change on either side reads as a diff rather than an archaeology exercise; see
`native/PROVENANCE.md`.

**Custom maps** are **SunJaycy's** and **Ethan's** ("dumb bad Ethan") scene —
the arena builder and the Skate 3 modding tools, plus SunJaycy's
[sk83.GLB2ARENA](https://github.com/SunJaycy/sk83.GLB2ARENA) and
sk83.LevelCompiler for getting custom models and levels into the game. The map
pack support here exists to load what they made possible.

Custom driver loading stands on **Billy Laws'**
[libadrenotools](https://github.com/bylaws/libadrenotools) and
liblinkernsbypass, on **Mesa/Turnip** for the driver, and on the **MrPurple**
Turnip builds for the one that ships here.

## Supporting the project

If you like this software, you can support the work with a donation. It is
entirely optional and everything here stays free either way.

[![Donate with PayPal](https://www.paypalobjects.com/en_US/i/btn/btn_donate_LG.gif)](https://www.paypal.com/donate/?hosted_button_id=VN7FLF8AKZR4Y)
