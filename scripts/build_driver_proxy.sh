#!/bin/bash
# Build the Vulkan driver proxy and the two libadrenotools hook libraries, and
# stage them where Gradle will package them.
#
# Minutes, not hours - this is a few hundred lines of C++, nothing like
# build_native.sh. Built out of band for the same reason libmain.so is: Gradle
# is only ever allowed to package native code here, never to produce it.
#
# usage: build_driver_proxy.sh [Release|Debug]
set -euo pipefail
. "$(dirname "$0")/env.sh"
CONFIG="${1:-Release}"
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
BUILD="$APP_DIR/build/driver-proxy"
STAGE="$BUILD/stage"
JNI="$APP_DIR/app/src/main/jniLibs/arm64-v8a"
LIBS="libvulkan.so libmain_hook.so libhook_impl.so"

[ -d "$ANDROID_NDK_HOME" ] || { echo "no NDK at $ANDROID_NDK_HOME - run scripts/setup_sdk.sh"; exit 1; }

echo "== configuring"
cmake -S "$APP_DIR/native" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE="$CONFIG" \
  -DCMAKE_INSTALL_PREFIX="$STAGE"

echo "== building"
cmake --build "$BUILD" --target vulkan main_hook hook_impl --parallel "${JOBS:-8}"
cmake --install "$BUILD"

echo "== checks"
# The engine finds the proxy by SONAME, so it has to be exactly libvulkan.so,
# and it has to export the entry points the engine resolves after dlopen.
"$NDK_BIN/llvm-nm" -D --defined-only "$STAGE/lib/arm64-v8a/libvulkan.so" \
  | grep -qE ' vkGetInstanceProcAddr$' || {
  echo "!! libvulkan.so does not export vkGetInstanceProcAddr"; exit 1; }
"$NDK_BIN/llvm-nm" -D --defined-only "$STAGE/lib/arm64-v8a/libvulkan.so" \
  | grep -qE ' Java_com_nakas_skate3_DriverBridge_nativeInit$' || {
  echo "!! libvulkan.so does not export the DriverBridge JNI entry point"; exit 1; }
# Nothing beyond the version script should be visible: the whole point is that
# this object shadows the platform loader without exporting anything else.
EXTRA=$("$NDK_BIN/llvm-nm" -D --defined-only "$STAGE/lib/arm64-v8a/libvulkan.so" \
  | awk '$NF ~ /^(vk|Java_com_nakas_skate3_DriverBridge_|JNI_OnLoad)/ {next} $2=="T" || $2=="W" {print $NF}')
[ -z "$EXTRA" ] || { echo "!! libvulkan.so exports more than exports.map allows:"; echo "$EXTRA"; exit 1; }
# Android 15+ devices ship 16 KB pages and refuse a 4 KB-aligned library.
for name in $LIBS; do
  "$NDK_BIN/llvm-readelf" -lW "$STAGE/lib/arm64-v8a/$name" | awk '$1=="LOAD"{print $NF}' | while read -r a; do
    [ $((a)) -ge 16384 ] || { echo "!! $name has a LOAD segment aligned to $a, below 16 KB"; exit 1; }
  done
done
echo "   exports and 16 KB alignment ok"

mkdir -p "$JNI"
for name in $LIBS; do
  cp "$STAGE/lib/arm64-v8a/$name" "$JNI/$name"
  # Keep the staged copy unstripped for ndk-stack.
  "$NDK_BIN/llvm-strip" --strip-debug "$JNI/$name"
  echo "== staged $(du -h "$JNI/$name" | cut -f1)	-> $JNI/$name"
done
echo "   unstripped copies kept under $STAGE/lib/arm64-v8a"
