# Driver manager regression

This isolated Android test app compiles `DriverStore.kt` and `DriverBridge.kt`
verbatim from this repository. The 25 cases exercise real Android JSON, ZIP,
filesystem and preferences APIs, with no mock replacement of either component.
It adds no JUnit, Robolectric or AndroidX testing dependencies.

## Build

Use the same prerequisites as the main app: Java 17, Python 3, Android SDK
platform 35 and the NDK version pinned in `sources.lock.json`. Set `JAVA_HOME`
and `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) for your installation. An explicit
`ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` is also supported. The shell/NDK helper
supports macOS and Linux.

From the repository root:

```sh
bash tests/driver-manager/build-tests.sh
```

The root Gradle wrapper builds this separate test project. Pass Gradle options
after the script name. For a fully cached build with no fixture downloads:

```sh
DRIVER_TEST_OFFLINE=1 bash tests/driver-manager/build-tests.sh --offline
```

`prepare-fixtures.py` takes T30 straight from the copy this app ships at
`app/src/main/assets/drivers/turnip-t30.zip`, after checking that the pin in
`fixtures.lock.json` matches the `ZIP_HASH` constant `DriverBridge` enforces at
runtime - so the tests cannot drift from the file the app will actually load.
R7 is downloaded from the public URL in `fixtures.lock.json` with mandatory size
and SHA-256 verification, cached under `build/driver-tests/downloads`. Set
`DRIVER_TEST_CACHE_DIR` to use another cache. No driver ZIP is committed here.

A tiny ARM64 ELF is compiled from `fixture.c` using the NDK for structural
validation cases. It is not a usable Vulkan driver. The generator creates 54
ZIP fixtures, copies the two production Kotlin files verbatim, and records their
hashes using repository-relative paths. `verify-build.py` checks APK integrity,
packaged fixture hashes and source drift during the build.

Generated assets, source copies, APKs, build directories and reports are ignored
by Git. No game image, game native library, signing key or previous device log is
needed or included.

## Run on Android

The APK is `tests/driver-manager/app/build/outputs/apk/debug/app-debug.apk`.
Use Android 11 / API 30 or newer: the real T30 fixture requires API 30.
With one authorized device connected:

```sh
adb install -r tests/driver-manager/app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w com.nakas.skate3.drivertests/com.nakas.skate3.drivertests.DriverStoreInstrumentation
adb exec-out run-as com.nakas.skate3.drivertests cat files/driver-regression.json > tests/driver-manager/device-regression.json
adb uninstall com.nakas.skate3.drivertests
```

The harness uses only its own package/private storage. It does not launch the
game or access game data. One limit test temporarily writes about 128 MiB; the
256 MiB expanded-size bomb is a small compressed fixture and is rejected before
extraction. Check `completed: true`, `pass: true`, 25 cases and an empty
`failures` array in the resulting device report. The exact production-source
and fixture hashes are embedded in that report.

## Coverage and previous result

**These tests have not yet been run against this repository.** They were ported
from `AlanConstantino/skate3-pocket` along with the driver manager itself and
are unrun here; treat the paragraph below as that project's evidence, not ours.

That harness passed **25 cases and 428 assertions** on a Retroid Pocket 6
running Android 13 (API 33), against these source hashes:

- `DriverStore.kt`: `8b2fb178bee2b10c9ea5583e7c2aa2020b743d90425e4c25e78d1aa5ba9a74a9`
- `DriverBridge.kt`: `a1c9d5b853302de1033f77a595f873030da16e534dbd7e34743dc545bb19f4cf`

`DriverStore.kt` here is byte-identical to the first of those. `DriverBridge.kt`
is not: this app defaults to the system driver rather than T30, and one case
("Fresh selection initializes to the system driver") was changed to match. Use a
fresh device report to establish results for this build.

Coverage includes T30/System preference migration, unavailable selections,
interrupted-check status, real T30/R7 imports, deduplication, persisted choices,
selected/in-use removal guards, corrupt-install recovery, ZIP traversal/links/
duplicates/header/CRC checks, metadata/API/ELF compatibility, resource limits,
rollback and interrupted/zero-byte document reads.

No native library is loaded. One actual initialization attempt fails at an
intentionally absent proxy to test error persistence and restart guards; the
in-use guard is exercised through the store's public API. Persistence checks use
a new Android Context and committed preferences XML, not a reboot. Legacy
preferences are seeded in the test app. Successful Vulkan loading, file-picker
UI behavior, driver performance and gameplay require separate app checks.
