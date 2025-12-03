package com.example.booxreader.data.repo

import android.content.Context
import com.example.booxreader.data.db.AiNoteEntity
import com.example.booxreader.data.db.AppDatabase
import com.example.booxreader.data.remote.HttpConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiNoteRepository(private val context: Context) {
    private val dao = AppDatabase.get(context).aiNoteDao()

    private fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        var url = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL) ?: HttpConfig.DEFAULT_BASE_URL
        return if (url.endsWith("/")) url.dropLast(1) else url
    }

    suspend fun add(bookId: String?, originalText: String, aiResponse: String, locatorJson: String? = null): Long {
        val note = AiNoteEntity(
            bookId = bookId,
            originalText = originalText,
            aiResponse = aiResponse,
            locatorJson = locatorJson
        )
        return dao.insert(note)
    }

    suspend fun update(note: AiNoteEntity) {
        dao.update(note)
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
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
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

        val notesArray = JSONArray().apply {
            notes.forEach { note ->
                put(
                    JSONObject().apply {
                        put("id", note.id)
                        put("bookId", note.bookId ?: JSONObject.NULL)
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
            OkHttpClient.Builder()
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

                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
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
                val lines = seg.trim().lines()
                val qLine = lines.firstOrNull()?.removePrefix("Q:")?.trim().orEmpty()
                val answer = lines.drop(1).joinToString("\n").trim()

                if (qLine.isNotEmpty()) {
                    history.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", qLine)))
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
