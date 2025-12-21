package my.hinoki.booxreader.data.core.utils

import org.json.JSONArray
import org.json.JSONObject

object AiNoteSerialization {
    private const val TURN_SEPARATOR = "\n\n---\nQ: "
    private const val QA_SEPARATOR = "\n\n"

    fun originalTextFromMessages(messagesJson: String?): String? {
        val msgs = parseMessages(messagesJson)
        val first = msgs.optJSONObject(0) ?: return null
        return first.optString("content").takeIf { it.isNotBlank() }
    }

    fun aiResponseFromMessages(messagesJson: String?): String? {
        val msgs = parseMessages(messagesJson)
        if (msgs.length() == 0) return null
        val sb = StringBuilder()
        val firstAssistant = msgs.optJSONObject(1)
        if (firstAssistant?.optString("role") == "assistant") {
            val content = firstAssistant.optString("content")
            if (content.isNotBlank()) sb.append(content)
        }

        var i = 2
        while (i < msgs.length()) {
            val user = msgs.optJSONObject(i)
            val assistant = msgs.optJSONObject(i + 1)
            val userContent =
                user?.takeIf { it.optString("role") == "user" }?.optString("content").orEmpty()
            val assistantContent =
                assistant?.takeIf { it.optString("role") == "assistant" }?.optString("content").orEmpty()

            if (userContent.isNotBlank()) {
                sb.append(TURN_SEPARATOR).append(userContent)
                if (assistantContent.isNotBlank()) {
                    sb.append(QA_SEPARATOR).append(assistantContent)
                }
            }
            i += 2
        }

        return sb.toString().takeIf { it.isNotBlank() }
    }

    fun messagesFromOriginalAndResponse(originalText: String?, aiResponse: String?): String {
        val messages = JSONArray()
        val safeOriginal = originalText?.trim().orEmpty()
        messages.put(JSONObject().put("role", "user").put("content", safeOriginal))

        val response = aiResponse?.trim().orEmpty()
        if (response.isBlank()) return messages.toString()

        val segments = response.split(TURN_SEPARATOR)
        val first = segments.firstOrNull()?.trim().orEmpty()
        if (first.isNotBlank()) {
            messages.put(JSONObject().put("role", "assistant").put("content", first))
        }

        if (segments.size > 1) {
            for (i in 1 until segments.size) {
                val seg = segments[i].trimStart()
                if (seg.isBlank()) continue
                val parts = seg.split(QA_SEPARATOR, limit = 2)
                val question = parts.getOrNull(0)?.trim().orEmpty()
                val answer = parts.getOrNull(1)?.trim().orEmpty()
                if (question.isNotBlank()) {
                    messages.put(JSONObject().put("role", "user").put("content", question))
                }
                if (answer.isNotBlank()) {
                    messages.put(JSONObject().put("role", "assistant").put("content", answer))
                }
            }
        }

        return messages.toString()
    }

    private fun parseMessages(messagesJson: String?): JSONArray {
        if (messagesJson.isNullOrBlank()) return JSONArray()
        return runCatching { JSONArray(messagesJson) }.getOrDefault(JSONArray())
    }
}
