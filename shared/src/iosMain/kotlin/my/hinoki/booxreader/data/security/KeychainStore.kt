package my.hinoki.booxreader.data.security

import cnames.structs.__CFData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * 最小化的 iOS Keychain 封裝（`kSecClassGenericPassword`）。
 *
 * 以 [service] 區分用途，同一 service 內以 [account] 識別項目。
 * 所有項目使用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
 * 裝置解鎖一次後背景可讀（每日摘要 worker 需要），且不隨備份遷移。
 *
 * 為什麼不自己做加解密：Kotlin/Native 的 CoreCrypto 只匯出 AES-CBC（無 GCM），
 * 而 `CFRelease` 也未匯出。把機密直接交給 Keychain（Secure Enclave / 硬體加密）
 * 保護，比手寫 CBC + MAC 更安全也更少程式碼。
 *
 * 注意：未簽章的測試 binary 或缺少 entitlement 時 Keychain 會回傳錯誤，
 * 呼叫端應自行決定降級行為（[put] 回傳 false）。
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainStore(private val service: String) {

    /** 寫入（或覆蓋）項目。回傳是否成功。 */
    fun put(account: String, value: String): Boolean {
        // 先刪再寫，讓重複寫入保持幂等（SecItemAdd 遇重複會回 errSecDuplicateItem）
        delete(account)

        val bytes = value.encodeToByteArray()
        return withQuery(account) { dict ->
            val data =
                    bytes.usePinned { pinned ->
                        CFDataCreate(
                                null,
                                if (bytes.isEmpty()) null
                                else pinned.addressOf(0).reinterpret(),
                                bytes.size.toLong()
                        )
                    }
            if (data == null) {
                false
            } else {
                try {
                    CFDictionaryAddValue(dict, kSecValueData, data)
                    CFDictionaryAddValue(
                            dict,
                            kSecAttrAccessible,
                            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                    )
                    SecItemAdd(dict, null) == errSecSuccess
                } finally {
                    freeRef(data)
                }
            }
        } ?: false
    }

    /** 讀取項目；不存在或失敗回傳 null。 */
    fun get(account: String): String? =
            withQuery(account) { dict ->
                CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
                CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)

                memScoped {
                    val result = alloc<CFTypeRefVar>()
                    if (SecItemCopyMatching(dict, result.ptr) != errSecSuccess) {
                        null
                    } else {
                        val data = result.value
                        if (data == null) {
                            null
                        } else {
                            try {
                                val cfData = data.reinterpret<__CFData>()
                                val length = CFDataGetLength(cfData).toInt()
                                val pointer = CFDataGetBytePtr(cfData)
                                if (length <= 0 || pointer == null) null
                                else pointer.readBytes(length).decodeToString()
                            } finally {
                                freeRef(data)
                            }
                        }
                    }
                }
            }

    /** 刪除項目。項目不存在視為成功。 */
    fun delete(account: String): Boolean =
            withQuery(account) { dict ->
                val status = SecItemDelete(dict)
                status == errSecSuccess || status == errSecItemNotFound
            } ?: false

    // --- 低階輔助 ---

    /**
     * 建立 query dictionary 並在結束後釋放所有 CF 物件。
     * 呼叫端負責讓傳入 dictionary 的值在 block 執行期間保持存活。
     */
    private fun <R> withQuery(
            account: String,
            block: (CFMutableDictionaryRef) -> R
    ): R? {
        val dict = CFDictionaryCreateMutable(null, 5, null, null) ?: return null
        val serviceRef = cfString(service)
        val accountRef = cfString(account)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, serviceRef)
            CFDictionaryAddValue(dict, kSecAttrAccount, accountRef)
            return block(dict)
        } finally {
            freeRef(serviceRef)
            freeRef(accountRef)
            freeRef(dict)
        }
    }

    private fun cfString(value: String): CFTypeRef? =
            CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)

    /**
     * 釋放 CF 物件。
     * Kotlin/Native 未匯出 `CFRelease`，改用 toll-free bridging 把所有權交給 ARC
     * （`CFBridgingRelease` 就是 CFRelease 的橋接版本，回傳值丟棄即可）。
     */
    private fun freeRef(ref: CFTypeRef?) {
        if (ref != null) CFBridgingRelease(ref)
    }
}
