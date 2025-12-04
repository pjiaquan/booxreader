package my.hinoki.booxreader.core.utils

import android.content.Context
import android.net.Uri
import java.io.File

object FileUtils {

    fun copyToCache(context: Context, uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open inputStream for $uri")

        val outFile = File(context.cacheDir, "epub_${System.currentTimeMillis()}.epub")
        input.use { ins ->
            outFile.outputStream().use { outs ->
                ins.copyTo(outs)
            }
        }
        return outFile
    }
}

