#!/bin/bash
set -Eeuo pipefail
IFS=$'\n\t'

log() { printf '%s\n' "$*"; }
warn() { printf 'Warning: %s\n' "$*" >&2; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }

is_tty() { [[ -t 0 && -t 1 ]]; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$SCRIPT_DIR"

if [ -f ".groq_env" ]; then
    source ".groq_env"
fi

is_writable_dir() {
    local dir="$1"
    mkdir -p "$dir" 2>/dev/null || return 1
    local probe=""
    probe="$(mktemp -p "$dir" .writable_probe.XXXXXX 2>/dev/null)" || return 1
    rm -f "$probe" 2>/dev/null || true
    return 0
}

seed_gradle_user_home_from_default() {
    local default_home="$HOME/.gradle"
    local local_home="$GRADLE_USER_HOME"

    # If we're already using the default Gradle home, nothing to do.
    if [ "$local_home" = "$default_home" ]; then
        return 0
    fi

    # If the user has an existing Gradle cache under ~/.gradle but this script
    # is forced to use a repo-local GRADLE_USER_HOME (e.g. sandboxed runs),
    # copy caches/wrapper state so Gradle doesn't need network access.
    if [ -d "$default_home" ]; then
        mkdir -p "$local_home"

        if [ -d "$default_home/wrapper" ]; then
            mkdir -p "$local_home/wrapper"
            cp -an "$default_home/wrapper/." "$local_home/wrapper/" 2>/dev/null || true
        fi

        if [ "${SEED_GRADLE_CACHES:-false}" = "true" ] && [ -d "$default_home/caches" ]; then
            mkdir -p "$local_home/caches"
            cp -an "$default_home/caches/." "$local_home/caches/" 2>/dev/null || true
        fi

        # Clean up stale partial downloads that would otherwise trigger network fetches.
        rm -f "$local_home"/wrapper/dists/*/*/*.part "$local_home"/wrapper/dists/*/*/*.lck 2>/dev/null || true
    fi
}

# Choose a writable Gradle home:
# - Prefer ~/.gradle when writable (keeps caches small and avoids copies)
# - Fall back to repo-local .gradle-local in restricted environments (e.g. sandbox)
if [ -z "${GRADLE_USER_HOME:-}" ]; then
    if is_writable_dir "$HOME/.gradle"; then
        export GRADLE_USER_HOME="$HOME/.gradle"
    else
        export GRADLE_USER_HOME="$SCRIPT_DIR/.gradle-local"
    fi
fi
mkdir -p "$GRADLE_USER_HOME"

# Telegram Bot Configuration
# Create a config file at .telegram_config with:
# TELEGRAM_BOT_TOKEN="your_bot_token"
# TELEGRAM_CHAT_ID="your_chat_id"
# TELEGRAM_ENABLED=true
TELEGRAM_CONFIG_FILE="${TELEGRAM_CONFIG_FILE:-.telegram_config}"
TELEGRAM_ENABLED="${TELEGRAM_ENABLED:-false}"
TELEGRAM_ENABLED_LOCKED="${TELEGRAM_ENABLED_LOCKED:-false}"
LOCAL_KEYSTORE_PATH="${LOCAL_KEYSTORE_PATH:-build/keystore/release.keystore}"
LOCAL_SECRET_ENV="${LOCAL_SECRET_ENV:-$HOME/.booxreader-keystore.env}"
ADB_AVAILABLE=false
BUILD_TYPE="${BUILD_TYPE:-debug}"
BUILD_TYPE_LOCKED="${BUILD_TYPE_LOCKED:-false}"
SKIP_TESTS="${SKIP_TESTS:-false}"
SKIP_INSTALL="${SKIP_INSTALL:-false}"
SKIP_GIT="${SKIP_GIT:-false}"
AUTO_SELECT_DEVICE="${AUTO_SELECT_DEVICE:-false}"
TARGET_DEVICE_SERIAL=""
CI_RELEASE_ONLY="${CI_RELEASE_ONLY:-false}"

load_telegram_config() {
    if [ -f "$TELEGRAM_CONFIG_FILE" ]; then
        # shellcheck disable=SC1090
        source "$TELEGRAM_CONFIG_FILE"
    fi

    if [ "$TELEGRAM_ENABLED_LOCKED" = "true" ]; then
        TELEGRAM_ENABLED=false
        return 0
    fi

    if [ "${TELEGRAM_ENABLED:-false}" = "true" ]; then
        log "Telegram integration enabled"
    else
        TELEGRAM_ENABLED=false
    fi
}

usage() {
    cat <<'EOF'
Usage: ./run.sh [options]

Options:
  --debug            Build debug (default)
  --release          Build release
  --ci-release       Bump version and tag only (skip local tests/build/install), let CI build
  --skip-tests       Skip running unit tests first
  --skip-install     Skip ADB install steps
  --skip-git         Skip git commit/tag/push steps (release only)
  --skip-telegram    Disable Telegram upload
  --auto-select      Automatically select first device (no interactive prompt)
  -s, --device       Specify target device serial (e.g., -s emulator-5554)
  -h, --help         Show this help

Features:
  - Automatic device detection and selection
  - Device compatibility checking
  - Multiple device support with interactive selection
  - Automatic signing key compatibility handling
  - Detailed device information display

Env vars (optional):
  BUILD_TYPE=debug|release
  SKIP_TESTS=true|false
  SKIP_INSTALL=true|false
  SKIP_GIT=true|false
  TELEGRAM_ENABLED=true|false
  AUTO_SELECT_DEVICE=true|false
  CI_RELEASE_ONLY=true|false
EOF
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --debug) BUILD_TYPE="debug"; BUILD_TYPE_LOCKED="true" ;;
            --release) BUILD_TYPE="release"; BUILD_TYPE_LOCKED="true" ;;
            --ci-release) 
                BUILD_TYPE="release"
                BUILD_TYPE_LOCKED="true"
                CI_RELEASE_ONLY="true"
                SKIP_TESTS="true"
                SKIP_INSTALL="true"
                ;;
            --skip-tests) SKIP_TESTS="true" ;;
            --skip-install) SKIP_INSTALL="true" ;;
            --skip-git) SKIP_GIT="true" ;;
            --skip-telegram|--no-telegram) TELEGRAM_ENABLED="false"; TELEGRAM_ENABLED_LOCKED="true" ;;
            --auto-select) AUTO_SELECT_DEVICE="true" ;;
            -s|--device) TARGET_DEVICE_SERIAL="$2"; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "Unknown argument: $1 (use --help)" ;;
        esac
        shift
    done
}

