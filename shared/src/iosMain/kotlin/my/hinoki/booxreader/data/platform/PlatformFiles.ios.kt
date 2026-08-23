package my.hinoki.booxreader.data.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readByteAt
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

// 注意：此檔需在 macOS 上驗證編譯（Linux 無法編譯 iosMain）。
actual fun platformFiles(): PlatformFiles = PlatformFiles()

actual class PlatformFiles {

    actual suspend fun readUriBytes(uri: String): ByteArray? =
            withContext(Dispatchers.Default) {
                    val path = uri.removePrefix("file://")
                    val data: NSData? = NSFileManager.defaultManager.contentsAtPath(path)
                    data ?: return@withContext null
                    memScoped {
                            val ptr = data.bytes ?: return@withContext null
                            val length = data.length.toInt()
                            ByteArray(length).apply {
                                    for (i in 0 until length) {
                                            this[i] = ptr.readByteAt(i)
                                    }
                            }
                    }
            }

    actual fun writeCacheFile(fileName: String, bytes: ByteArray): String? {
            val dir =
                    NSSearchPathForDirectoriesInDomains(
                                    NSCachesDirectory,
                                    NSUserDomainMask,
                                    true
                            )
                            .firstOrNull() as? String
                            ?: return null
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val path = "$dir/$safeName"
            val data =
                    memScoped {
                            val ptr = allocArray<ByteVar>(bytes.size)
                            bytes.forEachIndexed { i, b -> ptr[i] = b }
                            NSData.create(bytes = ptr, length = bytes.size.toULong())
                    }
            return if (data.writeToFile(path, atomically = true)) path else null
    }

    actual fun appFilesDir(): String? =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                    .firstOrNull() as? String
}
