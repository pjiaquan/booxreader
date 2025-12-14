#!/bin/bash
# Exit immediately if a command exits with a non-zero status.
set -e

# Telegram Bot Configuration
# Create a config file at .telegram_config with:
# TELEGRAM_BOT_TOKEN="your_bot_token"
# TELEGRAM_CHAT_ID="your_chat_id"
# TELEGRAM_ENABLED=true
TELEGRAM_CONFIG_FILE=".telegram_config"
TELEGRAM_ENABLED=true
SIGNING_ENV_HELPER="scripts/set-release-env.sh"
LOCAL_KEYSTORE_PATH="build/keystore/release.keystore"
LOCAL_SECRET_ENV="$HOME/.booxreader-keystore.env"
ADB_AVAILABLE=true
BUILD_TYPE="debug"

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
    
    # Prefer local Gradle wrapper
    if [ ! -x "./gradlew" ]; then
        missing_tools+=("./gradlew")
    fi
    for tool in git; do
        if ! command -v "$tool" &> /dev/null; then
            missing_tools+=("$tool")
        fi
    done
    
    if command -v adb &> /dev/null; then
        ADB_AVAILABLE=true
    else
        echo "Warning: adb not found; device install/launch will be skipped."
        ADB_AVAILABLE=false
    fi
    
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

load_signing_env() {
    if [ -f "$SIGNING_ENV_HELPER" ]; then
        echo "Loading signing env from $SIGNING_ENV_HELPER..."
        # shellcheck disable=SC1090
        source "$SIGNING_ENV_HELPER"
    else
        echo "Signing env helper not found at $SIGNING_ENV_HELPER; skipping."
    fi
}

use_local_keystore_if_present() {
    # If a keystore exists at the known local path, set STORE_FILE so builds can proceed
    if [ -z "${STORE_FILE:-}" ] && [ -f "$LOCAL_KEYSTORE_PATH" ]; then
        export STORE_FILE="$LOCAL_KEYSTORE_PATH"
        echo "Using local keystore at $STORE_FILE"
    fi

    # Provide a default alias for the bundled keystore if none is set
    if [ -z "${KEY_ALIAS:-}" ] && [ -f "$LOCAL_KEYSTORE_PATH" ]; then
        export KEY_ALIAS="key0"
        echo "Defaulting KEY_ALIAS to $KEY_ALIAS (from local keystore)"
    fi
}

load_local_secret_env() {
    if [ -f "$LOCAL_SECRET_ENV" ]; then
        echo "Loading signing secrets from $LOCAL_SECRET_ENV"
        # shellcheck disable=SC1090
        source "$LOCAL_SECRET_ENV"
    fi
}

# Auto-load from keystore.properties if variables are missing
load_from_keystore_properties() {
    local props_file="keystore.properties"
    if [ -f "$props_file" ]; then
        echo "Loading signing config from $props_file..."
        # Read file line by line properly
        while IFS= read -r line || [ -n "$line" ]; do
            # Skip comments and empty lines
            [[ "$line" =~ ^#.*$ ]] && continue
            [[ -z "$line" ]] && continue
            
            # Extract key and value
            if [[ "$line" =~ ^([^=]+)=(.*)$ ]]; then
                key="${BASH_REMATCH[1]}"
                value="${BASH_REMATCH[2]}"
                
                # Trim whitespace and carriage returns
                key=$(echo "$key" | tr -d '[:space:]')
                value=$(echo "$value" | tr -d '\r')

                # Map properties to Env Vars (only if not already set)
                case "$key" in
                    "keyAlias") [ -z "$KEY_ALIAS" ] && export KEY_ALIAS="$value" ;;
                    "keyPassword") [ -z "$KEY_ALIAS_PASSWORD" ] && export KEY_ALIAS_PASSWORD="$value" ;;
                    "storeFile") [ -z "$STORE_FILE" ] && export STORE_FILE="$value" ;;
                    "storePassword") [ -z "$KEYSTORE_PASSWORD" ] && export KEYSTORE_PASSWORD="$value" ;;
                esac
            fi
        done < "$props_file"
        echo "Finished loading properties."
    fi
}

