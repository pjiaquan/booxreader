# Fix Decryption Error - Status Update

## Problem
Users experience "cannot decrypt key" error during login when syncing AI profiles from cloud.

## Changes Made
Due to file editing complexities, the fix requires manual application or the repository may need to be edited with these changes:

### 1. Update `SyncCrypto.decrypt()` in `UserSyncRepository.kt` (around line 1908)

Change signature:
```kotlin
// OLD
fun decrypt(input: String): String {
    if (input.isBlank()) return ""
    // ...
}

// NEW
fun decrypt(input: String): String? {
    if (input.isBlank()) return null
    // ...
}
```

Update Base64 decode failure handling:
```kotlin
// OLD
} catch (e: Exception) {
    return ""
}

// NEW
} catch (e: Exception) {
    Log.e("SyncCrypto", "Failed to Base64 decode", e)
    return null
}
```

Update GCM decryption to log success and return variable:
```kotlin
// OLD
return String(
    cipher.doFinal(...),
    Charsets.UTF_8
)

// NEW
val decrypted = String(
    cipher.doFinal(...),
    Charsets.UTF_8
)
Log.d("SyncCrypto", "GCM decryption succeeded")
decrypted
```

Update GCM catch and ECB fallback to log failures:
```kotlin
// OLD
} catch (e: Exception) {
    // Failed to decrypt with GCM (likely old format or wrong key), fall through
    // to ECB
}

// 2. Fallback to ECB (Old Format)
return try {
    val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec)
    String(cipher.doFinal(decoded), Charsets.UTF_8)
} catch (e: Exception) {
    e.printStackTrace()
    ""
}
}

// NEW
} catch (e: Exception) {
    Log.w("SyncCrypto", "GCM decryption failed, trying ECB", e)

    // 2. Fallback to ECB (Old Format) for legacy compatibility
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
```

### 2. Update `pullProfiles()` in `UserSyncRepository.kt` (around line 1278)

Add checks before creating entity:
```kotlin
// Add these checks after getting remoteId and before creating entity

// Skip if no API key
if (remote.apiKey.isNullOrBlank()) {
    Log.w("UserSyncRepository", "Skipping profile $remoteId: No API key in remote data")
    return@forEach
}

// Decrypt API key (replace the SyncCrypto.decrypt call in AiProfileEntity creation)
val decryptedApiKey = SyncCrypto.decrypt(remote.apiKey)

// Skip if decryption failed
if (decryptedApiKey == null) {
    Log.e("UserSyncRepository", "Skipping profile $remoteId: Failed to decrypt API key")
    return@forEach
}

// Update entity creation to use decryptedApiKey
AiProfileEntity(
    // ... other fields ...
    apiKey = decryptedApiKey,  // Changed from SyncCrypto.decrypt(remote.apiKey ?: "")
    // ... other fields ...
)
```

## Benefits
- ✅ Decryption errors are logged to logcat for debugging
- ✅ Profiles with invalid keys are skipped (not saved)
- ✅ GCM and ECB fallback still works for legacy data
- ✅ App continues to work even if some profiles fail to decrypt

## Alternative: Apply Manually
If you have access to a Kotlin IDE (Android Studio), you can manually apply these changes by:
1. Open `app/src/main/java/my/hinoki/booxreader/data/repo/UserSyncRepository.kt`
2. Navigate to line ~1908 (decrypt function)
3. Apply all the changes listed above for decrypt function
4. Navigate to line ~1278 (pullProfiles function)
5. Apply the checks for apiKey and use decryptedApiKey variable
6. Build and test
