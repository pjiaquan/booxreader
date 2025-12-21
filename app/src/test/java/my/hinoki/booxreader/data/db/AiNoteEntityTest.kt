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
            locatorJson = "{...}",
            createdAt = timestamp
        )

        assertEquals(100L, note.id)
        assertEquals("book_abc", note.bookId)
        assertEquals(messagesJson, note.messages)
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
    }
}

