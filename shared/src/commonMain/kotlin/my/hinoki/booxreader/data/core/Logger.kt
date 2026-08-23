package my.hinoki.booxreader.data.core

/**
 * 日誌抽象（KMP commonMain）。
 * Android 實作：包裝 android.util.Log。
 */
interface Logger {
    fun v(tag: String, message: String)
    fun i(tag: String, message: String)
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
