package my.hinoki.booxreader.data.core

import android.content.Context

/**
 * Android 的 Reporter 實作：包裝 ErrorReporter（儲存 crash report 供上傳）。
 */
class AndroidReporter(private val context: Context) : Reporter {

    override fun report(source: String, message: String?, throwable: Throwable? = null) {
        ErrorReporter.report(context, source, message, throwable)
    }
}
