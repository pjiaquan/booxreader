package my.hinoki.booxreader.data.settings

import android.content.SharedPreferences
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.core.eink.EInkHelper

data class ReaderSettings(
    // 字體大小現在使用文石系統設定，不再在此處儲存
    // 字體粗細現在使用預設值，不再在此處儲存
    val pageTapEnabled: Boolean = true,
    val booxBatchRefresh: Boolean = true,
    val booxFastMode: Boolean = true,
    val contrastMode: Int = EInkHelper.ContrastMode.NORMAL.ordinal,
    val serverBaseUrl: String = HttpConfig.DEFAULT_BASE_URL,
    val useStreaming: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {

    fun saveTo(prefs: SharedPreferences) {
        val timestamp = if (updatedAt > 0) updatedAt else System.currentTimeMillis()
        prefs.edit()
            // 字體大小現在使用文石系統設定，不再在此處儲存
            // 字體粗細現在使用預設值，不再在此處儲存
            .putBoolean("page_tap_enabled", pageTapEnabled)
            .putBoolean("boox_batch_refresh", booxBatchRefresh)
            .putBoolean("boox_fast_mode", booxFastMode)
            .putInt("contrast_mode", contrastMode)
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
                // 字體大小現在使用文石系統設定，不再在此處讀取
                // 字體粗細現在使用預設值，不再在此處讀取
                pageTapEnabled = prefs.getBoolean("page_tap_enabled", true),
                booxBatchRefresh = prefs.getBoolean("boox_batch_refresh", true),
                booxFastMode = prefs.getBoolean("boox_fast_mode", true),
                contrastMode = prefs.getInt("contrast_mode", EInkHelper.ContrastMode.NORMAL.ordinal),
                serverBaseUrl = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                    ?: HttpConfig.DEFAULT_BASE_URL,
                useStreaming = prefs.getBoolean("use_streaming", false),
                updatedAt = updatedAt
            )
        }
    }
}
