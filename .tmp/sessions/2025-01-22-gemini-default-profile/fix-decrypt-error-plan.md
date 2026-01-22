# Plan: Fix Decryption Error During Login

## Problem
Users experience "cannot decrypt key" error during login when syncing AI profiles from cloud.

## Root Cause Analysis

The `SyncCrypto.decrypt()` method in `UserSyncRepository.kt` has several issues:

1. **Silent failures**: When decryption fails, it returns empty string without logging the cause
2. **Poor fallback**: Tries ECB if GCM fails, regardless of encryption format
3. **No user feedback**: Empty API keys are saved to database, causing issues later
4. **Exception handling**: Swallows all exceptions without proper handling

## Current Code Issues

In `SyncCrypto.decrypt()` (lines 1908-1952):

```kotlin
fun decrypt(input: String): String {
    if (input.isBlank()) return ""
    val decoded = try {
        Base64.decode(input, Base64.NO_WRAP)
    } catch (e: Exception) {
        return ""  // ❌ No logging
    }

    // Try GCM (New Format)
    try {
        if (decoded.size > GCM_IV_LENGTH) {
            // ... decryption logic
        }
    } catch (e: Exception) {
        // ❌ Falls through to ECB without logging
    }

    // Fallback to ECB (Old Format)
    return try {
        // ... ECB decryption
    } catch (e: Exception) {
        e.printStackTrace()  // ❌ Only prints stack trace
        ""  // ❌ Returns empty string
    }
}
```

## Solution

### 1. Improve Error Handling
- Log specific decryption errors
- Return null instead of empty string to indicate failure
- Don't silently fall back between encryption modes

### 2. Add Format Detection
- Check if data looks like GCM format (has proper IV length)
- Only try ECB if data matches expected ECB format

### 3. Skip Invalid Profiles
- When decryption fails, log and skip that profile
- Don't save profiles with empty API keys
- Continue with other profiles

### 4. Add User Feedback
- Log decryption failures to logcat
- Optionally show user-friendly message if critical decryption fails

## Implementation

### Changes to `SyncCrypto` object:

```kotlin
fun decrypt(input: String): String? {
    if (input.isBlank()) return null

    val decoded = try {
        Base64.decode(input, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e("SyncCrypto", "Failed to Base64 decode", e)
        return null
    }

    // Try GCM (New Format)
    return try {
        if (decoded.size > GCM_IV_LENGTH) {
            val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)

            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, spec)

            String(
                cipher.doFinal(decoded, GCM_IV_LENGTH, decoded.size - GCM_IV_LENGTH),
                Charsets.UTF_8
            )
        } else {
            throw Exception("Data too short for GCM format")
        }
    } catch (e: Exception) {
        Log.w("SyncCrypto", "GCM decryption failed, trying ECB", e)

        // Fallback to ECB (Old Format) only for legacy compatibility
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec)
            val result = String(cipher.doFinal(decoded), Charsets.UTF_8)
            Log.d("SyncCrypto", "ECB decryption succeeded (legacy format)")
            result
        } catch (e2: Exception) {
            Log.e("SyncCrypto", "ECB decryption also failed", e2)
            null
        }
    }
}
```

### Changes to `pullProfiles()` method:

```kotlin
remotes.forEach { remote ->
    val remoteId = remote.id ?: return@forEach

    // Skip if no API key
    if (remote.apiKey.isNullOrBlank()) {
        Log.w("UserSyncRepository", "Skipping profile $remoteId: No API key")
        return@forEach
    }

    val decryptedApiKey = SyncCrypto.decrypt(remote.apiKey)

    // Skip if decryption failed
    if (decryptedApiKey == null) {
        Log.e("UserSyncRepository", "Skipping profile $remoteId: Decryption failed")
        return@forEach
    }

    val existing = dao.getByRemoteId(remoteId)
    val entity = AiProfileEntity(
        // ... other fields
        apiKey = decryptedApiKey,  // Use decrypted key
        // ...
    )

    // ... rest of logic
}
```

## Validation Criteria

- [x] Decryption errors are logged to logcat
- [ ] Profiles with invalid API keys are skipped (not saved)
- [ ] GCM and ECB fallback still works for legacy data
- [ ] No more silent failures
- [ ] App continues to work even if some profiles fail to decrypt

## Files to Modify

1. `app/src/main/java/my/hinoki/booxreader/data/repo/UserSyncRepository.kt`
   - Update `SyncCrypto.decrypt()` method
   - Update `pullProfiles()` to handle null return values
