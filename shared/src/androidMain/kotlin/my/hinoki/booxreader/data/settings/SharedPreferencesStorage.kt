package my.hinoki.booxreader.data.settings

import android.content.SharedPreferences

/** Android 的 KeyValueStorage 實作：直接包裝 SharedPreferences（型別 1:1 對應，資料相容）。 */
class SharedPreferencesStorage(private val prefs: SharedPreferences) : KeyValueStorage {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun clearAll() {
        prefs.edit().clear().apply()
    }
}
