#!/usr/bin/env bash
# Real history/home/navigation check with temporary local profiles and in-process providers.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the intended device}"
adb_bin="$ANDROID_HOME/platform-tools/adb"
output_dir="$project_dir/app/build/android-dev/personalized-home"
mkdir -p "$output_dir"
(cd "$project_dir" && ./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug :app:assembleStableDebugAndroidTest --max-workers=4 --console=plain) > "$output_dir/build-and-tests.log" 2>&1
"$project_dir/tools/android-dev.sh" install
"$adb_bin" -s "$ANDROID_SERIAL" install -r "$project_dir/app/build/outputs/apk/androidTest/stable/debug/app-stable-debug-androidTest.apk"
set +e
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class com.lagradost.cloudstream3.PersonalizedHomeInstrumentedTest \
    com.lagradost.cloudstream3.debug.test/androidx.test.runner.AndroidJUnitRunner > "$output_dir/instrumentation.log" 2>&1
instrument_status=$?
set -e
printf '%s\n' "$instrument_status" > "$output_dir/adb-exit-status.txt"
cat "$output_dir/instrumentation.log"
# NVIDIA's shell app_process can exit 139 after JUnit OK; always reject failed tests.
if [[ "$instrument_status" != 0 && "$instrument_status" != 139 ]]; then exit "$instrument_status"; fi
rg -q '^OK \([0-9]+ tests?\)' "$output_dir/instrumentation.log"
if [[ "$instrument_status" == 139 ]]; then
    "$adb_bin" -s "$ANDROID_SERIAL" logcat -b crash -d -t 100 > "$output_dir/device-shell-crash.log"
    echo 'Device shell exited 139 after JUnit success; see device-shell-crash.log.' >&2
fi
for evidence in 01-for-you.png 02-common-detail.png 03-empty-profile.png 04-for-you-series.png report.json; do
    "$adb_bin" -s "$ANDROID_SERIAL" exec-out run-as com.lagradost.cloudstream3.debug \
        cat "cache/personalized-home-fixture/$evidence" > "$output_dir/$evidence"
done
printf 'Device evidence: %s\n' "$output_dir"
