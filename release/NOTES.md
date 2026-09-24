Skate 3 running as native ARM64 code on Android. This is not an emulator: the
Xbox 360 executable was translated ahead of time into C++ and compiled for the
phone, so the skating, physics and career mode are the retail game's own code
running natively. During gameplay the Xbox GPU is not emulated either, a native
renderer reads the game's scene state and draws it through Vulkan directly.

**No game content is included and none is bundled in the APK.** You supply your
own Skate 3 Xbox 360 disc image.

## What you need

- An arm64 Android phone, Android 9 or newer, with Vulkan. Built and measured on
  a Galaxy S23 FE.
- Your own Skate 3 Xbox 360 disc image, on the phone or a USB drive.
- About 7 GB free. Roughly 6 GB is extracted from the image and stays on the phone.

## Setting it up

1. Install the APK and open **Skate 3 Android**.
2. Choose **Install from a disc image** and pick your image. It is read where it
   lies and extracted, which takes a while.
3. Choose **Download title update**. The game cannot boot without Title Update 3
   and it is not shipped here. You can also pick your own copy of the package
   instead. Either way the engine verifies both patch payloads by hash and
   refuses anything that does not match.
4. Press **Play**.

## Controls

On-screen controls appear when no controller is attached and hide themselves
when one is. Bluetooth and USB controllers work through SDL.

Any button can be moved. **Controls -> Button Mapping** in the settings menu
lists every controller button; highlight one, press the button you want it on,
and it is bound. **Button Layout** has ready-made sets, including the A/B and
X/Y swap that Nintendo-style pads need.

## Tuning

The engine reads `files/user/android_args.txt` at startup, one setting per line.
The `android_args/` folder in the repository has profiles to start from, and
its README explains which of the iOS settings deliberately did not carry over.
`diagnostics.txt` turns on the frame pacing lines, and `scripts/perf.sh` reads
them back.

Defaults are inherited from a 4 GB iPhone and are conservative for a recent
phone. `quality.txt` spends that headroom on shadows, ambient occlusion and
antialiasing. Change one setting at a time.

## New in this release

**0.2.0 — remap any button, and a frame cap that actually held.**

### Remap any button

Every controller button can now be moved. Open the settings menu, go to
**Controls**, and the **Button Mapping** group at the bottom lists each button
on the pad. Highlight one, press the button you want it to be, and it is bound
— no typing, no config file. The row counts down while it waits, so it can
never sit there stuck.

Binding a button that is already used **swaps the two**, rather than leaving
the old one doing nothing. That is deliberate: it means the mapping is always a
complete set and no button can end up unreachable, which is what makes it safe
to experiment with.

**Button Layout** above the list applies a whole set at once. **Nintendo (A/B,
X/Y swapped)** is the one most people want: Switch-style and 8BitDo pads report
those four buttons transposed from an Xbox pad, so the game has always read B
when you pressed A. **Swap Bumpers & Triggers** moves the grabs onto the
shoulder buttons, which helps on a pad with worn triggers. **Reset All Buttons**
puts everything back.

Three things are deliberately left alone by a remap, so a mapping you regret
can always be undone: the shortcut that opens this menu always watches the
physical buttons, menu navigation itself is never remapped, and the on-screen
touch controls keep meaning what their labels say.

The stick options that were already there — Swap Sticks, Invert Camera Y, and
the deadzones — are unchanged and sit just above the new group.

### The frame cap was not being applied

The cap has a companion setting, `skate3_guest_fps_cap_auto`, and the code that
protects a player's saved choices matched setting names by substring — so
touching the auto row threw away the cap itself. The game then rendered as fast
as it could into a 60 Hz screen and threw away roughly half of that work as
heat. If your phone ran hot, or held 60 for a few minutes and then settled
lower, this is very likely why.

### Backgrounding the game no longer loses the session

Leaving the game and coming back has been a coin flip. The reason is that the
moment the activity stops, the process becomes a cached app, and Android picks
cached victims by size — this one is reliably the largest thing on the device
(measured at 1358 MB on a Galaxy S23 FE). It was picked first, every time, and
you came back to the launcher with no crash and nothing to report.

