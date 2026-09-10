package my.hinoki.booxreader.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS 機密儲存的 round-trip 測試（在 macOS runner 的 `iosSimulatorArm64Test` 執行）。
 *
 * 未簽章的測試 binary 可能拿不到 Keychain（`errSecMissingEntitlement`），此時 [Secrets]
 * 會 fail-open 成明文。因此這裡驗證的是最重要的安全不變量 —— **機密永遠不會遺失** ——
 * 並在 Keychain 真的可用時，額外確認落地值確實被換成 token。
 */
class IosSecretCipherTest {

    @Test
    fun protectAndRevealNeverLoseTheSecret() {
        val secret = "sk-ios-round-trip-12345"

        val stored = Secrets.protect(secret)

        assertEquals(secret, Secrets.reveal(stored))

        if (Secrets.isProtected(stored)) {
            assertTrue(stored.startsWith(Secrets.ENCRYPTED_PREFIX))
            assertFalse(stored.contains(secret))
        }
    }

    @Test
    fun repeatedProtectIsStable() {
        val secret = "sk-ios-stable-99999"

        val first = Secrets.protect(secret)
        val second = Secrets.protect(secret)

        assertEquals(first, second)
        assertEquals(secret, Secrets.reveal(second))
    }

    @Test
    fun differentSecretsGetDifferentTokens() {
        val a = Secrets.protect("sk-ios-a")
        val b = Secrets.protect("sk-ios-b")

        assertFalse(a == b)
        assertEquals("sk-ios-a", Secrets.reveal(a))
        assertEquals("sk-ios-b", Secrets.reveal(b))
    }

    @Test
    fun legacyPlaintextIsStillReadable() {
        assertEquals("plain-legacy", Secrets.reveal("plain-legacy"))
        assertFalse(Secrets.isProtected("plain-legacy"))
    }

    @Test
    fun blankValuesStayBlank() {
        assertEquals("", Secrets.protect(""))
        assertEquals("", Secrets.reveal(""))
    }
}
