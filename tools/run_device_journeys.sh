#!/usr/bin/env bash
set -euo pipefail

# Runs the same AndroidJUnitRunner suite as connectedDebugAndroidTest, retaining
# the installed app and screenshot evidence after the runner exits.
project_root="$(cd "$(dirname "$0")/.." && pwd)"
device_serial="${1:?usage: bash tools/run_device_journeys.sh device-serial [evidence-directory]}"
evidence_dir="${2:-$project_root/outputs/replay-verification}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
adb_bin="${sdk_root:?Set ANDROID_HOME or ANDROID_SDK_ROOT}/platform-tools/adb"
app_apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$project_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

[[ -x "$adb_bin" && -f "$app_apk" && -f "$test_apk" ]] || {
  echo "Build the debug app and Android test APK before running device journeys." >&2
  exit 2
}
mkdir -p "$evidence_dir"
"$adb_bin" -s "$device_serial" get-state
"$adb_bin" -s "$device_serial" install -r "$app_apk"
"$adb_bin" -s "$device_serial" install -r "$test_apk"
shasum -a 256 "$app_apk" "$test_apk" > "$evidence_dir/tested-apks.sha256"
set +e
"$adb_bin" -s "$device_serial" shell am instrument -w -r \
  dev.codex.libretroplatform.debug.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$evidence_dir/device-journeys.log"
instrument_status=${PIPESTATUS[0]}
set -e

# Pull even on assertion failure so layout evidence remains available. A missing
# screenshot directory must not hide the runner's actual test result.
"$adb_bin" -s "$device_serial" pull \
  /sdcard/Android/data/dev.codex.libretroplatform.debug/files/integration-evidence \
  "$evidence_dir/" || true
if [[ "$instrument_status" != 0 ]] ||
   grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed' "$evidence_dir/device-journeys.log" ||
   ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$evidence_dir/device-journeys.log"; then
  echo "Device journeys failed or did not report a complete passing suite." >&2
  exit 1
fi
echo "Device journeys passed; retained evidence at $evidence_dir"