# sed -i portability (GNU vs BSD/macOS)
sedi() {
    if sed --version >/dev/null 2>&1; then
        sed -i "$@"
    else
        sed -i '' "$@"
    fi
}

cleanup_paths=()
register_cleanup() { cleanup_paths+=("$1"); }

cleanup() {
    set +e
    for p in "${cleanup_paths[@]:-}"; do
        rm -f "$p" >/dev/null 2>&1 || true
    done
    restore_backup >/dev/null 2>&1 || true
}
trap cleanup EXIT

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

    # Java is required by Gradle/AGP.
    if ! command -v java &> /dev/null; then
        missing_tools+=("java")
    fi
    
    if command -v adb &> /dev/null; then
        ADB_AVAILABLE=true
    else
        warn "adb not found; device install will be skipped."
        ADB_AVAILABLE=false
    fi
    
    if [ ${#missing_tools[@]} -gt 0 ]; then
        die "Missing required tools: ${missing_tools[*]}"
    fi
    
    # Check for curl (needed for Telegram integration)
    if [ "$TELEGRAM_ENABLED" = "true" ] && ! command -v curl &> /dev/null; then
        warn "curl not found; Telegram integration will be disabled"
        TELEGRAM_ENABLED=false
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
        log "Loading signing secrets from $LOCAL_SECRET_ENV"
        # shellcheck disable=SC1090
        source "$LOCAL_SECRET_ENV"
        return
    fi

    # Convenience: allow repo-local secrets file too.
    if [ -f ".booxreader-keystore.env" ]; then
        log "Loading signing secrets from .booxreader-keystore.env"
        # shellcheck disable=SC1091
        source ".booxreader-keystore.env"
    fi
}

# Auto-load from keystore.properties if variables are missing
load_from_keystore_properties() {
    local props_file="keystore.properties"
    if [ -f "$props_file" ]; then
        log "Loading signing config from $props_file..."
        while IFS= read -r line || [ -n "$line" ]; do
            [[ "$line" =~ ^[[:space:]]*# ]] && continue
            [[ "$line" =~ ^[[:space:]]*$ ]] && continue
            [[ "$line" != *"="* ]] && continue

            local key="${line%%=*}"
            local value="${line#*=}"

            key="$(printf '%s' "$key" | tr -d '[:space:]')"
            value="$(printf '%s' "$value" | tr -d '\r')"

            case "$key" in
                keyAlias) [ -z "${KEY_ALIAS:-}" ] && export KEY_ALIAS="$value" ;;
                keyPassword) [ -z "${KEY_ALIAS_PASSWORD:-}" ] && export KEY_ALIAS_PASSWORD="$value" ;;
                storeFile) [ -z "${STORE_FILE:-}" ] && export STORE_FILE="$value" ;;
                storePassword) [ -z "${KEYSTORE_PASSWORD:-}" ] && export KEYSTORE_PASSWORD="$value" ;;
            esac
        done < "$props_file"
        log "Finished loading properties."
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

