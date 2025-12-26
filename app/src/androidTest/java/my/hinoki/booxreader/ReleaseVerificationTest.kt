package my.hinoki.booxreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.util.ChineseConverter
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(AndroidJUnit4::class)
class ReleaseVerificationTest {

    @Test
    fun testChineseConverterPresenceAndFunctionality() {
        // This test verifies that ChineseConverter (in data.util) is present and functional.
        // If ProGuard strips it, this test will fail with ClassNotFoundException or similar at runtime.
        val input = "简体中文"
        val expected = "簡體中文" // OpenCC should convert this
        val result = ChineseConverter.toTraditional(input)
        
        assertNotNull("Converted text should not be null", result)
        assertEquals("Text should be converted to Traditional Chinese", expected, result.toString())
    }

    @Test
    fun testLocatorJsonHelperPresenceAndFunctionality() {
        // This verifies data.reader / reader package (LocatorJsonHelper)
        val href = Url("chapter1.html")!!
        val locator = Locator(
            href = href,
            mediaType = MediaType.HTML,
            locations = Locator.Locations(progression = 0.5)
        )
        
        val json = LocatorJsonHelper.toJson(locator)
        assertNotNull("JSON should not be null", json)
        
        val parsed = LocatorJsonHelper.fromJson(json)
        assertNotNull("Parsed locator should not be null", parsed)
        assertEquals("Href should match", locator.href.toString(), parsed?.href.toString())
    }

    @Test
    fun testUserSyncRepositoryInstantiation() {
        // This verifies that UserSyncRepository and its dependencies (data.auth, data.core, etc.)
        // can be instantiated without crashing due to missing classes.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        try {
            val repo = UserSyncRepository(context)
            assertNotNull("UserSyncRepository should be instantiated", repo)
            
            // We can't easily test sync without a real token/network, but instantiation 
            // covers a lot of ProGuard issues (missing dependency classes).
        } catch (e: NoClassDefFoundError) {
            fail("Failed to instantiate UserSyncRepository due to missing class: ${e.message}")
        } catch (e: Exception) {
            // Other exceptions (e.g. database access on main thread if any) might occur, 
            // but we are looking for ClassNotFound/NoClassDefFound.
            if (e is NoClassDefFoundError || e.cause is NoClassDefFoundError) {
                 fail("Missing class in dependency graph: ${e.message}")
            }
        }
    }
}
