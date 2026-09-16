# Bringing the iOS port up to parity

Notes for whoever (or whatever) is working on the iOS side. Written from the
Android tree at v0.1.23, 2026-09-16.

The short version: **most of this is a merge, not a port.** The two platforms
share the engine and the SDK, and almost every feature below lives in that
shared code. Only the app shell differs.

---

## 1. Start here: the branches have diverged in one direction only

In `SK8-Engine` (the engine repo):

```
git log --oneline ios-port..android | wc -l   # 77
git log --oneline android..ios-port | wc -l   # 0
```

`ios-port` has **no unique commits**. It is a strict ancestor of `android`.
Everything that has been built in the last several releases — the settings menu
work, the touch controls, the map-pack handling, the shadow clamp, the audio
fixes, the memcpy fix — is on `android` and simply absent from `ios-port`.

So the first move is to merge `android` into `ios-port` and see what actually
breaks, rather than re-implementing anything. The platform gates are already in
the shared files; they are written as

```cpp
#if (defined(__APPLE__) && TARGET_OS_IPHONE) || defined(__ANDROID__)
```

for "touch device" and `#if REX_PLATFORM_IOS` for "iOS specifically", and both
are already used throughout. The code was written expecting both platforms.

Do the same for `rexglue-sdk`: the submodule's `android` branch carries the
settings overlay work.

---

## 2. What you get for free from the merge

These are entirely in shared code. No iOS work beyond merging and testing.

### The settings menu tells you when a restart is owed (v0.1.23)

`rexglue-sdk/src/ui/overlay/simple_settings_overlay.cpp`

Startup-only settings (resolution scale, MSAA, shadows, aspect, language, audio
buffer) do nothing visible until the app restarts. Backing out of the menu used
to close silently, so the change looked broken. Now leaving raises a notice
card.

**This matters more on iOS than on Android**, because your "restart" is a quit:

- `kApplyActionName` is `"Apply & Quit"` on iOS, `"Apply & Restart"` elsewhere.
- `ApplyAndRestart()` on iOS cannot spawn a new process (sandbox, `posix_spawn`
  returns EPERM), so it saves and quits; the player relaunches by hand. That is
  already handled in `skate3_app_common.cpp` under `#if REX_PLATFORM_IOS`.
- The card already has separate iOS wording for both the apply case and the new
  exit-notice case — grep `ios_quits` in the confirm block.
- The declining button is focused by default (`confirm_button_ = 0`). Keep it
  that way. On iOS the other button closes the app, and a reflex press should
  never land there.

The entry point is `SimpleSettingsDialog::RequestClose()`, which shows the
notice if `HasPendingRestart() && !restart_notice_shown_`, otherwise calls
`Hide()`. Anywhere the iOS shell closes the settings menu should call
`RequestClose()` rather than `Hide()` — on Android that is `ToggleSimpleSettings()`
and the Back handler. The one deliberate exception is the level picker's
close-menus callback, which uses `Hide()` because it is already relaunching.

### The touch layout editor has a second way out (v0.1.23)

`rexglue-sdk/src/input/touch/touch_input_driver.cpp`, `src/skate3_touch_controls.cpp`

Arranging the on-screen controls deliberately makes the pad report nothing —
a finger moves a button instead of pressing it. That also meant every chord was
dead and the gear was inert, so the editor's own Done button was the only exit.
A player whose panel did not render had to force-quit. Tapping the gear now
leaves the editor too.

It distinguishes a tap from a drag by distance from the landing point
(`kTapRadius`, 2% of the screen), because the control follows the finger, so
"released while still on it" is always true. Fully shared code — iOS gets it.

### Map pack chooser at startup (v0.1.23)

`src/skate3_app_common.cpp`

`skate3_content_pack_menu` was defined and read by nothing. Its help text said
it was waiting for an insertion point where the UI is actually painting; the
asynchronous chooser in `OnFinalizePaths` has been that for a while. It is
honoured now and defaults on.

The bug it fixes: the in-game level picker writes `skate3_content_pack` so its
choice survives the relaunch, and the startup chooser only ran when that was
empty — so using the picker once disabled the chooser permanently, with no way
back except editing a file. Check whether iOS has the same trap; the writing
path is shared.

### Everything else in those 77 commits

Shadow atlas clamped to `MaxTextureDimension2D()` rather than a hardcoded D3D12
constant; the overlapping-memcpy fix in the XEX patcher; the descriptor-set
layout work; `FlushDissolvedViews` quadratic fix; audio codec tag repair. All
shared, all absent from `ios-port`.

---

## 3. What needs an iOS equivalent

These are Android app-shell features written in Kotlin. The *idea* ports; the
code does not.

### Diagnostic report and device identification

`app/src/main/java/com/nakas/skate3/Diagnostics.kt`

Android's report gathers device, SoC, CPU clusters, free space, mount info, the
log tail, and hashes of the game files. iOS wants the same thing — it is how
any report from a stranger becomes actionable.

A lesson worth copying rather than re-learning: the Android version read
`Build.SOC_MANUFACTURER`, an API-31 field, under `minSdk 28`. It threw
`NoSuchFieldError` and killed the report on exactly the older devices whose
owners most needed to send one. **Whatever the iOS equivalent is, version-gate
the device-info calls and make sure the report survives a failure to gather any
single line.** Wrap each section, not the whole thing.

