package my.hinoki.booxreader.data.settings

import android.content.SharedPreferences
import my.hinoki.booxreader.data.remote.HttpConfig

data class ReaderSettings(
    val fontSize: Int = 150,
    val fontWeight: Int = 400,
    val pageTapEnabled: Boolean = true,
    val booxBatchRefresh: Boolean = true,
    val booxFastMode: Boolean = true,
    val serverBaseUrl: String = HttpConfig.DEFAULT_BASE_URL,
    val useStreaming: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun saveTo(prefs: SharedPreferences) {
        val timestamp = if (updatedAt > 0) updatedAt else System.currentTimeMillis()
        prefs.edit()
            .putInt("font_size", fontSize)
            .putInt("font_weight", fontWeight)
            .putBoolean("page_tap_enabled", pageTapEnabled)
            .putBoolean("boox_batch_refresh", booxBatchRefresh)
            .putBoolean("boox_fast_mode", booxFastMode)
            .putString("server_base_url", serverBaseUrl)
            .putBoolean("use_streaming", useStreaming)
            .putLong("settings_updated_at", timestamp)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "reader_prefs"

        fun fromPrefs(prefs: SharedPreferences): ReaderSettings {
            val updatedAt = prefs.getLong("settings_updated_at", 0L)
            return ReaderSettings(
                fontSize = prefs.getInt("font_size", 150),
                fontWeight = prefs.getInt("font_weight", 400),
                pageTapEnabled = prefs.getBoolean("page_tap_enabled", true),
                booxBatchRefresh = prefs.getBoolean("boox_batch_refresh", true),
                booxFastMode = prefs.getBoolean("boox_fast_mode", true),
                serverBaseUrl = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                    ?: HttpConfig.DEFAULT_BASE_URL,
                useStreaming = prefs.getBoolean("use_streaming", false),
                updatedAt = updatedAt
            )
        }
    }
}