Tip: You can provide these via environment variables, keystore.properties file,
or .booxreader-keystore.env file in the repository root or your home directory.
EOF
        echo "Missing vars: ${missing[*]}"
        exit 1
    fi
}

# Check if Android device is connected
check_adb_device() {
    if [ "$ADB_AVAILABLE" != "true" ]; then
        log "ADB not available; skipping device checks."
        return
    fi
    if ! adb devices -l | awk 'NR>1 && $2=="device" {found=1} END {exit found?0:1}'; then
        warn "No Android device connected/authorized; install steps will be skipped."
        ADB_AVAILABLE=false
    fi
}

# Get detailed device information
get_device_info() {
    local device_serial="$1"
    local adb_cmd="adb"
    if [ -n "$device_serial" ]; then
        adb_cmd="adb -s $device_serial"
    fi
    
    # Get device model
    local model=$($adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "Unknown")
    # Get Android version
    local version=$($adb_cmd shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "Unknown")
    # Get SDK version
    local sdk=$($adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo "Unknown")
    
    echo "$model (Android $version, SDK $sdk)"
}

# List all connected devices with detailed information
list_connected_devices() {
    if [ "$ADB_AVAILABLE" != "true" ]; then
        echo "ADB not available"
        return 1
    fi
    
    local devices=()
    local device_count=0
    
    echo "Connected Android devices:"
    echo "========================"
    
    # Get list of devices
    while IFS= read -r line; do
        if [[ $line =~ ^([^[:space:]]+)[[:space:]]+device$ ]]; then
            local serial="${BASH_REMATCH[1]}"
            local info=$(get_device_info "$serial")
            devices+=("$serial")
            echo "$((device_count + 1)). $serial - $info"
            device_count=$((device_count + 1))
        fi
    done < <(adb devices)
    
    if [ $device_count -eq 0 ]; then
        echo "No devices found"
        return 1
    fi
    
    echo ""
    return 0
}

