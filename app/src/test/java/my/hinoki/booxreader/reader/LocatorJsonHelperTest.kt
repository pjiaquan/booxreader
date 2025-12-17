package my.hinoki.booxreader.reader

import org.json.JSONObject
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

class LocatorJsonHelperTest {

    @Test
    fun `toJson serializes Locator correctly with all fields`() {
        val locator = Locator(
            href = Url("OEBPS/ch1.xhtml")!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                fragments = listOf("epubcfi(/6/2!/4/1:0)"),
                progression = 0.5,
                totalProgression = 0.1
            ),
            title = "Chapter 1"
        )

        val jsonString = LocatorJsonHelper.toJson(locator)
        assertNotNull(jsonString)

        val json = JSONObject(jsonString!!)
        assertEquals("OEBPS/ch1.xhtml", json.getString("href"))
        assertEquals("application/xhtml+xml", json.getString("type"))
        assertEquals("Chapter 1", json.getString("title"))
        
        val locations = json.getJSONObject("locations")
        assertEquals(0.5, locations.getDouble("progression"), 0.001)
        assertEquals(0.1, locations.getDouble("totalProgression"), 0.001)
        // fragments are usually serialized? Readium JSON puts 'fragment' or 'cfi' depending on version.
        // Usually Readium Android puts Cfi in 'locations.cfi' if extended, or just fragments.
        // Let's check what it actually outputs.
    }

    @Test
    fun `toJson serializes Locator without totalProgression`() {
        // Simulate the case where totalProgression is missing (0% bug scenario)
        val locator = Locator(
            href = Url("text/part1.xhtml")!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = 0.2
                // totalProgression null
            )
        )

        val jsonString = LocatorJsonHelper.toJson(locator)
        assertNotNull(jsonString)
        
        val json = JSONObject(jsonString!!)
        assertEquals("text/part1.xhtml", json.getString("href"))
        
        val locations = json.getJSONObject("locations")
        assertEquals(0.2, locations.getDouble("progression"), 0.001)
        assertTrue("totalProgression should be missing", !locations.has("totalProgression"))
    }

    @Test
    fun `fromJson parses JSON correctly`() {
        val jsonString = """
            {
                "href": "OEBPS/ch2.xhtml",
                "type": "application/xhtml+xml",
                "locations": {
                    "progression": 0.8,
                    "totalProgression": 0.4
                }
            }
        """.trimIndent()

        val locator = LocatorJsonHelper.fromJson(jsonString)
        assertNotNull(locator)
        assertEquals("OEBPS/ch2.xhtml", locator?.href.toString())
        assertEquals(0.8, locator?.locations?.progression ?: 0.0, 0.001)
        assertEquals(0.4, locator?.locations?.totalProgression ?: 0.0, 0.001)
    }
}
