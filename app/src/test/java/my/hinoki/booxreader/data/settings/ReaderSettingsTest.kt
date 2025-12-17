package my.hinoki.booxreader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {

    @Test
    fun safeUserPromptTemplate_appendsPlaceholderIfMissing() {
        val settings = ReaderSettings(aiUserPromptTemplate = "Just some text")
        val safeTemplate = settings.safeUserPromptTemplate
        assertEquals("%s\n\nJust some text", safeTemplate)
    }

    @Test
    fun safeUserPromptTemplate_keepsPlaceholderIfPresent() {
        val settings = ReaderSettings(aiUserPromptTemplate = "Text with %s placeholder")
        val safeTemplate = settings.safeUserPromptTemplate
        assertEquals("Text with %s placeholder", safeTemplate)
    }

    @Test
    fun safeUserPromptTemplate_handlesEmptyString() {
        val settings = ReaderSettings(aiUserPromptTemplate = "")
        val safeTemplate = settings.safeUserPromptTemplate
        assertEquals("%s\n\n", safeTemplate)
    }
}
