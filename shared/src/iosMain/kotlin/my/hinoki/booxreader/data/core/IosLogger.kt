package my.hinoki.booxreader.data.core

import platform.Foundation.NSLog

/**
 * iOS 的 Logger 實作：包裝 NSLog。
 * 注意：需在 macOS 上驗證編譯（Linux 無法編譯 iosMain）。
 */
class IosLogger : Logger {

    override fun v(tag: String, message: String) {
        NSLog("[V] $tag: $message")
    }

    override fun i(tag: String, message: String) {
        NSLog("[I] $tag: $message")
    }

    override fun d(tag: String, message: String) {
        NSLog("[D] $tag: $message")
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[W] $tag: $message — ${throwable.message ?: ""}")
        } else {
            NSLog("[W] $tag: $message")
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[E] $tag: $message — ${throwable.message ?: ""}")
        } else {
            NSLog("[E] $tag: $message")
        }
    }
}
