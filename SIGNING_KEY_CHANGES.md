# Signing Key Check Implementation

## Summary
Modified `run.sh` to implement intelligent signing key checking before uninstalling the app. The script now:

1. **Checks if the app is installed** - If not installed, no need to check keys
2. **Compares signing certificates** - Uses `apksigner` to extract SHA-256 fingerprints from both installed app and new APK
3. **Only uninstalls when necessary** - Uninstalls only if signing keys are different
4. **Safe fallback behavior** - If any step fails, assumes keys are different (uninstalls to be safe)

## Key Changes

### New Function: `check_signing_key_difference()`
- Takes APK path as parameter
- Returns 0 if keys are different (uninstall required)
- Returns 1 if keys are same (no uninstall needed)
- Uses robust certificate extraction via `apksigner verify --print-certs`
- Pulls installed APK from device for reliable comparison

### Modified Installation Logic
- Replaced unconditional `adb uninstall` with conditional check
- Only uninstalls when `check_signing_key_difference()` returns 0
- Preserves user data when upgrading with same signing key

## Benefits

1. **Data Preservation**: Users don't lose app data when upgrading with the same signing key
2. **Developer Friendly**: Debug builds can upgrade without data loss
3. **Safe Defaults**: Falls back to uninstall if certificate comparison fails
4. **Clear Logging**: Shows SHA-256 fingerprints for debugging

## Technical Details

The function:
1. Extracts SHA-256 digest from new APK using `apksigner`
2. Finds installed APK path using `adb shell pm path`
3. Pulls installed APK to local temp file
4. Extracts SHA-256 digest from installed APK
5. Compares the two SHA-256 fingerprints
6. Returns appropriate result

## Requirements

- `apksigner` (from Android SDK build-tools)
- `adb` (Android Debug Bridge)
- Device with app installed for comparison

## Error Handling

All error cases default to "keys are different" (return 0) to ensure safe behavior:
- `apksigner` not found
- Cannot find installed APK path
- Cannot pull APK from device
- Cannot extract certificates
- Any other failure during comparison
