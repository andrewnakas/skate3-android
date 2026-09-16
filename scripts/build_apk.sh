#!/bin/bash
# Package the APK. Seconds, not hours: the native library is already built.
set -euo pipefail
. "$(dirname "$0")/env.sh"
cd "$APP_DIR"
[ -f app/src/main/jniLibs/arm64-v8a/libmain.so ] || {
  echo "no libmain.so staged - run scripts/build_native.sh first"; exit 1; }
# Without these the APK builds happily and then refuses to load any driver but
# the system one, from inside SDL's startup handler, which is a poor place to
# discover a missing build step.
for lib in libvulkan.so libmain_hook.so libhook_impl.so; do
  [ -f "app/src/main/jniLibs/arm64-v8a/$lib" ] || {
    echo "no $lib staged - run scripts/build_driver_proxy.sh first"; exit 1; }
done
./gradlew "${1:-assembleRelease}"
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
