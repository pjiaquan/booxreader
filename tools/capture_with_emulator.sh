#!/usr/bin/env bash
set -euo pipefail

# Launch an emulator, install the APK, start the app, and capture a screenshot.
# Usage: tools/capture_with_emulator.sh <avd_name> [type] [name] [apk_path]
# type: phone (default) | seven | ten  (controls output folder)
# name: optional screenshot name (default timestamp)
# apk_path: optional; defaults to app/build/outputs/apk/release/app-release.apk
#
# Requires: emulator binary in PATH or $ANDROID_HOME/$ANDROID_SDK_ROOT, adb, and an existing AVD.

avd="${1:-}"
type="${2:-phone}"
name="${3:-screenshot-$(date +%Y%m%d-%H%M%S)}"
apk="${4:-app/build/outputs/apk/release/app-release.apk}"
port=5554
serial="emulator-${port}"

if [ -z "$avd" ]; then
  echo "Usage: $0 <avd_name> [type] [name] [apk_path]"
  exit 1
fi

find_emulator() {
  if command -v emulator &>/dev/null; then
    echo "$(command -v emulator)"
    return
  fi
  for base in "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"; do
    if [ -x "$base/emulator/emulator" ]; then
      echo "$base/emulator/emulator"
      return
    fi
  done
  echo ""
}

EMULATOR_BIN="$(find_emulator)"
if [ -z "$EMULATOR_BIN" ]; then
  echo "emulator binary not found. Ensure Android SDK emulator is installed and in PATH."
  exit 1
fi

if ! "$EMULATOR_BIN" -list-avds | grep -qx "$avd"; then
  echo "AVD '$avd' not found. Create it with avdmanager or Android Studio."
  exit 1
fi

ADB_BIN="${ADB_BIN:-adb}"
if ! command -v "$ADB_BIN" &>/dev/null; then
  echo "adb not found; install Android platform-tools."
  exit 1
fi

echo "Starting emulator $avd on port $port..."
"$EMULATOR_BIN" -avd "$avd" -port "$port" -no-snapshot -no-window -gpu swiftshader_indirect -no-audio -netdelay none -netspeed full -no-boot-anim >/tmp/boox_emulator.log 2>&1 &
emu_pid=$!

cleanup() {
  if ps -p $emu_pid >/dev/null 2>&1; then
    echo "Stopping emulator..."
    "$ADB_BIN" -s "$serial" emu kill >/dev/null 2>&1 || true
    kill $emu_pid >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "Waiting for emulator to boot..."
"$ADB_BIN" -s "$serial" wait-for-device
for _ in $(seq 1 120); do
  status=$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$status" = "1" ]; then
    break
  fi
  sleep 1
done

echo "Installing APK: $apk"
if [ ! -f "$apk" ]; then
  echo "APK not found at $apk. Build it first or pass a path."
  exit 1
fi
"$ADB_BIN" -s "$serial" install -r "$apk"

echo "Launching app..."
"$ADB_BIN" -s "$serial" shell am start -n my.hinoki.booxreader/my.hinoki.booxreader.data.ui.main.MainActivity >/dev/null
sleep 3

echo "Capturing screenshot via tools/capture_screens.sh..."
ADB_SERIAL="$serial" ADB_BIN="$ADB_BIN" "$(dirname "$0")/capture_screens.sh" "$type" "$name"

echo "Done. Screenshot saved."
