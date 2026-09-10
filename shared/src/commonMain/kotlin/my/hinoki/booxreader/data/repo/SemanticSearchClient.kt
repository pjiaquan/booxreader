package my.hinoki.booxreader.data.repo

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.platform.ioDispatcher
import my.hinoki.booxreader.data.remote.HttpConfig

/**
 * 語意搜尋（自 `AiNoteRepository` 抽出的 semantic 叢集，約 300 行）。
 *
 * 涵蓋 Qdrant 相關筆記查詢、語意搜尋、剩餘額度查詢，以及結果解析 helper。
 * `AiNoteRepository` 保留同名 delegate，因此呼叫端不變。
 */

internal class SemanticSearchClient(private val host: AiNoteRepository) {

    private fun parseSemanticResultsArray(body: String): JsonArray? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            return parseJsonArray(trimmed)
        }
        val root = parseJsonObject(trimmed) ?: return null
        val keys = listOf("results", "matches", "data", "items", "points", "hits")
        for (key in keys) {
            val candidate = root.optJsonArray(key)
            if (candidate != null) return candidate
        }
        return null
    }

    private fun parseReason(item: JsonObject, payload: JsonObject?): String? {
        val direct =
                host.firstNonBlank(
                        item.optString("reason", ""),
                        item.optString("matchReason", ""),
                        payload?.optString("reason", ""),
                        payload?.optString("matchReason", ""),
                        payload?.optString("reasoning", "")
                )
        if (!direct.isNullOrBlank()) return direct

        val reasonArray = payload?.optJsonArray("reasons") ?: item.optJsonArray("reasons")
        if (reasonArray != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until reasonArray.size) {
                val part = reasonArray.optString(i).trim()
                if (part.isNotEmpty()) parts.add(part)
            }
            if (parts.isNotEmpty()) return parts.take(3).joinToString(" / ")
        }

        val tagArray = payload?.optJsonArray("tags") ?: item.optJsonArray("tags")
        if (tagArray != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until tagArray.size) {
                val part = tagArray.optString(i).trim()
                if (part.isNotEmpty()) parts.add(part)
            }
            if (parts.isNotEmpty()) return "Shared themes: ${parts.take(3).joinToString(", ")}"
        }
        return null
    }

    private fun buildSemanticQuery(note: AiNoteEntity): String {
        val original =
                note.originalText?.takeIf { it.isNotBlank() }
                        ?: AiNoteSerialization.originalTextFromMessages(note.messages).orEmpty()
        val answer =
                note.aiResponse?.takeIf { it.isNotBlank() }
                        ?: AiNoteSerialization.aiResponseFromMessages(note.messages).orEmpty()
        val merged = listOf(original.trim(), answer.trim()).filter { it.isNotBlank() }
        if (merged.isEmpty()) return ""
        return merged.joinToString("\n\n").take(2_000)
    }

    suspend fun searchRelatedNotesFromQdrant(
            note: AiNoteEntity,
            limit: Int = 5
    ): List<AiNoteRepository.SemanticRelatedNote> =
            withContext(ioDispatcher) {
                val boundedLimit = limit.coerceIn(1, 5)
                val query = buildSemanticQuery(note)
                if (query.isBlank()) return@withContext emptyList()

                val baseUrl = host.getSemanticSearchBaseUrl()
                if (baseUrl.isBlank()) return@withContext emptyList()

                val url = baseUrl + HttpConfig.PATH_AI_NOTES_SEMANTIC_SEARCH
                val requestPayload =
                        jsonObj {
                            put("query", query)
                            put("limit", boundedLimit)
                            put("noteId", note.remoteId ?: note.id.toString())
                            // Detail-page related matching should search across books.
                            // Keep bookId unset here so the server doesn't apply same-book filter.
                            if (!note.remoteId.isNullOrBlank()) {
                                put("excludeRemoteId", note.remoteId)
                            }
                            put("excludeLocalId", note.id)
                        }
                return@withContext runCatching {
                            val response =
                                    host.ktorClient.post(url) {
                                            contentType(ContentType.Application.Json)
                                            setBody(requestPayload.toString())
                                    }
                            if (!response.status.isSuccess()) return@runCatching emptyList()
                            val responseBody = response.bodyAsText()
                                val results = parseSemanticResultsArray(responseBody)
                                if (results == null || results.size == 0) return@runCatching emptyList()

                                val currentRemoteId = note.remoteId?.trim().orEmpty()
                                val currentLocalId = note.id
                                val parsed = mutableListOf<AiNoteRepository.SemanticRelatedNote>()
                                for (i in 0 until results.size) {
                                    val item = results.optJsonObject(i) ?: continue
                                    val payload = item.optJsonObject("payload")
                                    val remoteId =
                                            host.firstNonBlank(
                                                    item.optString("remoteId", ""),
                                                    item.optString("recordId", ""),
                                                    payload?.optString("remoteId", ""),
                                                    payload?.optString("recordId", ""),
                                                    payload?.optString("id", "")
                                            )
                                    val localId =
                                            host.firstPresentLong(
                                                    host.optLongOrNull(item, "localId"),
                                                    host.optLongOrNull(item, "noteLocalId"),
                                                    host.optLongOrNull(payload, "localId"),
                                                    host.optLongOrNull(payload, "noteLocalId"),
                                                    host.optLongOrNull(payload, "id")
                                            )
                                    val noteId =
                                            host.firstNonBlank(
                                                    item.optString("noteId", ""),
                                                    item.optString("id", ""),
                                                    payload?.optString("noteId", ""),
                                                    payload?.optString("id", ""),
                                                    remoteId,
                                                    localId?.toString()
                                            )
                                                    ?: continue

                                    val isCurrentRemote =
                                            currentRemoteId.isNotBlank() &&
                                                    (currentRemoteId == remoteId ||
                                                            currentRemoteId == noteId)
                                    val isCurrentLocal =
                                            (localId != null && localId == currentLocalId) ||
                                                    noteId == currentLocalId.toString()
                                    if (isCurrentRemote || isCurrentLocal) continue

                                    val score = item.optDouble("score", 0.0)
                                    val reason = parseReason(item, payload)
                                    val originalText =
                                            host.firstNonBlank(
                                                    payload?.optString("originalText", ""),
                                                    item.optString("originalText", "")
                                            )
                                    val aiResponse =
                                            host.firstNonBlank(
                                                    payload?.optString("aiResponse", ""),
                                                    item.optString("aiResponse", "")
                                            )
                                    val bookTitle =
                                            host.firstNonBlank(
                                                    payload?.optString("bookTitle", ""),
                                                    item.optString("bookTitle", "")
                                            )
                                    parsed.add(
                                            AiNoteRepository.SemanticRelatedNote(
                                                    noteId = noteId,
                                                    score = score,
                                                    reason = reason,
                                                    bookTitle = bookTitle,
                                                    originalText = originalText,
                                                    aiResponse = aiResponse,
                                                    remoteId = remoteId,
                                                    localId = localId
                                            )
                                    )
                                    if (parsed.size >= boundedLimit) break
                                }
                                parsed.sortedByDescending { it.score }.take(boundedLimit)
                            }
                        .onFailure { error ->
                            host.logger.e(host.TAG, "searchRelatedNotesFromQdrant failed", error)
                        }
                        .getOrDefault(emptyList())
            }

    suspend fun searchNotesBySemanticQuery(
            queryText: String,
            limit: Int = 20,
            bookId: String? = null
    ): List<AiNoteRepository.SemanticRelatedNote> =
            withContext(ioDispatcher) {
                val query = queryText.trim()
                if (query.isBlank()) return@withContext emptyList()
                val boundedLimit = limit.coerceIn(1, 50)

                val baseUrl = host.getSemanticSearchBaseUrl()
                if (baseUrl.isBlank()) return@withContext emptyList()

                val url = baseUrl + HttpConfig.PATH_AI_NOTES_SEMANTIC_SEARCH
                val requestPayload =
                        jsonObj {
                            put("query", query)
                            put("limit", boundedLimit)
                            if (!bookId.isNullOrBlank()) {
                                put("bookId", bookId)
                            }
                        }
                return@withContext runCatching {
                            val response =
                                    host.ktorClient.post(url) {
                                            contentType(ContentType.Application.Json)
                                            setBody(requestPayload.toString())
                                    }
                            if (!response.status.isSuccess()) return@runCatching emptyList()
                            val responseBody = response.bodyAsText()
                                val results = parseSemanticResultsArray(responseBody)
                                if (results == null || results.size == 0) return@runCatching emptyList()

                                val parsed = mutableListOf<AiNoteRepository.SemanticRelatedNote>()
                                for (i in 0 until results.size) {
                                    val item = results.optJsonObject(i) ?: continue
                                    val payload = item.optJsonObject("payload")
                                    val remoteId =
                                            host.firstNonBlank(
                                                    item.optString("remoteId", ""),
                                                    item.optString("recordId", ""),
                                                    payload?.optString("remoteId", ""),
                                                    payload?.optString("recordId", ""),
                                                    payload?.optString("id", "")
                                            )
                                    val localId =
                                            host.firstPresentLong(
                                                    host.optLongOrNull(item, "localId"),
                                                    host.optLongOrNull(item, "noteLocalId"),
                                                    host.optLongOrNull(payload, "localId"),
                                                    host.optLongOrNull(payload, "noteLocalId"),
                                                    host.optLongOrNull(payload, "id")
                                            )
                                    val noteId =
                                            host.firstNonBlank(
                                                    item.optString("noteId", ""),
                                                    item.optString("id", ""),
                                                    payload?.optString("noteId", ""),
                                                    payload?.optString("id", ""),
                                                    remoteId,
                                                    localId?.toString()
                                            )
                                                    ?: continue

                                    val score = item.optDouble("score", 0.0)
                                    val reason = parseReason(item, payload)
                                    val originalText =
                                            host.firstNonBlank(
                                                    payload?.optString("originalText", ""),
                                                    item.optString("originalText", "")
                                            )
                                    val aiResponse =
                                            host.firstNonBlank(
                                                    payload?.optString("aiResponse", ""),
                                                    item.optString("aiResponse", "")
                                            )
                                    val title =
                                            host.firstNonBlank(
                                                    payload?.optString("bookTitle", ""),
                                                    item.optString("bookTitle", "")
                                            )
                                    parsed.add(
                                            AiNoteRepository.SemanticRelatedNote(
                                                    noteId = noteId,
                                                    score = score,
                                                    reason = reason,
                                                    bookTitle = title,
                                                    originalText = originalText,
                                                    aiResponse = aiResponse,
                                                    remoteId = remoteId,
                                                    localId = localId
                                            )
                                    )
                                }
                                parsed.sortedByDescending { it.score }.take(boundedLimit)
                            }
                        .onFailure { error ->
                            host.logger.e(host.TAG, "searchNotesBySemanticQuery failed", error)
                        }
                        .getOrDefault(emptyList())
            }

    suspend fun fetchRemainingCredits(): Int? =
            withContext(ioDispatcher) {
                val settings = host.getSettings()
                if (settings.apiKey.isNotBlank() || settings.aiModelName.isNotBlank()) {
                    return@withContext null
                }
                val baseUrl = host.getBaseUrl()
                if (baseUrl.isBlank()) return@withContext null

                val url = "$baseUrl/ai-chat/ai/credits"

                return@withContext runCatching {
                            val response = host.ktorClient.get(url)
                            if (!response.status.isSuccess()) return@runCatching null
                            val body = response.bodyAsText()
                            if (body.isBlank()) return@runCatching null
                            parseJsonObject(body)?.optInt("credits", -1)?.takeIf { it >= 0 }
                        }
                        .getOrNull()
            }
}
