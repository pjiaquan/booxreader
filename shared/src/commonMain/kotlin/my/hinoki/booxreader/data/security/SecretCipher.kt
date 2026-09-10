package my.hinoki.booxreader.data.security

import kotlin.concurrent.Volatile

/**
 * 裝置綁定的機密加解密（at-rest protection）。
 *
 * 用途：LLM API key 這類「不該以明文落地」的短字串。
 * Android 實作使用 AndroidKeyStore 的 AES-256/GCM（見 androidMain）。
 */
interface SecretCipher {
    /**
     * 回傳可安全存放的密文。實作若無法加密（例如平台無可用金鑰庫），
     * 可回傳原始明文；[Secrets.protect] 會偵測並維持明文，避免 App 無法運作。
     */
    fun encrypt(plaintext: String): String

    /** 還原 [encrypt] 的結果；無法解密時回傳 null。 */
    fun decrypt(stored: String): String?
}

/**
 * 無加密實作。
 * 用於 JVM/Robolectric 單元測試（沒有 AndroidKeyStore provider），
 * 或在平台金鑰庫不可用時作為降級路徑。
 */
object NoOpSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(stored: String): String = stored
}

/** 平台提供的機密加解密實作。 */
expect fun platformSecretCipher(): SecretCipher

/**
 * 機密 at-rest 保護的統一入口。
 *
 * 舊版本把 API key 以明文寫入 SharedPreferences / Room，因此 [reveal] 對沒有
 * [ENCRYPTED_PREFIX] 的值一律原樣回傳 —— 不需要資料遷移，舊資料讀得到，
 * 下次寫入時才自動加密。
 */
object Secrets {

    const val ENCRYPTED_PREFIX = "enc:v1:"

    @Volatile private var testCipher: SecretCipher? = null

    private val platformCipher: SecretCipher by lazy {
        try {
            platformSecretCipher()
        } catch (_: Throwable) {
            NoOpSecretCipher
        }
    }

    private val cipher: SecretCipher
        get() = testCipher ?: platformCipher

    /** 測試用：安裝替代的 cipher（傳 null 還原成平台實作）。 */
    fun setCipherForTesting(cipher: SecretCipher?) {
        testCipher = cipher
    }

    /** 將明文轉為可存放的形式（已加密者原樣回傳）。 */
    fun protect(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        if (plaintext.startsWith(ENCRYPTED_PREFIX)) return plaintext

        val encrypted =
                try {
                    cipher.encrypt(plaintext)
                } catch (_: Throwable) {
                    null
                }

        // 加密不可用時維持明文（fail-open）：寧可降級，也不要讓使用者無法使用 App。
        if (encrypted.isNullOrBlank() || encrypted == plaintext) return plaintext
        return ENCRYPTED_PREFIX + encrypted
    }

    /** 還原 [protect] 的結果；明文（舊資料）原樣回傳，解密失敗回傳空字串。 */
    fun reveal(stored: String): String {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored

        val payload = stored.removePrefix(ENCRYPTED_PREFIX)
        return try {
            cipher.decrypt(payload) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** 值是否已加密（相對於舊版明文）。 */
    fun isProtected(stored: String): Boolean = stored.startsWith(ENCRYPTED_PREFIX)
}
