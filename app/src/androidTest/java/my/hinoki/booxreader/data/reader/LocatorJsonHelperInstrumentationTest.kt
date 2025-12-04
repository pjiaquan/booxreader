package my.hinoki.booxreader.data.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator

@RunWith(AndroidJUnit4::class)
class LocatorJsonHelperInstrumentationTest {

    @Test
    fun testJsonConversion_isReversible() {
        // Create a dummy Locator
        // Note: Adjust properties based on the actual Locator version if needed
        val locator = Locator(
            href = "chapter1.html",
            type = "text/html",
            locations = Locator.Locations(progression = 0.5)
        )

        // 1. Convert to JSON string
        val json = LocatorJsonHelper.toJson(locator)
        assertNotNull("JSON string should not be null", json)
        assertTrue("JSON should contain href", json!!.contains("chapter1.html"))

        // 2. Convert back to Locator
        val restoredLocator = LocatorJsonHelper.fromJson(json)
        assertNotNull("Restored Locator should not be null", restoredLocator)

        // 3. Verify properties
        assertEquals("href matches", locator.href, restoredLocator?.href)
        assertEquals("type matches", locator.type, restoredLocator?.type)
        // Precision issues with doubles in JSON? usually fine for simple equality if exactly same
        assertEquals("progression matches", locator.locations.progression, restoredLocator?.locations?.progression)
    }

    @Test
    fun testFromJson_handleInvalidInput() {
        val result = LocatorJsonHelper.fromJson("invalid json }")
        assertNull("Should return null for invalid JSON", result)

        val resultEmpty = LocatorJsonHelper.fromJson("")
        assertNull("Should return null for empty string", resultEmpty)
    }
}

