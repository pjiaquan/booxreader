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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiNoteRepository(context: Context) {
    private val dao = AppDatabase.get(context).aiNoteDao()

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

                val request = Request.Builder()
                    .url(HttpConfig.TEXT_AI_ENDPOINT)
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
}
