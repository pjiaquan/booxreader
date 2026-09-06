package my.hinoki.booxreader.data.reader

import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AnnotationStyle
import my.hinoki.booxreader.data.repo.AnnotationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class AnnotationFeatureTest {

    @Test
    fun testAnnotationStyleValues() {
        assertEquals("UNDERLINE", AnnotationStyle.UNDERLINE.name)
        assertEquals("DASHED", AnnotationStyle.DASHED.name)
        assertEquals("GRAY_FILL", AnnotationStyle.GRAY_FILL.name)
    }

    @Test
    fun testAnnotationEntityCreation() {
        val entity = AnnotationEntity(
            id = 1L,
            bookId = "book-123",
            locatorJson = "{}",
            selectedText = "Sample highlighted text",
            note = "My thought",
            style = AnnotationStyle.DASHED.name
        )
        assertEquals(1L, entity.id)
        assertEquals("book-123", entity.bookId)
        assertEquals("Sample highlighted text", entity.selectedText)
        assertEquals("My thought", entity.note)
        assertEquals("DASHED", entity.style)
    }
}
