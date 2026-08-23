package my.hinoki.booxreader.data.core

/**
 * 錯誤回報抽象（KMP commonMain）。
 * Android 實作：包裝 ErrorReporter（:app 提供 adapter）。
 * iOS 實作：規劃中。
 */
interface Reporter {
    fun report(source: String, message: String?, throwable: Throwable? = null)
}