The game now runs a foreground service while a session is live, which moves it
out of that bucket, so backgrounding and returning is an ordinary resume. That
is why this build asks for the notification permission and shows one ongoing
"Game session" entry while you are playing — that notification *is* the
mechanism, not an advert, and nothing else is ever posted. It stops when you
close the game.

It is not immunity. A device genuinely out of memory still reclaims the
process, and this does nothing about the ~1.2 GB of GPU memory a session holds.
It stops the game being chosen first purely for being large and in the
background.

Adapted from darchap's Skate3-Port, which solved this before we did.

### "The frame rate is fine but it feels like slow motion"

If you have ever felt the game go syrupy while the counter still read a healthy
number, this was real and it is fixed.

Skate 3 advances exactly one refresh period of simulation per rendered frame,
however long that frame actually took. So its speed is period divided by frame
time: a 33 ms frame runs the game at half speed. It is not a dropped-frame
look — the simulation genuinely advances slower.

A clamp for this already existed, but it was compiled in only for one platform,
so on Android it was never active. It is now. It stays a ceiling rather than
being removed entirely, on purpose: uncapped, a three-second loading stall would
advance three seconds of physics in a single step and put your skater through
the world.

### A lot more to turn down

This build carries a batch of performance settings that were finished but never
released. On a phone this port runs out of CPU long before it runs out of fill
rate, so most of these cut work rather than pixels:

- **Pedestrians & Traffic**, **Movable Props** and **Other Skaters** stop those
  populations at the spawn instead of hiding them at the draw — an entity that
  is never created costs no collision, no audio, no hair and no update slot,
  which is the larger half of what a crowd costs here. These need a restart,
  because the spawners run while the world streams in.
- **3D Scene Resolution** renders the world at a fraction of the output and
  scales it up, leaving the HUD sharp.
- **NPC Update Rate** and **World Update Rate** run those systems every second,
  third or fourth frame.
- **Vegetation**, **Hair Detail** and **Water Effects** cuts.
- **Draw Distance** now goes down to an eighth, where it used to stop at half.
- **Frame Rate Cap** gains 40, 45 and 50. On a 120 Hz screen 40 divides evenly,
  and on 90 Hz so does 45, so each frame lands on a refresh instead of beating
  against one — which, with the fix above, means a lower cap plays at the right
  speed rather than in slow motion.
- **Draw Batching**, which trades a few extra triangles for fewer draw calls.
  It is a trade, not a free win, which is why it is off by default.

The crowd cuts are ported from darchap's Skate3-Port, which did them first.

The app is also aligned for 16 KB memory pages, which Android 15 and newer
devices require.

### Also in this build

- One fewer full-screen render pass per frame is available on Adreno
  (off by default while it is measured).
- The per-packet GPU timer that shipped switched **on** in every build to date
  is now off unless asked for. It was doing about 1.7 million clock reads a
  second on the busiest thread.
- The FPS counter can now show the **1% low and the p95/p99 frame times**
  (Video → FPS Percentiles). An average hides exactly the stutters that make a
  game feel bad: a steady 60 and a 60 that drops four frames a second read the
  same as an average and nothing like each other to play. These are the numbers
  worth quoting in a bug report.
- The developer benchmark that briefly sat beside those rows is gone. It was a
  measurement tool, it reported only to the log, and it did not belong in a
  player's settings menu.

Everything from 0.1.26 and 0.1.25 below is also in this build.

**0.1.26 — the first-run setup screen that could come up empty.**

On a fresh install there was a chance of reaching "Select a difficulty level"
with the list of options missing — the panel, the banner and the question all
drawn, and nothing to choose. There was no way forward except closing the game
and starting over, and a second attempt usually worked, which is what made it
so hard to pin down.

It only ever happened on a first launch, because a first launch is the only
time the game has no compiled shaders yet. While a piece of the screen is still
being prepared, drawing it is skipped — normally invisible, because the next
frame draws it again. The setup screens are drawn once and kept, so anything
skipped there was simply gone.

The game now waits for those pieces to finish during first-run setup instead of
skipping them. The wait only happens on the very first launch and only on
screens where it is not noticeable.

