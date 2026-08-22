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
import my.hinoki.booxreader.data.remote.PocketBaseRealtimeClient
import my.hinoki.booxreader.data.remote.TokenAuthenticator
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.worker.DailySummaryEmailScheduler
import okhttp3.OkHttpClient
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

    private var realtimeClient: PocketBaseRealtimeClient? = null

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

        val isRobolectric = android.os.Build.FINGERPRINT == "robolectric" || android.os.Build.HARDWARE == "robolectric"
        if (!isRobolectric) {
            // Initialize automatic AI profile sync
            initializeAiProfileSync()

            // Background check: upload any local books whose file is missing on the server.
            // This ensures books added on this device are always available for other devices to sync.
            initializeBackgroundBookUpload()

            // Upload any pending crash reports
            uploadPendingCrashReports()

            startRealtimeBookSync()
        }
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

    private var realtimeBookSyncClient: my.hinoki.booxreader.data.remote.PocketBaseSseClient? = null

    // Reimplemented with PocketBase realtime (SSE-based)
    fun startRealtimeBookSync() {
        if (realtimeBookSyncClient != null) return

        android.util.Log.d("BooxReaderApp", "startRealtimeBookSync - Starting realtime sync for books")

        val syncRepo = my.hinoki.booxreader.data.repo.UserSyncRepository(applicationContext)
        val baseUrl = tokenManager.getBackendUrl()

        realtimeBookSyncClient = my.hinoki.booxreader.data.remote.PocketBaseSseClient(
            client = okHttpClient,
            tokenManager = tokenManager,
            baseUrl = baseUrl,
            collectionName = "books",
            onMessage = { json ->
                applicationScope.launch {
                    try {
                        val action = json.optString("action")
                        val record = json.optJSONObject("record")
                        if (action.isNotEmpty() && record != null) {
                            android.util.Log.d("BooxReaderApp", "Realtime book sync action: $action")
                            syncRepo.pullBooks()
                            syncRepo.downloadPendingRemoteBooks()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("BooxReaderApp", "Error in realtime book sync", e)
                    }
                }
            },
            onError = { e ->
                android.util.Log.e("BooxReaderApp", "Realtime book sync error", e)
            }
        )
        realtimeBookSyncClient?.start()
    }

    fun stopRealtimeBookSync() {
        android.util.Log.d("BooxReaderApp", "stopRealtimeBookSync - Stopping realtime sync for books")
        realtimeBookSyncClient?.stop()
        realtimeBookSyncClient = null

    }

    private fun uploadPendingCrashReports() {
        ErrorReporter.flushPending(applicationContext)
    }
}
