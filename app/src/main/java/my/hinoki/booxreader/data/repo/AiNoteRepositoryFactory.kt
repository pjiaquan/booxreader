package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.core.AndroidLogger
import my.hinoki.booxreader.data.settings.KeyValueStorage
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

/** Android 專用 factory：以 Context 組出 AiNoteRepository 所需的平台依賴。 */
fun createAiNoteRepository(
        context: Context,
        syncRepo: UserSyncRepository? = null,
        prefs: KeyValueStorage? = null,
        pocketBaseUrl: String? = BuildConfig.POCKETBASE_URL
): AiNoteRepository {
        val appContext = context.applicationContext
        return AiNoteRepository(
                prefs =
                        prefs
                                ?: SharedPreferencesStorage(
                                        appContext.getSharedPreferences(
                                                "reader_prefs",
                                                Context.MODE_PRIVATE
                                        )
                                ),
                syncRepo = syncRepo,
                logger = AndroidLogger,
                pocketBaseUrl = pocketBaseUrl
        )
}