**0.1.25 — custom GPU drivers that actually load, and a launcher that stops
crying wolf.**

If you imported a GPU driver and the game behaved exactly as if you hadn't, it
wasn't your driver and it wasn't your phone. A driver taken from another device
is named `vulkan.adreno.so` — the same name Android has already loaded for its
own UI — so the system handed the game back the driver it already had. Imported
drivers are now renamed as they are installed, so yours is the one that loads.
That is why only Turnip ever appeared to work: Turnip happens to use a name of
its own. Drivers extracted from other devices, Qualcomm's own included, now
work too.

Picking a driver your phone cannot run used to leave the game unable to start,
with a message telling you to reinstall — which never helped, because the
driver selection survives a reinstall. It now recovers and says why.

The launcher also stopped reporting every abnormal exit as a harmless
out-of-memory kill, which had been hiding real crashes.

**0.1.23 — the settings menu tells you when a restart is owed.**

Resolution, MSAA, shadows, aspect ratio, language and audio buffer size are
only read when the game starts, so changing one and leaving the menu does
nothing visible until it restarts. There was an Apply row that said so, but
nothing stood in the way of making a change and simply backing out — which is
what most people do, and they reasonably concluded the setting was broken.

Leaving the menu with one outstanding now says so, whichever way you leave:
Back, the chord, or the gear. Declining closes the menu anyway, because
leaving is what you asked for, and it only asks once per pending restart rather
than every single time.

Everything from 0.1.22 below is also in this build.

**0.1.22 — choose your GPU driver, and choose your map again.**

*You can run a different GPU driver.* Nearly every hard bug in this port has
turned out to be a bug in the phone's Vulkan driver rather than in the game:
descriptor set limits, an illegal uniform layout, an Adreno refusing a shadow
map three times wider than it allows, a driver claiming Vulkan 1.1 on hardware
that plainly is newer. Until now the driver was the one thing a player could
not change, so "is this the driver?" could only be argued about. Open **GPU
driver** on the launcher, pick **Turnip T30**, and **Apply and restart**.
Turnip is the open-source Adreno driver; MrPurple's T30 build ships in the app,
and other Android ARM64 Turnip packages can be imported as ZIPs without
installing another APK. **Check selected driver** loads it and reports the GPU
and driver it actually got, without needing the game or any game files.

The default is still your phone's own driver, so nothing changes unless you go
looking. If a driver fails it says so and stays on the one you had — it never
silently falls back. Whether Turnip helps or hurts depends on the device, and
this release does not claim it is faster; it makes the question answerable.

*You can choose which map pack to load again.* With more than one pack
installed the game asks which to load at startup — except that using the
in-game level picker once turned that screen off permanently. Picking a map
there has to write the choice down so it survives the relaunch that applies it,
and the startup screen only appeared when nothing was written. So it removed
itself the first time it was used, with no way back except editing a file under
Android/data that no file manager will open. Reported as not being able to
choose a level at startup, which was exactly right. The chooser is back, there
is a row on the System page to turn it off, and it still only ever appears when
two or more packs are installed.

*The on-screen control editor has a second way out.* Arranging the controls
takes the pad away on purpose — a finger moves a button rather than pressing it
— which also meant Start, Back and every chord were dead, and the gear was just
another thing to drag. The Done button was the only way back to the game, so a
player whose panel did not appear had nothing left but to force-close the app,
and one did. Tapping the gear now leaves the editor too, saving as it goes.
The settings row that opens it also no longer tells you to pinch to resize, or
to reopen the menu to finish; neither of those has worked since the editor was
redesigned.

*The diagnostic report works on Android 9, 10 and 11.* It read a field that
only exists on Android 12 and newer, and died while being written on anything
older — on the one screen a player uses to tell us something is wrong.

*The right stick keeps working while a text field has focus.* Typing a name
could leave the stick dead, or turn it into d-pad presses.

*Smaller download.* 38 MB rather than 91 MB, because the engine is now
compressed inside the APK. It is unpacked at install time instead, so
installing takes longer and the installed size grows by roughly the size of the
engine — that trade is what lets a custom driver be loaded at all.

**0.1.21 — the reported bugs, and controls you can move.**

