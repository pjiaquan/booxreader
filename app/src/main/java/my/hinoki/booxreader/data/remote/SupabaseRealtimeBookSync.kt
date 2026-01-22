package my.hinoki.booxreader.data.remote

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.repo.UserSyncRepository
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SupabaseRealtimeBookSync(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
    private val scope: CoroutineScope
) {
    private val gson = Gson()
    private val syncRepo = UserSyncRepository(context)
    private val shouldReconnect = AtomicBoolean(false)
    private val refCounter = AtomicInteger(1)
    private var currentUserId: String? = null
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var hasShownConnectionError = false

    fun start() {
        if (shouldReconnect.getAndSet(true)) return
        connect()
    }

    fun stop() {
        shouldReconnect.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.close(1000, "client_stop")
        webSocket = null
    }

    private fun connect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            try {
                val token = tokenManager.getAccessToken()?.takeIf { it.isNotBlank() } ?: return@launch
                val userId = syncRepo.getUserId() ?: return@launch
                val wsUrl = buildRealtimeUrl(token) ?: return@launch
                currentUserId = userId

                val request = Request.Builder().url(wsUrl).build()
                okHttpClient.newWebSocket(request, createListener(userId))
            } catch (e: Exception) {
                // Handle network errors gracefully (e.g., no internet connection)
                android.util.Log.e("RealtimeBookSync", "Failed to connect WebSocket", e)

                // Show toast notification on main thread (only once until successful connection)
                if (!hasShownConnectionError) {
                    hasShownConnectionError = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            context,
                            "Network error: Unable to connect to sync service",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                scheduleReconnect()
            }
        }
    }

    private fun buildRealtimeUrl(token: String): HttpUrl? {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return null
        }
        val httpUrl = runCatching { BuildConfig.SUPABASE_URL.trimEnd('/').toHttpUrl() }.getOrNull()
            ?: run {
                return null
            }
        return httpUrl.newBuilder()
            .encodedPath("/realtime/v1/websocket")
            .addQueryParameter("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addQueryParameter("token", token)
            .addQueryParameter("vsn", "1.0.0")
            .build()
    }

    private fun createListener(userId: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@SupabaseRealtimeBookSync.webSocket = webSocket
            hasShownConnectionError = false // Reset error flag on successful connection
            sendJoin(webSocket, userId)
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun sendJoin(webSocket: WebSocket, userId: String) {
        val joinPayload =
            mapOf(
                "topic" to "realtime:public:books",
                "event" to "phx_join",
                "payload" to
                    mapOf(
                        "config" to
                            mapOf(
                                "broadcast" to mapOf("ack" to false),
                                "presence" to mapOf("key" to ""),
                                "postgres_changes" to
                                    listOf(
                                        mapOf(
                                            "event" to "*",
                                            "schema" to "public",
                                            "table" to "books",
                                            "filter" to "user_id=eq.$userId"
                                        )
                                    )
                            )
                    ),
                "ref" to nextRef()
            )
        webSocket.send(gson.toJson(joinPayload))
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(25_000)
                    val heartbeat =
                        mapOf(
                            "topic" to "phoenix",
                            "event" to "heartbeat",
                            "payload" to emptyMap<String, String>(),
                            "ref" to nextRef()
                        )
                    webSocket?.send(gson.toJson(heartbeat))
                }
            }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch(Dispatchers.IO) {
                delay(3_000)
                connect()
            }
    }

    private fun handleMessage(text: String) {
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        val event = root.stringOrNull("event") ?: return
        if (event != "postgres_changes") return
        val payload = root.get("payload").asJsonObjectOrNull() ?: return
        val eventType = payload.stringOrNull("type") ?: payload.stringOrNull("eventType") ?: return
        val record = payload.get("record").asJsonObjectOrNull() ?: payload.get("new").asJsonObjectOrNull()
        val oldRecord = payload.get("old_record").asJsonObjectOrNull() ?: payload.get("old").asJsonObjectOrNull()

        val userId = record.stringOrNull("user_id") ?: oldRecord.stringOrNull("user_id")
        if (!currentUserId.isNullOrBlank() && !userId.isNullOrBlank() && userId != currentUserId) {
            return
        }

        val bookId =
            record.stringOrNull("book_id_local")
                ?: record.stringOrNull("book_id")
                ?: oldRecord.stringOrNull("book_id_local")
                ?: oldRecord.stringOrNull("book_id")
        if (bookId.isNullOrBlank()) return

        val isDeleted = record?.get("is_deleted")?.asBoolean ?: false

        if (eventType == "UPDATE" && isDeleted) {
            deleteLocalBook(bookId)
        } else if (eventType == "DELETE") {
            deleteLocalBook(bookId)
        } else if (eventType == "INSERT" || eventType == "UPDATE") {
            fetchRemoteBook(bookId)
        }
    }

    private fun deleteLocalBook(bookId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { AppDatabase.get(context).bookDao().deleteById(bookId) }
        }
    }

    private fun fetchRemoteBook(bookId: String) {
        scope.launch(Dispatchers.IO) {
            syncRepo.pullBook(bookId)
        }
    }

    private fun nextRef(): String = refCounter.getAndIncrement().toString()
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? =
    if (this != null && this.isJsonObject) this.asJsonObject else null

private fun JsonObject?.stringOrNull(key: String): String? {
    if (this == null || !this.has(key)) return null
    val value = this.get(key) ?: return null
    if (value.isJsonNull) return null
    return runCatching { value.asString }.getOrNull()
}
