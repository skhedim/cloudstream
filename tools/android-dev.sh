#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh" ]]; then
    source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
adb_bin="$ANDROID_HOME/platform-tools/adb"
emulator_bin="$ANDROID_HOME/emulator/emulator"
apk_file="$project_dir/app/build/outputs/apk/stable/debug/app-stable-debug.apk"
package_name="com.lagradost.cloudstream3.debug"

device_adb() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        "$adb_bin" -s "$ANDROID_SERIAL" "$@"
    else
        # Never install onto a connected personal phone by accident.
        "$adb_bin" -e "$@"
    fi
}

require_device() {
    if ! device_adb get-state >/dev/null 2>&1; then
        echo 'Connect a device and set ANDROID_SERIAL, or start the sole emulator first.' >&2
        exit 1
    fi
}

build_app() {
    (cd "$project_dir" && ./gradlew :app:assembleStableDebug --max-workers=4 --console=plain)
}

install_app() {
    require_device
    if [[ ! -f "$apk_file" ]]; then
        echo 'APK missing. Run: tools/android-dev.sh build' >&2
        exit 1
    fi
    device_adb install -r "$apk_file"
}

run_app() {
    require_device
    # Resolve the exported launcher, preserving account selection and future entry-point changes.
    launcher_component="$(device_adb shell cmd package resolve-activity --brief \
        -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package_name" | tr -d '\r' | tail -n 1)"
    if [[ "$launcher_component" != "$package_name/"* ]]; then
        printf 'No launcher found for %s. Install the APK first.\n' "$package_name" >&2
        exit 1
    fi
    device_adb shell input keyevent KEYCODE_WAKEUP
    device_adb shell am start -n "$launcher_component"
}

case "${1:-help}" in
    doctor)
        printf 'SDK: %s\n' "$ANDROID_HOME"
        java -version
        "$adb_bin" version
        "$adb_bin" devices -l
        "$emulator_bin" -list-avds
        if [[ ! -w /dev/kvm && " $(id -Gn "$(id -un)") " == *' kvm '* ]]; then
            sg kvm -c '"$ANDROID_HOME/emulator/emulator" -accel-check'
        else
            "$emulator_bin" -accel-check
        fi
        ;;
    emulator)
        export QT_QPA_PLATFORM=xcb
        if [[ -r /dev/kvm && -w /dev/kvm ]]; then
            exec "$emulator_bin" -avd cloudstream_api35 -gpu swiftshader_indirect -feature -Vulkan -no-snapshot -no-boot-anim
        elif [[ " $(id -Gn "$(id -un)") " == *' kvm '* ]]; then
            # Refresh just this process's group, without restarting VS Code or other WSL work.
            exec sg kvm -c 'exec "$ANDROID_HOME/emulator/emulator" -avd cloudstream_api35 -gpu swiftshader_indirect -feature -Vulkan -no-snapshot -no-boot-anim'
        else
            echo 'KVM access required. In a WSL terminal, run: sudo usermod -aG kvm "$USER"' >&2
            exit 1
        fi
        ;;
    build) build_app ;;
    install) install_app ;;
    run) run_app ;;
    deploy)
        require_device
        build_app
        install_app
        run_app
        ;;
    test)
        cd "$project_dir"
        exec ./gradlew :shared:jvmTest :app:testStableDebugUnitTest --max-workers=4 --console=plain
        ;;
    capture)
        require_device
        mkdir -p "$project_dir/app/build/android-dev"
        capture_dir="$(mktemp -d "$project_dir/app/build/android-dev/$(date +%Y%m%d-%H%M%S)-XXXXXX")"
        device_adb exec-out screencap -p > "$capture_dir/screen.png"
        # Transitions and some Compose screens can temporarily expose no accessibility root.
        # Keep the screenshot and logs even when the optional hierarchy cannot be collected.
        hierarchy_saved=false
        for attempt in 1 2 3; do
            device_adb shell rm -f /sdcard/cloudstream-window.xml
            if device_adb shell uiautomator dump /sdcard/cloudstream-window.xml > "$capture_dir/hierarchy-capture.txt" 2>&1 &&
                device_adb pull /sdcard/cloudstream-window.xml "$capture_dir/window.xml" >> "$capture_dir/hierarchy-capture.txt" 2>&1; then
                hierarchy_saved=true
                break
            fi
            sleep 1
        done
        if [[ "$hierarchy_saved" == false ]]; then
            echo 'UI hierarchy unavailable; keeping the screenshot and app logs.' >&2
        fi
        app_pid="$(device_adb shell pidof -s "$package_name" | tr -d '\r')" || app_pid=''
        if [[ -n "$app_pid" ]]; then
            device_adb logcat -d --pid="$app_pid" -v threadtime -t 1000 > "$capture_dir/logcat.txt"
        else
            printf 'Application not running.\n' > "$capture_dir/logcat.txt"
        fi
        device_adb shell getprop > "$capture_dir/device.txt"
        printf 'Captured: %s\n' "$capture_dir"
        ;;
    stop)
        require_device
        device_adb emu kill
        ;;
    *)
        cat <<'USAGE'
Usage: tools/android-dev.sh COMMAND

  emulator   Start the shared Android 15 test device (keep this terminal open)
  deploy     Build, update the APK without clearing its data, and open CloudStream
  test       Run the JVM and Robolectric tests without an emulator
  capture    Save a screenshot, UI hierarchy, app logcat and device properties
  build      Build the debug APK
  install    Install the existing debug APK
  run        Open the installed debug application
  doctor     Inspect SDK, ADB, virtual devices and KVM access
  stop       Close the selected emulator

Commands default to the sole emulator. Set ANDROID_SERIAL when using several devices.
USAGE
        ;;
esac
