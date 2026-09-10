package my.hinoki.booxreader.data.repo

import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.platform.ioDispatcher
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings

/**
 * AI 對話 / 完成（自 `AiNoteRepository` 抽出的 chat 叢集，約 820 行）。
 *
 * 涵蓋 OpenAI 相容與 Google 原生兩種後端的請求轉換、一般回覆與串流回覆
 * （`fetchAiExplanation*` / `continueConversation*`），以及串流 SSE 的解析。
 *
 * `AiNoteRepository` 保留同名 delegate，因此呼叫端不變；共用的 helper
 * （`getSettings` / `loadExtraParams` / `buildMessages` 等）留在 host。
 */

internal class AiChatClient(private val host: AiNoteRepository) {

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

    suspend fun fetchAiExplanation(
            text: String,
            magicTag: MagicTag? = null,
            settingsOverride: ReaderSettings? = null
    ): Pair<String, String>? {
        val settings = settingsOverride ?: host.getSettings()
        if (settings.apiKey.isNotBlank()) {
            return withContext(ioDispatcher) {
                try {
                    val url = host.getBaseUrl()
                    val isGoogle = isGoogleNative(url)
                    val extraParams = host.loadExtraParams()
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
                        val merged = host.applyExtraParams(googlePayload, extraParams)
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
                        val merged = host.applyExtraParams(payload, extraParams)
                        requestBody = merged.withField("stream", JsonPrimitive(false)).toString()
                    }

                    host.logger.d(host.TAG, "Fetching AI Explanation from: $url")

                    val response =
                            host.ktorClient.post(url) {
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
                            host.logger.e(
                                    host.TAG,
                                    "AI Request Failed: Code=${response.status.value}"
                            )
                            null
                    }
                } catch (e: Exception) {
                    host.logger.e(host.TAG, "Exception in fetchAiExplanation", e)
                    null
                }
            }
        }

        // Legacy Implementation
        return withContext(ioDispatcher) {
            try {
                val jsonBody = jsonObj { put("text", text) }.toString()

                val url = host.getBaseUrl() + HttpConfig.PATH_TEXT_AI
                val response =
                        host.ktorClient.post(url) {
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
                host.logger.e(host.TAG, "fetchAiExplanation legacy failed", e)
                null
            }
        }
    }

    suspend fun fetchAiExplanationStreaming(
            text: String,
            magicTag: MagicTag? = null,
            onPartial: suspend (String) -> Unit
    ): Pair<String, String>? {
        val settings = host.getSettings()
        if (settings.apiKey.isNotBlank()) {
            // Direct DeepSeek API call
            val url = host.getBaseUrl() // Use base URL directly without appending path
            val extraParams = host.loadExtraParams()
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
            val merged = host.applyExtraParams(payload, extraParams)
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
                        host.applyExtraParams(googlePayload, extraParams)
                    } else {
                        payloadFinal
                    }

            return streamJsonPayloadSse(finalUrl, requestPayload, text, onPartial, settings.apiKey)
        } else {
            // Legacy Mode
            val payload = jsonObj { put("text", text) }
            val url = host.getBaseUrl() + HttpConfig.PATH_TEXT_AI_STREAM
            return streamJsonPayloadSse(url, payload, text, onPartial, null)
        }
    }

    suspend fun continueConversation(
            note: AiNoteEntity,
            followUpText: String,
            magicTag: MagicTag? = null
    ): String? =
            withContext(ioDispatcher) {
                val settings = host.getSettings()
                if (settings.apiKey.isNotBlank()) {
                    try {
                        val url = host.getBaseUrl()
                        val isGoogle = isGoogleNative(url)
                        val extraParams = host.loadExtraParams()
                        val systemPrompt = resolveSystemPrompt(settings, magicTag)

                        val requestBody: String

                        if (isGoogle) {
                            val history = host.buildMessages(note)
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
                                    host.applyExtraParams(googlePayload, extraParams)
                                            .toString()
                            
                        } else {
                            // Standard OpenAI
                            val history = host.buildMessages(note)

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
                                    host.applyExtraParams(payload, extraParams)
                                            .withField("stream", JsonPrimitive(false))
                                            .toString()
                        }

                        val response =
                                host.ktorClient.post(url) {
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
                        host.logger.e(host.TAG, "continueConversation failed", e)
                        null
                    }
                } else {
                    // Legacy
                    try {
                        val payload =
                                jsonObj {
                                    put("history", host.buildMessages(note))
                                    put("text", followUpText)
                                }

                        val url = host.getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE
                        val response =
                                host.ktorClient.post(url) {
                                        contentType(ContentType.Application.Json)
                                        setBody(payload.toString())
                                }
                        if (!response.status.isSuccess()) return@withContext null
                        response.bodyAsText().let { body ->
                                parseJsonObject(body)?.optString("content", "")
                                        ?.takeIf { it.isNotEmpty() }
                        }
                    } catch (e: Exception) {
                        host.logger.e(host.TAG, "continueConversation legacy failed", e)
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
        val settings = host.getSettings()
        if (settings.apiKey.isNotBlank()) {
            val url = host.getBaseUrl() // Direct URL
            val extraParams = host.loadExtraParams()
            val history = host.buildMessages(note)

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
            val merged = host.applyExtraParams(payload, extraParams)
            val payloadFinal = merged.withField("stream", JsonPrimitive(true))

            val isGoogle = isGoogleNative(url)
            val finalUrl = if (isGoogle) googleStreamUrl(url) else url

            val requestPayload =
                    if (isGoogle) {
                        // History + User Input -> Google 'contents'
                        val historyGoogle = host.buildMessages(note)
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
                        host.applyExtraParams(googlePayload, extraParams)
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
                        put("history", host.buildMessages(note))
                        put("text", followUpText)
                    }
            val url = host.getBaseUrl() + HttpConfig.PATH_TEXT_AI_CONTINUE_STREAM
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
                    host.logger.d(host.TAG, "Streaming SSE from: $url")

                    host.lastStreamingError = null
                    val response =
                            host.ktorClient.preparePost(url) {
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
                            host.lastStreamingError =
                                    my.hinoki.booxreader.data.remote.StreamingErrorHandler.parseError(
                                            response.status.value,
                                            errorBody
                                    )
                            host.logger.e(
                                    host.TAG,
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
                    host.lastStreamingError = my.hinoki.booxreader.data.remote.StreamingErrorHandler.parseError(0, e.message)
                    host.logger.e(host.TAG, "Streaming SSE failed: ${e.message}", e)
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
}

internal data class StreamingChunk(
            val serverText: String?,
            val delta: String,
            val done: Boolean
    )
