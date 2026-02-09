package my.hinoki.booxreader.data.repo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AiNoteRepository(
        private val context: Context,
        private val client: OkHttpClient,
        private val syncRepo: UserSyncRepository? = null
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
    private val dao = AppDatabase.get(context).aiNoteDao()
    private val bookDao = AppDatabase.get(context).bookDao()

    private fun prefs() = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    fun isStreamingEnabled(): Boolean {
        return prefs().getBoolean("use_streaming", false)
    }

    private fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        var url =
                prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                        ?: HttpConfig.DEFAULT_BASE_URL
        return if (url.endsWith("/")) url.dropLast(1) else url
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
            messages: JSONArray,
            systemPrompt: String?,
            temperature: Double,
            maxTokens: Int,
            topP: Double,
            frequencyPenalty: Double,
            presencePenalty: Double,
            includeGoogleSearch: Boolean = false
    ): JSONObject {
        val contents = JSONArray()
        var finalSystemPrompt = systemPrompt ?: ""

        val systemInstructionObj =
                if (finalSystemPrompt.isNotEmpty()) {
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", finalSystemPrompt)))
                    }
                } else null

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
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
            val parts = JSONArray().put(JSONObject().put("text", content))

            contents.put(
                    JSONObject().apply {
                        put("role", googleRole)
                        put("parts", parts)
                    }
            )
        }

        // Use local variable for systemInstruction to avoid re-assignment error if val used
        // incorrectly above
        // Re-evaluate system instruction if it was extracted from messages
        val finalSystemInstruction =
                if (finalSystemPrompt.isNotEmpty()) {
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", finalSystemPrompt)))
                    }
                } else systemInstructionObj

        return JSONObject().apply {
            // Google Native often embeds model in URL, but payload body structure is:
            // { contents: [], systemInstruction: {}, generationConfig: {} }
            put("contents", contents)
            if (finalSystemInstruction != null) {
                put("systemInstruction", finalSystemInstruction)
            }
            put(
                    "generationConfig",
                    JSONObject().apply {
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
                        JSONArray().apply {
                            put(JSONObject().apply { put("googleSearch", JSONObject()) })
                        }
                )
            }
        }
    }

    private fun parseGoogleResponse(json: JSONObject): String? {
        // candidates[0].content.parts[0].text
        val candidates = json.optJSONArray("candidates")
        val firstCandidate = candidates?.optJSONObject(0)
        val content = firstCandidate?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        return parts?.optJSONObject(0)?.optString("text", "")
    }

    private fun normalizeMagicRole(tag: MagicTag?): String? {
        return tag?.role?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
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
        return String.format(settings.safeUserPromptTemplate, userText)
    }

    private fun maybeAddAssistantMagic(messages: JSONArray, tag: MagicTag?) {
        val role = normalizeMagicRole(tag)
        val magicText = magicText(tag)
        if (role == "assistant" && magicText.isNotEmpty()) {
            messages.put(
                    JSONObject().apply {
                        put("role", "assistant")
                        put("content", magicText)
                    }
            )
        }
    }

    suspend fun add(
            bookId: String?,
            originalText: String,
            aiResponse: String,
            locatorJson: String? = null,
            bookTitle: String? = null
    ): Long {
        val resolvedTitle =
                bookTitle ?: bookId?.let { id -> bookDao.getByIds(listOf(id)).firstOrNull()?.title }

        val messages =
                JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", originalText))
                    if (aiResponse.isNotBlank()) {
                        put(JSONObject().put("role", "assistant").put("content", aiResponse))
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
                        updatedAt = System.currentTimeMillis()
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
                        updatedAt = System.currentTimeMillis()
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

    suspend fun getAll(): List<AiNoteEntity> {
        return dao.getAll().map { normalizeForRead(it) }
    }

    suspend fun getByBook(bookId: String): List<AiNoteEntity> {
        return dao.getByBookId(bookId).map { normalizeForRead(it) }
    }

    suspend fun getByIds(ids: Collection<Long>): List<AiNoteEntity> {
        if (ids.isEmpty()) return emptyList()
        return dao.getByIds(ids.toList()).map { normalizeForRead(it) }
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

    private fun buildMessages(note: AiNoteEntity): JSONArray {
        val json = buildMessagesJson(note)
        return runCatching { JSONArray(json) }.getOrDefault(JSONArray())
    }

    private fun getSettings(): ReaderSettings {
        return ReaderSettings.fromPrefs(prefs())
    }

    private fun parseExtraParamsJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private suspend fun loadExtraParams(): JSONObject? =
            withContext(Dispatchers.IO) {
                val activeProfileId = prefs().getLong("active_ai_profile_id", -1L)
                if (activeProfileId <= 0L) return@withContext null
                val profile = AppDatabase.get(context).aiProfileDao().getById(activeProfileId)
                return@withContext parseExtraParamsJson(profile?.extraParamsJson)
            }

    private fun mergeJson(target: JSONObject, extra: JSONObject) {
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val extraValue = extra.get(key)
            val targetValue = target.opt(key)
            if (extraValue is JSONObject && targetValue is JSONObject) {
                mergeJson(targetValue, extraValue)
            } else {
                target.put(key, extraValue)
            }
        }
    }

    private fun applyExtraParams(target: JSONObject, extra: JSONObject?) {
        if (extra == null) return
        mergeJson(target, extra)
    }

    private fun logPayload(tag: String, payload: JSONObject) {
        try {} catch (_: Exception) {}
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

    private fun optLongOrNull(json: JSONObject?, key: String): Long? {
        if (json == null || !json.has(key)) return null
        return runCatching { json.getLong(key) }.getOrNull()
    }

    private fun parseSemanticResultsArray(body: String): JSONArray? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }.getOrNull()
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val keys = listOf("results", "matches", "data", "items", "points", "hits")
        for (key in keys) {
            val candidate = root.optJSONArray(key)
            if (candidate != null) return candidate
        }
        return null
    }

    private fun parseReason(item: JSONObject, payload: JSONObject?): String? {
        val direct =
                firstNonBlank(
                        item.optString("reason", ""),
                        item.optString("matchReason", ""),
                        payload?.optString("reason", ""),
                        payload?.optString("matchReason", ""),
                        payload?.optString("reasoning", "")
                )
        if (!direct.isNullOrBlank()) return direct

        val reasonArray = payload?.optJSONArray("reasons") ?: item.optJSONArray("reasons")
        if (reasonArray != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until reasonArray.length()) {
                val part = reasonArray.optString(i).trim()
                if (part.isNotEmpty()) parts.add(part)
            }
            if (parts.isNotEmpty()) return parts.take(3).joinToString(" / ")
        }

        val tagArray = payload?.optJSONArray("tags") ?: item.optJSONArray("tags")
        if (tagArray != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until tagArray.length()) {
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
            withContext(Dispatchers.IO) {
                val boundedLimit = limit.coerceIn(1, 5)
                val query = buildSemanticQuery(note)
                if (query.isBlank()) return@withContext emptyList()

                val baseUrl = getBaseUrl()
                if (baseUrl.isBlank()) return@withContext emptyList()

                val url = baseUrl + HttpConfig.PATH_AI_NOTES_SEMANTIC_SEARCH
                val requestPayload =
                        JSONObject().apply {
                            put("query", query)
                            put("limit", boundedLimit)
                            put("noteId", note.remoteId ?: note.id.toString())
                            if (!note.bookId.isNullOrBlank()) {
                                put("bookId", note.bookId)
                            }
                            if (!note.remoteId.isNullOrBlank()) {
                                put("excludeRemoteId", note.remoteId)
                            }
                            put("excludeLocalId", note.id)
                        }
                val requestBody =
                        requestPayload
                                .toString()
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(requestBody).build()

                return@withContext runCatching {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return@use emptyList()
                                val responseBody = response.body?.string().orEmpty()
                                val results = parseSemanticResultsArray(responseBody)
                                if (results == null || results.length() == 0) return@use emptyList()

                                val currentRemoteId = note.remoteId?.trim().orEmpty()
                                val currentLocalId = note.id
                                val parsed = mutableListOf<SemanticRelatedNote>()
                                for (i in 0 until results.length()) {
                                    val item = results.optJSONObject(i) ?: continue
                                    val payload = item.optJSONObject("payload")
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
                        }
                        .onFailure { error ->
                            Log.e(TAG, "searchRelatedNotesFromQdrant failed", error)
                        }
                        .getOrDefault(emptyList())
            }

    suspend fun fetchRemainingCredits(): Int? =
            withContext(Dispatchers.IO) {
                val settings = getSettings()
                if (settings.apiKey.isNotBlank() || settings.aiModelName.isNotBlank()) {
                    return@withContext null
                }
                val baseUrl = getBaseUrl()
                if (baseUrl.isBlank()) return@withContext null

                val url = "$baseUrl/ai-chat/ai/credits"
                val request = Request.Builder().url(url).get().build()

                return@withContext runCatching {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) return@runCatching null
                                val body = response.body?.string().orEmpty()
                                if (body.isBlank()) return@runCatching null
                                JSONObject(body).optInt("credits", -1).takeIf { it >= 0 }
                            }
                        }
                        .getOrNull()
            }

    suspend fun fetchAiExplanation(
            text: String,
            magicTag: MagicTag? = null
    ): Pair<String, String>? {
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            return withContext(Dispatchers.IO) {
                try {
                    val url = getBaseUrl()
                    val isGoogle = isGoogleNative(url)
                    val extraParams = loadExtraParams()
                    val systemPrompt = resolveSystemPrompt(settings, magicTag)

                    val requestBody: okhttp3.RequestBody
                    val requestBuilder = Request.Builder().url(url)
                    // Google Native often puts API Key in Query Param for simplicity if using that
                    // style,
                    // but Header 'x-goog-api-key' is standard. Adapter supports Header.

                    if (isGoogle) {
                        val messages =
                                JSONArray().apply {
                                    // In Google adapter logic, we'll extract system prompt from
                                    // here or pass it explicitly.
                                    // Here we construct OpenAI style first, then transform.
                                    // OR we just use transform directly.
                                    put(
                                            JSONObject().apply {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        maybeAddAssistantMagic(messages, magicTag)

                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        messages,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        applyExtraParams(googlePayload, extraParams)
                        logPayload("fetchAiExplanation_google", googlePayload)
                        requestBody =
                                googlePayload
                                        .toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        // Header for Google
                        requestBuilder.header("x-goog-api-key", settings.apiKey)
                    } else {
                        // Standard OpenAI logic
                        val messages =
                                JSONArray().apply {
                                    put(
                                            JSONObject().apply {
                                                put("role", "system")
                                                put("content", systemPrompt)
                                            }
                                    )
                                    maybeAddAssistantMagic(this, magicTag)
                                    put(
                                            JSONObject().apply {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        val payload =
                                JSONObject().apply {
                                    put("model", settings.aiModelName)
                                    put("messages", messages)
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
                        applyExtraParams(payload, extraParams)
                        payload.put("stream", false)
                        requestBody =
                                payload.toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )
                        requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
                    }

                    val request =
                            requestBuilder
                                    .post(requestBody)
                                    .tag(
                                            String::class.java,
                                            "SKIP_AUTH"
                                    ) // Skip internal auth interceptor/authenticator
                                    .build()

                    Log.d(TAG, "Fetching AI Explanation from: $url")

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val respBody = response.body?.string()
                            if (respBody != null) {
                                val respJson = JSONObject(respBody)
                                val content =
                                        if (isGoogle) {
                                            parseGoogleResponse(respJson)
                                        } else {
                                            val choices = respJson.optJSONArray("choices")
                                            choices?.optJSONObject(0)
                                                    ?.optJSONObject("message")
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
                            val errorBody = response.body?.string()
                            Log.e(TAG, "AI Request Failed: Code=${response.code}, Body=$errorBody")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in fetchAiExplanation", e)
                    e.printStackTrace()
                    null
                }
            }
        }

        // Legacy Implementation
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply { put("text", text) }.toString()

                val requestBody =
                        jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI
                val request = Request.Builder().url(url).post(requestBody).build()
                // ... existing client call ...
                client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { response ->
                            if (response.isSuccessful) {
                                val respBody = response.body?.string()
                                if (respBody != null) {
                                    val respJson = JSONObject(respBody)
                                    val serverText = respJson.optString("text", "")
                                    val responseText =
                                            if (serverText.isNotBlank()) serverText else text
                                    val content = respJson.optString("content", "")
                                    Pair(responseText, content)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
            } catch (e: Exception) {
                e.printStackTrace()
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
                    JSONArray().apply {
                        put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put("content", systemPrompt)
                                }
                        )
                        maybeAddAssistantMagic(this, magicTag)
                        put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", resolveUserInput(settings, text, magicTag))
                                }
                        )
                    }

            val payload =
                    JSONObject().apply {
                        put("model", settings.aiModelName)
                        put("messages", messages)
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
            applyExtraParams(payload, extraParams)
            payload.put("stream", true)

            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload =
                    if (isGoogle) {
                        // OpenAI 'messages' -> Google 'contents'
                        val messages =
                                JSONArray().apply {
                                    put(
                                            JSONObject().apply {
                                                put("role", "user")
                                                put(
                                                        "content",
                                                        resolveUserInput(settings, text, magicTag)
                                                )
                                            }
                                    )
                                }
                        maybeAddAssistantMagic(messages, magicTag)
                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        messages,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        applyExtraParams(googlePayload, extraParams)
                        logPayload("fetchAiExplanationStreaming_google", googlePayload)
                        googlePayload
                    } else {
                        payload
                    }

            return streamJsonPayloadSse(finalUrl, requestPayload, text, onPartial, settings.apiKey)
        } else {
            // Legacy Mode
            val payload = JSONObject().apply { put("text", text) }
            val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_STREAM
            return streamJsonPayloadSse(url, payload, text, onPartial, null)
        }
    }
    suspend fun deleteSelectedNotes(noteIds: Collection<Long>): DeleteResult =
            withContext(Dispatchers.IO) {
                if (noteIds.isEmpty()) {
                    return@withContext DeleteResult(0, 0)
                }
                val notes = dao.getByIds(noteIds.toList())
                var deletedCount = 0
                var failedCount = 0
                for (note in notes) {
                    if (!note.remoteId.isNullOrBlank()) {
                        val deletedRemote = syncRepo?.deleteAiNote(note.remoteId) ?: true
                        if (!deletedRemote) {
                            failedCount++
                            continue
                        }
                    }
                    dao.deleteById(note.id)
                    deletedCount++
                }
                DeleteResult(deletedCount = deletedCount, failedCount = failedCount)
            }

    suspend fun exportSelectedNotes(noteIds: Collection<Long>): ExportResult =
            withContext(Dispatchers.IO) {
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
            withContext(Dispatchers.IO) {
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
                    else bookDao.getByIds(ids).associateBy({ it.bookId }, { it.title })
                }

        val notesArray =
                JSONArray().apply {
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

                        put(
                                JSONObject().apply {
                                    put("id", note.id)
                                    put("bookId", note.bookId ?: JSONObject.NULL)
                                    put(
                                            "bookTitle",
                                            note.bookTitle
                                                    ?: bookTitlesById[note.bookId]
                                                            ?: JSONObject.NULL
                                    )
                                    put("originalText", originalText)
                                    put("aiResponse", aiResponse)
                                    put("messages", msgs)
                                    put("locatorJson", note.locatorJson ?: JSONObject.NULL)
                                    put("createdAt", note.createdAt)
                                }
                        )
                    }
                }

        val payload = JSONObject().apply { put("notes", notesArray) }
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
            val httpUrl = normalizedExportUrl.toHttpUrlOrNull()
            if (httpUrl == null) {
                statusMessages += "Invalid export URL: $exportUrl"
            } else {
                val requestBody =
                        payloadString.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(httpUrl).post(requestBody).build()

                try {
                    client.newBuilder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build()
                            .newCall(request)
                            .execute()
                            .use { response ->
                                if (response.isSuccessful) {
                                    remoteSuccess = true
                                    statusMessages += "Uploaded ${notes.size} notes to $httpUrl"
                                } else {
                                    statusMessages += "Server export failed (${response.code})"
                                }
                            }
                } catch (e: Exception) {
                    e.printStackTrace()
                    statusMessages += "Server export error: ${e.message ?: "Unknown error"}"
                }
            }
        }

        var localSuccess = true
        var localPath: String? = null
        if (settings.exportToLocalDownloads) {
            val hasLegacyPermission =
                    ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
            val canUsePublicDir =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && hasLegacyPermission

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyPermission) {
                statusMessages += "Local export skipped: storage permission not granted"
                localSuccess = false
            } else {
                localSuccess =
                        try {
                            val locationHint: String
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val resolver = context.contentResolver
                                val fileName = "ai-notes.json"
                                val contentValues =
                                        ContentValues().apply {
                                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                                            put(
                                                    MediaStore.Downloads.MIME_TYPE,
                                                    "application/json"
                                            )
                                            put(
                                                    MediaStore.Downloads.RELATIVE_PATH,
                                                    Environment.DIRECTORY_DOWNLOADS
                                            )
                                            put(MediaStore.Downloads.IS_PENDING, 1)
                                        }
                                val uri =
                                        resolver.insert(
                                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                                contentValues
                                        )
                                                ?: throw IllegalStateException(
                                                        "Unable to create download entry"
                                                )
                                resolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                                    writer?.write(payload.toString(2))
                                            ?: throw IllegalStateException(
                                                    "Unable to open output stream"
                                            )
                                }
                                contentValues.clear()
                                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                                resolver.update(uri, contentValues, null, null)
                                locationHint = "Downloads"
                                localPath = "Downloads/$fileName"
                                statusMessages += "Saved to Downloads/$fileName (public)"
                            } else {
                                val targetDir =
                                        when {
                                            canUsePublicDir ->
                                                    Environment.getExternalStoragePublicDirectory(
                                                            Environment.DIRECTORY_DOWNLOADS
                                                    )
                                            else ->
                                                    context.getExternalFilesDir(
                                                            Environment.DIRECTORY_DOWNLOADS
                                                    )
                                        }
                                                ?: throw IllegalStateException(
                                                        "No Downloads directory available"
                                                )

                                if (!targetDir.exists() && !targetDir.mkdirs()) {
                                    throw IllegalStateException("Unable to create Downloads directory")
                                }
                                val outFile = File(targetDir, "ai-notes.json")
                                outFile.writeText(payload.toString(2))
                                locationHint =
                                        if (canUsePublicDir) "Downloads"
                                        else "app Downloads (no permission required)"
                                localPath = outFile.absolutePath
                                statusMessages +=
                                        "Saved to ${outFile.absolutePath} ($locationHint)"
                            }
                            true
                        } catch (e: Exception) {
                            statusMessages += "Local export failed: ${e.message ?: "Unknown error"}"
                            false
                        }
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
            withContext(Dispatchers.IO) {
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
                        JSONObject().apply {
                            put("ping", "ai-notes-export-test")
                            put("timestamp", System.currentTimeMillis())
                        }
                val httpUrl = normalizedUrl.toHttpUrlOrNull() ?: return@withContext "Invalid URL"
                val request =
                        Request.Builder()
                                .url(httpUrl)
                                .post(
                                        payload.toString()
                                                .toRequestBody(
                                                        "application/json; charset=utf-8".toMediaType()
                                                )
                                )
                                .build()

                return@withContext try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            "Success (${response.code})"
                        } else {
                            "Failed (${response.code})"
                        }
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
            withContext(Dispatchers.IO) {
                val settings = getSettings()
                if (settings.apiKey.isNotBlank()) {
                    try {
                        val url = getBaseUrl()
                        val isGoogle = isGoogleNative(url)
                        val extraParams = loadExtraParams()
                        val systemPrompt = resolveSystemPrompt(settings, magicTag)

                        val requestBody: okhttp3.RequestBody
                        val requestBuilder = Request.Builder().url(url)

                        if (isGoogle) {
                            val history = buildMessages(note)
                            // Add current user message
                            val userInputWithHint =
                                    resolveUserInput(settings, followUpText, magicTag)
                            maybeAddAssistantMagic(history, magicTag)
                            history.put(
                                    JSONObject().apply {
                                        put("role", "user")
                                        put("content", userInputWithHint)
                                    }
                            )

                            val googlePayload =
                                    transformToGooglePayload(
                                            settings.aiModelName,
                                            history,
                                            systemPrompt,
                                            settings.temperature,
                                            settings.maxTokens,
                                            settings.topP,
                                            settings.frequencyPenalty,
                                            settings.presencePenalty,
                                            includeGoogleSearch = settings.enableGoogleSearch
                                    )
                            applyExtraParams(googlePayload, extraParams)
                            requestBody =
                                    googlePayload
                                            .toString()
                                            .toRequestBody(
                                                    "application/json; charset=utf-8".toMediaType()
                                            )
                            requestBuilder.header("x-goog-api-key", settings.apiKey)
                        } else {
                            // Standard OpenAI
                            val history = buildMessages(note)

                            val messages = JSONArray()
                            messages.put(
                                    JSONObject().apply {
                                        put("role", "system")
                                        put("content", systemPrompt)
                                    }
                            )
                            for (i in 0 until history.length()) messages.put(history.get(i))
                            maybeAddAssistantMagic(messages, magicTag)

                            val userInputWithHint =
                                    resolveUserInput(settings, followUpText, magicTag)
                            messages.put(
                                    JSONObject().apply {
                                        put("role", "user")
                                        put("content", userInputWithHint)
                                    }
                            )

                            val payload =
                                    JSONObject().apply {
                                        put("model", settings.aiModelName)
                                        put("messages", messages)
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
                            applyExtraParams(payload, extraParams)
                            payload.put("stream", false)
                            requestBody =
                                    payload.toString()
                                            .toRequestBody(
                                                    "application/json; charset=utf-8".toMediaType()
                                            )
                            requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
                        }

                        val request =
                                requestBuilder
                                        .post(requestBody)
                                        .tag(String::class.java, "SKIP_AUTH")
                                        .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val respBody = response.body?.string()
                                if (respBody != null) {
                                    val respJson = JSONObject(respBody)
                                    val content =
                                            if (isGoogle) {
                                                parseGoogleResponse(respJson)
                                            } else {
                                                val choices = respJson.optJSONArray("choices")
                                                choices?.optJSONObject(0)
                                                        ?.optJSONObject("message")
                                                        ?.optString("content", "")
                                            }
                                                    ?: ""
                                    if (content.isNotEmpty()) content else null
                                } else null
                            } else null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else {
                    // Legacy
                    try {
                        val payload =
                                JSONObject().apply {
                                    put("history", buildMessages(note))
                                    put("text", followUpText)
                                }

                        val requestBody =
                                payload.toString()
                                        .toRequestBody(
                                                "application/json; charset=utf-8".toMediaType()
                                        )

                        val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE
                        val request = Request.Builder().url(url).post(requestBody).build()

                        client.newBuilder()
                                .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout(60, TimeUnit.SECONDS)
                                .writeTimeout(60, TimeUnit.SECONDS)
                                .build()
                                .newCall(request)
                                .execute()
                                .use { response ->
                                    if (!response.isSuccessful) return@withContext null
                                    response.body?.string()?.let { body ->
                                        JSONObject(body).optString("content", "").takeIf {
                                            it.isNotEmpty()
                                        }
                                    }
                                }
                    } catch (e: Exception) {
                        e.printStackTrace()
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

            val messages = JSONArray()
            messages.put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
            )

            // Add history items
            for (i in 0 until history.length()) {
                messages.put(history.get(i))
            }
            maybeAddAssistantMagic(messages, magicTag)

            // Add current user message with template
            val userInputWithHint = resolveUserInput(settings, followUpText, magicTag)

            messages.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userInputWithHint)
                    }
            )

            val payload =
                    JSONObject().apply {
                        put("model", settings.aiModelName)
                        put("messages", messages)
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
            applyExtraParams(payload, extraParams)
            payload.put("stream", true)

            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload =
                    if (isGoogle) {
                        // History + User Input -> Google 'contents'
                        val historyGoogle = buildMessages(note)
                        val userInputWithHintGoogle =
                                resolveUserInput(settings, followUpText, magicTag)
                        maybeAddAssistantMagic(historyGoogle, magicTag)
                        historyGoogle.put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", userInputWithHintGoogle)
                                }
                        )
                        val googlePayload =
                                transformToGooglePayload(
                                        settings.aiModelName,
                                        historyGoogle,
                                        systemPrompt,
                                        settings.temperature,
                                        settings.maxTokens,
                                        settings.topP,
                                        settings.frequencyPenalty,
                                        settings.presencePenalty,
                                        includeGoogleSearch = settings.enableGoogleSearch
                                )
                        applyExtraParams(googlePayload, extraParams)
                        logPayload("continueConversationStreaming_google", googlePayload)
                        googlePayload
                    } else {
                        payload
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
                    JSONObject().apply {
                        put("history", buildMessages(note))
                        put("text", followUpText)
                    }
            val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE_STREAM
            return streamJsonPayloadSse(url, payload, followUpText, onPartial)?.second
        }
    }

    private suspend fun streamJsonPayloadSse(
            url: String,
            payload: JSONObject,
            fallbackText: String,
            onPartial: suspend (String) -> Unit,
            apiKey: String? = null
    ): Pair<String, String>? =
            withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Streaming SSE from: $url")

                    val requestBody =
                            payload.toString()
                                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                    val requestBuilder =
                            Request.Builder()
                                    .url(url)
                                    .post(requestBody)
                                    .header("Accept", "text/event-stream")

                    if (!apiKey.isNullOrBlank()) {
                        if (isGoogleNative(url)) {
                            requestBuilder.header("x-goog-api-key", apiKey)
                        } else {
                            requestBuilder.header("Authorization", "Bearer $apiKey")
                        }
                        requestBuilder.tag(String::class.java, "SKIP_AUTH")
                    }

                    val request = requestBuilder.build()

                    client.newBuilder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(0, TimeUnit.MILLISECONDS) // keep stream open
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build()
                            .newCall(request)
                            .execute()
                            .use { response ->
                                if (!response.isSuccessful) {
                                    val errorBody = response.body?.string()
                                    Log.e(
                                            TAG,
                                            "Streaming Request Failed: Code=${response.code}, Body=$errorBody"
                                    )
                                    return@withContext null
                                }
                                val source = response.body?.source() ?: return@withContext null
                                val contentBuilder = StringBuilder()
                                var serverText: String? = null

                                while (true) {
                                    if (source.exhausted()) break
                                    val line = source.readUtf8Line() ?: break
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
                            }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

    private fun parseStreamingChunk(raw: String): StreamingChunk {
        return try {
            val json = JSONObject(raw)
            val serverText = json.optString("text", "").takeIf { it.isNotBlank() }
            val doneFromFlag = json.optBoolean("done", false)

            // OpenAI-style SSE: choices[0].delta.content, finish_reason == "stop"
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val deltaObj = firstChoice?.optJSONObject("delta")
            val contentFromDelta = deltaObj?.optString("content", "") ?: ""
            val finish = firstChoice?.optString("finish_reason", "")

            // Google Native SSE: candidates[0].content.parts[0].text
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val contentParts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
            val contentFromGoogle = contentParts?.optJSONObject(0)?.optString("text", "") ?: ""
            val finishGoogle = firstCandidate?.optString("finishReason", "")

            val delta =
                    when {
                        contentFromDelta.isNotEmpty() -> contentFromDelta
                        contentFromGoogle.isNotEmpty() -> contentFromGoogle
                        json.has("delta") -> json.optString("delta", "")
                        json.has("content") -> json.optString("content", "")
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
