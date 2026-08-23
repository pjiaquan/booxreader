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
}
