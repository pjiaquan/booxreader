package my.hinoki.booxreader

import android.app.Application
import android.content.Context
import android.widget.Toast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.core.CrashReportHandler
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.remote.AuthInterceptor
import my.hinoki.booxreader.data.remote.TokenAuthenticator
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.worker.DailySummaryEmailScheduler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

class BooxReaderApp : Application() {

    lateinit var tokenManager: TokenManager
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicSyncHandler: android.os.Handler? = null
    private var periodicSyncRunnable: Runnable = Runnable {}
    private var realtimeEventSource: EventSource? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(my.hinoki.booxreader.ui.common.LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()

        // Install crash handler first (before any other initialization)
        CrashReportHandler.install(this)

        tokenManager = TokenManager(this)

        okHttpClient =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .addInterceptor(AuthInterceptor(tokenManager))
                        .authenticator(TokenAuthenticator(tokenManager))
                        .build()

        DailySummaryEmailScheduler.schedule(
                this,
                ReaderSettings.fromPrefs(
                        getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
                )
        )

        // Initialize automatic AI profile sync
        initializeAiProfileSync()

        // Background check: upload any local books whose file is missing on the server.
        // This ensures books added on this device are always available for other devices to sync.
        initializeBackgroundBookUpload()

        // Upload any pending crash reports
        uploadPendingCrashReports()

        // Start realtime sync (uses SSE)
        startRealtimeBookSync()
    }

    private fun initializeAiProfileSync() {
        applicationScope.launch {
            try {
                val syncRepo = UserSyncRepository(applicationContext)
                val profileRepo = AiProfileRepository(applicationContext, syncRepo)

                // Perform initial sync on app startup
                profileRepo.sync()

                // Ensure default profile only after initial pull, to avoid creating
                // duplicate same-name profiles on a newly signed-in device.
                val profileCreated = profileRepo.ensureDefaultProfile()
                if (profileCreated) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                                        applicationContext,
                                        R.string.ai_profile_default_created,
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                }

                // Set up periodic sync (every 30 minutes)
                setupPeriodicSync(profileRepo)
            } catch (e: Exception) {
                // Don't crash the app if sync fails - it's not critical
                ErrorReporter.report(
                        applicationContext,
                        "BooxReaderApp.initializeAiProfileSync",
                        "Initial AI profile sync failed",
                        e
                )
            }
        }
    }

    private fun initializeBackgroundBookUpload() {
        applicationScope.launch {
            try {
                val syncRepo = UserSyncRepository(applicationContext)
                val uploaded = syncRepo.ensureAllLocalBooksUploaded()
                if (uploaded > 0) {
                    android.util.Log.i(
                        "BooxReaderApp",
                        "initializeBackgroundBookUpload - uploaded $uploaded books to server"
                    )
                }
            } catch (e: Exception) {
                ErrorReporter.report(
                    applicationContext,
                    "BooxReaderApp.initializeBackgroundBookUpload",
                    "Background book upload check failed",
                    e
                )
            }
        }
    }

    private fun setupPeriodicSync(profileRepo: AiProfileRepository) {
        // Cancel any existing periodic sync
        periodicSyncHandler?.removeCallbacks(periodicSyncRunnable)

        // Reset the runnable
        periodicSyncRunnable = Runnable {}

        // Create new handler and runnable for periodic sync
        periodicSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
        periodicSyncRunnable =
                object : Runnable {
                    override fun run() {
                        applicationScope.launch {
                            try {
                                profileRepo.sync()
                            } catch (e: Exception) {
                                ErrorReporter.report(
                                        applicationContext,
                                        "BooxReaderApp.periodicProfileSync",
                                        "Periodic AI profile sync failed",
                                        e
                                )
                            }
                        }

                        // Schedule next sync in 30 minutes
                        periodicSyncHandler?.postDelayed(this, 30 * 60 * 1000)
                    }
                }

        // Start the periodic sync
        periodicSyncHandler?.postDelayed(periodicSyncRunnable, 30 * 60 * 1000)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up periodic sync when app terminates
        periodicSyncHandler?.removeCallbacks(periodicSyncRunnable)
        periodicSyncHandler = null
        stopRealtimeBookSync()
    }

    fun startRealtimeBookSync() {
        stopRealtimeBookSync()

        if (tokenManager.getAccessToken() == null) {
            android.util.Log.d("BooxReaderApp", "startRealtimeBookSync: Not logged in")
            return
        }

        val baseUrl = BuildConfig.POCKETBASE_URL.trimEnd('/')
        val request = Request.Builder()
            .url("$baseUrl/api/realtime")
            .build()

        val factory = EventSources.createFactory(okHttpClient)
        realtimeEventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                android.util.Log.d("BooxReaderApp", "Realtime event: type=$type data=$data")
                if (type == "PB_CONNECT") {
                    try {
                        val json = JSONObject(data)
                        val clientId = json.optString("clientId")
                        if (clientId.isNotEmpty()) {
                            // Subscribe to books and progress
                            val payload = JSONObject().apply {
                                put("clientId", clientId)
                                put("subscriptions", org.json.JSONArray().apply {
                                    put("books")
                                    put("progress")
                                })
                            }

                            val postRequest = Request.Builder()
                                .url("$baseUrl/api/realtime")
                                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                                .build()

                            // Make the POST request to set subscriptions
                            applicationScope.launch(Dispatchers.IO) {
                                try {
                                    okHttpClient.newCall(postRequest).execute().use { response ->
                                        if (!response.isSuccessful) {
                                            android.util.Log.e("BooxReaderApp", "Failed to set subscriptions: ${response.code}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("BooxReaderApp", "Error setting subscriptions", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BooxReaderApp", "Failed to parse PB_CONNECT", e)
                    }
                } else if (!type.isNullOrEmpty() && type != "PB_CONNECT") {
                    // Trigger sync based on collection update
                    applicationScope.launch(Dispatchers.IO) {
                        try {
                            val syncRepo = UserSyncRepository(applicationContext)
                            when (type) {
                                "books" -> syncRepo.pullBooks()
                                "progress" -> syncRepo.pullAllProgress()
                                else -> {
                                    syncRepo.pullBooks()
                                    syncRepo.pullAllProgress()
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BooxReaderApp", "Error handling realtime event", e)
                        }
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                android.util.Log.d("BooxReaderApp", "Realtime connection closed")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                android.util.Log.e("BooxReaderApp", "Realtime connection failed", t)
                // Optional: add retry logic if needed
            }
        })
    }

    fun stopRealtimeBookSync() {
        realtimeEventSource?.cancel()
        realtimeEventSource = null
        android.util.Log.d("BooxReaderApp", "Realtime connection stopped")
    }

    private fun uploadPendingCrashReports() {
        ErrorReporter.flushPending(applicationContext)
    }
}
