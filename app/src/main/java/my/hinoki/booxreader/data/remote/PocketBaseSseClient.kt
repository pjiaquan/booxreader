package my.hinoki.booxreader.data.remote

import android.util.Log
import io.ktor.client.plugins.sse.serverSentEventsSession
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.prefs.TokenManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * PocketBase SSE 即時訂閱。Ktor 版本：OkHttp EventSource → serverSentEventsSession，
 * 保留指數退避重連與 PB_CONNECT 訂閱流程。
 */
class PocketBaseSseClient(
    private val tokenManager: TokenManager,
    private val baseUrl: String,
    private val collectionName: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var connectionJob: Job? = null
    private var isConnected = false
    private val TAG = "PocketBaseSseClient"

    private var clientId: String? = null
    private val client = createApiClient()

    // For retries
    private var retryDelay = 1000L
    private val maxRetryDelay = 30000L

    fun start() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            connectWithRetry()
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        isConnected = false
        clientId = null
        scope.cancel()
    }

    private suspend fun connectWithRetry() {
        while (scope.isActive) {
            connect()

            // Wait for disconnect or error
            while (isConnected && scope.isActive) {
                delay(100)
            }

            if (!scope.isActive) break

            // Exponential backoff before retry
            Log.d(TAG, "Reconnecting in $retryDelay ms")
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
        }
    }

    private suspend fun connect() {
        val sseUrl = "$baseUrl/api/realtime"
        try {
            val session =
                    client.serverSentEventsSession(sseUrl) {
                            header("Accept", "text/event-stream")
                            timeout { requestTimeoutMillis = 0; socketTimeoutMillis = 0 }
                    }
            Log.d(TAG, "SSE Connected to $sseUrl")
            isConnected = true
            retryDelay = 1000L // Reset retry delay on successful connection
            session.incoming.collect { event ->
                    Log.v(TAG, "SSE Event: type=${event.event}, id=${event.id}")
                    handleEvent(event.event, event.data ?: "")
            }
            isConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "SSE Failure: ${e.message}", e)
            isConnected = false
            onError(e)
        }
    }

    private fun handleEvent(name: String?, data: String) {
        try {
            if (name == "PB_CONNECT") {
                val json = JSONObject(data)
                clientId = json.getString("clientId")
                Log.d(TAG, "Received PB_CONNECT, clientId: $clientId")

                // Now we authenticate the connection and subscribe to our collection
                authenticateAndSubscribe()
            } else if (name == collectionName) {
                // The event name is the collection name for standard record updates
                val json = JSONObject(data)
                Log.d(
                        TAG,
                        "Received realtime event for $collectionName: ${json.optString("action")}"
                )
                onMessage(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SSE event: ${e.message}", e)
        }
    }

    private fun authenticateAndSubscribe() {
        if (clientId == null) return

        scope.launch {
                try {
                        val token = tokenManager.getAccessToken() ?: return@launch

                        val payload =
                                JSONObject().apply {
                                        put("clientId", clientId)
                                        put("subscriptions", JSONArray().put(collectionName))
                                }

                        val response =
                                client.post("$baseUrl/api/realtime") {
                                        header("Authorization", "Bearer $token")
                                        contentType(ContentType.Application.Json)
                                        setBody(payload.toString())
                                }
                        if (response.status.isSuccess()) {
                                Log.d(TAG, "Successfully authenticated and subscribed to $collectionName")
                        } else {
                                Log.e(TAG, "Failed to subscribe to $collectionName: ${response.status.value}")
                        }
                } catch (e: Exception) {
                        Log.e(TAG, "Error in authenticateAndSubscribe: ${e.message}", e)
                }
        }
    }
}
