package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.core.Logger
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.platform.ioDispatcher
import my.hinoki.booxreader.data.platform.platformFiles
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.remote.isValidHttpUrl
import my.hinoki.booxreader.data.settings.KeyValueStorage
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import io.ktor.client.request.post
import io.ktor.client.plugins.timeout
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import my.hinoki.booxreader.data.remote.createApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class AiNoteRepository(
        internal val prefs: KeyValueStorage,
        private val syncRepo: UserSyncRepository? = null,
        internal val logger: Logger,
        internal val pocketBaseUrl: String? = null
) {
    data class SemanticRelatedNote(
            val noteId: String,
            val score: Double,
            val reason: String?,
            val bookTitle: String?,
            val originalText: String?,
            val aiResponse: String?,
            val remoteId: String?,
            val localId: Long?
    )

    internal val TAG = "AiNoteRepository"
    internal val dao = AppDatabase.get().aiNoteDao()
    internal val bookDao = AppDatabase.get().bookDao()

    internal val ktorClient = createApiClient()

    /** 語意搜尋（實作見 SemanticSearchClient.kt）。 */
    private val semanticSearch by lazy { SemanticSearchClient(this) }

    /** 筆記匯出（實作見 NoteExportService.kt）。 */
    private val noteExport by lazy { NoteExportService(this) }

    /** AI 對話 / 完成（實作見 AiChatClient.kt）。 */
    private val aiChat by lazy { AiChatClient(this) }

    var lastStreamingError: my.hinoki.booxreader.data.remote.StreamingErrorInfo? = null

    fun isStreamingEnabled(): Boolean {
        return prefs.getBoolean("use_streaming", false)
    }

    internal fun getBaseUrl(): String {
        var url =
                prefs.getString("server_base_url") ?: HttpConfig.DEFAULT_BASE_URL
        return if (url.endsWith("/")) url.dropLast(1) else url
    }

    internal fun getSemanticSearchBaseUrl(): String {
        val pb = pocketBaseUrl?.trim().orEmpty()
        if (pb.isNotEmpty()) {
            return pb.trimEnd('/')
        }
        return getBaseUrl()
    }

    // --- AI Chat / Completions（實作見 AiChatClient.kt） ---

    suspend fun fetchAiExplanation(
            text: String,
            magicTag: MagicTag? = null,
            settingsOverride: ReaderSettings? = null
    ): Pair<String, String>? =
            aiChat.fetchAiExplanation(text, magicTag, settingsOverride)

    suspend fun fetchAiExplanationStreaming(
            text: String,
            magicTag: MagicTag? = null,
            onPartial: suspend (String) -> Unit
    ): Pair<String, String>? =
            aiChat.fetchAiExplanationStreaming(text, magicTag, onPartial)

    suspend fun continueConversation(
            note: AiNoteEntity,
            followUpText: String,
            magicTag: MagicTag? = null
    ): String? =
            aiChat.continueConversation(note, followUpText, magicTag)

    suspend fun continueConversationStreaming(
            note: AiNoteEntity,
            followUpText: String,
            magicTag: MagicTag? = null,
            onPartial: suspend (String) -> Unit
    ): String? =
            aiChat.continueConversationStreaming(note, followUpText, magicTag, onPartial)


    suspend fun add(
            bookId: String?,
            originalText: String,
            aiResponse: String,
            locatorJson: String? = null,
            bookTitle: String? = null
    ): Long {
        val resolvedTitle =
                bookTitle ?: bookId?.let { id -> bookDao.getById(id)?.title }

        val messages =
                jsonArr {
                    add(jsonObj { put("role", "user"); put("content", originalText) })
                    if (aiResponse.isNotBlank()) {
                        add(jsonObj { put("role", "assistant"); put("content", aiResponse) })
                    }
                }

        val note =
                AiNoteEntity(
                        bookId = bookId,
                        bookTitle = resolvedTitle,
                        messages = messages.toString(),
                        originalText = originalText,
                        aiResponse = aiResponse,
                        locatorJson = locatorJson,
                        updatedAt = currentEpochMillis()
                )
        val newId = dao.insert(note)
        val saved = note.copy(id = newId)
        val remoteId = syncRepo?.pushAiNote(saved)
        if (!remoteId.isNullOrBlank()) {
            dao.update(saved.copy(remoteId = remoteId))
        }
        return newId
    }

    suspend fun update(note: AiNoteEntity) {
        val base =
                note.copy(
                        originalText = note.originalText?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.originalTextFromMessages(
                                                note.messages
                                        ),
                        aiResponse = note.aiResponse?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.aiResponseFromMessages(
                                                note.messages
                                        ),
                        updatedAt = currentEpochMillis()
                )
        val hasSource = !base.originalText.isNullOrBlank() || !base.aiResponse.isNullOrBlank()
        val messages =
                if (hasSource) {
                    AiNoteSerialization.messagesFromOriginalAndResponse(
                            base.originalText,
                            base.aiResponse
                    )
                } else {
                    base.messages
                }
        val updated = base.copy(messages = messages)
        dao.update(updated)
        val remoteId = syncRepo?.pushAiNote(updated)
        if (!remoteId.isNullOrBlank() && remoteId != updated.remoteId) {
            dao.update(updated.copy(remoteId = remoteId))
        }
    }

    suspend fun getById(id: Long): AiNoteEntity? {
        return dao.getById(id)?.let { normalizeForRead(it) }
    }

    suspend fun getByRemoteId(remoteId: String): AiNoteEntity? {
        if (remoteId.isBlank()) return null
        return dao.getByRemoteId(remoteId)?.let { normalizeForRead(it) }
    }

    suspend fun getAll(): List<AiNoteEntity> {
        return dao.getAll().map { normalizeForRead(it) }
    }

    suspend fun getByBook(bookId: String): List<AiNoteEntity> {
        return dao.getByBookId(bookId).map { normalizeForRead(it) }
    }

    suspend fun getByIds(ids: Collection<Long>): List<AiNoteEntity> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(900).flatMap { chunk ->
            dao.getByIds(chunk.toList()).map { normalizeForRead(it) }
        }
    }

    suspend fun findNoteByText(text: String): AiNoteEntity? {
        return null
    }

    private suspend fun normalizeForRead(note: AiNoteEntity): AiNoteEntity {
        val hasSource = !note.originalText.isNullOrBlank() || !note.aiResponse.isNullOrBlank()
        if (!hasSource) return note
        val messages =
                AiNoteSerialization.messagesFromOriginalAndResponse(
                        note.originalText,
                        note.aiResponse
                )
        return note.copy(messages = messages)
    }

    private fun buildMessagesJson(note: AiNoteEntity): String {
        val hasSource = !note.originalText.isNullOrBlank() || !note.aiResponse.isNullOrBlank()
        return if (hasSource) {
            AiNoteSerialization.messagesFromOriginalAndResponse(note.originalText, note.aiResponse)
        } else {
            note.messages
        }
    }

    internal fun buildMessages(note: AiNoteEntity): JsonArray {
        val json = buildMessagesJson(note)
        return parseJsonArray(json) ?: jsonArr {}
    }

    internal fun getSettings(): ReaderSettings {
        return ReaderSettings.fromStorage(prefs)
    }

    private fun parseExtraParamsJson(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return parseJsonObject(raw)
    }

    internal suspend fun loadExtraParams(): JsonObject? =
            withContext(ioDispatcher) {
                val activeProfileId = prefs.getLong("active_ai_profile_id", -1L)
                if (activeProfileId <= 0L) return@withContext null
                val profile = AppDatabase.get().aiProfileDao().getById(activeProfileId)
                return@withContext parseExtraParamsJson(profile?.extraParamsJson)
            }

    suspend fun fetchMagicTagSuggestions(
            note: AiNoteEntity,
            relatedNotes: List<SemanticRelatedNote> = emptyList(),
            limit: Int = 5,
            settingsOverride: ReaderSettings? = null
    ): List<MagicTag> {
        val prompt = buildMagicTagSuggestionPrompt(note, relatedNotes, limit)
        val response = fetchAiExplanation(prompt, settingsOverride = settingsOverride)
        val raw = response?.first?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        return parseMagicTagSuggestions(raw, limit)
    }

    private fun mergeJson(target: JsonObject, extra: JsonObject): JsonObject {
        val result = target.toMutableMap()
        for ((key, extraValue) in extra) {
            val targetValue = result[key]
            if (extraValue is JsonObject && targetValue is JsonObject) {
                result[key] = mergeJson(targetValue, extraValue)
            } else {
                result[key] = extraValue
            }
        }
        return JsonObject(result)
    }

    internal fun applyExtraParams(target: JsonObject, extra: JsonObject?): JsonObject {
        if (extra == null) return target
        return mergeJson(target, extra)
    }

    internal fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return null
    }

    internal fun firstPresentLong(vararg values: Long?): Long? {
        for (value in values) {
            if (value != null) return value
        }
        return null
    }

    internal fun optLongOrNull(json: JsonObject?, key: String): Long? {
        if (json == null || !json.containsKey(key)) return null
        return (json[key] as? JsonPrimitive)?.longOrNull
    }

    private fun extractOriginalTextForPrompt(note: AiNoteEntity): String {
        return note.originalText?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.originalTextFromMessages(note.messages).orEmpty()
    }

    private fun extractAiResponseForPrompt(note: AiNoteEntity): String {
        return note.aiResponse?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.aiResponseFromMessages(note.messages).orEmpty()
    }

    private fun buildMagicTagSuggestionPrompt(
            note: AiNoteEntity,
            relatedNotes: List<SemanticRelatedNote>,
            limit: Int
    ): String {
        val original = extractOriginalTextForPrompt(note).trim().takeIf { it.isNotBlank() }
        val response = extractAiResponseForPrompt(note).trim().takeIf { it.isNotBlank() }
        val builder = StringBuilder()
        builder.append(
                """
        請扮演一位會產生追問方向的標籤策展人，根據下面的 AI Note 內容與相關歷史筆記，產出最多 $limit 筆可以繼續追問的 Magic Tag 建議。
        請直接回傳 JSON 陣列，格式例如：
        [
          {"label": "關鍵概念", "description": "補充說明", "role": "user", "content": "關鍵概念"},
          ...
        ]
        不要額外加說明文字，若無法產出就回傳 []。
        """.trimIndent()
        )

        if (!original.isNullOrBlank()) {
            builder.append("\n\n問題：\n$original")
        }
        if (!response.isNullOrBlank()) {
            builder.append("\n\n回答：\n$response")
        }
        val history = formatRelatedHistoryForPrompt(relatedNotes)
        if (!history.isNullOrBlank()) {
            builder.append("\n\n相關歷史筆記：\n$history")
        }
        builder.append("\n\n請集中在延伸角度，標籤可以是問題、主題或視角。")
        return builder.toString()
    }

    private fun formatRelatedHistoryForPrompt(
            relatedNotes: List<SemanticRelatedNote>
    ): String? {
        if (relatedNotes.isEmpty()) return null
        return relatedNotes.take(3).mapIndexed { index, note ->
            val title = note.bookTitle?.takeIf { it.isNotBlank() } ?: "Note ${index + 1}"
            val reason = note.reason?.takeIf { it.isNotBlank() }?.let { "原因：$it" } ?: ""
            val snippet =
                    (note.aiResponse ?: note.originalText)
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()
                            ?.take(90)
                            .orEmpty()
            val snippetText = if (snippet.isNotBlank()) "摘錄：$snippet" else ""
            "- $title${if (reason.isNotBlank()) " ($reason)" else ""}${
                    if (snippetText.isNotBlank()) "，$snippetText" else ""
            }"
        }.joinToString("\n")
    }

    private fun parseMagicTagSuggestions(raw: String, limit: Int): List<MagicTag> {
        val trimmed = raw.trim()
        val jsonArray = extractFirstJsonArray(trimmed)
        if (jsonArray != null) {
            val parsed = parseMagicTagsFromJson(jsonArray, limit)
            if (parsed.isNotEmpty()) return parsed
        }
        return parseMagicTagsFromLines(trimmed, limit)
    }

    private fun extractFirstJsonArray(raw: String): JsonArray? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val candidate = raw.substring(start, end + 1)
            return parseJsonArray(candidate)
        }
        return null
    }

    private fun parseMagicTagsFromJson(array: JsonArray, limit: Int): List<MagicTag> {
        val suggestions = mutableListOf<MagicTag>()
        val usedIds = mutableSetOf<String>()
        for (i in 0 until array.size) {
            if (suggestions.size >= limit) break
            val obj = array.optJsonObject(i) ?: continue
            val label =
                    firstNonBlank(
                            obj.optString("label", ""),
                            obj.optString("name", ""),
                            obj.optString("tag", ""),
                            obj.optString("title", "")
                    )
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: continue
            val description =
                    firstNonBlank(
                            obj.optString("description", ""),
                            obj.optString("detail", ""),
                            obj.optString("hint", ""),
                            obj.optString("context", "")
                    )
            val role =
                    obj.optString("role", "")
                            .takeIf { it.isNotBlank() }
                            ?: "user"
            val content =
                    firstNonBlank(
                            obj.optString("content", ""),
                            label
                    )
                            .orEmpty()
            val id = generateUniqueTagId(label, usedIds)
            suggestions.add(
                    MagicTag(
                            id = id,
                            label = label,
                            content = content,
                            description = description.orEmpty(),
                            role = role
                    )
            )
        }
        return suggestions
    }

    private fun parseMagicTagsFromLines(raw: String, limit: Int): List<MagicTag> {
        val lines = raw.lines()
                .map { it.trim().replace(Regex("^\\s*\\d+[\\).\\-]*"), "").trim() }
                .filter { it.isNotBlank() }
        val suggestions = mutableListOf<MagicTag>()
        val usedIds = mutableSetOf<String>()
        for (line in lines) {
            if (suggestions.size >= limit) break
            val parts = line.split(Regex("\\s*[-–—:：]+\\s*"), limit = 2)
            val label = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
            val description = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val id = generateUniqueTagId(label, usedIds)
            suggestions.add(
                    MagicTag(
                            id = id,
                            label = label,
                            content = label,
                            description = description ?: "",
                            role = "user"
                    )
            )
        }
        return suggestions
    }

    private fun generateUniqueTagId(baseLabel: String, used: MutableSet<String>): String {
        val base =
                baseLabel.lowercase()
                        .replace(Regex("[^a-z0-9]+"), "-")
                        .trim('-')
                        .takeIf { it.isNotBlank() }
                        ?: "ai-generated"
        var candidate = base
        var suffix = 1
        while (used.contains(candidate)) {
            candidate = "$base-$suffix"
            suffix++
        }
        used.add(candidate)
        return candidate
    }

    // --- Semantic Search（實作見 SemanticSearchClient.kt） ---

    suspend fun searchRelatedNotesFromQdrant(
            note: AiNoteEntity,
            limit: Int = 5
    ): List<SemanticRelatedNote> = semanticSearch.searchRelatedNotesFromQdrant(note, limit)

    suspend fun searchNotesBySemanticQuery(
            queryText: String,
            limit: Int = 20,
            bookId: String? = null
    ): List<SemanticRelatedNote> =
            semanticSearch.searchNotesBySemanticQuery(queryText, limit, bookId)

    suspend fun fetchRemainingCredits(): Int? = semanticSearch.fetchRemainingCredits()


    suspend fun deleteSelectedNotes(noteIds: Collection<Long>): DeleteResult =
            withContext(ioDispatcher) {
                if (noteIds.isEmpty()) {
                    return@withContext DeleteResult(0, 0)
                }
                val notes = noteIds.chunked(900).flatMap { chunk ->
                    dao.getByIds(chunk.toList())
                }
                var failedCount = 0
                val idsToDelete = mutableListOf<Long>()

                for (note in notes) {
                    val noteRemoteId = note.remoteId
                    if (!noteRemoteId.isNullOrBlank()) {
                        val deletedRemote = syncRepo?.deleteAiNote(noteRemoteId) ?: true
                        if (!deletedRemote) {
                            failedCount++
                            continue
                        }
                    }
                    idsToDelete.add(note.id)
                }

                var deletedCount = 0
                // ⚡ Optimized: Replaced N+1 dao.deleteById with batch deletion to reduce SQLite overhead
                idsToDelete.chunked(900).forEach { chunk ->
                    deletedCount += dao.deleteByIds(chunk)
                }

                DeleteResult(deletedCount = deletedCount, failedCount = failedCount)
            }

    // --- Notes Export（實作見 NoteExportService.kt） ---

    suspend fun exportSelectedNotes(noteIds: Collection<Long>): ExportResult =
            noteExport.exportSelectedNotes(noteIds)

    suspend fun exportAllNotes(bookId: String): ExportResult = noteExport.exportAllNotes(bookId)

    suspend fun testExportEndpoint(targetUrl: String): String = noteExport.testExportEndpoint(targetUrl)


}