*Title Update 3 now installs on a phone that could not take it.* A Samsung M53
could not get it in by any route. Picking the file yourself died the instant it
was chosen — the launcher hands the engine a file descriptor rather than a
path, which is right for a seven-gigabyte disc image and wrong for this, whose
reader opens the path again and fails a permission check it cannot pass. The
package is 1.7 MB, so it is simply copied in now. Downloading it failed with
502 Bad Gateway on eight attempts across four sessions, from a site that is not
this project's and does go down; the download retries four times with backoff
instead of twice, says so plainly when the site is the problem, and offers a
copy you have already put in the folder rather than fetching it again.

*The settings menu applies what you change.* Choosing something that needs a
restart put a prompt on screen for four tenths of a second and then took it
away again — the menu saved your choice, decided nothing was outstanding, and
greyed out the button for applying it. Reported as a quarter-second window to
hit Apply & Restart, which was almost exactly right. The menu now remembers
that a restart is owed until you take it, and Revert goes back to what the
session started with rather than to the change it just saved.

*Audio Buffer Size can be changed.* It was written to the settings file and
then overwritten by the launch arguments at every start, so the row snapped
back and looked broken. It is the player's setting now, like the graphics rows
already were.

*One shadow setting instead of two.* "Shadow Quality" and "Enhanced Shadow
Resolution" were two halves of the same decision, and the pairing that looks
right was not discoverable by hand. They are one row of matched steps, and the
steps a device cannot actually build are no longer offered — which is what went
wrong at the top setting: it asked for a shadow map three times wider than the
hardware allows, and the shadows disappeared entirely with nothing said. It is
clamped to what the device reports now, and the log says what was asked for and
what was granted.

*The Performance page stopped repeating the Video page.* Ten settings appeared
on both. It keeps the three that are worth turning while watching a frame time
and points at Video for the rest.

*The Level Picker works.* Its shortcut was the Guide button, which is delivered
only when a setting nothing turns on is turned on, so it had never once opened
for anybody. It is LB + Select now, it lists the map packs you have installed,
and choosing one restarts the game on it.

*The on-screen controls can be moved.* Drag any control where you want it, or
pinch it to resize; the arrangement is remembered. There is also an opacity
setting. Six of the buttons — Start, Back and the d-pad — were drawing as
question marks, because their symbols were characters the font does not have.
They are drawn as shapes now.

*Switching away from the game.* Returning from the task switcher could leave
the game painting but not playing: the event that reattaches the display is not
reliably delivered, and a resume that arrived the other way left it unbound for
good. Both events reattach now. Quitting is bounded at six seconds rather than
taking as long as it takes, which also stops the relaunch after Apply & Restart
arriving while the old process is still tearing down — the other half of that
crash. And when Android kills the game in the background for its memory, which
it will, the launcher now says so instead of leaving you to guess whether it
crashed.

**0.1.19 — the handhelds that never loaded the world now do.** Three devices
built on the same Qualcomm chip, an AYN Thor, a Retroid Pocket 6 and an AYN
Odin2 Portal, had never once reached the skatepark on any build. They sat on a
menu forever. The cause turned out to have nothing to do with those devices'
graphics or audio, and everything to do with how the game's title update is
applied.

Applying that update means shifting blocks of the game's code and data to new
positions in memory. Ninety-two of those shifts move a block onto a region that
overlaps where it already sits. The code used a copy that is only correct when
the two regions do not touch, and Android chooses its copying routine to suit
the processor, so the same app assembled a different executable depending on the
phone. On these three handhelds one of the scrambled blocks held the table the
game uses to find its audio decoders, so audio setup failed and the world never
loaded. The rest of the scrambled blocks are where the crashes and the strange
buffer sizes in earlier reports came from. One cause, not four.

The copy now handles overlap correctly. This is verified in both directions: it
repairs the broken devices, and on a phone that already worked the assembled
executable is byte-for-byte what it was before. The Retroid Pocket 6 and the AYN
Thor have both confirmed the game running.

The loader also logs a fingerprint of the executable after it is unpacked and
again after the update is applied, so a diagnostic report now says outright
whether that phone assembled the game correctly.

