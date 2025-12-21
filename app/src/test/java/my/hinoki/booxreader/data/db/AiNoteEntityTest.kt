package my.hinoki.booxreader.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiNoteEntityTest {

    @Test
    fun createAiNoteEntity_verifiesFields() {
        val timestamp = System.currentTimeMillis()
        val messagesJson = "[{\"role\":\"user\",\"content\":\"What is this?\"}]"
        val note = AiNoteEntity(
            id = 100L,
            bookId = "book_abc",
            messages = messagesJson,
            originalText = "What is this?",
            aiResponse = "This is a test.",
            locatorJson = "{...}",
            createdAt = timestamp
        )

        assertEquals(100L, note.id)
        assertEquals("book_abc", note.bookId)
        assertEquals(messagesJson, note.messages)
        assertEquals("What is this?", note.originalText)
        assertEquals("This is a test.", note.aiResponse)
        assertEquals("{...}", note.locatorJson)
        assertEquals(timestamp, note.createdAt)
    }

    @Test
    fun createAiNoteEntity_defaults() {
        val note = AiNoteEntity(
            bookId = "book_def",
            messages = "[]"
        )

        assertEquals(0L, note.id)
        assertNotNull(note.createdAt)
        assertEquals(null, note.locatorJson)
        assertEquals(null, note.originalText)
        assertEquals(null, note.aiResponse)
    }
}
