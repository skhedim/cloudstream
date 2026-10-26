#!/usr/bin/env bash
# Reproducible device check with generated local video, never a third-party media source.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the intended device}"
adb_bin="$ANDROID_HOME/platform-tools/adb"
fixture_dir="$project_dir/app/build/android-dev/milestone4/fixtures"
ffmpeg_bin="${CLOUDSTREAM_FFMPEG:-$HOME/.local/bin/cloudstream-ffmpeg}"
if [[ ! -x "$ffmpeg_bin" ]]; then ffmpeg_bin="$(command -v ffmpeg || true)"; fi
if [[ -z "$ffmpeg_bin" || ! -x "$ffmpeg_bin" ]]; then
    echo 'Set CLOUDSTREAM_FFMPEG to an FFmpeg executable with libx264, AAC and subtitles support.' >&2
    exit 1
fi
mkdir -p "$fixture_dir"
cat > "$fixture_dir/subtitles.srt" <<'SUBTITLES'
1
00:00:00,000 --> 00:01:00,000
Version originale : sous-titres de test en français.
SUBTITLES
for variant in vf720 vf1080 vost1080; do
    size=1920x1080
    color=blue
    extra_filters=()
    if [[ "$variant" == vf720 ]]; then size=1280x720; fi
    if [[ "$variant" == vost1080 ]]; then
        color=darkred
        extra_filters=(-vf "subtitles=$fixture_dir/subtitles.srt:force_style='FontSize=30'")
    fi
    "$ffmpeg_bin" -hide_banner -loglevel error -y -f lavfi -i "color=c=$color:s=$size:r=24" \
        -f lavfi -i sine=frequency=440:sample_rate=44100 -t 60 "${extra_filters[@]}" \
        -c:v libx264 -preset ultrafast -crf 28 -pix_fmt yuv420p -c:a aac -movflags +faststart \
        "$fixture_dir/$variant.mp4"
done
(cd "$project_dir" && ./gradlew :app:assembleStableDebug :app:assembleStableDebugAndroidTest --max-workers=4 --console=plain)
"$project_dir/tools/android-dev.sh" install
"$adb_bin" -s "$ANDROID_SERIAL" install -r "$project_dir/app/build/outputs/apk/androidTest/stable/debug/app-stable-debug-androidTest.apk"
remote_dir='/sdcard/Android/data/com.lagradost.cloudstream3.debug/files/canonical-fixtures'
"$adb_bin" -s "$ANDROID_SERIAL" shell mkdir -p "$remote_dir"
for variant in vf720 vf1080 vost1080; do
    "$adb_bin" -s "$ANDROID_SERIAL" push "$fixture_dir/$variant.mp4" "$remote_dir/"
done
python3 "$project_dir/tools/canonical-fixture-server.py" --directory "$fixture_dir" --port 18765 > "$fixture_dir/server.log" 2>&1 &
fixture_server_pid=$!
trap 'kill "$fixture_server_pid" 2>/dev/null || true; "$adb_bin" -s "$ANDROID_SERIAL" reverse --remove tcp:18765 >/dev/null 2>&1 || true' EXIT
"$adb_bin" -s "$ANDROID_SERIAL" reverse tcp:18765 tcp:18765
"$adb_bin" -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
output_dir="$project_dir/app/build/android-dev/milestone4/native-fixture"
mkdir -p "$output_dir"
set +e
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class com.lagradost.cloudstream3.CanonicalPlaybackInstrumentedTest \
    com.lagradost.cloudstream3.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$output_dir/instrumentation.log"
command_status=("${PIPESTATUS[@]}")
set -e
printf '%s\n' "${command_status[0]}" > "$output_dir/adb-exit-status.txt"
if [[ "${command_status[1]}" != 0 ]]; then exit "${command_status[1]}"; fi
# This Shield's shell app_process can exit 139 in NVIDIA's EGL destructor, after
# the runner reports success. Keep that status visible; never accept failed tests.
if [[ "${command_status[0]}" != 0 && "${command_status[0]}" != 139 ]]; then
    exit "${command_status[0]}"
fi
rg -q '^OK \([0-9]+ tests?\)' "$output_dir/instrumentation.log"
if [[ "${command_status[0]}" == 139 ]]; then
    echo 'Warning: device shell exited 139 after JUnit success; inspect device-shell-crash.log.' >&2
    "$adb_bin" -s "$ANDROID_SERIAL" logcat -b crash -d -t 100 > "$output_dir/device-shell-crash.log"
fi
for evidence in 01-detail.png 02-vf1080.png 03-tracks.png 04-vost1080.png report.json; do
    "$adb_bin" -s "$ANDROID_SERIAL" exec-out run-as com.lagradost.cloudstream3.debug \
        cat "cache/milestone4-fixture/$evidence" > "$output_dir/$evidence"
done
printf 'Device evidence: %s\n' "$output_dir"
