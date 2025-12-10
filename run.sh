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

# 4. Update version information
echo "Updating version information..."

# Extract current version from build.gradle.kts
BUILD_GRADLE="app/build.gradle.kts"
CURRENT_VERSION_CODE=$(grep -E 'versionCode = [0-9]+' "$BUILD_GRADLE" | grep -o '[0-9]\+')
CURRENT_VERSION_NAME=$(grep -E 'versionName = "[^"]*"' "$BUILD_GRADLE" | grep -o '"[^"]*"' | tr -d '"')

# Increment version code
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

# Parse version name (e.g., "1.1.6") and increment patch version
IFS='.' read -ra VERSION_PARTS <<< "$CURRENT_VERSION_NAME"
MAJOR=${VERSION_PARTS[0]}
MINOR=${VERSION_PARTS[1]}
PATCH=${VERSION_PARTS[2]}
NEW_PATCH=$((PATCH + 1))
NEW_VERSION_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"

echo "Current version: $CURRENT_VERSION_NAME (code: $CURRENT_VERSION_CODE)"
echo "New version: $NEW_VERSION_NAME (code: $NEW_VERSION_CODE)"

# Update versionCode and versionName in build.gradle.kts
sed -i "s/versionCode = $CURRENT_VERSION_CODE/versionCode = $NEW_VERSION_CODE/" "$BUILD_GRADLE"
sed -i "s/versionName = \"$CURRENT_VERSION_NAME\"/versionName = \"$NEW_VERSION_NAME\"/" "$BUILD_GRADLE"

# 5. Git operations
echo "Performing Git operations..."

# Check if there are changes to commit
if git diff --quiet "$BUILD_GRADLE"; then
    echo "No changes to commit."
else
    # Stage the updated build.gradle.kts
    git add "$BUILD_GRADLE"

    # Stage any other modified files
    git add .

    # Commit with version bump message
    COMMIT_MESSAGE="chore(release): Bump version to $NEW_VERSION_NAME"
    git commit -m "$COMMIT_MESSAGE"

    # Push to remote
    echo "Pushing to remote..."
    git push

    # Create and push tag
    TAG_NAME="v$NEW_VERSION_NAME"
    echo "Creating and pushing tag: $TAG_NAME"
    git tag -a "$TAG_NAME" -m "Release $NEW_VERSION_NAME"
    git push origin "$TAG_NAME"
fi

echo "Done."
echo "Application has been built, installed, and version updated to $NEW_VERSION_NAME"
