package my.hinoki.booxreader.data.core

import platform.Foundation.NSLog

/**
 * iOS 的 Reporter 實作：NSLog 記錄（best-effort）。
 * 目前 iOS 端沒有 crash report 上傳管道；未來可接自家後端或第三方 crash 服務。
 * 注意：需在 macOS 上驗證編譯（Linux 無法編譯 iosMain）。
 */
class IosReporter : Reporter {

    override fun report(source: String, message: String?, throwable: Throwable?) {
        val msg = message ?: "unknown"
        if (throwable != null) {
            NSLog("[REPORT] $source: $msg — ${throwable.message ?: ""}")
        } else {
            NSLog("[REPORT] $source: $msg")
        }
    }
}
