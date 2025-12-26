package my.hinoki.booxreader.data.repo

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class SyncCryptoTest {

    // Mirroring private SyncCrypto for testing (Updated to match new implementation)
    private object SyncCryptoMirror {
        private const val ALGORITHM_AES = "AES"
        private const val TRANSFORMATION_ECB = "AES/ECB/PKCS5Padding" // Legacy
        private const val TRANSFORMATION_GCM = "AES/GCM/NoPadding"    // New (Secure)
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        
        // Use a fixed key for simplicity in this context.
        private const val KEY_STR = "BooxReaderAiKeysSyncSecret2024!!"

        private val secretKeySpec = SecretKeySpec(KEY_STR.toByteArray(Charsets.UTF_8), ALGORITHM_AES)

        fun encrypt(input: String): String {
            if (input.isBlank()) return ""
            return try {
                // Generate random IV
                val iv = ByteArray(GCM_IV_LENGTH)
                SecureRandom().nextBytes(iv)
                
                val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, spec)
                
                val encrypted = cipher.doFinal(input.toByteArray(Charsets.UTF_8))
                
                // Combine IV + Encrypted Data
                val combined = ByteArray(iv.size + encrypted.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
                
                Base64.encodeToString(combined, Base64.NO_WRAP)
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }

        fun decrypt(input: String): String {
            if (input.isBlank()) return ""
            val decoded = try {
                Base64.decode(input, Base64.NO_WRAP)
            } catch (e: Exception) {
                return ""
            }

            // 1. Try GCM (New Format)
            try {
                if (decoded.size > GCM_IV_LENGTH) {
                    val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
                    // Extract IV
                    val iv = ByteArray(GCM_IV_LENGTH)
                    System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)
                    
                    val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, spec)
                    
                    // Decrypt only the ciphertext part
                    return String(
                        cipher.doFinal(decoded, GCM_IV_LENGTH, decoded.size - GCM_IV_LENGTH),
                        Charsets.UTF_8
                    )
                }
            } catch (e: Exception) {
                // Failed to decrypt with GCM (likely old format or wrong key), fall through to ECB
            }

            // 2. Fallback to ECB (Old Format)
            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec)
                String(cipher.doFinal(decoded), Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    // Helper to generate old ECB format for testing backward compatibility
    private fun legacyEncrypt(input: String): String {
        val KEY_STR = "BooxReaderAiKeysSyncSecret2024!!"
        val ALGORITHM = "AES"
        val TRANSFORMATION = "AES/ECB/PKCS5Padding"
        
        val key = SecretKeySpec(KEY_STR.toByteArray(), ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(input.toByteArray())
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    @Test
    fun testNewGcmEncryptionDecryption() {
        val original = "sk-new-gcm-secret-key-12345"
        val encrypted = SyncCryptoMirror.encrypt(original)
        
        // Verify it looks different from original
        assertNotEquals(original, encrypted)
        
        // Verify we can decrypt it back
        val decrypted = SyncCryptoMirror.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun testGcmRandomness() {
        val original = "same-secret-text"
        val encrypted1 = SyncCryptoMirror.encrypt(original)
        val encrypted2 = SyncCryptoMirror.encrypt(original)
        
        // GCM should produce different outputs for same input due to random IV
        assertNotEquals(encrypted1, encrypted2)
        
        // But both should decrypt to the same value
        assertEquals(original, SyncCryptoMirror.decrypt(encrypted1))
        assertEquals(original, SyncCryptoMirror.decrypt(encrypted2))
    }

    @Test
    fun testBackwardCompatibility() {
        val original = "sk-legacy-ecb-secret-key-98765"
        
        // Encrypt using the OLD method (ECB)
        val legacyEncrypted = legacyEncrypt(original)
        
        // Try to decrypt using the NEW method (GCM -> fallback to ECB)
        val decrypted = SyncCryptoMirror.decrypt(legacyEncrypted)
        
        assertEquals("Should handle legacy ECB encrypted data", original, decrypted)
    }
}