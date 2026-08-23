package my.hinoki.booxreader.data.settings

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

    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) != null) defaults.longLongForKey(key).toLong() else default

    override fun putLong(key: String, value: Long) {
        defaults.setLongLong(value.toLong(), forKey = key)
    }

    override fun getFloat(key: String, default: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key).toFloat() else default

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value.toFloat(), forKey = key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun clearAll() {
        defaults.removePersistentDomainForName(defaults.dictionaryRepresentation().let { "" })
    }
}