ensure_signing_env() {
    local missing=()
    for var in KEY_ALIAS KEY_ALIAS_PASSWORD KEYSTORE_PASSWORD STORE_FILE; do
        if [ -z "${!var:-}" ]; then
            missing+=("$var")
        fi
    done

    if [ ${#missing[@]} -gt 0 ]; then
        cat <<'EOF'
Release signing env vars are missing. Set the following before running the build:
  export STORE_FILE=/absolute/path/to/release.keystore   # or a base64 string of the keystore
  export KEYSTORE_PASSWORD=***
  export KEY_ALIAS=***
  export KEY_ALIAS_PASSWORD=***

Tip: scripts/set-release-env.sh can export these from a local backup directory.
EOF
        echo "Missing vars: ${missing[*]}"
        exit 1
    fi
}

# Check if Android device is connected
check_adb_device() {
    if [ "$ADB_AVAILABLE" != "true" ]; then
        echo "ADB not available; skipping device checks."
        return
    fi
    if ! adb devices | grep -q "device"; then
        echo "Warning: No Android device connected or authorized; install/launch steps will be skipped."
        ADB_AVAILABLE=false
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

choose_build_type() {
    local prompt_choice
    read -p "Build type (debug/release) [${BUILD_TYPE}]: " -r prompt_choice
    prompt_choice=${prompt_choice,,}
    if [ "$prompt_choice" = "release" ]; then
        BUILD_TYPE="release"
    else
        BUILD_TYPE="debug"
    fi
    echo "Selected build type: $BUILD_TYPE"
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
    
    local apk_path
    if [ "$BUILD_TYPE" = "release" ]; then
        apk_path="app/build/outputs/apk/release/app-release.apk"
    else
        apk_path="app/build/outputs/apk/debug/app-debug.apk"
    fi
    
    if [ ! -f "$apk_path" ]; then
        echo "Error: APK not found at $apk_path"
        return 1
    fi
    
    echo "Sending APK to Telegram..."
    
    # Extract version info for the message
    local version_name="unknown"
    local version_code="0"
    
    if [ "$BUILD_TYPE" = "release" ]; then
        # For release builds, use the new version info
        version_name="$NEW_VERSION_NAME"
        version_code="$NEW_VERSION_CODE"
    else
        # For debug builds, extract current version from build.gradle.kts
        local build_gradle="app/build.gradle.kts"
        if [ -f "$build_gradle" ]; then
            version_code=$(grep -E 'versionCode = [0-9]+' "$build_gradle" | grep -o '[0-9]\+' || echo "0")
            version_name=$(grep -E 'versionName = "[^"]*"' "$build_gradle" | grep -o '"[^"]*"' | tr -d '"' || echo "0.0.0")
        fi
    fi
    
    # Send message with version info
    local version_info="${version_name} (code: ${version_code})"
    if [ "$BUILD_TYPE" = "debug" ]; then
        version_info="${version_info} - DEBUG"
    fi
    
    local message="📦 New BooxReader Build Available\n\nVersion: ${version_info}\nType: ${BUILD_TYPE}^\nSize: $(du -h "$apk_path" | cut -f1)\nBuilt: $(date)"
    
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
            -F caption="BooxReader APK - ${BUILD_TYPE}^ - ${version_info}" > /dev/null
        
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

    # Let the user choose build type (default debug)
    choose_build_type
    
    # For release builds, load signing material and enforce presence
    if [ "$BUILD_TYPE" = "release" ]; then
        load_local_secret_env
        use_local_keystore_if_present
        load_from_keystore_properties
        echo "Verifying signing environment..."
        ensure_signing_env
        echo "Signing environment verified."
    fi
    
    # Check ADB device (non-fatal)
    echo "Checking for connected Android devices..."
    check_adb_device

    # Update version only for release builds so debug loops stay fast
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "Updating version information..."
        extract_version_info
        update_version_info
    fi
    
    # Build the requested APK
    echo "Building the application ($BUILD_TYPE)..."
    if [ "$BUILD_TYPE" = "release" ]; then
        # Build both APK and Bundle for release
        ./gradlew assembleRelease bundleRelease
    else
        ./gradlew assembleDebug
    fi
    
    # Install the APK (if ADB is available)
    local apk_path
    if [ "$BUILD_TYPE" = "release" ]; then
        apk_path="app/build/outputs/apk/release/app-release.apk"
    else
        apk_path="app/build/outputs/apk/debug/app-debug.apk"
    fi
    if [ "$ADB_AVAILABLE" = "true" ]; then
        echo "Installing the APK..."
        if [ ! -f "$apk_path" ]; then
            echo "Error: APK not found at $apk_path"
            exit 1
        fi
        adb install -r "$apk_path"
        
        # 3. Launch the main activity
        echo "Launching the application..."
        # Prefer the manifest alias (.MainActivity) but fall back to the full class name
        if ! adb shell am start -n my.hinoki.booxreader/.MainActivity; then
            adb shell am start -n my.hinoki.booxreader/my.hinoki.booxreader.data.ui.main.MainActivity
        fi
    else
        echo "Skipping install/launch because ADB is unavailable."
    fi
    
    # Git operations only for release builds
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "Performing Git operations..."
        git_operations
    fi
    
    # Send APK to Telegram (if enabled) - works for both debug and release
    echo "Sending APK to Telegram..."
    send_apk_to_telegram
    
    echo "Done."
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "Application has been built, installed, and version updated to $NEW_VERSION_NAME"
    else
        echo "Application has been built and installed (debug build)."
    fi
}

# Run main function
main
