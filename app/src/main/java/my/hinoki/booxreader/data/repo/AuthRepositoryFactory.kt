package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.auth.TokenProvider
import my.hinoki.booxreader.data.core.AndroidLogger
import my.hinoki.booxreader.data.core.AndroidReporter
import my.hinoki.booxreader.data.prefs.TokenManager

/** Android 專用 factory：以 Context 組出 AuthRepository 所需的平台依賴。 */
fun createAuthRepository(
        context: Context,
        tokenManager: TokenProvider? = null
): AuthRepository {
        val appContext = context.applicationContext
        val resolvedTokenManager = tokenManager ?: TokenManager(appContext)
        return AuthRepository(
                tokenProvider = resolvedTokenManager,
                syncRepo = createUserSyncRepository(appContext, tokenManager = resolvedTokenManager),
                reporter = AndroidReporter(appContext),
                logger = AndroidLogger
        )
}
