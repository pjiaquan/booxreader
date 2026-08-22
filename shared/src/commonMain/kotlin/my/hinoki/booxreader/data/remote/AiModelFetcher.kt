package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 從 AI 服務商 API 抓取可用模型清單。KMP 版本：OkHttp→Ktor、org.json→kotlinx.serialization。
 */
object AiModelFetcher {

    private val client = HttpClient()

    val defaultPresetModels = listOf(
        // Google Gemini
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-pro",
        "gemini-1.5-flash",
        // OpenAI
        "gpt-4o",
        "gpt-4o-mini",
        "o1",
        "o1-mini",
        "o3-mini",
        "gpt-4-turbo",
        // Groq
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "mixtral-8x7b-32768",
        "deepseek-r1-distill-llama-70b",
        // DeepSeek
        "deepseek-chat",
        "deepseek-reasoner",
        // Anthropic
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022"
    )

    suspend fun fetchModelsFromApi(baseUrl: String, apiKey: String): List<String> {
        val fetchedList = mutableListOf<String>()
        val cleanUrl = baseUrl.trim()
        val key = apiKey.trim()

        try {
            // Case 1: Google Gemini Native API
            if (cleanUrl.contains("googleapis.com") || cleanUrl.contains("generativelanguage")) {
                val fetchUrl = if (key.isNotBlank()) {
                    "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                } else {
                    "https://generativelanguage.googleapis.com/v1beta/models"
                }
                val response = client.get(fetchUrl)
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val json = Json.parseToJsonElement(body).jsonObject
                    val modelsArray = json["models"] as? JsonArray
                    modelsArray?.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val name = item.optString("name").removePrefix("models/")
                        if (name.isNotBlank()) {
                            fetchedList.add(name)
                        }
                    }
                }
            } else {
                // Case 2: OpenAI / Groq / DeepSeek / OpenRouter / Ollama Compatible API (/v1/models or /models)
                var endpoint = cleanUrl
                if (endpoint.isNotBlank()) {
                    if (endpoint.contains("/chat/completions")) {
                        endpoint = endpoint.substringBefore("/chat/completions")
                    }
                    if (endpoint.contains("/models")) {
                        endpoint = endpoint.substringBefore("/models")
                    }
                    endpoint = endpoint.trimEnd('/')
                    endpoint = if (endpoint.endsWith("/v1")) "$endpoint/models" else "$endpoint/v1/models"
                } else {
                    endpoint = "https://api.openai.com/v1/models"
                }

                val response =
                    if (key.isNotBlank()) {
                        client.get(endpoint) { header("Authorization", "Bearer $key") }
                    } else {
                        client.get(endpoint)
                    }
                if (response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    val json = Json.parseToJsonElement(body).jsonObject
                    val dataArray = json["data"] as? JsonArray ?: json["models"] as? JsonArray
                    dataArray?.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            fetchedList.add(id)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 與原本行為一致：抓取失敗時回傳空清單
        }

        return if (fetchedList.isNotEmpty()) fetchedList.distinct() else emptyList()
    }

    private fun JsonObject.optString(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
