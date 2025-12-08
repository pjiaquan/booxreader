#!/bin/bash
# Exit immediately if a command exits with a non-zero status.
set -e

# 1. Compile the debug APK
echo "Building the application..."
./gradlew assembleDebug

# 2. Install the APK
echo "Installing the APK..."
android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Launch the main activity
echo "Launching the application..."
android-sdk/platform-tools/adb shell am start -n my.hinoki.booxreader/my.hinoki.booxreader.data.ui.main.MainActivity

echo "Done."
