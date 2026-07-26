package my.hinoki.booxreader.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PocketBaseSseClient(
    private val client: OkHttpClient,
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

    private var eventSource: EventSource? = null
    private var clientId: String? = null

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
        eventSource?.cancel()
        eventSource = null
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

    private fun connect() {
        val sseUrl = "$baseUrl/api/realtime"

        val request = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .build()

        val sseClient = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for SSE
            .build()

        val factory = EventSources.createFactory(sseClient)

        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE Connected to $sseUrl")
                isConnected = true
                retryDelay = 1000L // Reset retry delay on successful connection
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.v(TAG, "SSE Event: type=$type, id=$id")
                handleEvent(type, data)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "SSE Closed")
                isConnected = false
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e(TAG, "SSE Failure: ${t?.message}, code=${response?.code}", t)
                isConnected = false
                t?.let { onError(Exception(it)) } ?: onError(Exception("SSE Failure with code: ${response?.code}"))
            }
        })
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
                Log.d(TAG, "Received realtime event for $collectionName: ${json.optString("action")}")
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

                val url = "$baseUrl/api/realtime"

                // Create payload for subscription
                val payload = JSONObject().apply {
                    put("clientId", clientId)
                    put("subscriptions", org.json.JSONArray().put(collectionName))
                }

                val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Successfully authenticated and subscribed to $collectionName")
                    } else {
                        Log.e(TAG, "Failed to subscribe to $collectionName: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in authenticateAndSubscribe: ${e.message}", e)
            }
        }
    }
}
