package my.hinoki.booxreader.data.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import my.hinoki.booxreader.data.security.KeychainStore
import platform.Foundation.NSUserDefaults

/**
 * iOS token 儲存測試（在 macOS runner 的 `iosSimulatorArm64Test` 執行）。
 *
 * 最重要的不變量：token 一定讀得回來 —— 改用 Keychain 不能讓使用者莫名其妙被登出。
 * 若 Keychain 真的可用（已簽章 / 有 entitlement），額外確認 NSUserDefaults 不再留明文副本。
 */
class IosTokenProviderTest {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val provider = IosTokenProvider(defaults)

    private val keychainAvailable: Boolean =
        KeychainStore(PROBE_SERVICE).let { probe ->
            val ok = probe.put(PROBE_ACCOUNT, "1") && probe.get(PROBE_ACCOUNT) == "1"
            probe.delete(PROBE_ACCOUNT)
            ok
        }

    @BeforeTest
    fun setUp() {
        provider.clearTokens()
        defaults.removeObjectForKey(KEY_BACKEND_URL)
    }

    @AfterTest
    fun tearDown() {
        provider.clearTokens()
        defaults.removeObjectForKey(KEY_BACKEND_URL)
    }

    @Test
    fun savedTokenIsReadBack() {
        provider.saveAccessToken("token-123")

        assertEquals("token-123", provider.getAccessToken())
    }

    @Test
    fun clearTokensRemovesToken() {
        provider.saveAccessToken("token-456")

        provider.clearTokens()

        assertNull(provider.getAccessToken())
    }

    @Test
    fun tokenIsNotLeftInPlainDefaultsWhenKeychainWorks() {
        provider.saveAccessToken("token-789")

        // 不論 Keychain 是否可用，token 都必須讀得回來
        assertEquals("token-789", provider.getAccessToken())

        if (keychainAvailable) {
            // 明文副本必須被清掉
            assertNull(defaults.stringForKey(KEY_ACCESS_TOKEN))
        }
    }

    @Test
    fun legacyPlaintextTokenIsStillReadableAndMigrated() {
        // 模擬舊版本：明文 token 直接放在 NSUserDefaults
        defaults.setObject("legacy-token", forKey = KEY_ACCESS_TOKEN)

        assertEquals("legacy-token", provider.getAccessToken())

        if (keychainAvailable) {
            assertNull(defaults.stringForKey(KEY_ACCESS_TOKEN))
        }

        // 遷移後仍必須讀得到
        assertEquals("legacy-token", provider.getAccessToken())
    }

    @Test
    fun backendUrlReadsStoredValueAndFallsBackToDefault() {
        assertEquals(DEFAULT_BACKEND_URL, provider.getBackendUrl())

        defaults.setObject("https://example.test", forKey = KEY_BACKEND_URL)
        assertEquals("https://example.test", provider.getBackendUrl())

        // 空白視為未設定
        defaults.setObject("   ", forKey = KEY_BACKEND_URL)
        assertEquals(DEFAULT_BACKEND_URL, provider.getBackendUrl())
    }

    private companion object {
        /** 與 IosTokenProvider 內部的 key 保持一致（private companion 無法從測試存取）。 */
        const val KEY_ACCESS_TOKEN = "pocketbase_access_token"
        const val KEY_BACKEND_URL = "pocketbase_backend_url"
        const val DEFAULT_BACKEND_URL = "https://pocket.risc-v.tw"

        const val PROBE_SERVICE = "my.hinoki.booxreader.test.probe"
        const val PROBE_ACCOUNT = "probe"
    }
}
