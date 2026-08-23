@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package my.hinoki.booxreader.data.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

// 注意：此檔需在 macOS 上驗證編譯（Linux 無法編譯 iosMain）。
actual fun platformFiles(): PlatformFiles = PlatformFiles()

private fun nsDataToByteArray(data: NSData): ByteArray? {
        val src = data.bytes ?: return null
        val length = data.length.toInt()
        return ByteArray(length).apply {
                usePinned {
                        memcpy(it.addressOf(0), src, data.length)
                }
        }
}

private fun byteArrayToNSData(bytes: ByteArray): NSData? =
        if (bytes.isEmpty()) {
                NSData()
        } else {
                memScoped {
                        NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.toULong())
                }
        }

private fun filePathOf(uri: String): String = uri.removePrefix("file://")

actual class PlatformFiles {

    actual suspend fun readUriBytes(uri: String): ByteArray? =
            withContext(Dispatchers.Default) {
                    val data =
                            NSFileManager.defaultManager.contentsAtPath(filePathOf(uri))
                                    ?: return@withContext null
                    nsDataToByteArray(data)
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
            val data = byteArrayToNSData(bytes) ?: return null
            return if (data.writeToFile(path, atomically = true)) path else null
    }

    actual fun appFilesDir(): String? =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                    .firstOrNull() as? String

    actual fun isUriReadable(uri: String): Boolean =
            NSFileManager.defaultManager.isReadableFileAtPath(filePathOf(uri))

    actual fun contentName(uri: String): String? =
            filePathOf(uri).substringAfterLast('/').takeIf { it.isNotBlank() }

    actual fun exists(path: String): Boolean =
            NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun mkdirs(path: String): Boolean =
            NSFileManager.defaultManager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
            )

    actual fun writeFile(path: String, bytes: ByteArray): Boolean {
            val parent = path.substringBeforeLast('/')
            if (parent.isNotEmpty() && parent != path) {
                    if (!exists(parent) && !mkdirs(parent)) {
                            return false
                    }
            }
            val data = byteArrayToNSData(bytes) ?: return false
            return data.writeToFile(path, atomically = true)
    }

    actual fun readFile(path: String): ByteArray? =
            NSFileManager.defaultManager.contentsAtPath(path)?.let { nsDataToByteArray(it) }

    actual fun delete(path: String): Boolean =
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)

    actual fun rename(from: String, to: String): Boolean =
            NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null)

    actual fun fileLength(path: String): Long {
            val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
                    ?: return 0L
            return (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
    }

    actual fun readFilePrefix(path: String, maxBytes: Int): ByteArray? {
            val data = NSFileManager.defaultManager.contentsAtPath(path)
                    ?: return null
            val length = minOf(data.length.toInt(), maxBytes.coerceAtLeast(0))
            if (length <= 0) {
                    return ByteArray(0)
            }
            return nsDataToByteArray(data)?.take(length)?.toByteArray()
    }

    actual fun contentType(uri: String): String? =
            when (filePathOf(uri).substringAfterLast('.', "").lowercase()) {
                    "json" -> "application/json"
                    "epub" -> "application/epub+zip"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    else -> null
            }

    actual fun writeDownloadsFile(fileName: String, content: String): DownloadsWriteResult {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir =
                    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                            .firstOrNull() as? String
                            ?: return DownloadsWriteResult(
                                    localPath = null,
                                    message = "Local export failed: no Documents directory"
                            )
            val path = "$dir/$safeName"
            val data = byteArrayToNSData(content.encodeToByteArray()) ?: return DownloadsWriteResult(
                    localPath = null,
                    message = "Local export failed: encode error"
            )
            return if (data.writeToFile(path, atomically = true)) {
                    DownloadsWriteResult(
                            localPath = path,
                            message = "Saved to $path (Documents)"
                    )
            } else {
                    DownloadsWriteResult(
                            localPath = null,
                            message = "Local export failed: write error"
                    )
            }
    }
}
