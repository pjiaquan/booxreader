package my.hinoki.booxreader.data.settings

/**
 * 平台中立的 key-value 儲存抽象。
 * Android 實作：SharedPreferencesStorage（androidMain）
 * iOS 實作：NSUserDefaultsStorage（iosMain，規劃中）
 *
 * 刻意保留型別化 API（而非全字串化），確保與現有 SharedPreferences 資料相容、無遷移問題。
 */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)

    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)

    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)

    fun contains(key: String): Boolean
}
