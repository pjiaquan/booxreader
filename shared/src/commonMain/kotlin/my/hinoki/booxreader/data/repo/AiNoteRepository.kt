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
        private val prefs: KeyValueStorage,
        private val syncRepo: UserSyncRepository? = null,
        private val logger: Logger,
        private val pocketBaseUrl: String? = null
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

    private val TAG = "AiNoteRepository"
    private val dao = AppDatabase.get().aiNoteDao()
    private val bookDao = AppDatabase.get().bookDao()

    private val ktorClient = createApiClient()

    var lastStreamingError: my.hinoki.booxreader.data.remote.StreamingErrorInfo? = null

    fun isStreamingEnabled(): Boolean {
        return prefs.getBoolean("use_streaming", false)
    }

    private fun getBaseUrl(): String {
        var url =
                prefs.getString("server_base_url") ?: HttpConfig.DEFAULT_BASE_URL
        return if (url.endsWith("/")) url.dropLast(1) else url
    }

    private fun getSemanticSearchBaseUrl(): String {
        val pb = pocketBaseUrl?.trim().orEmpty()
        if (pb.isNotEmpty()) {
            return pb.trimEnd('/')
        }
        return getBaseUrl()
    }

    private fun isGoogleNative(url: String): Boolean {
        return url.contains("generativelanguage.googleapis.com") && !url.contains("/openai/")
    }

    private fun isGoogleHost(url: String): Boolean {
        return url.contains("generativelanguage.googleapis.com")
    }

    // Prefer native streaming endpoint for Gemini; append alt=sse if missing
    private fun googleStreamUrl(url: String): String {
        val streamUrl =
                if (url.contains(":streamGenerateContent")) {
                    url
                } else {
                    url.replace(":generateContent", ":streamGenerateContent")
                }
        return if (streamUrl.contains("alt=sse")) {
            streamUrl
        } else if (streamUrl.contains("?")) {
            "$streamUrl&alt=sse"
        } else {
            "$streamUrl?alt=sse"
        }
    }

    private fun transformToGooglePayload(
            model: String,
            messages: JsonArray,
            systemPrompt: String?,
            temperature: Double,
            maxTokens: Int,
            topP: Double,
            frequencyPenalty: Double,
            presencePenalty: Double,
            includeGoogleSearch: Boolean = false
    ): JsonObject {
        var finalSystemPrompt = systemPrompt ?: ""

        val contents = mutableListOf<JsonObject>()
        for (i in 0 until messages.size) {
            val msg = messages.optJsonObject(i) ?: continue
            val role = msg.optString("role")
            val content = msg.optString("content")

            if (role == "system") {
                // If system prompt is in messages, prefer it or append it?
                // For simplicity, if we already have systemPrompt from Settings, we might ignore
                // this or override?
                // The caller usually passes systemPrompt from settings.
                // If caller put system prompt in messages (like in fetchAiExplanation), we extract
                // it.
                if (finalSystemPrompt.isEmpty()) {
                    finalSystemPrompt = content
                }
                continue
            }

            val googleRole = if (role == "user") "user" else "model"
            contents.add(
                    jsonObj {
                        put("role", googleRole)
                        put("parts", jsonArr { add(jsonObj { put("text", content) }) })
                    }
            )
        }

        return jsonObj {
            // Google Native often embeds model in URL, but payload body structure is:
            // { contents: [], systemInstruction: {}, generationConfig: {} }
            put("contents", jsonArr { addAll(contents) })
            if (finalSystemPrompt.isNotEmpty()) {
                put(
                        "systemInstruction",
                        jsonObj {
                            put("parts", jsonArr { add(jsonObj { put("text", finalSystemPrompt) }) })
                        }
                )
            }
            put(
                    "generationConfig",
                    jsonObj {
                        put("temperature", temperature)
                        put("maxOutputTokens", maxTokens)
                        put("topP", topP)
                        // Google Gemini API (v1beta) does not yet support frequency/presence
                        // penalty in standard generationConfig
                    }
            )
            if (includeGoogleSearch) {
                // Use googleSearch tool (retrieval variant currently rejected by API)
                put(
                        "tools",
                        jsonArr {
                            add(jsonObj { put("googleSearch", jsonObj {}) })
                        }
                )
            }
        }
    }

    private fun parseGoogleResponse(json: JsonObject): String? {
        // candidates[0].content.parts[0].text
        val candidates = json.optJsonArray("candidates")
        val firstCandidate = candidates?.optJsonObject(0)
        val content = firstCandidate?.optJsonObject("content")
        val parts = content?.optJsonArray("parts")
        return parts?.optJsonObject(0)?.optString("text", "")
    }

    private fun normalizeMagicRole(tag: MagicTag?): String? {
        return tag?.role?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    private fun magicText(tag: MagicTag?): String {
        val content = tag?.content?.trim().orEmpty()
        val label = tag?.label?.trim().orEmpty()
        return if (content.isNotEmpty()) content else label
    }

    private fun resolveSystemPrompt(settings: ReaderSettings, tag: MagicTag?): String {
        val role = normalizeMagicRole(tag)
        val magicText = magicText(tag)
        return if (role == "system" && magicText.isNotEmpty()) {
            magicText
        } else {
            settings.aiSystemPrompt
        }
    }

    private fun resolveUserInput(settings: ReaderSettings, text: String, tag: MagicTag?): String {
        val role = normalizeMagicRole(tag)
        val magicText = magicText(tag)
        val userText =
                if (role == "user" && magicText.isNotEmpty()) {
                    "$magicText $text".trim()
                } else {
                    text
                }
        return settings.safeUserPromptTemplate.replaceFirst("%s", userText)
    }

    private fun maybeAddAssistantMagic(messages: JsonArray, tag: MagicTag?): JsonArray {
        val role = normalizeMagicRole(tag)
        val magicText = magicText(tag)
        if (role == "assistant" && magicText.isNotEmpty()) {
            return jsonArr {
                addAll(messages)
                add(jsonObj { put("role", "assistant"); put("content", magicText) })
            }
        }
        return messages
    }

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

    private fun buildMessages(note: AiNoteEntity): JsonArray {
        val json = buildMessagesJson(note)
        return parseJsonArray(json) ?: jsonArr {}
    }

    private fun getSettings(): ReaderSettings {
        return ReaderSettings.fromStorage(prefs)
    }

    private fun parseExtraParamsJson(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return parseJsonObject(raw)
    }

    private suspend fun loadExtraParams(): JsonObject? =
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

    private fun applyExtraParams(target: JsonObject, extra: JsonObject?): JsonObject {
        if (extra == null) return target
        return mergeJson(target, extra)
    }

    private fun firstNonBlank(vararg values: String?): String? {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return null
    }

    private fun firstPresentLong(vararg values: Long?): Long? {
        for (value in values) {
            if (value != null) return value
        }
        return null
    }

    private fun optLongOrNull(json: JsonObject?, key: String): Long? {
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
                firstNonBlank(
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
    ): List<SemanticRelatedNote> =
            withContext(ioDispatcher) {
                val boundedLimit = limit.coerceIn(1, 5)
                val query = buildSemanticQuery(note)
                if (query.isBlank()) return@withContext emptyList()

                val baseUrl = getSemanticSearchBaseUrl()
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
                                    ktorClient.post(url) {
                                            contentType(ContentType.Application.Json)
                                            setBody(requestPayload.toString())
                                    }
                            if (!response.status.isSuccess()) return@runCatching emptyList()
                            val responseBody = response.bodyAsText()
                                val results = parseSemanticResultsArray(responseBody)
                                if (results == null || results.size == 0) return@runCatching emptyList()

                                val currentRemoteId = note.remoteId?.trim().orEmpty()
                                val currentLocalId = note.id
                                val parsed = mutableListOf<SemanticRelatedNote>()
                                for (i in 0 until results.size) {
                                    val item = results.optJsonObject(i) ?: continue
                                    val payload = item.optJsonObject("payload")
                                    val remoteId =
                                            firstNonBlank(
                                                    item.optString("remoteId", ""),
                                                    item.optString("recordId", ""),
                                                    payload?.optString("remoteId", ""),
                                                    payload?.optString("recordId", ""),
                                                    payload?.optString("id", "")
                                            )
                                    val localId =
                                            firstPresentLong(
                                                    optLongOrNull(item, "localId"),
                                                    optLongOrNull(item, "noteLocalId"),
                                                    optLongOrNull(payload, "localId"),
                                                    optLongOrNull(payload, "noteLocalId"),
                                                    optLongOrNull(payload, "id")
                                            )
                                    val noteId =
                                            firstNonBlank(
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
                                            firstNonBlank(
                                                    payload?.optString("originalText", ""),
                                                    item.optString("originalText", "")
                                            )
                                    val aiResponse =
                                            firstNonBlank(
                                                    payload?.optString("aiResponse", ""),
                                                    item.optString("aiResponse", "")
                                            )
                                    val bookTitle =
                                            firstNonBlank(
                                                    payload?.optString("bookTitle", ""),
                                                    item.optString("bookTitle", "")
                                            )
                                    parsed.add(
                                            SemanticRelatedNote(
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
                            logger.e(TAG, "searchRelatedNotesFromQdrant failed", error)
                        }
                        .getOrDefault(emptyList())
            }

    suspend fun searchNotesBySemanticQuery(
            queryText: String,
            limit: Int = 20,
            bookId: String? = null
    ): List<SemanticRelatedNote> =
            withContext(ioDispatcher) {
                val query = queryText.trim()
                if (query.isBlank()) return@withContext emptyList()
                val boundedLimit = limit.coerceIn(1, 50)

                val baseUrl = getSemanticSearchBaseUrl()
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
                                    ktorClient.post(url) {
                                            contentType(ContentType.Application.Json)
                                            setBody(requestPayload.toString())
                                    }
                            if (!response.status.isSuccess()) return@runCatching emptyList()
                            val responseBody = response.bodyAsText()
                                val results = parseSemanticResultsArray(responseBody)
                                if (results == null || results.size == 0) return@runCatching emptyList()

                                val parsed = mutableListOf<SemanticRelatedNote>()
                                for (i in 0 until results.size) {
                                    val item = results.optJsonObject(i) ?: continue
                                    val payload = item.optJsonObject("payload")
                                    val remoteId =
                                            firstNonBlank(
                                                    item.optString("remoteId", ""),
                                                    item.optString("recordId", ""),
                                                    payload?.optString("remoteId", ""),
                                                    payload?.optString("recordId", ""),
                                                    payload?.optString("id", "")
                                            )
                                    val localId =
                                            firstPresentLong(
                                                    optLongOrNull(item, "localId"),
                                                    optLongOrNull(item, "noteLocalId"),
                                                    optLongOrNull(payload, "localId"),
                                                    optLongOrNull(payload, "noteLocalId"),
                                                    optLongOrNull(payload, "id")
                                            )
                                    val noteId =
                                            firstNonBlank(
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
                                            firstNonBlank(
                                                    payload?.optString("originalText", ""),
                                                    item.optString("originalText", "")
                                            )
                                    val aiResponse =
                                            firstNonBlank(
                                                    payload?.optString("aiResponse", ""),
                                                    item.optString("aiResponse", "")
                                            )
                                    val title =
                                            firstNonBlank(
                                                    payload?.optString("bookTitle", ""),
                                                    item.optString("bookTitle", "")
                                            )
                                    parsed.add(
                                            SemanticRelatedNote(
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
                            logger.e(TAG, "searchNotesBySemanticQuery failed", error)
                        }
                        .getOrDefault(emptyList())
            }

    suspend fun fetchRemainingCredits(): Int? =
            withContext(ioDispatcher) {
                val settings = getSettings()
                if (settings.apiKey.isNotBlank() || settings.aiModelName.isNotBlank()) {
                    return@withContext null
                }
                val baseUrl = getBaseUrl()
                if (baseUrl.isBlank()) return@withContext null

                val url = "$baseUrl/ai-chat/ai/credits"

                return@withContext runCatching {
                            val response = ktorClient.get(url)
                            if (!response.status.isSuccess()) return@runCatching null
                            val body = response.bodyAsText()
                            if (body.isBlank()) return@runCatching null
                            parseJsonObject(body)?.optInt("credits", -1)?.takeIf { it >= 0 }
                        }
                        .getOrNull()
            }

    suspend fun fetchAiExplanation(
            text: String,
            magicTag: MagicTag? = null,
            settingsOverride: ReaderSettings? = null
    ): Pair<String, String>? {
        val settings = settingsOverride ?: getSettings()
        if (settings.apiKey.isNotBlank()) {
            return withContext(ioDispatcher) {
                try {
                    val url = getBaseUrl()
                    val isGoogle = isGoogleNative(url)
                    val extraParams = loadExtraParams()
                    val systemPrompt = resolveSystemPrompt(settings, magicTag)

                    val requestBody: String

                    if (isGoogle) {
                        val messages =
                                jsonArr {
                                    // In Google adapter logic, we'll extract system prompt from
                                    // here or pass it explicitly.
                                    // Here we construct OpenAI style first, then transform.
                                    // OR we just use transform directly.
                                    add(
                                            jsonObj {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)

                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        messagesWithMagic,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        val merged = applyExtraParams(googlePayload, extraParams)
                        requestBody = merged.toString()
                    } else {
                        // Standard OpenAI logic
                        val messages =
                                jsonArr {
                                    add(
                                            jsonObj {
                                                put("role", "system")
                                                put("content", systemPrompt)
                                            }
                                    )
                                    add(
                                            jsonObj {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)
                        val payload =
                                jsonObj {
                                    put("model", settings.aiModelName)
                                    put("messages", messagesWithMagic)
                                    put("stream", false)
                                    put("temperature", settings.temperature)
                                    put("max_tokens", settings.maxTokens)
                                    put("top_p", settings.topP)
                                    if (!isGoogleHost(url)) {
                                        if (settings.frequencyPenalty != 0.0) {
                                            put("frequency_penalty", settings.frequencyPenalty)
                                        }
                                        if (settings.presencePenalty != 0.0) {
                                            put("presence_penalty", settings.presencePenalty)
                                        }
                                    }
                                }
                        val merged = applyExtraParams(payload, extraParams)
                        requestBody = merged.withField("stream", JsonPrimitive(false)).toString()
                    }

                    logger.d(TAG, "Fetching AI Explanation from: $url")

                    val response =
                            ktorClient.post(url) {
                                    contentType(ContentType.Application.Json)
                                    if (isGoogle) {
                                            header("x-goog-api-key", settings.apiKey)
                                    } else {
                                            header("Authorization", "Bearer ${settings.apiKey}")
                                    }
                                    setBody(requestBody)
                            }
                    if (response.status.isSuccess()) {
                            val respBody = response.bodyAsText()
                            if (respBody != null) {
                                val respJson = parseJsonObject(respBody)
                                val content =
                                        if (respJson == null) {
                                            ""
                                        } else if (isGoogle) {
                                            parseGoogleResponse(respJson)
                                        } else {
                                            val choices = respJson.optJsonArray("choices")
                                            choices?.optJsonObject(0)
                                                    ?.optJsonObject("message")
                                                    ?.optString("content", "")
                                        }
                                                ?: ""

                                if (content.isNotEmpty()) {
                                    Pair(content, content)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                    } else {
                            val errorBody = response.bodyAsText()
                            logger.e(
                                    TAG,
                                    "AI Request Failed: Code=${response.status.value}, Body=$errorBody"
                            )
                            null
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Exception in fetchAiExplanation", e)
                    null
                }
            }
        }

        // Legacy Implementation
        return withContext(ioDispatcher) {
            try {
                val jsonBody = jsonObj { put("text", text) }.toString()

                val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI
                val response =
                        ktorClient.post(url) {
                                contentType(ContentType.Application.Json)
                                setBody(jsonBody)
                        }
                if (response.status.isSuccess()) {
                                val respBody = response.bodyAsText()
                                if (respBody != null) {
                                    val respJson = parseJsonObject(respBody)
                                    val serverText =
                                            respJson?.optString("text", "").orEmpty()
                                    val responseText =
                                            if (serverText.isNotBlank()) serverText else text
                                    val content = respJson?.optString("content", "").orEmpty()
                                    Pair(responseText, content)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
            } catch (e: Exception) {
                logger.e(TAG, "fetchAiExplanation legacy failed", e)
                null
            }
        }
    }

    suspend fun fetchAiExplanationStreaming(
            text: String,
            magicTag: MagicTag? = null,
            onPartial: suspend (String) -> Unit
    ): Pair<String, String>? {
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            // Direct DeepSeek API call
            val url = getBaseUrl() // Use base URL directly without appending path
            val extraParams = loadExtraParams()
            val systemPrompt = resolveSystemPrompt(settings, magicTag)

            val messages =
                    jsonArr {
                        add(
                                jsonObj {
                                    put("role", "system")
                                    put("content", systemPrompt)
                                }
                        )
                        add(
                                jsonObj {
                                    put("role", "user")
                                    put("content", resolveUserInput(settings, text, magicTag))
                                }
                        )
                    }
            val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)

            val payload =
                    jsonObj {
                        put("model", settings.aiModelName)
                        put("messages", messagesWithMagic)
                        put("stream", true)
                        put("temperature", settings.temperature)
                        put("max_tokens", settings.maxTokens)
                        put("top_p", settings.topP)
                        if (!isGoogleHost(url)) {
                            if (settings.frequencyPenalty != 0.0) {
                                put("frequency_penalty", settings.frequencyPenalty)
                            }
                            if (settings.presencePenalty != 0.0) {
                                put("presence_penalty", settings.presencePenalty)
                            }
                        }
                    }
            val merged = applyExtraParams(payload, extraParams)
            val payloadFinal = merged.withField("stream", JsonPrimitive(true))

            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload =
                    if (isGoogle) {
                        // OpenAI 'messages' -> Google 'contents'
                        val messages =
                                jsonArr {
                                    add(
                                            jsonObj {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)
                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        messagesWithMagic,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        applyExtraParams(googlePayload, extraParams)
                    } else {
                        payloadFinal
                    }

            return streamJsonPayloadSse(finalUrl, requestPayload, text, onPartial, settings.apiKey)
        } else {
            // Legacy Mode
            val payload = jsonObj { put("text", text) }
            val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_STREAM
            return streamJsonPayloadSse(url, payload, text, onPartial, null)
        }
    }
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

    suspend fun exportSelectedNotes(noteIds: Collection<Long>): ExportResult =
            withContext(ioDispatcher) {
                try {
                    val notes = getByIds(noteIds)
                    exportNotesInternal(notes, "No selected AI notes to export")
                } catch (e: Exception) {
                    ExportResult(
                            success = false,
                            exportedCount = 0,
                            isEmpty = false,
                            message = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }

    suspend fun exportAllNotes(bookId: String): ExportResult =
            withContext(ioDispatcher) {
                try {
                    val notes = getByBook(bookId)
                    exportNotesInternal(notes, "No AI notes to export for this book")
                } catch (e: Exception) {
                    ExportResult(
                            success = false,
                            exportedCount = 0,
                            isEmpty = false,
                            message = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }

    private suspend fun exportNotesInternal(
            notes: List<AiNoteEntity>,
            emptyMessage: String
    ): ExportResult {
        if (notes.isEmpty()) {
            return ExportResult(
                    success = false,
                    exportedCount = 0,
                    isEmpty = true,
                    message = emptyMessage
            )
        }

        val settings = getSettings()

        val bookTitlesById: Map<String, String?> =
                notes.mapNotNull { it.bookId }.distinct().let { ids ->
                    if (ids.isEmpty()) emptyMap()
                    else ids.chunked(900).flatMap { chunk -> bookDao.getByIds(chunk) }.associateBy({ it.bookId }, { it.title })
                }

        val notesArray =
                jsonArr {
                    notes.forEach { note ->
                        val msgs = buildMessages(note)
                        val originalText =
                                note.originalText?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.originalTextFromMessages(
                                                        msgs.toString()
                                                )
                                                .orEmpty()
                        val aiResponse =
                                note.aiResponse?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.aiResponseFromMessages(
                                                        msgs.toString()
                                                )
                                                .orEmpty()

                        add(
                                jsonObj {
                                    put("id", note.id)
                                    put("bookId", note.bookId?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put(
                                            "bookTitle",
                                            (note.bookTitle
                                                    ?: bookTitlesById[note.bookId])
                                                    ?.let { JsonPrimitive(it) }
                                                    ?: JsonNull
                                    )
                                    put("originalText", originalText)
                                    put("aiResponse", aiResponse)
                                    put("messages", msgs)
                                    put("locatorJson", note.locatorJson?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("createdAt", note.createdAt)
                                }
                        )
                    }
                }

        val payload = jsonObj { put("notes", notesArray) }
        val payloadString = payload.toString()

        val exportUrl =
                if (settings.exportToCustomUrl && settings.exportCustomUrl.isNotBlank()) {
                    settings.exportCustomUrl.trim()
                } else {
                    getBaseUrl() + HttpConfig.PATH_AI_NOTES_EXPORT
                }

        val statusMessages = mutableListOf<String>()
        var remoteSuccess = false
        var remoteAttempted = false

        if (exportUrl.isNotBlank()) {
            remoteAttempted = true
            val normalizedExportUrl =
                    when {
                        exportUrl.startsWith("http://", ignoreCase = true) ||
                                exportUrl.startsWith("https://", ignoreCase = true) -> exportUrl
                        else -> "https://$exportUrl"
                    }
            if (!normalizedExportUrl.isValidHttpUrl()) {
                statusMessages += "Invalid export URL: $exportUrl"
            } else {
                try {
                    val response =
                            ktorClient.post(normalizedExportUrl) {
                                    contentType(ContentType.Application.Json)
                                    setBody(payloadString)
                            }
                    if (response.status.isSuccess()) {
                        remoteSuccess = true
                        statusMessages += "Uploaded ${notes.size} notes to $normalizedExportUrl"
                    } else {
                        statusMessages += "Server export failed (${response.status.value})"
                    }
                } catch (e: Exception) {
                    statusMessages += "Server export error: ${e.message ?: "Unknown error"}"
                }
            }
        }

        var localSuccess = true
        var localPath: String? = null
        if (settings.exportToLocalDownloads) {
            val result = platformFiles().writeDownloadsFile("ai-notes.json", prettyJsonString(payload))
            if (result.localPath != null) {
                localPath = result.localPath
                statusMessages += result.message
            } else {
                statusMessages += result.message
                localSuccess = false
            }
        }

        val overallSuccess =
                (!remoteAttempted || remoteSuccess) &&
                        (!settings.exportToLocalDownloads || localSuccess)

        return ExportResult(
                success = overallSuccess,
                exportedCount = notes.size,
                isEmpty = false,
                message = statusMessages.joinToString(" | ").ifBlank { null },
                localPath = localPath
        )
    }

    suspend fun testExportEndpoint(targetUrl: String): String =
            withContext(ioDispatcher) {
                val safeUrl = targetUrl.trim()
                if (safeUrl.isEmpty()) {
                    return@withContext "URL is empty"
                }
                val normalizedUrl =
                        when {
                            safeUrl.startsWith("http://", ignoreCase = true) ||
                                    safeUrl.startsWith("https://", ignoreCase = true) -> safeUrl
                            else -> "https://$safeUrl"
                        }

                val payload =
                        jsonObj {
                            put("ping", "ai-notes-export-test")
                            put("timestamp", currentEpochMillis())
                        }
                if (!normalizedUrl.isValidHttpUrl()) return@withContext "Invalid URL"

                return@withContext try {
                    val response =
                            ktorClient.post(normalizedUrl) {
                                    contentType(ContentType.Application.Json)
                                    setBody(payload.toString())
                            }
                    if (response.status.isSuccess()) {
                        "Success (${response.status.value})"
                    } else {
                        "Failed (${response.status.value})"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message ?: "Unknown error"}"
                }
            }
    suspend fun continueConversation(
            note: AiNoteEntity,
            followUpText: String,
            magicTag: MagicTag? = null
    ): String? =
            withContext(ioDispatcher) {
                val settings = getSettings()
                if (settings.apiKey.isNotBlank()) {
                    try {
                        val url = getBaseUrl()
                        val isGoogle = isGoogleNative(url)
                        val extraParams = loadExtraParams()
                        val systemPrompt = resolveSystemPrompt(settings, magicTag)

                        val requestBody: String

                        if (isGoogle) {
                            val history = buildMessages(note)
                            // Add current user message
                            val userInputWithHint =
                                    resolveUserInput(settings, followUpText, magicTag)
                            val historyWithMagic = maybeAddAssistantMagic(history, magicTag)
                            val historyFinal =
                                    jsonArr {
                                        addAll(historyWithMagic)
                                        add(
                                                jsonObj {
                                                    put("role", "user")
                                                    put("content", userInputWithHint)
                                                }
                                        )
                                    }

                            val googlePayload =
                                    transformToGooglePayload(
                                            settings.aiModelName,
                                            historyFinal,
                                            systemPrompt,
                                            settings.temperature,
                                            settings.maxTokens,
                                            settings.topP,
                                            settings.frequencyPenalty,
                                            settings.presencePenalty,
                                            includeGoogleSearch = settings.enableGoogleSearch
                                    )
                            requestBody =
                                    applyExtraParams(googlePayload, extraParams)
                                            .toString()
                            
                        } else {
                            // Standard OpenAI
                            val history = buildMessages(note)

                            val messages =
                                    jsonArr {
                                        add(
                                                jsonObj {
                                                    put("role", "system")
                                                    put("content", systemPrompt)
                                                }
                                        )
                                        addAll(history)
                                    }
                            val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)

                            val userInputWithHint =
                                    resolveUserInput(settings, followUpText, magicTag)
                            val messagesFinal =
                                    jsonArr {
                                        addAll(messagesWithMagic)
                                        add(
                                                jsonObj {
                                                    put("role", "user")
                                                    put("content", userInputWithHint)
                                                }
                                        )
                                    }

                            val payload =
                                    jsonObj {
                                        put("model", settings.aiModelName)
                                        put("messages", messagesFinal)
                                        put("stream", false)
                                        put("temperature", settings.temperature)
                                        put("max_tokens", settings.maxTokens)
                                        put("top_p", settings.topP)
                                        if (!isGoogleHost(url)) {
                                            if (settings.frequencyPenalty != 0.0) {
                                                put("frequency_penalty", settings.frequencyPenalty)
                                            }
                                            if (settings.presencePenalty != 0.0) {
                                                put("presence_penalty", settings.presencePenalty)
                                            }
                                        }
                                    }
                            requestBody =
                                    applyExtraParams(payload, extraParams)
                                            .withField("stream", JsonPrimitive(false))
                                            .toString()
                        }

                        val response =
                                ktorClient.post(url) {
                                        contentType(ContentType.Application.Json)
                                        if (isGoogle) {
                                                header("x-goog-api-key", settings.apiKey)
                                        } else {
                                                header("Authorization", "Bearer ${settings.apiKey}")
                                        }
                                        setBody(requestBody)
                                }
                        if (response.status.isSuccess()) {
                                val respBody = response.bodyAsText()
                                if (respBody != null) {
                                    val respJson = parseJsonObject(respBody)
                                    val content =
                                            if (respJson == null) {
                                                ""
                                            } else if (isGoogle) {
                                                parseGoogleResponse(respJson)
                                            } else {
                                                val choices = respJson.optJsonArray("choices")
                                                choices?.optJsonObject(0)
                                                        ?.optJsonObject("message")
                                                        ?.optString("content", "")
                                            }
                                                    ?: ""
                                    if (content.isNotEmpty()) content else null
                                } else null
                            } else null
                    } catch (e: Exception) {
                        logger.e(TAG, "continueConversation failed", e)
                        null
                    }
                } else {
                    // Legacy
                    try {
                        val payload =
                                jsonObj {
                                    put("history", buildMessages(note))
                                    put("text", followUpText)
                                }

                        val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE
                        val response =
                                ktorClient.post(url) {
                                        contentType(ContentType.Application.Json)
                                        setBody(payload.toString())
                                }
                        if (!response.status.isSuccess()) return@withContext null
                        response.bodyAsText().let { body ->
                                parseJsonObject(body)?.optString("content", "")
                                        ?.takeIf { it.isNotEmpty() }
                        }
                    } catch (e: Exception) {
                        logger.e(TAG, "continueConversation legacy failed", e)
                        null
                    }
                }
            }

    suspend fun continueConversationStreaming(
            note: AiNoteEntity,
            followUpText: String,
            magicTag: MagicTag? = null,
            onPartial: suspend (String) -> Unit
    ): String? {
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            val url = getBaseUrl() // Direct URL
            val extraParams = loadExtraParams()
            val history = buildMessages(note)

            // System Prompt from Settings
            val systemPrompt = resolveSystemPrompt(settings, magicTag)

            val messages =
                    jsonArr {
                        add(
                                jsonObj {
                                    put("role", "system")
                                    put("content", systemPrompt)
                                }
                        )
                        addAll(history)
                    }
            val messagesWithMagic = maybeAddAssistantMagic(messages, magicTag)

            // Add current user message with template
            val userInputWithHint = resolveUserInput(settings, followUpText, magicTag)

            val messagesFinal =
                    jsonArr {
                        addAll(messagesWithMagic)
                        add(
                                jsonObj {
                                    put("role", "user")
                                    put("content", userInputWithHint)
                                }
                        )
                    }

            val payload =
                    jsonObj {
                        put("model", settings.aiModelName)
                        put("messages", messagesFinal)
                        put("stream", true)
                        put("temperature", settings.temperature)
                        put("max_tokens", settings.maxTokens)
                        put("top_p", settings.topP)
                        if (!isGoogleHost(url)) {
                            if (settings.frequencyPenalty != 0.0) {
                                put("frequency_penalty", settings.frequencyPenalty)
                            }
                            if (settings.presencePenalty != 0.0) {
                                put("presence_penalty", settings.presencePenalty)
                            }
                        }
                    }
            val merged = applyExtraParams(payload, extraParams)
            val payloadFinal = merged.withField("stream", JsonPrimitive(true))

            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload =
                    if (isGoogle) {
                        // History + User Input -> Google 'contents'
                        val historyGoogle = buildMessages(note)
                        val userInputWithHintGoogle =
                                resolveUserInput(settings, followUpText, magicTag)
                        val historyWithMagic = maybeAddAssistantMagic(historyGoogle, magicTag)
                        val historyFinal =
                                jsonArr {
                                    addAll(historyWithMagic)
                                    add(
                                            jsonObj {
                                                put("role", "user")
                                                put("content", userInputWithHintGoogle)
                                            }
                                    )
                                }
                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        historyFinal,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        applyExtraParams(googlePayload, extraParams)
                    } else {
                        payloadFinal
                    }

            return streamJsonPayloadSse(
                            finalUrl,
                            requestPayload,
                            followUpText,
                            onPartial,
                            settings.apiKey
                    )
                    ?.second
        } else {
            val payload =
                    jsonObj {
                        put("history", buildMessages(note))
                        put("text", followUpText)
                    }
            val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE_STREAM
            return streamJsonPayloadSse(url, payload, followUpText, onPartial)?.second
        }
    }

    private suspend fun streamJsonPayloadSse(
            url: String,
            payload: JsonObject,
            fallbackText: String,
            onPartial: suspend (String) -> Unit,
            apiKey: String? = null
    ): Pair<String, String>? =
            withContext(ioDispatcher) {
                try {
                    logger.d(TAG, "Streaming SSE from: $url")

                    lastStreamingError = null
                    val response =
                            ktorClient.preparePost(url) {
                                    header("Accept", "text/event-stream")
                                    if (!apiKey.isNullOrBlank()) {
                                            if (isGoogleNative(url)) {
                                                    header("x-goog-api-key", apiKey)
                                            } else {
                                                    header("Authorization", "Bearer $apiKey")
                                            }
                                    }
                                    contentType(ContentType.Application.Json)
                                    setBody(payload.toString())
                                    timeout { requestTimeoutMillis = 0; socketTimeoutMillis = 0 }
                            }.execute()
                    if (!response.status.isSuccess()) {
                            val errorBody = response.bodyAsText()
                            lastStreamingError =
                                    my.hinoki.booxreader.data.remote.StreamingErrorHandler.parseError(
                                            response.status.value,
                                            errorBody
                                    )
                            logger.e(
                                    TAG,
                                    "Streaming Request Failed: Code=${response.status.value}"
                            )
                            return@withContext null
                    }
                    val channel = response.bodyAsChannel()
                    val contentBuilder = StringBuilder()
                    var serverText: String? = null

                    while (true) {
                            val line = channel.readUTF8Line() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) continue
                            if (trimmed.startsWith(":"))
                                    continue // SSE comment (e.g., OpenRouter status)
                            if (!trimmed.startsWith("data:")) continue

                            val payloadLine = trimmed.removePrefix("data:").trim()
                            if (payloadLine == "[DONE]") break

                            val chunk = parseStreamingChunk(payloadLine)
                            if (chunk.serverText != null) serverText = chunk.serverText
                            if (chunk.delta.isNotEmpty()) {
                                    contentBuilder.append(chunk.delta)
                                    // push partial immediately
                                    withContext(Dispatchers.Main) {
                                            onPartial(contentBuilder.toString())
                                    }
                            }
                    }

                    val content = contentBuilder.toString()
                    if (content.isBlank()) return@withContext null
                    Pair(serverText ?: fallbackText, content)
                } catch (e: Exception) {
                    lastStreamingError = my.hinoki.booxreader.data.remote.StreamingErrorHandler.parseError(0, e.message)
                    logger.e(TAG, "Streaming SSE failed: ${e.message}", e)
                    null
                }
            }

    private fun parseStreamingChunk(raw: String): StreamingChunk {
        return try {
            val json = parseJsonObject(raw) ?: return StreamingChunk(null, raw, false)
            val serverText = json.optString("text", "").takeIf { it.isNotBlank() }
            val doneFromFlag = json.optBoolean("done", false)

            // OpenAI-style SSE: choices[0].delta.content, finish_reason == "stop"
            val choices = json.optJsonArray("choices")
            val firstChoice = choices?.optJsonObject(0)
            val deltaObj = firstChoice?.optJsonObject("delta")
            val contentFromDelta = deltaObj?.optString("content", "") ?: ""
            val finish = firstChoice?.optString("finish_reason", "")

            // Google Native SSE: candidates[0].content.parts[0].text
            val candidates = json.optJsonArray("candidates")
            val firstCandidate = candidates?.optJsonObject(0)
            val contentParts = firstCandidate?.optJsonObject("content")?.optJsonArray("parts")
            val contentFromGoogle = contentParts?.optJsonObject(0)?.optString("text", "") ?: ""
            val finishGoogle = firstCandidate?.optString("finishReason", "")

            val delta =
                    when {
                        contentFromDelta.isNotEmpty() -> contentFromDelta
                        contentFromGoogle.isNotEmpty() -> contentFromGoogle
                        json.containsKey("delta") -> json.optString("delta", "")
                        json.containsKey("content") -> json.optString("content", "")
                        else -> json.optString("text", "")
                    }

            val done =
                    doneFromFlag ||
                            (finish != null && finish != "null" && finish != "unknown") ||
                            (finishGoogle != null &&
                                    finishGoogle != "null" &&
                                    finishGoogle != "unknown" &&
                                    finishGoogle != "STOP") // Google often sends STOP at end
            StreamingChunk(serverText, delta, done)
        } catch (e: Exception) {
            StreamingChunk(null, raw, false)
        }
    }

    private data class StreamingChunk(
            val serverText: String?,
            val delta: String,
            val done: Boolean
    )
}

private val aiNoteJson = Json { ignoreUnknownKeys = true }

private fun jsonObj(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

private fun jsonArr(block: JsonArrayBuilder.() -> Unit): JsonArray = buildJsonArray(block)

/** 安全讀取 JsonObject 欄位：缺失或 JSON null 回傳 default（對應 org.json optString）。 */
private fun JsonObject.optString(key: String, default: String = ""): String {
    val v = this[key] ?: return default
    if (v is JsonNull) return default
    return (v as? JsonPrimitive)?.contentOrNull ?: default
}

private fun JsonObject.optJsonObject(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.optJsonArray(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonArray.optJsonObject(index: Int): JsonObject? = getOrNull(index) as? JsonObject

private fun JsonArray.optString(index: Int): String {
    val v = getOrNull(index) ?: return ""
    if (v is JsonNull) return ""
    return (v as? JsonPrimitive)?.contentOrNull ?: ""
}

private fun JsonObject.optDouble(key: String, default: Double): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

private fun JsonObject.optInt(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: default

private fun JsonObject.optBoolean(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

private fun parseJsonObject(raw: String): JsonObject? =
        runCatching { aiNoteJson.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun parseJsonArray(raw: String): JsonArray? =
        runCatching { aiNoteJson.parseToJsonElement(raw).jsonArray }.getOrNull()

private fun prettyJsonString(element: JsonElement): String =
        Json { prettyPrint = true; ignoreUnknownKeys = true }
                .encodeToString(JsonElement.serializer(), element)

/** 回傳帶指定欄位的新 JsonObject（不可變物件輔助）。 */
private fun JsonObject.withField(key: String, value: JsonElement): JsonObject =
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
