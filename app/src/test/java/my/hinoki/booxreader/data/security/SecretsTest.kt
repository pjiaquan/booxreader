package my.hinoki.booxreader.data.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Secrets] 的行為測試。
 *
 * 這裡不安裝真的 AndroidKeyStore（Robolectric 沒有 provider），而是注入一個可逆的假
 * cipher，驗證加密包裝、舊明文相容、以及加密失敗時的降級行為。
 */
class SecretsTest {

    /** 可逆假 cipher：用來確認 Secrets 真的把值換掉了，而不是原樣回傳。 */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(stored: String): String = stored.reversed()
    }

    private object BrokenCipher : SecretCipher {
        override fun encrypt(plaintext: String): String =
                throw IllegalStateException("keystore unavailable")

        override fun decrypt(stored: String): String =
                throw IllegalStateException("keystore unavailable")
    }

    @After
    fun tearDown() {
        Secrets.setCipherForTesting(null)
    }

    @Test
    fun protectEncryptsAndRevealRestores() {
        Secrets.setCipherForTesting(ReversingCipher())

        val stored = Secrets.protect("sk-secret-value")

        assertTrue(stored.startsWith(Secrets.ENCRYPTED_PREFIX))
        assertFalse(stored.contains("sk-secret-value"))
        assertTrue(Secrets.isProtected(stored))
        assertEquals("sk-secret-value", Secrets.reveal(stored))
    }

    @Test
    fun protectIsIdempotentForAlreadyEncryptedValues() {
        Secrets.setCipherForTesting(ReversingCipher())

        val once = Secrets.protect("sk-secret-value")
        assertEquals(once, Secrets.protect(once))
    }

    @Test
    fun revealReturnsLegacyPlaintextUnchanged() {
        Secrets.setCipherForTesting(ReversingCipher())

        // 舊版本寫入的明文沒有前綴，必須原樣讀出（不需要資料遷移）。
        assertEquals("sk-legacy-plain", Secrets.reveal("sk-legacy-plain"))
        assertFalse(Secrets.isProtected("sk-legacy-plain"))
    }

    @Test
    fun blankValuesStayBlank() {
        Secrets.setCipherForTesting(ReversingCipher())

        assertEquals("", Secrets.protect(""))
        assertEquals("", Secrets.reveal(""))
    }

    @Test
    fun protectFallsBackToPlaintextWhenCipherFails() {
        Secrets.setCipherForTesting(BrokenCipher)

        // fail-open：加密不可用時仍可保存，不讓 App 無法使用。
        assertEquals("sk-secret-value", Secrets.protect("sk-secret-value"))
    }

    @Test
    fun revealReturnsEmptyWhenDecryptionFails() {
        Secrets.setCipherForTesting(BrokenCipher)

        assertEquals("", Secrets.reveal(Secrets.ENCRYPTED_PREFIX + "garbage"))
    }

    @Test
    fun noOpCipherRoundTrips() {
        Secrets.setCipherForTesting(NoOpSecretCipher)

        assertEquals("sk-secret-value", Secrets.reveal(Secrets.protect("sk-secret-value")))
    }
}
