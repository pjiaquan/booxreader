package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.UserSyncRepository
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import my.hinoki.booxreader.data.settings.ReaderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
import android.provider.MediaStore

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

    private fun isGoogleNative(url: String): Boolean {
        return url.contains("generativelanguage.googleapis.com") && !url.contains("/openai/")
    }

    private fun isGoogleHost(url: String): Boolean {
        return url.contains("generativelanguage.googleapis.com")
    }
    
    // Prefer native streaming endpoint for Gemini; append alt=sse if missing
    private fun googleStreamUrl(url: String): String {
        val streamUrl = if (url.contains(":streamGenerateContent")) {
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

        val systemInstructionObj = if (finalSystemPrompt.isNotEmpty()) {
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
                // For simplicity, if we already have systemPrompt from Settings, we might ignore this or override?
                // The caller usually passes systemPrompt from settings.
                // If caller put system prompt in messages (like in fetchAiExplanation), we extract it.
                if (finalSystemPrompt.isEmpty()) {
                    finalSystemPrompt = content
                }
                continue
            }

            val googleRole = if (role == "user") "user" else "model"
            val parts = JSONArray().put(JSONObject().put("text", content))
            
            contents.put(JSONObject().apply {
                put("role", googleRole)
                put("parts", parts)
            })
        }
        
        // Use local variable for systemInstruction to avoid re-assignment error if val used incorrectly above
        // Re-evaluate system instruction if it was extracted from messages
        val finalSystemInstruction = if (finalSystemPrompt.isNotEmpty()) {
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
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
                put("topP", topP)
                // Google Gemini API (v1beta) does not yet support frequency/presence penalty in standard generationConfig
            })
            if (includeGoogleSearch) {
                // Use googleSearch tool (retrieval variant currently rejected by API)
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("googleSearch", JSONObject())
                    })
                })
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

    private fun getSettings(): ReaderSettings {
        return ReaderSettings.fromPrefs(prefs())
    }

    private fun logPayload(tag: String, payload: JSONObject) {
        try {
            android.util.Log.d(TAG, "$tag payload=${payload.toString(2)}")
        } catch (_: Exception) {
            android.util.Log.d(TAG, "$tag payload=${payload.toString()}")
        }
    }

    suspend fun fetchAiExplanation(text: String): Pair<String, String>? {
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            return withContext(Dispatchers.IO) {
                try {
                    val url = getBaseUrl()
                    val isGoogle = isGoogleNative(url)
                    
                    val requestBody: okhttp3.RequestBody
                    val requestBuilder = Request.Builder()
                        .url(url)
                        // Google Native often puts API Key in Query Param for simplicity if using that style, 
                        // but Header 'x-goog-api-key' is standard. Adapter supports Header.
                    
                    if (isGoogle) {
                        val messages = JSONArray().apply {
                            // In Google adapter logic, we'll extract system prompt from here or pass it explicitly.
                            // Here we construct OpenAI style first, then transform.
                            // OR we just use transform directly.
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", String.format(settings.safeUserPromptTemplate, text))
                            })
                        }
                        
                        val googlePayload = transformToGooglePayload(
                            settings.aiModelName, 
                            messages, 
                            settings.aiSystemPrompt,
                            settings.temperature,
                            settings.maxTokens,
                            settings.topP,
                            settings.frequencyPenalty,
                            settings.presencePenalty,
                            includeGoogleSearch = settings.enableGoogleSearch
                        )
                        logPayload("fetchAiExplanation_google", googlePayload)
                        requestBody = googlePayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        
                        // Header for Google
                        requestBuilder.header("x-goog-api-key", settings.apiKey)

                    } else {
                        // Standard OpenAI logic
                        val messages = JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", settings.aiSystemPrompt)
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", String.format(settings.safeUserPromptTemplate, text))
                            })
                        }
                        val payload = JSONObject().apply {
                            put("model", settings.aiModelName)
                            put("messages", messages)
                            put("stream", false)
                            put("temperature", settings.temperature)
                            put("max_tokens", settings.maxTokens)
                            put("top_p", settings.topP)
                            if (!isGoogleHost(url)) {
                                put("frequency_penalty", settings.frequencyPenalty)
                                put("presence_penalty", settings.presencePenalty)
                            }
                        }
                        requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
                    }

                    val request = requestBuilder
                        .post(requestBody)
                        .tag(String::class.java, "SKIP_AUTH") // Skip internal auth interceptor/authenticator
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val respBody = response.body?.string()
                            if (respBody != null) {
                                val respJson = JSONObject(respBody)
                                val content = if (isGoogle) {
                                    parseGoogleResponse(respJson)
                                } else {
                                    val choices = respJson.optJSONArray("choices")
                                    choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                                } ?: ""

                                if (content.isNotEmpty()) {
                                    Pair(content, content)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            android.util.Log.e(TAG, "fetchAiExplanation failed: ${response.code} ${response.message}")
                            null
                        }
                     }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
    
        // Legacy Implementation
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
// ... existing client call ...
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
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            // Direct DeepSeek API call
            val url = getBaseUrl() // Use base URL directly without appending path
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", settings.aiSystemPrompt)
                })
                    put(JSONObject().apply {
                    put("role", "user")
                    put("content", String.format(settings.safeUserPromptTemplate, text))
                })
            }

            val payload = JSONObject().apply {
                put("model", settings.aiModelName)
                put("messages", messages)
                put("stream", true)
                put("stream", true)
                put("temperature", settings.temperature)
                put("max_tokens", settings.maxTokens)
                put("top_p", settings.topP)
                if (!isGoogleHost(url)) {
                    put("frequency_penalty", settings.frequencyPenalty)
                    put("presence_penalty", settings.presencePenalty)
                }
            }

            android.util.Log.d(TAG, "fetchAiExplanationStreaming (Direct) url=$url")
            
            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload = if (isGoogle) {
                // OpenAI 'messages' -> Google 'contents'
                 val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", String.format(settings.safeUserPromptTemplate, text))
                    })
                }
                val googlePayload = transformToGooglePayload(
                    settings.aiModelName, 
                    messages, 
                    settings.aiSystemPrompt,
                    settings.temperature,
                    settings.maxTokens,
                    settings.topP,
                    settings.frequencyPenalty,
                    settings.presencePenalty,
                    includeGoogleSearch = settings.enableGoogleSearch
                )
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
            android.util.Log.d(TAG, "fetchAiExplanationStreaming (Legacy) url=$url")
            return streamJsonPayloadSse(url, payload, text, onPartial, null)
        }
    }
    suspend fun exportAllNotes(bookId: String): ExportResult = withContext(Dispatchers.IO) {
        try {
        val notes = getByBook(bookId)
        if (notes.isEmpty()) {
            return@withContext ExportResult(
                success = false,
                exportedCount = 0,
                isEmpty = true,
                message = "No AI notes to export for this book"
            )
        }

        val settings = getSettings()

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
        val payloadString = payload.toString()

        // Remote export (default or custom URL)
        val exportUrl = if (settings.exportToCustomUrl && settings.exportCustomUrl.isNotBlank()) {
            settings.exportCustomUrl.trim()
        } else {
            getBaseUrl() + HttpConfig.PATH_AI_NOTES_EXPORT
        }

        val statusMessages = mutableListOf<String>()
        var remoteSuccess = false
        var remoteAttempted = false

        if (exportUrl.isNotBlank()) {
            remoteAttempted = true
            val normalizedExportUrl = when {
                exportUrl.startsWith("http://", ignoreCase = true) || exportUrl.startsWith("https://", ignoreCase = true) -> exportUrl
                else -> "https://$exportUrl"
            }
            val httpUrl = normalizedExportUrl.toHttpUrlOrNull()
            if (httpUrl == null) {
                statusMessages += "Invalid export URL: $exportUrl"
            } else {
                val requestBody =
                    payloadString.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(httpUrl)
                    .post(requestBody)
                    .build()

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

        // Local export to Downloads (optional)
        var localSuccess = true
        var localPath: String? = null
        if (settings.exportToLocalDownloads) {
            val hasLegacyPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val canUsePublicDir = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && hasLegacyPermission

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyPermission) {
                statusMessages += "Local export skipped: storage permission not granted"
                localSuccess = false
            } else {
                localSuccess = try {
                    val locationHint: String
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = context.contentResolver
                        val fileName = "ai-notes.json"
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            ?: throw IllegalStateException("Unable to create download entry")
                        resolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                            writer?.write(payload.toString(2)) ?: throw IllegalStateException("Unable to open output stream")
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        locationHint = "Downloads"
                        localPath = "Downloads/$fileName"
                        statusMessages += "Saved to Downloads/$fileName (public)"
                    } else {
                        val targetDir = when {
                            canUsePublicDir -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            else -> context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        } ?: throw IllegalStateException("No Downloads directory available")

                        if (!targetDir.exists() && !targetDir.mkdirs()) {
                            throw IllegalStateException("Unable to create Downloads directory")
                        }
                        val outFile = File(targetDir, "ai-notes.json")
                        outFile.writeText(payload.toString(2))
                        locationHint = if (canUsePublicDir) "Downloads" else "app Downloads (no permission required)"
                        localPath = outFile.absolutePath
                        statusMessages += "Saved to ${outFile.absolutePath} ($locationHint)"
                    }
                    true
                } catch (e: Exception) {
                    statusMessages += "Local export failed: ${e.message ?: "Unknown error"}"
                    false
                }
            }
        }

        val overallSuccess = (!remoteAttempted || remoteSuccess) && (!settings.exportToLocalDownloads || localSuccess)

        return@withContext ExportResult(
            success = overallSuccess,
            exportedCount = notes.size,
            isEmpty = false,
            message = statusMessages.joinToString(" | ").ifBlank { null },
            localPath = localPath
        )
        } catch (e: Exception) {
            return@withContext ExportResult(
                success = false,
                exportedCount = 0,
                isEmpty = false,
                message = "Export failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    suspend fun testExportEndpoint(targetUrl: String): String = withContext(Dispatchers.IO) {
        val safeUrl = targetUrl.trim()
        if (safeUrl.isEmpty()) {
            return@withContext "URL is empty"
        }
        val normalizedUrl = when {
            safeUrl.startsWith("http://", ignoreCase = true) || safeUrl.startsWith(
                "https://",
                ignoreCase = true
            ) -> safeUrl
            else -> "https://$safeUrl"
        }

        val payload = JSONObject().apply {
            put("ping", "ai-notes-export-test")
            put("timestamp", System.currentTimeMillis())
        }
        val httpUrl = normalizedUrl.toHttpUrlOrNull()
            ?: return@withContext "Invalid URL"
        val request = Request.Builder()
            .url(httpUrl)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
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
    suspend fun continueConversation(note: AiNoteEntity, followUpText: String): String? =
        withContext(Dispatchers.IO) {
            val settings = getSettings()
            if (settings.apiKey.isNotBlank()) {
                 try {
                    val url = getBaseUrl()
                    val isGoogle = isGoogleNative(url)
                    
                    val requestBody: okhttp3.RequestBody
                    val requestBuilder = Request.Builder().url(url)

                    if (isGoogle) {
                        val history = buildOpenAiHistory(note)
                        // Add current user message
                        val userInputWithHint = String.format(settings.safeUserPromptTemplate, followUpText)
                        history.put(JSONObject().apply {
                            put("role", "user")
                            put("content", userInputWithHint)
                        })
                        
                        val googlePayload = transformToGooglePayload(
                            settings.aiModelName, 
                            history, 
                            settings.aiSystemPrompt,
                            settings.temperature,
                            settings.maxTokens,
                            settings.topP,
                            settings.frequencyPenalty,
                            settings.presencePenalty,
                            includeGoogleSearch = settings.enableGoogleSearch
                        )
                        requestBody = googlePayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                         requestBuilder.header("x-goog-api-key", settings.apiKey)

                    } else {
                        // Standard OpenAI
                        val history = buildOpenAiHistory(note)
                        val systemPrompt = settings.aiSystemPrompt
                        
                        val messages = JSONArray()
                        messages.put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        for (i in 0 until history.length()) messages.put(history.get(i))
                        
                        val userInputWithHint = String.format(settings.safeUserPromptTemplate, followUpText)
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", userInputWithHint)
                        })

                        val payload = JSONObject().apply {
                            put("model", settings.aiModelName)
                            put("messages", messages)
                            put("stream", false)
                            put("temperature", settings.temperature)
                            put("max_tokens", settings.maxTokens)
                            put("top_p", settings.topP)
                            if (!isGoogleHost(url)) {
                                put("frequency_penalty", settings.frequencyPenalty)
                                put("presence_penalty", settings.presencePenalty)
                            }
                        }
                        requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
                    }

                    val request = requestBuilder
                        .post(requestBody)
                        .tag(String::class.java, "SKIP_AUTH")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val respBody = response.body?.string()
                             if (respBody != null) {
                                val respJson = JSONObject(respBody)
                                val content = if (isGoogle) {
                                    parseGoogleResponse(respJson)
                                } else {
                                    val choices = respJson.optJSONArray("choices")
                                    choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                                } ?: ""
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
        }

    suspend fun continueConversationStreaming(
        note: AiNoteEntity,
        followUpText: String,
        onPartial: suspend (String) -> Unit
    ): String? {
        val settings = getSettings()
        if (settings.apiKey.isNotBlank()) {
            val url = getBaseUrl() // Direct URL
            val history = buildOpenAiHistory(note)
            
            // System Prompt from Settings
            val systemPrompt = settings.aiSystemPrompt

            val messages = JSONArray()
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            
            // Add history items
            for (i in 0 until history.length()) {
                messages.put(history.get(i))
            }

            // Add current user message with template
            val userInputWithHint = String.format(settings.safeUserPromptTemplate, followUpText)

            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userInputWithHint)
            })

            val payload = JSONObject().apply {
                put("model", settings.aiModelName)
                put("messages", messages)
                put("stream", true)
                put("temperature", settings.temperature)
                put("max_tokens", settings.maxTokens)
                put("top_p", settings.topP)
                if (!isGoogleHost(url)) {
                    put("frequency_penalty", settings.frequencyPenalty)
                    put("presence_penalty", settings.presencePenalty)
                }
            }

            android.util.Log.d(TAG, "continueConversationStreaming (Direct) url=$url")
            
            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

             val requestPayload = if (isGoogle) {
                // History + User Input -> Google 'contents'
                val history = buildOpenAiHistory(note)
                val userInputWithHint = String.format(settings.safeUserPromptTemplate, followUpText)
                history.put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInputWithHint)
                })
                val googlePayload = transformToGooglePayload(
                    settings.aiModelName, 
                    history, 
                    settings.aiSystemPrompt,
                    settings.temperature,
                    settings.maxTokens,
                    settings.topP,
                    settings.frequencyPenalty,
                    settings.presencePenalty,
                    includeGoogleSearch = settings.enableGoogleSearch
                )
                logPayload("continueConversationStreaming_google", googlePayload)
                googlePayload
            } else {
                payload
            }

            return streamJsonPayloadSse(finalUrl, requestPayload, followUpText, onPartial, settings.apiKey)?.second

        } else {
            val payload = JSONObject().apply {
                put("history", buildHistoryArray(note))
                put("text", followUpText)
            }
            val url = getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE_STREAM
            android.util.Log.d(TAG, "continueConversationStreaming url=$url")
            return streamJsonPayloadSse(url, payload, followUpText, onPartial)?.second
        }
    }



    private suspend fun streamJsonPayloadSse(
        url: String,
        payload: JSONObject,
        fallbackText: String,
        onPartial: suspend (String) -> Unit,
        apiKey: String? = null
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val requestBody =
                payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
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
                        android.util.Log.e("AiNoteRepository", "Streaming failed: ${response.code} $errorBody")
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
            
            // Google Native SSE: candidates[0].content.parts[0].text
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val contentParts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")
            val contentFromGoogle = contentParts?.optJSONObject(0)?.optString("text", "") ?: ""
            val finishGoogle = firstCandidate?.optString("finishReason", "")

            val delta = when {
                contentFromDelta.isNotEmpty() -> contentFromDelta
                contentFromGoogle.isNotEmpty() -> contentFromGoogle
                json.has("delta") -> json.optString("delta", "")
                json.has("content") -> json.optString("content", "")
                else -> json.optString("text", "")
            }

            val done = doneFromFlag || 
                       (finish != null && finish != "null" && finish != "unknown") ||
                       (finishGoogle != null && finishGoogle != "null" && finishGoogle != "unknown" && finishGoogle != "STOP") // Google often sends STOP at end
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
                put("parts", JSONArray().put(JSONObject().put("text", String.format(getSettings().safeUserPromptTemplate, note.originalText))))
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

    private fun buildOpenAiHistory(note: AiNoteEntity): JSONArray {
        val history = JSONArray()

        // Base user message
        history.put(
            JSONObject().apply {
                put("role", "user")
                put("content", String.format(getSettings().safeUserPromptTemplate, note.originalText))
            }
        )

        if (note.aiResponse.isBlank()) {
            return history
        }

        // Split existing responses
        val segments = note.aiResponse.split("\n---\n").filter { it.isNotBlank() }
        if (segments.isNotEmpty()) {
            // First segment: original AI answer
            val baseAnswer = segments.first().trim()
            if (baseAnswer.isNotEmpty()) {
                history.put(
                    JSONObject().apply {
                        put("role", getSettings().assistantRole)
                        put("content", baseAnswer)
                    }
                )
            }

            // Subsequent segments
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
                                put("content", question)
                            }
                        )
                    }
                    if (answer.isNotEmpty()) {
                        history.put(
                            JSONObject().apply {
                                put("role", getSettings().assistantRole)
                                put("content", answer)
                            }
                        )
                    }
                } else {
                    history.put(
                        JSONObject().apply {
                            put("role", getSettings().assistantRole)
                            put("content", trimmed)
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
    val message: String? = null,
    val localPath: String? = null
)
