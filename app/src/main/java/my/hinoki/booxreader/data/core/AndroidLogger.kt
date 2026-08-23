package my.hinoki.booxreader.data.core

/** Android 的 Logger 實作：包裝 android.util.Log。 */
object AndroidLogger : Logger {

    override fun v(tag: String, message: String) {
        android.util.Log.v(tag, message)
    }

    override fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    override fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            android.util.Log.w(tag, message, throwable)
        } else {
            android.util.Log.w(tag, message)
        }
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            android.util.Log.e(tag, message, throwable)
        } else {
            android.util.Log.e(tag, message)
        }
    }
}