# Let user select a device
select_device() {
    local devices=("$@")
    local device_count=${#devices[@]}
    
    if [ $device_count -eq 0 ]; then
        echo "No devices available"
        return 1
    fi
    
    if [ $device_count -eq 1 ]; then
        echo "${devices[0]}"
        return 0
    fi
    
    # Multiple devices - let user choose
    echo "Multiple devices detected. Please select a device:"
    for i in "${!devices[@]}"; do
        local serial="${devices[$i]}"
        local info=$(get_device_info "$serial")
        echo "$((i + 1)). $serial - $info"
    done
    
    local choice=""
    while true; do
        read -p "Enter device number (1-$device_count): " choice
        if [[ $choice =~ ^[0-9]+$ ]] && [ $choice -ge 1 ] && [ $choice -le $device_count ]; then
            echo "${devices[$((choice - 1))]}"
            return 0
        else
            echo "Invalid choice. Please enter a number between 1 and $device_count."
        fi
    done
}

# Check device compatibility
check_device_compatibility() {
    local device_serial="$1"
    local adb_cmd="adb"
    if [ -n "$device_serial" ]; then
        adb_cmd="adb -s $device_serial"
    fi

    # Check minimum SDK version (Android 6.0 Marshmallow - API 23)
    local sdk=$($adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo "0")

    if [ "$sdk" -lt 23 ]; then
        echo "Warning: Device has SDK $sdk, but minimum required is 23 (Android 6.0)"
        return 1
    fi

    # Check available storage
    local storage=$($adb_cmd shell df /data | tail -1 | awk '{print $4}' 2>/dev/null || echo "0")
    if [ "$storage" -lt 100000 ]; then # Less than 100MB
        echo "Warning: Device has low storage space ($storage KB available)"
        return 1
    fi

    echo "Device is compatible"
    return 0
}

# ADB install with signature fallback handling
adb_install_with_signature_fallback() {
    local apk_path="$1"
    local device_serial="$2"
    local package_name="my.hinoki.booxreader"

    local adb_cmd="adb"
    if [ -n "$device_serial" ]; then
        adb_cmd="adb -s $device_serial"
    fi

    local install_out
    install_out=$($adb_cmd install -r "$apk_path" 2>&1) || true
    if echo "$install_out" | grep -q "Success"; then
        return 0
    fi

    # Only uninstall when the install failure indicates a signature mismatch.
    if echo "$install_out" | grep -Eq "INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES|INSTALL_FAILED_INCONSISTENT_CERTIFICATES|INSTALL_FAILED_SIGNATURE"; then
        echo "Install failed due to signature mismatch; uninstalling and retrying..."
        $adb_cmd uninstall "$package_name" || true
        $adb_cmd install "$apk_path"
        return $?
    fi

    echo "$install_out"
    return 1
}

# Get the first connected device serial number
get_first_device() {
    if [ "$ADB_AVAILABLE" != "true" ]; then
        echo "ADB not available"
        return 1
    fi
    
    # Get list of devices and select the first one
    local devices
    devices=$(adb devices | grep -E '^[^\s]+\s+device$' | awk '{print $1}' | head -n 1)
    
    if [ -n "$devices" ]; then
        echo "$devices"
        return 0
    fi
    
    echo "No devices found"
    return 1
}

# Check if signing keys are different between installed app and new APK.
# Returns 0 only when keys are confirmed different (uninstall required), 1 otherwise.
# This avoids unnecessary data loss; if verification is inconclusive we skip uninstall.
check_signing_key_difference() {
    local apk_path="$1"
    local device_serial="$2"
    local package_name="my.hinoki.booxreader"
    
    local adb_cmd="adb"
    if [ -n "$device_serial" ]; then
        adb_cmd="adb -s $device_serial"
    fi
    
    # Check if app is already installed
    if ! $adb_cmd shell pm list packages | grep -q "$package_name"; then
        echo "App not currently installed, no need to check signing keys"
        return 1  # No uninstall needed
    fi
    
    # Check if apksigner is available (part of Android SDK build-tools)
    if ! command -v apksigner &> /dev/null; then
        echo "apksigner not found, cannot verify signing keys - skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    # Extract certificate from new APK first (this is more reliable)
    echo "Extracting signing certificate from new APK..."
    
    # Use apksigner to verify and extract the certificate
    local apksigner_output
    apksigner_output="$(mktemp -t apksigner_output.XXXXXX)"
    register_cleanup "$apksigner_output"
    if ! apksigner verify --print-certs "$apk_path" > "$apksigner_output" 2>/dev/null; then
        echo "Could not verify new APK signature, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    # Extract the SHA-256 digest from apksigner output
    local new_sha256
    new_sha256="$(awk '/Signer #1 certificate/ {flag=1} flag && /SHA-256 digest:/ {print $NF; exit}' "$apksigner_output" 2>/dev/null | tr -d '[:space:]')"
    
    if [ -z "$new_sha256" ]; then
        echo "Could not extract SHA-256 from new APK, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    echo "New APK SHA-256: $new_sha256"
    
    # Now extract certificate from installed app using a more reliable method
    echo "Extracting signing certificate from installed app..."
    
    # Pull the APK from the device to extract its certificate
    local device_apk_path
    device_apk_path="$(mktemp -t device_apk.XXXXXX.apk)"
    register_cleanup "$device_apk_path"
    
    # Try to get the APK path from the device
    local apk_on_device
    apk_on_device="$($adb_cmd shell pm path "$package_name" | tr -d '\r' | grep -o '/[^ ]*.apk' | head -1 || true)"
    
    if [ -z "$apk_on_device" ]; then
        echo "Could not find APK path on device, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    # Pull the APK from device
    if ! $adb_cmd pull "$apk_on_device" "$device_apk_path" 2>/dev/null; then
        echo "Could not pull APK from device, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    # Extract certificate from the pulled APK
    local device_apksigner_output
    device_apksigner_output="$(mktemp -t device_apksigner_output.XXXXXX)"
    register_cleanup "$device_apksigner_output"
    if ! apksigner verify --print-certs "$device_apk_path" > "$device_apksigner_output" 2>/dev/null; then
        echo "Could not verify pulled APK signature, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    # Extract the SHA-256 digest from device APK
    local installed_sha256
    installed_sha256="$(awk '/Signer #1 certificate/ {flag=1} flag && /SHA-256 digest:/ {print $NF; exit}' "$device_apksigner_output" 2>/dev/null | tr -d '[:space:]')"
    
    if [ -z "$installed_sha256" ]; then
        echo "Could not extract SHA-256 from installed app, skipping uninstall"
        return 1  # Inconclusive; don't uninstall
    fi
    
    echo "Installed app SHA-256: $installed_sha256"
    
    # Compare the SHA-256 fingerprints
    if [ "$installed_sha256" = "$new_sha256" ]; then
        echo "Signing keys are the same - no need to uninstall"
        return 1  # Same keys
    else
        echo "Signing keys are different - uninstall required"
        return 0  # Different keys
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
    if ! sedi "s/versionCode = [0-9]\\+/versionCode = $NEW_VERSION_CODE/" "$build_gradle"; then
        echo "Error: Failed to update versionCode"
        restore_backup
        exit 1
    fi
    
    if ! sedi "s/versionName = \"[^\"]*\"/versionName = \"$NEW_VERSION_NAME\"/" "$build_gradle"; then
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
    if [ "$BUILD_TYPE_LOCKED" = "true" ]; then
        log "Selected build type: $BUILD_TYPE (from command line)"
        return 0
    fi

    local prompt_choice=""
    local timeout=5

    echo -n "Build type (debug/release) [${BUILD_TYPE}]: "

    # Use read with timeout if available
    if is_tty && read -t $timeout -r prompt_choice; then
        # User provided input
        prompt_choice=${prompt_choice,,}
        if [ "$prompt_choice" = "release" ]; then
            BUILD_TYPE="release"
        else
            BUILD_TYPE="debug"
        fi
    else
        # Timeout reached, default to debug
        echo "debug"
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

# Run unit tests first (fails fast).
run_tests() {
    if [ "${SKIP_TESTS}" = "true" ]; then
        log "Skipping tests (SKIP_TESTS=true)."
        return 0
    fi

    local gradle_flags=()
    if [ "${GRADLE_OFFLINE:-false}" = "true" ]; then
        gradle_flags+=(--offline)
    fi

    log "Running unit tests..."
    # Prefer variant-specific unit tests for speed; avoid requiring release signing.
    ./gradlew "${gradle_flags[@]}" :app:testDebugUnitTest --stacktrace
    log "Unit tests passed."
}

# Send APK to Telegram bot
send_apk_to_telegram() {
    if [ "$TELEGRAM_ENABLED" != "true" ]; then
        log "Telegram integration disabled."
        return 0
    fi
    
    if [ -z "${TELEGRAM_BOT_TOKEN:-}" ] || [ -z "${TELEGRAM_CHAT_ID:-}" ]; then
        warn "Telegram is enabled but TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID is not configured; skipping upload."
        return 0
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
    
    local message="📦 New BooxReader Build Available\n\nVersion: ${version_info}\nType: ${BUILD_TYPE}\nSize: $(du -h "$apk_path" | cut -f1)\nBuilt: $(date)"
    
    # Use curl to send the APK file
    if command -v curl &> /dev/null; then
        # Send the message first
        if ! curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
            -d chat_id="${TELEGRAM_CHAT_ID}" \
            -d text="${message}" \
            -d parse_mode="Markdown" > /dev/null; then
            warn "Failed to send Telegram message; skipping upload."
            return 0
        fi
        
        # Send the APK file
        if ! curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendDocument" \
            -F chat_id="${TELEGRAM_CHAT_ID}" \
            -F document=@"${apk_path}" \
            -F caption="BooxReader APK - ${BUILD_TYPE} - ${version_info}" > /dev/null; then
            warn "Failed to upload APK to Telegram; continuing."
            return 0
        fi
        
        echo "✅ APK successfully sent to Telegram"
        return 0
    else
        warn "curl not found; cannot send APK to Telegram"
        return 0
    fi
}

# Generate AI commit message using Groq
generate_ai_commit_message() {
    local diff_content="$1"
    local extra_system="${2:-}"
    if [ -z "${GROQ_API_KEY:-}" ]; then
        return 1
    fi
    
    # Check if jq is available
    if ! command -v jq &> /dev/null; then
        warn "jq not found; cannot generate AI commit message"
        return 1
    fi
    
    # Escape diff content for JSON
    local escaped_diff
    escaped_diff=$(echo "$diff_content" | jq -sRr @json)
    
    local system_content="You are a senior software engineer. Carefully analyze the git diff to identify core functional changes. Focus on explaining WHAT the code accomplishes, WHAT bugs were fixed, and WHAT features were added. Do NOT mention file names, paths, or line numbers.

Generate a Conventional Commit message with:
- Subject line: Concise summary of the main functional change (e.g., 'Fix user login issue', 'Add AI note feature', 'Optimize background sync')
- Body: Detailed bullet points explaining:
  * What bugs were fixed (specific issue description)
  * What features were added (specific functionality)
  * What was optimized (specific improvements)
  * Code logic and implementation intent

If you cannot infer the functional change from the diff, return an empty response.

Output ONLY the raw commit message in plain text. Do not use markdown code blocks, quotes, or extra explanations."
    if [ -n "$extra_system" ]; then
        system_content="${system_content}"$'\n\n'"${extra_system}"
    fi
    local user_content="Generate a descriptive commit message for these changes:\\n"
    
    local json_body
    json_body=$(jq -n \
                  --arg sys "$system_content" \
                  --arg usr "$user_content" \
                  --arg diff "$escaped_diff" \
                  --arg model "llama-3.3-70b-versatile" \
                  '{
                    model: $model,
                    messages: [
                      {role: "system", content: $sys},
                      {role: "user", content: ($usr + ($diff | fromjson))}
                    ]
                  }')

    echo "Generating commit message with AI..." >&2
    local response
    response=$(curl -s -X POST "https://api.groq.com/openai/v1/chat/completions" \
        -H "Authorization: Bearer $GROQ_API_KEY" \
        -H "Content-Type: application/json" \
        -d "$json_body")
        
    local message
    message=$(echo "$response" | jq -r '.choices[0].message.content // empty')
    
    # Clean up message (remove surrounding quotes if present, trim leading/trailing whitespace)
    # Using sed to avoid flattening newlines (xargs would flatten)
    message=$(echo "$message" | sed -e 's/^["`]*//' -e 's/["`]*$//' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    
    if [ -n "$message" ]; then
        echo "$message"
        return 0
    else
        # Print error details to stderr
        local error_msg=$(echo "$response" | jq -r '.error.message // empty')
        if [ -n "$error_msg" ]; then
             warn "Groq API Error: $error_msg"
        else
             warn "Groq API returned empty response or invalid format."
             # warn "Raw response: ${response:0:200}..."
        fi
        return 1
    fi
}

is_low_quality_ai_message() {
    local msg="$1"
    local subject="${msg%%$'\n'*}"
    if [[ "$subject" =~ [[:alnum:]]+/.+ ]]; then
        return 0
    fi
    if [[ "$subject" =~ \.(kt|java|xml|gradle|kts|md|txt|json|yml|yaml|png|jpg|jpeg|gif|svg) ]]; then
        return 0
    fi
    if [[ "$subject" =~ ^(feat|fix|chore|refactor|docs|test|style|perf|build|ci|revert)(\(.+\))?:[[:space:]]*(Update|Change|Modify|Refactor)[[:space:]] ]]; then
        return 0
    fi
    return 1
}

# Git operations with safety checks
git_operations() {
    local build_gradle="app/build.gradle.kts"
    
    # Check if there are changes to commit
    if [ "$BUILD_TYPE" = "release" ]; then
        if git diff --quiet "$build_gradle"; then
            echo "No version changes to commit."
            return 0
        fi
    else
        # In debug/other modes, check for any changes (staged or unstaged)
        if git diff --quiet && git diff --cached --quiet; then
            return 0
        fi
    fi

    if [ "$SKIP_GIT" = "true" ]; then
        log "Skipping git operations (SKIP_GIT=true)."
        return 0
    fi

    if [ "$CI_RELEASE_ONLY" = "true" ]; then
        log "CI release mode: Automatically proceeding with git operations."
        REPLY="y"
    else
        if ! is_tty; then
            log "Non-interactive shell; skipping git operations."
            return 0
        fi
        
        # Ask for confirmation before committing and pushing
        local prompt_msg="Version has been updated. Do you want to commit and push these changes? [Y/n]: "
        if [ "$BUILD_TYPE" != "release" ]; then
            prompt_msg="Do you want to commit and push changes? [Y/n]: "
        fi
        
        read -p "$prompt_msg" -n 1 -r
        echo
        # Default to 'y' if Enter is pressed (REPLY is empty)
        REPLY=${REPLY:-y}
    fi
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Skipping git operations."
        return 0
    fi
    
    # Stage all changes (code + version bump)
    git add .
    
    local AI_SUCCESS="false"
    local DEFAULT_MSG=""
    
    # Try to generate AI commit message
    if [ -n "${GROQ_API_KEY:-}" ]; then
        # Get staged changes per file to ensure we don't miss files due to truncation
        local STAGED_DIFF=""
        local total_chars=0
        local max_total_chars=25000
        
        while IFS= read -r file; do
            [ -z "$file" ] && continue
            
            local file_diff
            file_diff=$(git diff --cached -- "$file")
            local diff_len=${#file_diff}
            
            # Truncate large single-file diffs to leave room for others
            if [ $diff_len -gt 6000 ]; then
                file_diff="${file_diff:0:6000}\n... (file $file truncated)\n"
                diff_len=${#file_diff}
            fi
            
            if [ $((total_chars + diff_len)) -gt $max_total_chars ]; then
                STAGED_DIFF+="${file_diff:0:$((max_total_chars - total_chars))}\n... (global limit reached)"
                break
            fi
            
            STAGED_DIFF+="$file_diff\n"
            total_chars=$((total_chars + diff_len))
        done < <(git diff --cached --name-only)
        
        local AI_MSG
        if AI_MSG=$(generate_ai_commit_message "$STAGED_DIFF"); then
            if is_low_quality_ai_message "$AI_MSG"; then
                warn "AI message looks like a file list; output was:"
                warn "${AI_MSG:0:4096}"
                warn "Retrying with stricter prompt."
                if AI_MSG=$(generate_ai_commit_message "$STAGED_DIFF" \
                    "Retry: previous output listed files or generic 'update'. Describe behavior changes only; avoid file names and vague 'update' wording."); then
                    if is_low_quality_ai_message "$AI_MSG"; then
                        warn "AI message still low quality; output was:"
                        warn "${AI_MSG:0:4096}"
                        warn "Falling back to manual/automatic message."
                    else
                        DEFAULT_MSG="$AI_MSG"
                        AI_SUCCESS="true"
                    fi
                fi
            else
                DEFAULT_MSG="$AI_MSG"
                AI_SUCCESS="true"
            fi
        fi
    else
        if [ "$CI_RELEASE_ONLY" != "true" ]; then
            echo "Tip: Set GROQ_API_KEY to enable AI-powered commit summaries."
        fi
    fi
    
    # Fallback if AI failed or no key
    if [ -z "$DEFAULT_MSG" ]; then
        # Generate a default commit message based on changed files
        # Get top 3 changed files (excluding build.gradle.kts which is always changed)
        CHANGED_FILES=$(git diff --cached --name-only | grep -v "build.gradle.kts" | head -n 3 | xargs)
        FILE_COUNT=$(git diff --cached --name-only | grep -v "build.gradle.kts" | wc -l)
        
        local V_NAME="${NEW_VERSION_NAME:-}"
        if [ -z "$V_NAME" ]; then
             # Try to extract if not set (debug mode)
             local v_line
             v_line=$(grep -E 'versionName = "[^"]*"' "$build_gradle" || echo "")
             V_NAME=$(echo "$v_line" | grep -o '"[^"]*"' | tr -d '"' || echo "unknown")
        fi
        
        if [ -z "$CHANGED_FILES" ]; then
            DEFAULT_MSG="chore(release): Bump version to $V_NAME"
        else
            local v_suffix=""
            if [ -n "$V_NAME" ]; then
                v_suffix=" (v$V_NAME)"
            fi
            
            # Create a more descriptive fallback message
            local file_list=$(echo "$CHANGED_FILES" | tr '\n' ',' | sed 's/,$//' | sed 's/,/, /g')
            if [ "$FILE_COUNT" -eq 1 ]; then
                 DEFAULT_MSG="fix: update $file_list$v_suffix"
            elif [ "$FILE_COUNT" -le 3 ]; then
                 DEFAULT_MSG="fix: update $file_list$v_suffix"
            else
                 DEFAULT_MSG="chore: update app behavior ($FILE_COUNT files changed)$v_suffix"
            fi
        fi
    fi
    
    COMMIT_MESSAGE="$DEFAULT_MSG"
    if [ "$CI_RELEASE_ONLY" = "true" ]; then
        echo "Using automated commit message: $COMMIT_MESSAGE"
    elif [ "$AI_SUCCESS" = "true" ]; then
        echo "AI Commit Message: $DEFAULT_MSG"
        read -p "Use this AI message? [Y/n]: " AI_REPLY
        AI_REPLY=${AI_REPLY:-y}
        if [[ ! $AI_REPLY =~ ^[Yy]$ ]]; then
            read -p "Enter custom commit message: " USER_MSG
            COMMIT_MESSAGE="$USER_MSG"
        fi
    else
        echo "Proposed commit message: $DEFAULT_MSG"
        read -p "Enter custom commit message (or press Enter to use proposed): " USER_MSG
        COMMIT_MESSAGE=${USER_MSG:-$DEFAULT_MSG}
    fi
    
    git commit -m "$COMMIT_MESSAGE"
    
    # Push to remote
    echo "Pushing to remote..."
    git push
    
    # Create and push tag only for release
    if [ "$BUILD_TYPE" = "release" ]; then
        TAG_NAME="v$NEW_VERSION_NAME"
        echo "Creating and pushing tag: $TAG_NAME"
        git tag -a "$TAG_NAME" -m "Release $NEW_VERSION_NAME"
        git push origin "$TAG_NAME"
    fi
}

# Main execution
main() {
    parse_args "$@"
    load_telegram_config

    echo "=== BooxReader Build and Install Script ==="

    # Check dependencies
    check_dependencies

    # Ensure Gradle wrapper/dist state is available without needing downloads.
    seed_gradle_user_home_from_default

    # Let the user choose build type (default debug)
    choose_build_type

    # Run tests first (fail fast before mutating version/building/installing).
    run_tests
    
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
    if [ "$CI_RELEASE_ONLY" = "true" ]; then
        echo "Skipping local build (CI release mode)..."
    else
        echo "Building the application ($BUILD_TYPE)..."
        local gradle_flags=()
        if [ "${GRADLE_OFFLINE:-false}" = "true" ]; then
            gradle_flags+=(--offline)
        fi
        if [ "$BUILD_TYPE" = "release" ]; then
            # Build both APK and Bundle for release
            ./gradlew "${gradle_flags[@]}" assembleRelease bundleRelease
        else
            ./gradlew "${gradle_flags[@]}" assembleDebug
        fi
    fi
    
    # Install the APK (if ADB is available)
    local apk_path
    if [ "$BUILD_TYPE" = "release" ]; then
        apk_path="app/build/outputs/apk/release/app-release.apk"
    else
        apk_path="app/build/outputs/apk/debug/app-debug.apk"
    fi
    if [ "$SKIP_INSTALL" = "true" ]; then
        log "Skipping install (SKIP_INSTALL=true)."
    elif [ "$ADB_AVAILABLE" = "true" ]; then
        echo "Installing the APK..."
        if [ ! -f "$apk_path" ]; then
            echo "Error: APK not found at $apk_path"
            exit 1
        fi

        local selected_device=""
        
        if [ -n "$TARGET_DEVICE_SERIAL" ]; then
            echo "Target device specified: $TARGET_DEVICE_SERIAL"
            if ! adb devices | grep -q "^$TARGET_DEVICE_SERIAL[[:space:]]"; then
                warn "Device $TARGET_DEVICE_SERIAL not found in connected devices list."
            fi
            selected_device="$TARGET_DEVICE_SERIAL"
        else
            # List all connected devices with detailed information
            if ! list_connected_devices; then
                echo "Error: No Android devices found"
                exit 1
            fi
            
            # Get all device serials
            local devices=()
            while IFS= read -r line; do
                if [[ $line =~ ^([^[:space:]]+)[[:space:]]+device$ ]]; then
                    devices+=("${BASH_REMATCH[1]}")
                fi
            done < <(adb devices)
            
            # Let user select a device (or auto-select if enabled)
            if [ "$AUTO_SELECT_DEVICE" = "true" ]; then
                # Auto-select the first device
                selected_device="${devices[0]}"
                echo "Auto-selected device: $selected_device"
            else
                # Interactive selection
                selected_device=$(select_device "${devices[@]}")
                if [ -z "$selected_device" ]; then
                    echo "Error: No device selected"
                    exit 1
                fi
            fi
        fi
        
        echo "Selected device: $selected_device"
        
        # Check device compatibility
        echo "Checking device compatibility..."
        if ! check_device_compatibility "$selected_device"; then
            echo "Device has compatibility issues. Continuing installation anyway..."
        fi
        
        # Check if signing keys are different - only uninstall if they are
        echo "Checking signing key compatibility..."
        if check_signing_key_difference "$apk_path" "$selected_device"; then
            # Keys are different - uninstall required
            echo "Uninstalling previous version due to signing key difference..."
            adb -s "$selected_device" uninstall my.hinoki.booxreader || true
        fi
        
        # Install the new APK on the selected device
        echo "Installing APK on $selected_device..."
        if ! adb -s "$selected_device" install -r "$apk_path"; then
            echo "Install failed, trying with signature fallback..."
            if ! adb_install_with_signature_fallback "$apk_path" "$selected_device"; then
                echo "Error: Failed to install APK on device $selected_device"
                exit 1
            fi
        fi
        
        # Wait for the app to be fully installed
        sleep 2
        
        # Check if package is installed
        if ! adb -s "$selected_device" shell pm list packages | grep -q "my.hinoki.booxreader"; then
            echo "Error: Package not installed after installation attempt"
            exit 1
        fi
        
        echo "Installation completed successfully."
        
        # Launch the app using monkey command (commented out to prevent auto-launch)
        # echo "Launching the app..."
        # adb shell monkey -p my.hinoki.booxreader -c android.intent.category.LAUNCHER 1
    else
        echo "Skipping install because ADB is unavailable."
    fi
    
    # Git operations (for release or if changes exist in debug)
    if [ "$SKIP_GIT" != "true" ]; then
        echo "Checking for Git operations..."
        git_operations
    fi
    
    # Send APK to Telegram (if enabled) - works for both debug and release
    if [ "$CI_RELEASE_ONLY" = "true" ]; then
        if [ "$TELEGRAM_ENABLED" = "true" ]; then
            log "CI release mode: Telegram upload will be handled by GitHub Actions."
        fi
    else
        echo "Sending APK to Telegram..."
        send_apk_to_telegram || true
    fi
    
    echo "Done."
    if [ "$CI_RELEASE_ONLY" = "true" ]; then
        echo "Version updated to $NEW_VERSION_NAME and pushed to remote. CI should handle the build."
    elif [ "$BUILD_TYPE" = "release" ]; then
        echo "Application has been built, installed, and version updated to $NEW_VERSION_NAME"
    else
        echo "Application has been built and installed (debug build)."
    fi
}

# Run main function
main "$@"
