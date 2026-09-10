package my.hinoki.booxreader.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "booxreader_secret_v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

actual fun platformSecretCipher(): SecretCipher =
        if (isKeystoreUnavailable()) NoOpSecretCipher else AndroidKeystoreSecretCipher

/**
 * Robolectric / 純 JVM 測試環境沒有 AndroidKeyStore provider。
 * 這裡退回明文實作，避免所有 Robolectric 測試都必須 mock 金鑰庫；
 * 真機（Build.FINGERPRINT/HARDWARE 不會是 robolectric）一律走加密路徑。
 */
private fun isKeystoreUnavailable(): Boolean =
        Build.FINGERPRINT == "robolectric" || Build.HARDWARE == "robolectric"

/**
 * AES-256/GCM，金鑰存在 AndroidKeyStore（不可匯出，離開 App 就無法取出）。
 * 密文格式：`<base64(iv)>:<base64(ciphertext||tag)>`
 */
private object AndroidKeystoreSecretCipher : SecretCipher {

    @Volatile private var cachedKey: SecretKey? = null

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) + ":" + encode(encrypted)
    }

    override fun decrypt(stored: String): String? {
        val separator = stored.indexOf(':')
        if (separator <= 0 || separator == stored.length - 1) return null

        val iv = decode(stored.substring(0, separator))
        val payload = decode(stored.substring(separator + 1))
        if (iv.size != GCM_IV_BYTES) return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
                KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
