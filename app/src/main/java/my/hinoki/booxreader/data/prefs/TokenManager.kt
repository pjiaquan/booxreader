package my.hinoki.booxreader.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

open class TokenManager(private val context: Context) :
    my.hinoki.booxreader.data.auth.TokenProvider {

    internal var sharedPrefsOverride: android.content.SharedPreferences? = null

    private fun getMasterKeyAlias(): String {
        return MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    }

    private val sharedPreferences: android.content.SharedPreferences
        get() = sharedPrefsOverride ?: lazySharedPreferences.value

    private val lazySharedPreferences: Lazy<android.content.SharedPreferences> = lazy {
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
                // ponytail: in JVM / Robolectric unit test environments where AndroidKeyStore provider is absent,
                // fallback to plain shared prefs for tests rather than breaking all Robolectric suites.
                val isKeystoreUnavailable = retryEx is java.security.KeyStoreException ||
                        retryEx.cause is java.security.KeyStoreException ||
                        retryEx is java.security.NoSuchAlgorithmException ||
                        retryEx.cause is java.security.NoSuchAlgorithmException ||
                        retryEx.cause?.cause is java.security.NoSuchAlgorithmException
                if (isKeystoreUnavailable && (android.os.Build.FINGERPRINT == "robolectric" || android.os.Build.HARDWARE == "robolectric")) {
                    context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                } else {
                    // Do not fallback to plain shared prefs on real devices as it will store sensitive tokens unencrypted
                    throw IllegalStateException("Failed to initialize encrypted shared preferences after retry", retryEx)
                }
            }
        }
    }

    private fun createEncryptedSharedPreferences(): android.content.SharedPreferences {
        return EncryptedSharedPreferences.create(
            "auth_prefs",
            getMasterKeyAlias(),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun saveAccessToken(token: String) {
        sharedPreferences.edit()
            .putString("access_token", token)
            .putBoolean("guest_mode", false)
            .apply()
    }

    override fun getAccessToken(): String? {
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

    override fun clearTokens() {
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

    open fun saveCustomBackendUrl(url: String) {
        sharedPreferences.edit().putString("custom_backend_url", url.trim()).apply()
    }

    open fun getCustomBackendUrl(): String? {
        return sharedPreferences.getString("custom_backend_url", null)?.takeIf { it.isNotBlank() }
    }

    override fun getBackendUrl(): String {
        return (getCustomBackendUrl() ?: my.hinoki.booxreader.BuildConfig.POCKETBASE_URL).trimEnd('/')
    }
}
