package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.settings.KeyValueStorage
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

/** Android 專用 factory：以 Context 組出 AiProfileRepository 所需的平台依賴。 */
fun createAiProfileRepository(
        context: Context,
        syncRepo: UserSyncRepository,
        prefs: KeyValueStorage? = null
): AiProfileRepository {
        val appContext = context.applicationContext
        return AiProfileRepository(
                prefs =
                        prefs
                                ?: SharedPreferencesStorage(
                                        appContext.getSharedPreferences(
                                                ReaderSettings.PREFS_NAME,
                                                Context.MODE_PRIVATE
                                        )
                                ),
                syncRepo = syncRepo
        )
}
