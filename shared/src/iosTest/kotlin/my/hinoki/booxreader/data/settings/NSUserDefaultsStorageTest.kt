package my.hinoki.booxreader.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 的 KeyValueStorage（NSUserDefaults）行為測試
 * （由 :shared:iosSimulatorArm64Test 在 macOS 上執行）。
 */
class NSUserDefaultsStorageTest {

    @Test
    fun typedRoundTripAndClear() {
        val storage = NSUserDefaultsStorage()
        storage.clearAll()

        assertNull(storage.getString("string_key"))

        storage.putString("string_key", "value")
        assertEquals("value", storage.getString("string_key"))

        storage.putBoolean("bool_key", true)
        assertTrue(storage.getBoolean("bool_key", false))

        storage.putInt("int_key", 42)
        assertEquals(42, storage.getInt("int_key", -1))

        storage.putLong("long_key", 9_000_000_000L)
        assertEquals(9_000_000_000L, storage.getLong("long_key", -1L))

        storage.putFloat("float_key", 1.5f)
        assertEquals(1.5f, storage.getFloat("float_key", 0f))

        assertTrue(storage.contains("string_key"))

        storage.clearAll()
        assertFalse(storage.contains("string_key"))
        assertNull(storage.getString("string_key"))
    }
}
