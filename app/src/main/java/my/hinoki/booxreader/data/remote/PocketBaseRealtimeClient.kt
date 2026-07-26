package my.hinoki.booxreader.data.remote

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

class PocketBaseRealtimeClient(
    private val pocketBaseUrl: String,
    private val tokenManager: TokenManager,
    private val client: OkHttpClient,
    private val coroutineScope: CoroutineScope
) {
    private var eventSource: EventSource? = null
    private var clientId: String? = null
    private var isStopped = false
    var onBookChanged: (() -> Unit)? = null

    fun start() {
        stop()
        isStopped = false

        val url = "$pocketBaseUrl/api/realtime"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        val sseClient = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        eventSource = EventSources.createFactory(sseClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d("PocketBaseRealtime", "SSE Connection opened")
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.d("PocketBaseRealtime", "SSE Event: type=$type, data=$data")
                    if (type == "PB_CONNECT") {
                        try {
                            val json = JSONObject(data)
                            clientId = json.getString("clientId")
                            Log.d("PocketBaseRealtime", "Got clientId: $clientId. Subscribing...")
                            submitSubscriptions()
                        } catch (e: Exception) {
                            Log.e("PocketBaseRealtime", "Failed to parse PB_CONNECT", e)
                        }
                    } else if (data.contains("\"collectionName\":\"books\"")) {
                        Log.d("PocketBaseRealtime", "Book collection changed!")
                        onBookChanged?.invoke()
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.d("PocketBaseRealtime", "SSE Connection closed")
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.e("PocketBaseRealtime", "SSE Connection failed", t)
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
            })
    }

    private fun submitSubscriptions() {
        val cid = clientId ?: return
        val token = tokenManager.getAccessToken() ?: return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("clientId", cid)
                    put("subscriptions", org.json.JSONArray().put("books"))
                }

                val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url("$pocketBaseUrl/api/realtime")
                    .post(requestBody)
                    .header("Authorization", token)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("PocketBaseRealtime", "Failed to subscribe: ${response.code}")
                    } else {
                        Log.d("PocketBaseRealtime", "Successfully subscribed to realtime events")
                    }
                }
            } catch (e: Exception) {
                Log.e("PocketBaseRealtime", "Exception during subscription", e)
            }
        }
    }

    fun stop() {
        isStopped = true
        eventSource?.cancel()
        eventSource = null
        clientId = null
    }
}
