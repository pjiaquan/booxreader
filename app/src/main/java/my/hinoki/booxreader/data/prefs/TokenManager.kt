package my.hinoki.booxreader.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

open class TokenManager(private val context: Context) {

    private val masterKeyAlias by lazy {
        try {
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        } catch (e: Exception) {
            android.util.Log.e("TokenManager", "Failed to create master key", e)
            throw RuntimeException("Failed to create master key", e)
        }
    }

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
        sharedPreferences.edit()
            .putString("access_token", token)
            .putBoolean("guest_mode", false)
            .apply()
    }

    open fun getAccessToken(): String? {
        return sharedPreferences.getString("access_token", null)
    }

    open fun saveRefreshToken(token: String) {
        sharedPreferences.edit().putString("refresh_token", token).apply()
    }

    open fun getRefreshToken(): String? {
        return sharedPreferences.getString("refresh_token", null)
    }

    open fun saveUser(userId: String, email: String) {
        sharedPreferences.edit()
            .putString("user_id", userId)
            .putString("user_email", email)
            .apply()
    }

    open fun clearTokens() {
        sharedPreferences.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("user_id")
            .remove("user_email")
            .putBoolean("guest_mode", false)
            .apply()
    }

    open fun saveRememberMe(remember: Boolean, email: String) {
        if (remember) {
            sharedPreferences.edit()
                .putBoolean("remember_me", true)
                .putString("saved_email", email)
                .apply()
        } else {
            sharedPreferences.edit()
                .putBoolean("remember_me", false)
                .remove("saved_email")
                .apply()
        }
    }

    open fun isRememberMeEnabled(): Boolean {
        return sharedPreferences.getBoolean("remember_me", false)
    }

    open fun getSavedEmail(): String? {
        return sharedPreferences.getString("saved_email", null)
    }

    open fun saveGuestMode(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("guest_mode", enabled).apply()
    }

    open fun isGuestMode(): Boolean {
        return sharedPreferences.getBoolean("guest_mode", false)
    }
}