Two other fixes ride along. Reads from the game folder are no longer allowed to
stop early, which they may legitimately do on some Android storage; a partial
read used to be handed to the game as if it were complete. And choosing a
skater's name could crash, because the on-screen keyboard could be dismissed
before the engine had finished connecting it to the game's request.

**0.1.18 — the static-image write watch, robust file reads, and the name-prompt
crash.** Three QCS8550 handhelds (AYN Thor, Retroid Pocket 6, AYN Odin2 Portal)
never load the world: a table of codec tags in the game's static data is found
overwritten with English text about ten seconds after the executable loads, on
every run, on every one of those devices, while every file they read is
byte-identical to a working phone's. The text exists in no game file and in no
code, nothing in the recompiled game stores to that address, and the engine
never checked the image after loading it. This build does three things about it.

- *The image is checked and guarded.* Right after the executable and its title
  update are loaded, the engine keeps a copy of the image's read-only data,
  logs one hash of it, and makes those pages actually read-only. The first
  write into any of them is caught by the engine's own fault handler, recorded
  with the writer's address and the game's call chain, and then allowed
  through. Every thirty seconds the live image is compared against the copy and
  any difference is logged in full. When the codec table is found damaged, the
  whole neighbourhood is put back from the copy, not just the two tags. All of
  this costs nothing while nothing writes.
- *File reads are looped.* A read from the game folder used to be a single
  system call whose result was passed to the game as-is. On a folder served
  through Android's FUSE layer a read can legitimately come back short or be
  interrupted by a signal, and the game, written for a console where that never
  happened, would parse zeros. Reads now continue until they are complete, and
  any short or interrupted read is logged with the file and offset. The
  diagnostic report also says which filesystem serves the game folder.
- *Choosing a name no longer crashes.* A Retroid Pocket 6 that got past the
  frontend crashed the moment the game asked for the skater's name. The on-screen
  keyboard dialog could be dismissed before the engine had finished wiring it up
  to the game's request, and the engine then wrote into a dialog that no longer
  existed. Dialogs are now built and wired in one step on the thread that draws
  them.

Also fixed: loading the game's web module three minutes into a session erased
the engine's own bookkeeping for the main executable's memory. If a report from
0.1.18 still shows the table damaged, it now also names what wrote it.

**Mali GPUs are no longer turned away.** A Galaxy S20 FE reported the app
opening and closing again with no message, which read as a crash. It was not a
crash: the engine was refusing the GPU. An Arm Mali-G77 reports no
`vertexPipelineStoresAndAtomics`, and the device was rejected before anything
was drawn.

That requirement was never as hard as the check enforcing it. The emulated GPU
path already handles the feature being missing - it routes vertex memexport
through compute shaders, which is what that path is for - and the only thing it
cannot serve is a draw that exports from a vertex shader. The device is
accepted now, and a draw that genuinely needs it fails where it happens instead
of at startup. On a GPU that has the feature nothing changes at all, because
the check passes there regardless.

This is the third time a requirement belonging to the emulated pipeline this
build replaces has turned away hardware that could otherwise run: geometry
shaders and non-solid fill were the first two.

If you are on a Mali device, this is worth trying - but it has only been proven
not to break the GPUs that already worked. Whether Skate 3 runs to the end of a
session on Mali is untested, and a report either way is useful.


**Devices that could not create their own storage folder now work.** A tester
on LineageOS could open the file picker and select a disc image, and nothing
ever arrived - the launcher was quietly showing "free space unknown", which is
what this app prints when the folder it extracts into does not exist and could
not be created. `Android/data` is restricted ground on recent Android versions.
When that happens the game is kept in the app's own private storage instead,
which always works. The setup screen says so, and says the trade-off: a file
manager cannot see it, and uninstalling takes the game with it. If the folder
works, nothing changes - and whichever location already holds an install is
always preferred, so an existing 6 GB copy is never orphaned.


**Settings you change now survive the session.** Every graphics row was being
put back at the next launch - resolution, V-Sync, antialiasing, shadows,
ambient occlusion, bloom, sun shafts, draw distance, the frame cap and the
texture budget. The tuned values this build ships are applied first and the
menu writes over them, rather than the other way round, so what you pick is
what you get next time.


