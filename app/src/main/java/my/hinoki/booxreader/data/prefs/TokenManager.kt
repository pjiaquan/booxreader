package my.hinoki.booxreader.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

open class TokenManager(private val context: Context) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val sharedPreferences: android.content.SharedPreferences by lazy {
        try {
            createEncryptedSharedPreferences()
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to initialize encrypted shared prefs, clearing and retrying", e)
            e.printStackTrace()
            // If initialization fails (e.g. data corruption, R8 issues, or device change), delete and retry
            try {
                context.deleteSharedPreferences("auth_prefs")
            } catch (deleteEx: Exception) {
                android.util.Log.e("TokenManager", "Failed to clear shared prefs", deleteEx)
                deleteEx.printStackTrace()
            }
            try {
                createEncryptedSharedPreferences()
            } catch (retryEx: Exception) {
                android.util.Log.e("TokenManager", "Failed to create encrypted shared prefs on retry", retryEx)
                retryEx.printStackTrace()
                // If still failing, create fallback plain shared prefs (less secure but functional)
                context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedSharedPreferences(): android.content.SharedPreferences {
        return EncryptedSharedPreferences.create(
            "auth_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    open fun saveAccessToken(token: String) {
        try {
            sharedPreferences.edit().putString("access_token", token).apply()
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to save access token", e)
        }
    }

    open fun getAccessToken(): String? {
        return try {
            sharedPreferences.getString("access_token", null)
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to read access token, clearing", e)
            clearTokens()
            null
        }
    }

    open fun getRefreshToken(): String? {
        return try {
            sharedPreferences.getString("refresh_token", null)
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to read refresh token, clearing", e)
            clearTokens()
            null
        }
    }

    open fun saveRefreshToken(token: String) {
        try {
            sharedPreferences.edit().putString("refresh_token", token).apply()
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to save refresh token", e)
        }
    }

    open fun clearTokens() {
        try {
            sharedPreferences.edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to clear tokens", e)
        }
    }
}

