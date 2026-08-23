package my.hinoki.booxreader.data.platform

import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun platformFiles(): PlatformFiles = PlatformFiles()

actual class PlatformFiles {

    private val context get() = requireAppContext()

    actual suspend fun readUriBytes(uri: String): ByteArray? =
            withContext(Dispatchers.IO) {
                runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                }.getOrNull()
            }

    actual fun writeCacheFile(fileName: String, bytes: ByteArray): String? =
            runCatching {
                    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = File(context.cacheDir, safeName)
                    file.writeBytes(bytes)
                    file.absolutePath
            }.getOrNull()

    actual fun appFilesDir(): String? = context.filesDir?.absolutePath

    actual fun contentName(uri: String): String? =
            runCatching {
                    val cursor =
                            context.contentResolver.query(
                                    Uri.parse(uri),
                                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                    null,
                                    null,
                                    null
                            )
                    cursor?.use {
                            if (!it.moveToFirst()) return@use null
                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) it.getString(idx) else null
                    }
            }.getOrNull()

    actual fun isUriReadable(uri: String): Boolean =
            runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
            }.getOrDefault(false)

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun mkdirs(path: String): Boolean = File(path).mkdirs()

    actual fun writeFile(path: String, bytes: ByteArray): Boolean =
            runCatching {
                    File(path).parentFile?.mkdirs()
                    File(path).writeBytes(bytes)
                    true
            }.getOrDefault(false)

    actual fun readFile(path: String): ByteArray? =
            runCatching { File(path).readBytes() }.getOrNull()

    actual fun delete(path: String): Boolean = File(path).deleteRecursively()

    actual fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    actual fun fileLength(path: String): Long = File(path).length()

    actual fun readFilePrefix(path: String, maxBytes: Int): ByteArray? =
            runCatching {
                    File(path).inputStream().use { input ->
                            val buf = ByteArray(maxBytes.coerceAtLeast(0))
                            val read = input.read(buf)
                            if (read < 0) ByteArray(0) else buf.copyOf(read)
                    }
            }.getOrNull()

    actual fun contentType(uri: String): String? =
            runCatching { context.contentResolver.getType(Uri.parse(uri)) }.getOrNull()

    actual fun writeDownloadsFile(fileName: String, content: String): DownloadsWriteResult {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return runCatching {
                    val resolver = context.contentResolver
                    val contentValues =
                            android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                                    put(
                                            android.provider.MediaStore.Downloads.MIME_TYPE,
                                            "application/json"
                                    )
                                    put(
                                            android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                            android.os.Environment.DIRECTORY_DOWNLOADS
                                    )
                                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                            }
                    val uri =
                            resolver.insert(
                                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                            contentValues
                                    )
                                    ?: throw IllegalStateException("Unable to create download entry")
                    resolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                            writer?.write(content)
                                    ?: throw IllegalStateException("Unable to open output stream")
                    }
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    DownloadsWriteResult(
                            localPath = "Downloads/$safeName",
                            message = "Saved to Downloads/$safeName (public)"
                    )
            }.getOrElse { e ->
                    DownloadsWriteResult(localPath = null, message = "Local export failed: ${e.message ?: "Unknown error"}")
            }
    }
}