internal val aiNoteJson = Json { ignoreUnknownKeys = true }

internal fun jsonObj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

internal fun jsonArr(block: JsonArrayBuilder.() -> Unit): JsonArray = buildJsonArray(block)

/** 安全讀取 JsonObject 欄位：缺失或 JSON null 回傳 default（對應 org.json optString）。 */
internal fun JsonObject.optString(key: String, default: String = ""): String {
    val v = this[key] ?: return default
    if (v is JsonNull) return default
    return (v as? JsonPrimitive)?.contentOrNull ?: default
}

internal fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.optJsonObject(index: Int): JsonObject? = getOrNull(index) as? JsonObject

internal fun JsonArray.optString(index: Int): String {
    val v = getOrNull(index) ?: return ""
    if (v is JsonNull) return ""
    return (v as? JsonPrimitive)?.contentOrNull ?: ""
}

internal fun JsonObject.optDouble(key: String, default: Double): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

internal fun JsonObject.optInt(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.optBoolean(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun parseJsonObject(raw: String): JsonObject? =
        runCatching { aiNoteJson.parseToJsonElement(raw).jsonObject }.getOrNull()

internal fun parseJsonArray(raw: String): JsonArray? =
        runCatching { aiNoteJson.parseToJsonElement(raw).jsonArray }.getOrNull()

internal fun prettyJsonString(element: JsonElement): String =
        Json { prettyPrint = true; ignoreUnknownKeys = true }
                .encodeToString(JsonElement.serializer(), element)

/** 回傳帶指定欄位的新 JsonObject（不可變物件輔助）。 */
internal fun JsonObject.withField(key: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(key, value) })

data class ExportResult(
        val success: Boolean,
        val exportedCount: Int,
        val isEmpty: Boolean = false,
        val message: String? = null,
        val localPath: String? = null
)

data class DeleteResult(
        val deletedCount: Int,
        val failedCount: Int
)
