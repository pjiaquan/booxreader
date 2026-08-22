package my.hinoki.booxreader.data.core.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 序列化/反序列化 AI 對話 messages（[{role, content}] 陣列）的共用工具。
 * 已由 org.json 改用 kotlinx.serialization，可在 KMP commonMain 使用。
 */
object AiNoteSerialization {
    private const val TURN_SEPARATOR = "\n\n---\nQ: "
    private const val QA_SEPARATOR = "\n\n"

    fun originalTextFromMessages(messagesJson: String?): String? {
        val msgs = parseMessages(messagesJson)
        val first = msgs.getOrNull(0) as? JsonObject ?: return null
        return first.optString("content").takeIf { it.isNotBlank() }
    }

    fun aiResponseFromMessages(messagesJson: String?): String? {
        val msgs = parseMessages(messagesJson)
        if (msgs.isEmpty()) return null
        val sb = StringBuilder()
        val firstAssistant = msgs.getOrNull(1) as? JsonObject
        if (firstAssistant?.optString("role") == "assistant") {
            val content = firstAssistant.optString("content")
            if (content.isNotBlank()) sb.append(content)
        }

        var i = 2
        while (i < msgs.size) {
            val user = msgs.getOrNull(i) as? JsonObject
            val assistant = msgs.getOrNull(i + 1) as? JsonObject
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
        val messages = buildJsonArray {
            val safeOriginal = originalText?.trim().orEmpty()
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", safeOriginal)
                }
            )

            val response = aiResponse?.trim().orEmpty()
            if (response.isBlank()) return@buildJsonArray

            val segments = response.split(TURN_SEPARATOR)
            val first = segments.firstOrNull()?.trim().orEmpty()
            if (first.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", first)
                    }
                )
            }

            if (segments.size > 1) {
                for (i in 1 until segments.size) {
                    val seg = segments[i].trimStart()
                    if (seg.isBlank()) continue
                    val parts = seg.split(QA_SEPARATOR, limit = 2)
                    val question = parts.getOrNull(0)?.trim().orEmpty()
                    val answer = parts.getOrNull(1)?.trim().orEmpty()
                    if (question.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", question)
                            }
                        )
                    }
                    if (answer.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", answer)
                            }
                        )
                    }
                }
            }
        }

        return messages.toString()
    }

    private fun parseMessages(messagesJson: String?): JsonArray {
        if (messagesJson.isNullOrBlank()) return JsonArray(emptyList())
        return runCatching {
            Json.parseToJsonElement(messagesJson) as? JsonArray ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
    }

    private fun JsonObject.optString(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
