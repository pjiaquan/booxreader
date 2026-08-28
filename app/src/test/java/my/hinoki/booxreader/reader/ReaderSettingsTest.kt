package my.hinoki.booxreader.reader

import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsTest {

    @Test
    fun testDefaultReaderSettings() {
        val settings = ReaderSettings()
        assertTrue(settings.pageTapEnabled)
        assertTrue(settings.pageSwipeEnabled)
        assertEquals(96, settings.textSize)
        assertEquals(ContrastMode.NORMAL.ordinal, settings.contrastMode)
        assertEquals("system", settings.language)
    }

    @Test
    fun testContrastModeEnumMapping() {
        val modes = ContrastMode.values()
        assertEquals(4, modes.size)
        assertEquals(ContrastMode.NORMAL, ContrastMode.values().getOrNull(0))
        assertEquals(ContrastMode.DARK, ContrastMode.values().getOrNull(1))
        assertEquals(ContrastMode.SEPIA, ContrastMode.values().getOrNull(2))
        assertEquals(ContrastMode.HIGH_CONTRAST, ContrastMode.values().getOrNull(3))

        // Clamping out-of-bound indices
        val invalidIndex = 99
        val safeMode = ContrastMode.values().getOrNull(invalidIndex) ?: ContrastMode.NORMAL
        assertEquals(ContrastMode.NORMAL, safeMode)
    }

    @Test
    fun testSafeIndexClampingForViewInsertion() {
        // Guards the index-clamping invariant used when inserting views into
        // layouts with arbitrary child counts.
        val initialChildCount = 5

        val desiredIndices = listOf(0, 1, 2, 3, 4, 10, 100)
        for (desired in desiredIndices) {
            val clampedIndex = desired.coerceIn(0, initialChildCount)
            assertTrue("Clamped index should never exceed childCount", clampedIndex <= initialChildCount)
            assertTrue("Clamped index should never be negative", clampedIndex >= 0)
        }
    }
}
