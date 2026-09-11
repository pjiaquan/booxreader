package my.hinoki.booxreader.ui.common

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import my.hinoki.booxreader.data.settings.ReaderSettings
import java.util.Locale

object LocaleHelper {

    fun onAttach(context: Context): Context {
        val lang = getPersistedLanguage(context)
        return setLocale(context, lang)
    }

    fun getPersistedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("app_language", "system") ?: "system"
    }

    fun setLocale(context: Context, language: String): Context {
        if (language == "system") {
            return context // Do nothing, let system default take over (though typically system default is baked into context resources)
            // Note regarding system: If the user previously selected a language, we might need to clear overrides. 
            // However, Android's configuration handling usually resets on activity recreation if we don't override it.
            // But to be safe, if we want to revert to system, we might not be able to easily "unset" without restarting process
            // or we try to fetch system default locale.
            // For now, if "system", we try to respect the device's default.
        }

        return updateResources(context, localeFor(language))
    }

    /**
     * 語言設定值 → [Locale]。
     *
     * 抽成獨立函式以便測試，並且**刻意保留** `"zh"` = 繁體：既有使用者的偏好是這個值，
     * 不能改動其意義。簡體是新增的 `"zh-Hans"`（`Locale.SIMPLIFIED_CHINESE`，
     * 會解析到 `values-zh`；繁體則解析到 `values-zh-rTW`）。
     */
    fun localeFor(language: String): Locale =
            when (language) {
                LANGUAGE_TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
                LANGUAGE_SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
                LANGUAGE_ENGLISH -> Locale.ENGLISH
                else -> Locale.getDefault()
            }

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_TRADITIONAL_CHINESE = "zh"
    const val LANGUAGE_SIMPLIFIED_CHINESE = "zh-Hans"
    const val LANGUAGE_ENGLISH = "en"

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        
        config.setLocale(locale)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        config.setLocales(localeList)
        
        return context.createConfigurationContext(config)
    }
}
