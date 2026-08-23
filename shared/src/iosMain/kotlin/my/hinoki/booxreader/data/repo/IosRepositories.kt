package my.hinoki.booxreader.data.repo

import my.hinoki.booxreader.data.auth.IosTokenProvider
import my.hinoki.booxreader.data.auth.TokenProvider
import my.hinoki.booxreader.data.core.IosLogger
import my.hinoki.booxreader.data.core.IosReporter
import my.hinoki.booxreader.data.settings.NSUserDefaultsStorage

/**
 * iOS 專用 factory：以 NSUserDefaults 組出各 repo（對應 Android 的 create*Repository）。
 * Swift 端透過 Shared framework 呼叫這些函式。
 *
 * ⚠️ 注意：需在 macOS 上驗證編譯與 Swift 互操作（Linux 無法編譯 iosMain）。
 */
fun createIosUserSyncRepository(
    tokenProvider: TokenProvider = IosTokenProvider(),
    baseUrl: String? = null
): UserSyncRepository =
    UserSyncRepository(
        tokenProvider = tokenProvider,
        baseUrl = baseUrl,
        prefs = NSUserDefaultsStorage(),
        syncPrefs = NSUserDefaultsStorage(),
        reporter = IosReporter(),
        logger = IosLogger()
    )

fun createIosAuthRepository(
    tokenProvider: TokenProvider = IosTokenProvider()
): AuthRepository =
    AuthRepository(
        tokenProvider = tokenProvider,
        syncRepo = createIosUserSyncRepository(tokenProvider = tokenProvider),
        reporter = IosReporter(),
        logger = IosLogger()
    )

fun createIosAiProfileRepository(syncRepo: UserSyncRepository): AiProfileRepository =
    AiProfileRepository(
        prefs = NSUserDefaultsStorage(),
        syncRepo = syncRepo
    )

fun createIosAiNoteRepository(syncRepo: UserSyncRepository? = null): AiNoteRepository =
    AiNoteRepository(
        prefs = NSUserDefaultsStorage(),
        syncRepo = syncRepo,
        logger = IosLogger()
    )
