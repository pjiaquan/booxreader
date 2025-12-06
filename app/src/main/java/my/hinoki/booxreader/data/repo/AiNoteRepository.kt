package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.UserSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiNoteRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val syncRepo: UserSyncRepository? = null
) {
    private val TAG = "AiNoteRepository"
    private val dao = AppDatabase.get(context).aiNoteDao()
    private val bookDao = AppDatabase.get(context).bookDao()

    private fun prefs() = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    fun isStreamingEnabled(): Boolean {
        return prefs().getBoolean("use_streaming", false)
    }

    private fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        var url = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL) ?: HttpConfig.DEFAULT_BASE_URL
        return if (url.endsWith("/")) url.dropLast(1) else url
    }

    suspend fun add(
        bookId: String?,
        originalText: String,
        aiResponse: String,
        locatorJson: String? = null,
        bookTitle: String? = null
    ): Long {
        val resolvedTitle = bookTitle ?: bookId?.let { id ->
            bookDao.getByIds(listOf(id)).firstOrNull()?.title
        }

        val note = AiNoteEntity(
            bookId = bookId,
            bookTitle = resolvedTitle,
            originalText = originalText,
            aiResponse = aiResponse,
            locatorJson = locatorJson,
            updatedAt = System.currentTimeMillis()
        )
        val newId = dao.insert(note)
        val saved = note.copy(id = newId)
        syncRepo?.pushNote(saved)
        return newId
    }

    suspend fun update(note: AiNoteEntity) {
        val updated = note.copy(updatedAt = System.currentTimeMillis())
        dao.update(updated)
        syncRepo?.pushNote(updated)
    }

    suspend fun getById(id: Long): AiNoteEntity? {
        return dao.getById(id)
    }

    suspend fun getAll(): List<AiNoteEntity> {
        return dao.getAll()
    }
    
    suspend fun getByBook(bookId: String): List<AiNoteEntity> {
        return dao.getByBookId(bookId)
    }

    suspend fun findNoteByText(text: String): AiNoteEntity? {
        return dao.findLatestByOriginalText(text)
    }

    suspend fun fetchAiExplanation(text: String): Pair<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("text", text)
                }.toString()

        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI
        android.util.Log.d(TAG, "fetchAiExplanation url=$url")
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

                client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val respBody = response.body?.string()
                            if (respBody != null) {
                                val respJson = JSONObject(respBody)
                                val serverText = respJson.optString("text", "")
                                val responseText = if (serverText.isNotBlank()) serverText else text
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
        onPartial: suspend (String) -> Unit
    ): Pair<String, String>? {
        val payload = JSONObject().apply { put("text", text) }
        val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_STREAM
        android.util.Log.d(TAG, "fetchAiExplanationStreaming url=$url")
        return streamJsonPayloadSse(url, payload, text, onPartial)
    }

    suspend fun exportAllNotes(): ExportResult = withContext(Dispatchers.IO) {
        val notes = dao.getAll()
        if (notes.isEmpty()) {
            return@withContext ExportResult(
                success = false,
                exportedCount = 0,
                isEmpty = true,
                message = "No AI notes to export"
            )
        }

        val bookTitlesById: Map<String, String?> = notes
            .mapNotNull { it.bookId }
            .distinct()
            .let { ids ->
                if (ids.isEmpty()) emptyMap()
                else bookDao.getByIds(ids).associateBy({ it.bookId }, { it.title })
            }

        val notesArray = JSONArray().apply {
            notes.forEach { note ->
                put(
                    JSONObject().apply {
                        put("id", note.id)
                        put("bookId", note.bookId ?: JSONObject.NULL)
                        put("bookTitle", note.bookTitle ?: bookTitlesById[note.bookId] ?: JSONObject.NULL)
                        put("originalText", note.originalText)
                        put("aiResponse", note.aiResponse)
                        put("locatorJson", note.locatorJson ?: JSONObject.NULL)
                        put("createdAt", note.createdAt)
                    }
                )
            }
        }

        val payload = JSONObject().apply {
            put("notes", notesArray)
        }

        val requestBody =
            payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val url = getBaseUrl() + HttpConfig.PATH_AI_NOTES_EXPORT
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return@withContext try {
            client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (response.isSuccessful) {
                        ExportResult(success = true, exportedCount = notes.size)
                    } else {
                        ExportResult(
                            success = false,
                            exportedCount = notes.size,
                            message = "Server error: ${response.code}"
                        )
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            ExportResult(
                success = false,
                exportedCount = notes.size,
                message = e.message ?: "Unknown error"
            )
        }
    }

    suspend fun continueConversation(note: AiNoteEntity, followUpText: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("history", buildHistoryArray(note))
                    put("text", followUpText)
                }

                val requestBody =
                    payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

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
                            JSONObject(body).optString("content", "")
                                .takeIf { it.isNotEmpty() }
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    suspend fun continueConversationStreaming(
        note: AiNoteEntity,
        followUpText: String,
        onPartial: suspend (String) -> Unit
    ): String? {
        val payload = JSONObject().apply {
            put("history", buildHistoryArray(note))
            put("text", followUpText)
        }
        val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE_STREAM
        android.util.Log.d(TAG, "continueConversationStreaming url=$url")
        return streamJsonPayloadSse(url, payload, followUpText, onPartial)?.second
    }

    private suspend fun streamJsonPayloadSse(
        url: String,
        payload: JSONObject,
        fallbackText: String,
        onPartial: suspend (String) -> Unit
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val requestBody =
                payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Accept", "text/event-stream")
                .build()

            client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // keep stream open
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val source = response.body?.source() ?: return@withContext null
                    val contentBuilder = StringBuilder()
                    var serverText: String? = null

                    while (true) {
                        if (source.exhausted()) break
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        if (trimmed.startsWith(":")) continue // SSE comment (e.g., OpenRouter status)
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

            val delta = when {
                contentFromDelta.isNotEmpty() -> contentFromDelta
                json.has("delta") -> json.optString("delta", "")
                json.has("content") -> json.optString("content", "")
                else -> json.optString("text", "")
            }

            val done = doneFromFlag || (finish != null && finish != "null" && finish != "unknown")
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

    private fun buildHistoryArray(note: AiNoteEntity): JSONArray {
        val history = JSONArray()

        // Base user message
        history.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", note.originalText)))
            }
        )

        if (note.aiResponse.isBlank()) {
            return history
        }

        // Split existing responses by separator to reconstruct turns
        val segments = note.aiResponse.split("\n---\n").filter { it.isNotBlank() }
        if (segments.isNotEmpty()) {
            // First segment: original AI answer
            val baseAnswer = segments.first().trim()
            if (baseAnswer.isNotEmpty()) {
                history.put(
                    JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", baseAnswer)))
                    }
                )
            }

            // Subsequent segments: follow-up Q/A pairs formatted as "Q: <question>\n\n<answer>"
            segments.drop(1).forEach { seg ->
                val trimmed = seg.trim()
                if (trimmed.isEmpty()) return@forEach
                val lines = trimmed.lines()
                val first = lines.firstOrNull()?.trim().orEmpty()

                val qPrefix =
                    first.startsWith("Q:", true) ||
                        first.startsWith("Question:", true) ||
                        first.startsWith("User:", true)

                if (qPrefix) {
                    val question = first.substringAfter(":").trim()
                    val answer = lines.drop(1).joinToString("\n").trim()

                    if (question.isNotEmpty()) {
                        history.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", question)))
                            }
                        )
                    }
                    if (answer.isNotEmpty()) {
                        history.put(
                            JSONObject().apply {
                                put("role", "model")
                                put("parts", JSONArray().put(JSONObject().put("text", answer)))
                            }
                        )
                    }
                } else {
                    // No recognizable user prefix: treat whole segment as model to avoid mislabeling
                    history.put(
                        JSONObject().apply {
                            put("role", "model")
                            put("parts", JSONArray().put(JSONObject().put("text", trimmed)))
                        }
                    )
                }
            }
        }

        return history
    }
}

data class ExportResult(
    val success: Boolean,
    val exportedCount: Int,
    val isEmpty: Boolean = false,
    val message: String? = null
)