**The app no longer dies when the file picker opens.** Two people reported it
closing at the exact moment the picker appeared, on LineageOS and on stock
Samsung, and it was neither of those: it was a deadlock in this app. The
engine's installer asked the game activity to show the picker and waited for an
answer, on the one thread SDL runs the whole game on. Opening the picker pauses
that activity and Android destroys its rendering surface - and the event that
releases the surface is handled on the same thread that was sitting there
waiting. The renderer went on using a window Android had already freed.

Files are chosen on the launcher screen now, before the engine starts, where
there is no surface to lose and no thread to block. There is also a button to
pick your own title update file, which went through the same broken path.

**"Save a diagnostic report".** If something goes wrong, this gathers what is
needed to work out why into one file you can attach to a message: the build
version, the ROM fingerprint, the SoC, ABIs, per-core clocks, RAM, what is in
the game folder, free space, the engine's log and its crash report, and the
system log - which survives the crashed process, so a report gathered after a
crash contains the crash. All of that normally lives under `Android/data`,
which recent Android versions will not let a file manager open, so there was
previously no way for anyone to hand it over.

**The game's threads are placed on the fast CPU cores.** Phones split their
cores into clusters at different clock ceilings, and the threads the frame
depends on were landing wherever the scheduler happened to put them. The
frame-critical threads now go to the faster cores and streaming and decoding go
to the slower ones, out of the frame's way. The cluster layout is read from the
device at startup rather than assumed, because it varies: a Galaxy S23 FE has
three clusters - four cores at 1.79 GHz, three at 2.50 and a single 2.99 GHz
core - and an earlier version of this that assumed the usual two put every
frame-critical thread on that one prime core and left the 2.5 GHz cores idle.
Measured after the fix on that phone: a locked 60.1 fps in gameplay, 16.66 ms
at the 95th percentile, no frame over 20 ms.

**A profile for devices under 4 GB of RAM**, selected automatically from the
memory the device reports, so nothing larger is affected. It drops two mip
levels from the biggest textures rather than one - sixteen times fewer bytes,
and sixteen times less CPU spent expanding them on GPUs that cannot sample the
compressed format at all - halves the draw distance, lets the texture and mesh
stores go below the old 256 MB floor, bounds a lock spin that previously
hammered a single cache line up to 65,280 times without yielding, and builds
the guest's static-world draw packets every other frame. That last one is safe
because the native renderer already discards them; they were simply the guest
render thread's largest per-item cost.

This is aimed at low-end phones and tablets generally. On the worst case to
hand - a Galaxy Tab A7 Lite, eight in-order Cortex-A53s and 2.9 GB of RAM - it
reaches gameplay but is still slow, and the honest summary is that the
remaining limit there is the emulated game code itself.

## Fixed since the first builds

Three separate reasons the app closed or refused to install, all found by
people testing on hardware the author does not own.

**It was built for one phone's CPU.** The recompiled game code carried ARMv8.3
instructions, which fault on anything older - a Cortex-A78, A76 or A55, which
is most Android hardware. The app closed the instant guest code ran, which
looked like the setup buttons failing because each one starts the game. It now
targets the common ARMv8 baseline and picks its atomics at run time, so recent
phones keep the fast path and older ones still work.

**It demanded GPU features it does not use.** Geometry shaders were required
by the emulated pipeline this build replaces. Adreno has them; PowerVR and
many Mali parts do not, and those devices exited during graphics setup.

**The title update could not be downloaded from inside the installer.** The
engine's wizard shelled out to curl, which Android does not ship, so it failed
on every device and reported it as a connection problem. It now downloads
through the app.

**It could not open a disc image from a USB drive.** The file picker returns an
open descriptor; naming it by path and re-opening it fails for anything under
system-only storage. It now reads the descriptor directly.

Also fixed: the app's own folder is created wherever it is needed rather than
only when starting the game, which is what made the title update download fail
with a missing-file error and free space read as 0.0 GB.

## Low-memory devices

