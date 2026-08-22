package my.hinoki.booxreader.data.auth

/**
 * Token 存取抽象，供共用網路層（Ktor client、SSE 等）使用。
 *
 * Android 實作：TokenManager（EncryptedSharedPreferences）
 * iOS 實作：Keychain 版（規劃中）
 */
interface TokenProvider {
    fun getAccessToken(): String?
    fun getBackendUrl(): String
}
