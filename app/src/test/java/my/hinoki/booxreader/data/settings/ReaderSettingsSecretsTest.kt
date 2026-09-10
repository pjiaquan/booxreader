package my.hinoki.booxreader.data.settings

import my.hinoki.booxreader.data.security.SecretCipher
import my.hinoki.booxreader.data.security.Secrets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 驗證 ReaderSettings 的 API key 是「落地加密、讀取解密」，且 syncAiApiKeys 預設關閉。
 */
class ReaderSettingsSecretsTest {

    private class InMemoryStorage : KeyValueStorage {
        val values = mutableMapOf<String, Any>()

        override fun getString(key: String): String? = values[key] as? String

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getBoolean(key: String, default: Boolean): Boolean =
                values[key] as? Boolean ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default

        override fun putFloat(key: String, value: Float) {
            values[key] = value
        }

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun clearAll() {
            values.clear()
        }
    }

    private class ReversingCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(stored: String): String = stored.reversed()
    }

    @Before
    fun setUp() {
        Secrets.setCipherForTesting(ReversingCipher())
    }

    @After
    fun tearDown() {
        Secrets.setCipherForTesting(null)
    }

    @Test
    fun apiKeyIsEncryptedAtRestAndDecryptedOnRead() {
        val storage = InMemoryStorage()

        ReaderSettings(apiKey = "sk-live-123", syncAiApiKeys = true).saveTo(storage)

        val raw = storage.getString("api_key").orEmpty()
        assertTrue(raw.startsWith(Secrets.ENCRYPTED_PREFIX))
        assertNotEquals("sk-live-123", raw)

        val loaded = ReaderSettings.fromStorage(storage)
        assertEquals("sk-live-123", loaded.apiKey)
        assertTrue(loaded.syncAiApiKeys)
    }

    @Test
    fun legacyPlaintextApiKeyIsStillReadable() {
        val storage = InMemoryStorage()
        storage.putString("api_key", "sk-legacy-plain")

        assertEquals("sk-legacy-plain", ReaderSettings.fromStorage(storage).apiKey)
    }

    @Test
    fun syncAiApiKeysDefaultsToOff() {
        assertFalse(ReaderSettings.fromStorage(InMemoryStorage()).syncAiApiKeys)
    }
}