### Controller input that survives a focused text field

`app/src/main/java/com/nakas/skate3/Skate3Activity.kt`

SDL attaches its motion listener to the surface view only, so any other focused
view swallows stick events; the OS then converts stick X/Y to d-pad keys and the
right stick disappears. Android fixes it with an Activity-level
`onGenericMotionEvent` fallback that runs only after the view hierarchy declines
the event.

SDL's own handler also tests `event.getSource() == SOURCE_JOYSTICK` with exact
equality, which fails for a pad reporting a combined `JOYSTICK|GAMEPAD|DPAD`
mask. If the iOS SDL path has an equivalent exact-equality source test, it has
the same latent bug.

### Restart-after-settings

Android has a separate `:restart` process (`RestartActivity`) that outlives the
game process, watches for it to exit, then relaunches. iOS cannot do this at all
— see above. The iOS answer is already "save and quit"; just make sure the
wording never promises a restart that will not happen. There is a comment in
`simple_settings_overlay.cpp` explaining that naming it "Restart" on iOS
promised something that silently did not happen, which is why `kApplyActionName`
is split.

### Map pack installation

`app/src/main/java/com/nakas/skate3/MapPacks.kt`

Android installs a pack from a folder *or* a zip via the system document picker,
because packs ship zipped and hand-copying dies on EACCES under `Android/data`.
The header layout matters: the pack folder name must match the id inside the
`.header`, and the header goes in `Headers/00000002/<PKG>.header`, not beside
the `.big`. That layout knowledge is in `skate3_app_common.cpp` and is shared;
only the picker plumbing is Android-specific.

---

## 4. What does not apply: the GPU driver manager

**Skip this one.** It is Android-only and cannot be ported.

v0.1.22 added a Vulkan driver proxy that lets a player run a Mesa Turnip driver
instead of the Qualcomm blob, switchable from the launcher. It works because the
engine opens Vulkan by bare name at runtime (`dlopen("libvulkan.so")`), so
shipping our own `libvulkan.so` in `nativeLibraryDir` shadows the platform
loader, and `libadrenotools` sets up a linker namespace to load an arbitrary
driver.

None of that exists on iOS:

- There is one Vulkan implementation, MoltenVK, translating to Metal. There are
  no alternative drivers to choose between.
- The sandbox does not permit loading an unsigned dylib from app storage.
- The engine already special-cases iOS to link Vulkan symbols directly rather
  than `dlopen` them — see the `#if REX_PLATFORM_IOS` branch in
  `rexglue-sdk/src/ui/vulkan/vulkan_instance.cpp`, which assigns
  `ifn.vkGetInstanceProcAddr = &::vkGetInstanceProcAddr` instead of loading a
  loader at all. The hook the Android proxy relies on is not there.

The *diagnostic* half is worth stealing, though: Android's driver check builds a
surface-free Vulkan instance and reports `driverID`, `driverName`, `driverInfo`
and the device name before the game starts. An iOS equivalent reporting the
MoltenVK and Metal versions in the diagnostic report would be useful for the
same reason — several long bug hunts here turned out to be driver bugs, and
knowing the driver version up front would have shortened all of them.

Credit where due: the driver manager is Alan Constantino's work, from
[skate3-pocket](https://github.com/AlanConstantino/skate3-pocket).

---

## 5. Process notes that apply to both platforms

Learned the hard way on Android; all of it costs the same on iOS.

**Log at WARN for anything you want to see in a user's report.** Android ships
at `log_level=warn`, so every `REXLOG_INFO` describing the touch controls,
restarts or driver state was invisible in exactly the reports that were about
those things. This trap has been hit at least four separate times. Check what
level iOS ships at and make the same adjustment.

**Check for a stale argument-override file before measuring anything.**
`user/android_args.txt` (iOS: `ios_args/`) silently overrides launch arguments.
A forgotten `--log_level=debug` from an old experiment buried a whole debugging
session in GPU spam.

**A cvar default change does not reach existing players.** `settings.toml` is
applied over the compiled defaults, so anyone who has run the app before keeps
the old value. Changing a default needs a migration for existing files, and it
must be narrow enough to only fire on the provably-inert combination. See the
`picker_chord` migration in `skate3_app_common.cpp`.

**When you change a gate, grep for every caller.** The X shortcut advertised
itself in the footer while its key handler still tested the old predicate, so
the legend and the action disagreed for all but 0.4 seconds after an edit.

**Check the fault site before adopting a plausible diagnosis.** Both of the
biggest bugs in this project were initially misdiagnosed by finding a real
defect that was not the one causing the symptom. The Adreno uniform-layout bug
was genuine and fixed and was *not* the cause of the crashes attributed to it.

---

## 6. Suggested order

1. Merge `android` into `ios-port` in both the engine and `rexglue-sdk`. Build.
   Fix what breaks. Expect the platform gates to mostly already be right.
2. Verify the settings restart notice on a device, specifically the "Apply &
   Quit" path — it closes the app, so it is the highest-consequence button in
   the menu.
3. Verify the touch editor gear exit.
4. Verify the startup map-pack chooser with two packs installed, and confirm the
   in-game level picker does not permanently disable it.
5. Port the diagnostic report, with per-section failure isolation.
6. Consider a MoltenVK/Metal version line in that report.
