package my.hinoki.booxreader.data.settings

import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/** iOS 的 KeyValueStorage 實作：NSUserDefaults（型別 1:1 對應）。 */
class NSUserDefaultsStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStorage {

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else default

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun getInt(key: String, default: Int): Int =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key).toInt() else default

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    // Long 以字串儲存：Kotlin/Native 的 NSUserDefaults 未匯出 longLongForKey/setLongLong
    // （ObjC selector 綁定名稱不同），用 stringForKey 儲存最穩妥。
    override fun getLong(key: String, default: Long): Long =
        defaults.stringForKey(key)?.toLongOrNull() ?: default

    override fun putLong(key: String, value: Long) {
        defaults.setObject(value.toString(), forKey = key)
    }

    override fun getFloat(key: String, default: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key).toFloat() else default

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value.toFloat(), forKey = key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun clearAll() {
        val domain = NSBundle.mainBundle.bundleIdentifier
        if (domain != null) {
            defaults.removePersistentDomainForName(domain)
        } else {
            // 測試二進位或無 bundle 的環境：逐一移除標準 domain 的 key
            val keys = defaults.dictionaryRepresentation().keys
            for (key in keys) {
                defaults.removeObjectForKey(key.toString())
            }
        }
    }
}
