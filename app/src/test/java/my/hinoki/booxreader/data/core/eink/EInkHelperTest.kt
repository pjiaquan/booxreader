package my.hinoki.booxreader.core.eink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EInkHelperTest {

    private fun setBuildProperties(manufacturer: String, brand: String, model: String) {
        ShadowBuild.setManufacturer(manufacturer)
        ShadowBuild.setBrand(brand)
        ShadowBuild.setModel(model)
    }

    @Test
    fun isBooxDevice_whenManufacturerIsOnyx_returnsTrue() {
        setBuildProperties("ONYX", "unknown", "unknown")
        assertTrue(EInkHelper.isBooxDevice())
    }

    @Test
    fun isBooxDevice_whenBrandIsOnyx_returnsTrue() {
        setBuildProperties("unknown", "Onyx", "unknown")
        assertTrue(EInkHelper.isBooxDevice())
    }

    @Test
    fun isBooxDevice_whenModelIsBoox_returnsTrue() {
        setBuildProperties("unknown", "unknown", "boox")
        assertTrue(EInkHelper.isBooxDevice())
    }

    @Test
    fun isBooxDevice_whenModelContainsBoox_returnsTrue() {
        setBuildProperties("unknown", "unknown", "Nova3 Color (boox)")
        assertTrue(EInkHelper.isBooxDevice())
    }

    @Test
    fun isBooxDevice_whenNotBoox_returnsFalse() {
        setBuildProperties("samsung", "samsung", "SM-G998B")
        assertFalse(EInkHelper.isBooxDevice())
    }
}
