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
            e.printStackTrace()
            // If initialization fails (e.g. data corruption or R8 issues), delete and retry
            try {
                context.deleteSharedPreferences("auth_prefs")
            } catch (deleteEx: Exception) {
                deleteEx.printStackTrace()
            }
            createEncryptedSharedPreferences()
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
        sharedPreferences.edit().putString("access_token", token).apply()
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

    open fun clearTokens() {
        sharedPreferences.edit().clear().apply()
    }
}

