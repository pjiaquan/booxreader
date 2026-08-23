package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.auth.TokenProvider
import my.hinoki.booxreader.data.core.AndroidLogger
import my.hinoki.booxreader.data.core.AndroidReporter
import my.hinoki.booxreader.data.core.Logger
import my.hinoki.booxreader.data.core.Reporter
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.settings.KeyValueStorage
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

/**
 * Android 專用 factory：以 Context 組出 UserSyncRepository 所需的平台依賴。
 * （commonMain 版 UserSyncRepository 只依賴 TokenProvider / KeyValueStorage / Reporter / Logger。）
 */
fun createUserSyncRepository(
        context: Context,
        baseUrl: String? = null,
        tokenManager: TokenProvider? = null,
        prefs: KeyValueStorage? = null,
        syncPrefs: KeyValueStorage? = null,
        reporter: Reporter? = null,
        logger: Logger? = null
): UserSyncRepository {
        val appContext = context.applicationContext
        val resolvedTokenManager = tokenManager ?: TokenManager(appContext)
        return UserSyncRepository(
                tokenProvider = resolvedTokenManager,
                baseUrl = baseUrl,
                prefs =
                        prefs
                                ?: SharedPreferencesStorage(
                                        appContext.getSharedPreferences(
                                                ReaderSettings.PREFS_NAME,
                                                Context.MODE_PRIVATE
                                        )
                                ),
                syncPrefs =
                        syncPrefs
                                ?: SharedPreferencesStorage(
                                        appContext.getSharedPreferences(
                                                "sync_prefs",
                                                Context.MODE_PRIVATE
                                        )
                                ),
                reporter = reporter ?: AndroidReporter(appContext),
                logger = logger ?: AndroidLogger
        )
}
