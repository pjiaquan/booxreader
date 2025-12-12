#!/usr/bin/env bash
set -euo pipefail

# Capture device screenshots into fastlane metadata.
# Usage: tools/capture_screens.sh [type] [name]
# type: phone (default) | seven | ten
# name: optional filename stem; defaults to a timestamp
# You can target a specific device by setting ADB_SERIAL (e.g., emulator-5554).

type="${1:-phone}"
name="${2:-screenshot-$(date +%Y%m%d-%H%M%S)}"

case "$type" in
  phone) OUT_DIR="fastlane/metadata/android/en-US/images/phoneScreenshots" ;;
  seven) OUT_DIR="fastlane/metadata/android/en-US/images/sevenInchScreenshots" ;;
  ten) OUT_DIR="fastlane/metadata/android/en-US/images/tenInchScreenshots" ;;
  *) echo "Unknown type '$type'. Use phone | seven | ten."; exit 1 ;;
esac

mkdir -p "$OUT_DIR"

ADB_BIN="${ADB_BIN:-adb}"
if ! command -v "$ADB_BIN" &> /dev/null; then
    echo "adb not found; install Android platform-tools."
    exit 1
fi

ADB_CMD=("$ADB_BIN")
if [ -n "${ADB_SERIAL:-}" ]; then
    ADB_CMD+=("-s" "$ADB_SERIAL")
fi

if ! "${ADB_CMD[@]}" devices | grep -q "device"; then
    echo "No device detected. Connect/authorize and retry."
    exit 1
fi

tmp="/sdcard/${name}.png"
dest="${OUT_DIR}/${name}.png"

echo "Capturing ${dest} ..."
"${ADB_CMD[@]}" exec-out screencap -p > "${dest}.raw" || { rm -f "${dest}.raw"; echo "adb screencap failed"; exit 1; }
mv "${dest}.raw" "$dest"

echo "Saved to $dest"
