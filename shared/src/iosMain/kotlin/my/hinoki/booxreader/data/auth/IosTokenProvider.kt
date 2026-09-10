package my.hinoki.booxreader.data.auth

import my.hinoki.booxreader.data.security.KeychainStore
import platform.Foundation.NSUserDefaults

/**
 * iOS 的 TokenProvider 實作。
 *
 * access token 存在 **Keychain**（`kSecClassGenericPassword`，service
 * `my.hinoki.booxreader.tokens`），不再像舊版一樣明文存在 NSUserDefaults。
 * 後端 URL 不是機密，仍留在 NSUserDefaults。
 *
 * 升級相容：若 Keychain 還沒有 token、而 NSUserDefaults 有舊的明文值，
 * 讀取時會自動搬進 Keychain 並移除明文副本。
 * Keychain 不可用時（例如缺少 entitlement）退回 NSUserDefaults，確保登入狀態不會遺失。
 */
class IosTokenProvider(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val defaultBackendUrl: String = DEFAULT_BACKEND_URL
) : TokenProvider {

    /** 無參數建構子：Kotlin/Native 不匯出預設參數，Swift 需要顯式 init()。 */
    constructor() : this(NSUserDefaults.standardUserDefaults, DEFAULT_BACKEND_URL)

    private val keychain = KeychainStore(KEYCHAIN_SERVICE)

    override fun getAccessToken(): String? {
        keychain.get(KEY_ACCESS_TOKEN)?.let {
            return it
        }

        // 舊版（或 Keychain 不可用時）的明文值放在 NSUserDefaults：讀到就順手搬進 Keychain。
        val plaintext = defaults.stringForKey(KEY_ACCESS_TOKEN) ?: return null
        if (keychain.put(KEY_ACCESS_TOKEN, plaintext)) {
            defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
        }
        return plaintext
    }

    override fun getBackendUrl(): String =
        defaults.stringForKey(KEY_BACKEND_URL)?.takeIf { it.isNotBlank() } ?: defaultBackendUrl

    override fun saveAccessToken(token: String) {
        if (keychain.put(KEY_ACCESS_TOKEN, token)) {
            // 不要在 NSUserDefaults 留下明文副本
            defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
        } else {
            // Keychain 不可用時退回 NSUserDefaults，避免每次啟動都變成登出狀態
            defaults.setObject(token, forKey = KEY_ACCESS_TOKEN)
        }
    }

    override fun clearTokens() {
        keychain.delete(KEY_ACCESS_TOKEN)
        defaults.removeObjectForKey(KEY_ACCESS_TOKEN)
    }

    private companion object {
        const val KEYCHAIN_SERVICE = "my.hinoki.booxreader.tokens"

        /** 同時作為 Keychain account 與舊版的 NSUserDefaults key。 */
        const val KEY_ACCESS_TOKEN = "pocketbase_access_token"
        const val KEY_BACKEND_URL = "pocketbase_backend_url"

        // 與 Android app/build.gradle.kts 的 POCKETBASE_URL 預設值一致
        const val DEFAULT_BACKEND_URL = "https://pocket.risc-v.tw"
    }
}
