#!/usr/bin/env bash
# Device smoke test: catalogue wizard and installer integrity with in-process dummy providers.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the intended device}"
adb_bin="$ANDROID_HOME/platform-tools/adb"
output_dir="$project_dir/app/build/android-dev/catalog"
mkdir -p "$output_dir"
if [[ "${1:-}" != --skip-build ]]; then
    (cd "$project_dir" && ./gradlew :shared:jvmTest :app:testStableDebugUnitTest :app:assembleStableDebug :app:assembleStableDebugAndroidTest --max-workers=4 --console=plain) > "$output_dir/build-and-tests.log" 2>&1
fi
"$project_dir/tools/android-dev.sh" install
"$adb_bin" -s "$ANDROID_SERIAL" install -r "$project_dir/app/build/outputs/apk/androidTest/stable/debug/app-stable-debug-androidTest.apk"
"$adb_bin" -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" -s "$ANDROID_SERIAL" shell wm dismiss-keyguard
set +e
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class com.lagradost.cloudstream3.CatalogInstrumentedTest \
    com.lagradost.cloudstream3.debug.test/androidx.test.runner.AndroidJUnitRunner > "$output_dir/instrumentation.log" 2>&1
instrument_status=$?
set -e
printf '%s\n' "$instrument_status" > "$output_dir/adb-exit-status.txt"
cat "$output_dir/instrumentation.log"
if [[ "$instrument_status" != 0 && "$instrument_status" != 139 ]]; then exit "$instrument_status"; fi
rg -q '^OK \([0-9]+ tests?\)' "$output_dir/instrumentation.log"
if [[ "$instrument_status" == 139 ]]; then
    "$adb_bin" -s "$ANDROID_SERIAL" logcat -b crash -d -t 100 > "$output_dir/device-shell-crash.log"
fi
for evidence in 01-catalogue-menu.png 02-configure-catalogue.png 03-film-search.png report.json; do
    "$adb_bin" -s "$ANDROID_SERIAL" exec-out run-as com.lagradost.cloudstream3.debug \
        cat "cache/catalog-fixture/$evidence" > "$output_dir/$evidence"
done
printf 'Device evidence: %s\n' "$output_dir"
