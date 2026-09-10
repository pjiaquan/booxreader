package my.hinoki.booxreader.data.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

private const val KEYCHAIN_SERVICE = "my.hinoki.booxreader.secrets"
private const val SHA256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"

actual fun platformSecretCipher(): SecretCipher = IosKeychainSecretCipher

/**
 * iOS 機密儲存：機密本體存進 Keychain，落地在 prefs / Room 的只是一個 token。
 *
 * token = 明文的 SHA-256（hex，64 字元），因此：
 * - 同一機密重複 [SecretCipher.encrypt] 會得到相同 token，寫入是幂等的
 *   （`ReaderSettings.saveTo` 每次存檔都會呼叫，不會讓 Keychain 項目無限成長）。
 * - token 不洩漏明文；沒有 Keychain 存取權就無法還原。
 *
 * 加解密交給 [KeychainStore]（Keychain / Secure Enclave），不自行實作密碼學。
 * Keychain 失敗時會丟出例外，由 [Secrets] 決定降級行為（fail-open 為明文）。
 */
private object IosKeychainSecretCipher : SecretCipher {

    private val store = KeychainStore(KEYCHAIN_SERVICE)

    override fun encrypt(plaintext: String): String {
        val account = sha256Hex(plaintext)
        check(store.put(account, plaintext)) { "Keychain write failed for secret" }
        return account
    }

    override fun decrypt(stored: String): String? {
        if (stored.length != SHA256_HEX_LENGTH) return null
        return store.get(stored)
    }
}

/**
 * SHA-256（hex）。
 * Foundation 沒有摘要 API，CryptoKit 是 Swift-only，因此用 CommonCrypto。
 */
@OptIn(ExperimentalForeignApi::class)
private fun sha256Hex(value: String): String {
    val input = value.encodeToByteArray()
    val digest = ByteArray(32)

    input.usePinned { pinned ->
        digest.usePinned { out ->
            val inputPointer = if (input.isEmpty()) null else pinned.addressOf(0)
            CC_SHA256(inputPointer, input.size.toUInt(), out.addressOf(0).reinterpret())
        }
    }

    return buildString(digest.size * 2) {
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            append(HEX_DIGITS[unsigned shr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
}
