package my.hinoki.booxreader.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiModelFetcher {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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

    suspend fun fetchModelsFromApi(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
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
                val request = Request.Builder().url(fetchUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val modelsArray = json.optJSONArray("models")
                        if (modelsArray != null) {
                            for (i in 0 until modelsArray.length()) {
                                val item = modelsArray.getJSONObject(i)
                                val name = item.optString("name").removePrefix("models/")
                                if (name.isNotBlank()) {
                                    fetchedList.add(name)
                                }
                            }
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

                val reqBuilder = Request.Builder().url(endpoint).get()
                if (key.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $key")
                }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val dataArray = json.optJSONArray("data") ?: json.optJSONArray("models")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.getJSONObject(i)
                                val id = item.optString("id").ifBlank { item.optString("name") }
                                if (id.isNotBlank()) {
                                    fetchedList.add(id)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (fetchedList.isNotEmpty()) {
            fetchedList.distinct()
        } else {
            emptyList()
        }
    }
}