`android_args/tiny.txt` in the repository puts every memory and distance
setting at its floor, for devices with around 3 GB of RAM. It quarters the
memory the largest textures take, quarters how much of the world is drawn, and
turns off every effect that carries its own full-screen target.

It cannot lower the 3D scene's resolution: the render scales only multiply
upward from 1, so there is no fractional setting to give. And it cannot help a
device whose CPU is the limit - the emulated game code is what costs the frame,
and no setting reduces how much the game simulates.

## Known rough edges

- Whether Turnip is better than your phone's own driver is untested and varies
  by device. If a driver misbehaves, reopen the launcher and apply another; a
  driver is only ever loaded after the app restarts, so the launcher is always
  reachable.
- The GPU driver manager has been verified on a Galaxy S23 FE and, by the fork
  it came from, on a Retroid Pocket 6. Other devices are unproven.
- The runtime logs a thread priority permission denial at startup. Android
  refuses the real-time scheduler to apps; it is harmless.
- It looks for a controller mapping database in a system path that does not
  exist on Android, and says so once.
- Custom map packs work. Drop a pack folder, the data file and its header
  together, into the app's own folder alongside `game`. Confirmed on a phone
  with two packs installed.
- Suspending and resuming has been reported as freezing on at least one device.
  It has not reproduced here across repeated background and resume cycles, so
  if it happens to you the details are worth reporting.

## Building it yourself

`scripts/build_native.sh` builds the native library from the engine tree,
`scripts/build_driver_proxy.sh` builds the Vulkan driver proxy (seconds, and
only when `native/` changes), then `scripts/build_apk.sh` packages both. The
native build takes hours and needs your own game files, since the recompiler
consumes them. See the repository README.

## Credits

This port is the top of a stack of other people's work, and almost none of the
hard parts started here.

**The recompilation.** Skate 3 runs as native code because of
**Alex McHugh's** [Skate 3 recompilation](https://github.com/mchughalex/skate3recomp),
which turns the Xbox 360 executable into C++ ahead of time. That in turn is
built on the [**ReXGlue SDK**](https://github.com/rexglue/rexglue-sdk), the
Xbox 360 recompilation runtime and toolkit, which is itself derived from the
**Xenia** project's years of Xbox 360 research (Ben Vanik and contributors).
**portingpete** did early Skate 3 bring-up on ReXGlue in
[skate3-recomp](https://github.com/portingpete/skate3-recomp).

Without those four, there is no game to put on a phone.

**Getting it onto Android.** **Buku313**
([Skate3-Mobile](https://github.com/Buku313/Skate3-Mobile)) and **darchap**
([Skate3-Port](https://github.com/darchap/Skate3-Port)) have both been working
on Skate 3 on ARM64, and this build is better for it. Two things in this
release come straight from darchap's port: stopping ambient crowds and props at
the spawn rather than hiding them at the draw, and the foreground service that
keeps a backgrounded session from being killed.

**The GPU driver manager** — the Vulkan driver proxy, the driver importer and
its verification, and the selection UI — is **Alan Constantino's** work, from
[skate3-pocket](https://github.com/AlanConstantino/skate3-pocket), his
handheld-focused fork of this app where it was written and tested on a Retroid
Pocket 6. It is adopted here with the default left on the device's own driver.
`native/driver_proxy.cpp` is kept byte-identical to his so that changes on
either side stay readable as a diff.

**Custom maps.** The Skate 3 custom-map scene is **SunJaycy's** and
**Ethan's** work — SunJaycy's
[sk83.GLB2ARENA](https://github.com/SunJaycy/sk83.GLB2ARENA) and
sk83.LevelCompiler for getting custom models and levels into the game, and
Ethan's [Dumbads Skate 3 Modding Tools](https://github.com/Ethanw05/DumbadsSkate3ModdingTools),
the arena builder behind the custom maps people actually skate. The map pack
support in this app exists to load what they made possible.

**Driver loading** rests on **Billy Laws'**
[libadrenotools](https://github.com/bylaws/libadrenotools) and
liblinkernsbypass, and the drivers themselves on **Mesa/Turnip** and the
**MrPurple** builds. Full licences ship in the app under **Driver licences**.

No game content is included, and none ever will be — you bring your own disc.
