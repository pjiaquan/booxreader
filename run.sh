#!/bin/bash
# Exit immediately if a command exits with a non-zero status.
set -e

# Telegram Bot Configuration
# Create a config file at .telegram_config with:
# TELEGRAM_BOT_TOKEN="your_bot_token"
# TELEGRAM_CHAT_ID="your_chat_id"
# TELEGRAM_ENABLED=true
TELEGRAM_CONFIG_FILE=".telegram_config"
TELEGRAM_ENABLED=false

# Load Telegram configuration if file exists
if [ -f "$TELEGRAM_CONFIG_FILE" ]; then
    source "$TELEGRAM_CONFIG_FILE"
    if [ "$TELEGRAM_ENABLED" = "true" ]; then
        echo "Telegram integration enabled"
    fi
fi

# Safety: Check if required tools are available
check_dependencies() {
    local missing_tools=()
    
    for tool in gradle adb git; do
        if ! command -v "$tool" &> /dev/null; then
            missing_tools+=("$tool")
        fi
    done
    
    if [ ${#missing_tools[@]} -gt 0 ]; then
        echo "Error: Missing required tools: ${missing_tools[*]}"
        exit 1
    fi
    
    # Check for curl (needed for Telegram integration)
    if [ "$TELEGRAM_ENABLED" = "true" ] && ! command -v curl &> /dev/null; then
        echo "Warning: curl not found, Telegram integration will be disabled"
        TELEGRAM_ENABLED=false
    fi
}

# Check if Android device is connected
check_adb_device() {
    if ! adb devices | grep -q "device"; then
        echo "Error: No Android device connected or authorized"
        exit 1
    fi
}

# Extract version information safely
extract_version_info() {
    local build_gradle="app/build.gradle.kts"
    
    if [ ! -f "$build_gradle" ]; then
        echo "Error: build.gradle.kts not found at $build_gradle"
        exit 1
    fi
    
    # Create backup
    cp "$build_gradle" "${build_gradle}.backup"
    
    # Extract version code and name with validation
    local version_code_line=$(grep -E 'versionCode = [0-9]+' "$build_gradle" || echo "")
    local version_name_line=$(grep -E 'versionName = "[^"]*"' "$build_gradle" || echo "")
    
    if [ -z "$version_code_line" ] || [ -z "$version_name_line" ]; then
        echo "Error: Could not extract version information from build.gradle.kts"
        rm -f "${build_gradle}.backup"
        exit 1
    fi
    
    CURRENT_VERSION_CODE=$(echo "$version_code_line" | grep -o '[0-9]\+' || echo "0")
    CURRENT_VERSION_NAME=$(echo "$version_name_line" | grep -o '"[^"]*"' | tr -d '"' || echo "0.0.0")
    
    # Validate version format
    if ! [[ "$CURRENT_VERSION_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "Error: Version name '$CURRENT_VERSION_NAME' does not match expected format X.Y.Z"
        rm -f "${build_gradle}.backup"
        exit 1
    fi
}

# Update version information safely
update_version_info() {
    local build_gradle="app/build.gradle.kts"
    
    # Increment version code
    NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
    
    # Parse version name and increment patch version
    IFS='.' read -ra VERSION_PARTS <<< "$CURRENT_VERSION_NAME"
    MAJOR=${VERSION_PARTS[0]}
    MINOR=${VERSION_PARTS[1]}
    PATCH=${VERSION_PARTS[2]}
    NEW_PATCH=$((PATCH + 1))
    NEW_VERSION_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"
    
    echo "Current version: $CURRENT_VERSION_NAME (code: $CURRENT_VERSION_CODE)"
    echo "New version: $NEW_VERSION_NAME (code: $NEW_VERSION_CODE)"
    
    # Update versionCode and versionName using safe sed operations
    if ! sed -i "s/versionCode = [0-9]\+/versionCode = $NEW_VERSION_CODE/" "$build_gradle"; then
        echo "Error: Failed to update versionCode"
        restore_backup
        exit 1
    fi
    
    if ! sed -i "s/versionName = \"[^\"]*\"/versionName = \"$NEW_VERSION_NAME\"/" "$build_gradle"; then
        echo "Error: Failed to update versionName"
        restore_backup
        exit 1
    fi
    
    # Verify changes
    if ! grep -q "versionCode = $NEW_VERSION_CODE" "$build_gradle"; then
        echo "Error: versionCode update verification failed"
        restore_backup
        exit 1
    fi
    
    if ! grep -q "versionName = \"$NEW_VERSION_NAME\"" "$build_gradle"; then
        echo "Error: versionName update verification failed"
        restore_backup
        exit 1
    fi
    
    # Clean up backup
    rm -f "${build_gradle}.backup"
}

# Restore backup in case of failure
restore_backup() {
    local build_gradle="app/build.gradle.kts"
    if [ -f "${build_gradle}.backup" ]; then
        echo "Restoring backup..."
        mv "${build_gradle}.backup" "$build_gradle"
    fi
}

# Send APK to Telegram bot
send_apk_to_telegram() {
    if [ "$TELEGRAM_ENABLED" != "true" ]; then
        echo "Telegram integration disabled, skipping APK upload"
        return 0
    fi
    
    if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
        echo "Error: Telegram bot token or chat ID not configured"
        return 1
    fi
    
    local apk_path="app/build/outputs/apk/debug/app-debug.apk"
    
    if [ ! -f "$apk_path" ]; then
        echo "Error: APK not found at $apk_path"
        return 1
    fi
    
    echo "Sending APK to Telegram..."
    
    # Send message with version info
    local message="📦 New BooxReader Build Available\n\nVersion: $NEW_VERSION_NAME (code: $NEW_VERSION_CODE)\nSize: $(du -h "$apk_path" | cut -f1)\nBuilt: $(date)"
    
    # Use curl to send the APK file
    if command -v curl &> /dev/null; then
        # Send the message first
        curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d chat_id="${TELEGRAM_CHAT_ID}" \
            -d text="${message}" \
            -d parse_mode="Markdown" > /dev/null
        
        # Send the APK file
        curl -s -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
            -F chat_id="${TELEGRAM_CHAT_ID}" \
            -F document=@"${apk_path}" \
            -F caption="BooxReader APK - Version ${NEW_VERSION_NAME}" > /dev/null
        
        echo "✅ APK successfully sent to Telegram"
        return 0
    else
        echo "Error: curl not found, cannot send APK to Telegram"
        return 1
    fi
}

# Git operations with safety checks
git_operations() {
    local build_gradle="app/build.gradle.kts"
    
    # Check if there are changes to commit
    if git diff --quiet "$build_gradle"; then
        echo "No version changes to commit."
        return 0
    fi
    
    # Ask for confirmation before committing and pushing
    read -p "Version has been updated. Do you want to commit and push these changes? (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Skipping git operations."
        return 0
    fi
    
    # Stage all changes (code + version bump)
    git add .
    
    # Generate a default commit message based on changed files
    # Get top 3 changed files (excluding build.gradle.kts which is always changed)
    CHANGED_FILES=$(git diff --cached --name-only | grep -v "build.gradle.kts" | head -n 3 | xargs)
    FILE_COUNT=$(git diff --cached --name-only | grep -v "build.gradle.kts" | wc -l)
    
    if [ -z "$CHANGED_FILES" ]; then
        DEFAULT_MSG="chore(release): Bump version to $NEW_VERSION_NAME"
    else
        if [ "$FILE_COUNT" -gt 3 ]; then
            DEFAULT_MSG="feat: Update $CHANGED_FILES and others (v$NEW_VERSION_NAME)"
        else
            DEFAULT_MSG="feat: Update $CHANGED_FILES (v$NEW_VERSION_NAME)"
        fi
    fi
    
    echo "Default commit message: $DEFAULT_MSG"
    read -p "Enter custom commit message (or press Enter to use default): " USER_MSG
    
    COMMIT_MESSAGE=${USER_MSG:-$DEFAULT_MSG}
    
    git commit -m "$COMMIT_MESSAGE"
    
    # Push to remote
    echo "Pushing to remote..."
    git push
    
    # Create and push tag
    TAG_NAME="v$NEW_VERSION_NAME"
    echo "Creating and pushing tag: $TAG_NAME"
    git tag -a "$TAG_NAME" -m "Release $NEW_VERSION_NAME"
    git push origin "$TAG_NAME"
}

# Main execution
main() {
    echo "=== BooxReader Build and Install Script ==="
    
    # Check dependencies
    check_dependencies
    
    # Check ADB device
    check_adb_device
    
    # 1. Compile the debug APK
    echo "Building the application..."
    ./gradlew assembleDebug
    
    # 2. Install the APK
    echo "Installing the APK..."
    local apk_path="app/build/outputs/apk/debug/app-debug.apk"
    
    if [ ! -f "$apk_path" ]; then
        echo "Error: APK not found at $apk_path"
        exit 1
    fi
    
    adb install -r "$apk_path"
    
    # 3. Launch the main activity
    echo "Launching the application..."
    adb shell am start -n my.hinoki.booxreader/my.hinoki.booxreader.data.ui.main.MainActivity
    
    # 4. Update version information
    echo "Updating version information..."
    extract_version_info
    update_version_info
    
    # 5. Git operations
    echo "Performing Git operations..."
    git_operations
    
    # 6. Send APK to Telegram (if enabled)
    echo "Sending APK to Telegram..."
    send_apk_to_telegram
    
    echo "Done."
    echo "Application has been built, installed, and version updated to $NEW_VERSION_NAME"
}

# Run main function
main
