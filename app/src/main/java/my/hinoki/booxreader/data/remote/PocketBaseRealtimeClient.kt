package my.hinoki.booxreader.data.remote

import android.util.Log
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.prefs.TokenManager

/**
 * PocketBase 即時同步（SSE）。Ktor 版本：OkHttp EventSource → serverSentEventsSession。
 * 連線失敗時 5 秒後自動重連（與原本 onFailure 行為一致）。
 */
class PocketBaseRealtimeClient(
    private val pocketBaseUrl: String,
    private val tokenManager: TokenManager,
    private val coroutineScope: CoroutineScope
) {
    private var connectionJob: Job? = null
    private var clientId: String? = null
    private var isStopped = false
    var onBookChanged: (() -> Unit)? = null
    private val client = createApiClient()

    fun start() {
        stop()
        isStopped = false

        connectionJob =
                coroutineScope.launch {
                        try {
                                val session =
                                        client.serverSentEventsSession("$pocketBaseUrl/api/realtime") {
                                                header("Accept", "text/event-stream")
                                        }
                                Log.d("PocketBaseRealtime", "SSE Connection opened")
                                session.incoming.collect { event ->
                                        val type = event.event
                                        val data = event.data ?: ""
                                        Log.d(
                                                "PocketBaseRealtime",
                                                "SSE Event: type=$type, data=$data"
                                        )
                                        if (type == "PB_CONNECT") {
                                                try {
                                                        val json =
                                                                Json.parseToJsonElement(data)
                                                                        .jsonObject
                                                        clientId =
                                                                json["clientId"]
                                                                        ?.jsonPrimitive
                                                                        ?.content
                                                        Log.d(
                                                                "PocketBaseRealtime",
                                                                "Got clientId: $clientId. Subscribing..."
                                                        )
                                                        submitSubscriptions()
                                                } catch (e: Exception) {
                                                        Log.e(
                                                                "PocketBaseRealtime",
                                                                "Failed to parse PB_CONNECT",
                                                                e
                                                        )
                                                }
                                        } else if (data.contains("\"collectionName\":\"books\"")) {
                                                Log.d("PocketBaseRealtime", "Book collection changed!")
                                                onBookChanged?.invoke()
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e("PocketBaseRealtime", "SSE Connection failed", e)
                                // Implement basic reconnect logic
                                if (!isStopped) {
                                        coroutineScope.launch {
                                                delay(5000)
                                                if (!isStopped) {
                                                        start()
                                                }
                                        }
                                }
                        }
                }
    }

    private fun submitSubscriptions() {
        val cid = clientId ?: return
        val token = tokenManager.getAccessToken() ?: return

        coroutineScope.launch {
                try {
                        val payload =
                                buildJsonObject {
                                        put("clientId", cid)
                                        put("subscriptions", buildJsonArray { add("books") })
                                }

                        val response =
                                client.post("$pocketBaseUrl/api/realtime") {
                                        header("Authorization", "Bearer $token")
                                        contentType(ContentType.Application.Json)
                                        setBody(payload.toString())
                                }
                        if (!response.status.isSuccess()) {
                                Log.e(
                                        "PocketBaseRealtime",
                                        "Failed to subscribe: ${response.status.value}"
                                )
                        } else {
                                Log.d(
                                        "PocketBaseRealtime",
                                        "Successfully subscribed to realtime events"
                                )
                        }
                } catch (e: Exception) {
                        Log.e("PocketBaseRealtime", "Exception during subscription", e)
                }
        }
    }

    fun stop() {
        isStopped = true
        connectionJob?.cancel()
        connectionJob = null
        clientId = null
    }
}
