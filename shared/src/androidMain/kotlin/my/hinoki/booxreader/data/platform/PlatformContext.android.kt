package my.hinoki.booxreader.data.platform

import android.content.Context

/**
 * 平台 Application Context holder（androidMain）。
 * 由 BooxReaderApp.onCreate 呼叫 initPlatformContext 初始化；
 * Robolectric 測試在 @Before 中呼叫。
 */
private var appContext: Context? = null

fun initPlatformContext(context: Context) {
    appContext = context.applicationContext
}

internal fun requireAppContext(): Context =
        appContext
                ?: throw IllegalStateException(
                        "Platform context not initialized; call initPlatformContext(context)"
                )
