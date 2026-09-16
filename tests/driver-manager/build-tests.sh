#!/usr/bin/env bash
set -euo pipefail
test_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$test_root/../.." && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory." >&2
  exit 1
fi
python3 "$test_root/prepare-fixtures.py"
bash "$repo_root/gradlew" -p "$test_root" --no-daemon --console=plain assembleDebug "$@"
python3 "$test_root/verify-build.py"
