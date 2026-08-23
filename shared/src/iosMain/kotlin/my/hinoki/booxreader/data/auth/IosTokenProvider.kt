package my.hinoki.booxreader.data.auth

import platform.Foundation.NSUserDefaults

/**
 * iOS 的 TokenProvider 實作：以 NSUserDefaults 儲存 token 與後端 URL。
 *
 * ⚠️ best-effort：正式版建議改用 Keychain（kSecClassGenericPassword）。
 * 注意：需在 macOS 上驗證編譯（Linux 無法編譯 iosMain）。
 */
class IosTokenProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val defaultBackendUrl: String = DEFAULT_BACKEND_URL
) : TokenProvider {

    override fun getAccessToken(): String? = defaults.stringForKey(KEY_ACCESS_TOKEN)

    override fun getBackendUrl(): String =
        defaults.stringForKey(KEY_BACKEND_URL)
            ?.takeIf { it.isNotBlank() }
            ?: defaultBackendUrl

    override fun saveAccessToken(token: String) {
        defaults.setObject(token, forKey = KEY_ACCESS_TOKEN)
    }

    override fun clearTokens() {
        defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "pocketbase_access_token"
        const val KEY_BACKEND_URL = "pocketbase_backend_url"

        // 與 Android app/build.gradle.kts 的 POCKETBASE_URL 預設值一致
        const val DEFAULT_BACKEND_URL = "https://pocket.risc-v.tw"
    }
}
