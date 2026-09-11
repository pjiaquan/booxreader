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

/**
 * 不記錄任何東西的 [Logger]。
 *
 * 用在「記錄失敗細節有幫助、但不能強制所有呼叫端都提供 Logger」的地方：
 * 建構子參數以它為預設值，正式環境再由 factory 注入 `AndroidLogger`。
 */
object NoOpLogger : Logger {
    override fun v(tag: String, message: String) = Unit

    override fun i(tag: String, message: String) = Unit

    override fun d(tag: String, message: String) = Unit

    override fun w(tag: String, message: String, throwable: Throwable?) = Unit

    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
