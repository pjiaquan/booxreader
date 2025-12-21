package my.hinoki.booxreader.data.core.utils

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AiNoteSerializationTest {

    @Test
    fun serializeMessages_matchesWebFormat() {
        val messages =
            JSONArray()
                .put(message("user", "Original highlight"))
                .put(message("assistant", "Initial answer"))
                .put(message("user", "Follow-up question"))
                .put(message("assistant", "Follow-up answer"))

        val response = AiNoteSerialization.aiResponseFromMessages(messages.toString())

        val expected =
            "Initial answer\n\n---\nQ: Follow-up question\n\nFollow-up answer"
        assertEquals(expected, response)
    }

    @Test
    fun deserializeResponse_rebuildsMessages() {
        val original = "Original highlight"
        val response =
            "Initial answer\n\n---\nQ: Follow-up question\n\nFollow-up answer"

        val messagesJson = AiNoteSerialization.messagesFromOriginalAndResponse(original, response)
        val messages = JSONArray(messagesJson)

        assertEquals(4, messages.length())
        assertMessage(messages, 0, "user", "Original highlight")
        assertMessage(messages, 1, "assistant", "Initial answer")
        assertMessage(messages, 2, "user", "Follow-up question")
        assertMessage(messages, 3, "assistant", "Follow-up answer")
    }

    @Test
    fun originalTextFromMessages_readsFirstUser() {
        val messages =
            JSONArray()
                .put(message("user", "Original highlight"))
                .put(message("assistant", "Initial answer"))
        val original = AiNoteSerialization.originalTextFromMessages(messages.toString())
        assertEquals("Original highlight", original)
        assertNotNull(original)
    }

    @Test
    fun serializeThenDeserialize_roundTrips() {
        val original = "Original highlight"
        val messages =
            JSONArray()
                .put(message("user", original))
                .put(message("assistant", "Initial answer"))
                .put(message("user", "Q1"))
                .put(message("assistant", "A1"))
                .put(message("user", "Q2"))
                .put(message("assistant", "A2"))

        val response = AiNoteSerialization.aiResponseFromMessages(messages.toString())
        val rebuilt = JSONArray(
            AiNoteSerialization.messagesFromOriginalAndResponse(original, response)
        )

        assertEquals(messages.length(), rebuilt.length())
        for (i in 0 until messages.length()) {
            val expected = messages.getJSONObject(i)
            val actual = rebuilt.getJSONObject(i)
            assertEquals(expected.optString("role"), actual.optString("role"))
            assertEquals(expected.optString("content"), actual.optString("content"))
        }
    }

    private fun message(role: String, content: String) =
        org.json.JSONObject().put("role", role).put("content", content)

    private fun assertMessage(messages: JSONArray, index: Int, role: String, content: String) {
        val msg = messages.getJSONObject(index)
        assertEquals(role, msg.optString("role"))
        assertEquals(content, msg.optString("content"))
    }
}
