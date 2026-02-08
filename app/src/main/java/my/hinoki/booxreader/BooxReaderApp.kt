package my.hinoki.booxreader

import android.app.Application
import android.content.Context
import android.widget.Toast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.core.CrashReportHandler
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.remote.AuthInterceptor
import my.hinoki.booxreader.data.remote.TokenAuthenticator
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import okhttp3.OkHttpClient

class BooxReaderApp : Application() {

    lateinit var tokenManager: TokenManager
        private set

    lateinit var okHttpClient: OkHttpClient
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicSyncHandler: android.os.Handler? = null
    private var periodicSyncRunnable: Runnable = Runnable {}

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

        // Initialize automatic AI profile sync
        initializeAiProfileSync()

        // Upload any pending crash reports
        uploadPendingCrashReports()

        // TODO: Implement PocketBase realtime sync (uses SSE instead of WebSocket)
        // startRealtimeBookSync()
    }

    private fun initializeAiProfileSync() {
        applicationScope.launch {
            try {
                val syncRepo = UserSyncRepository(applicationContext)
                val profileRepo = AiProfileRepository(applicationContext, syncRepo)

                // Ensure default profile exists
                val profileCreated = profileRepo.ensureDefaultProfile()

                // Show notification if default profile was created
                if (profileCreated) {
                    Toast.makeText(
                                    applicationContext,
                                    R.string.ai_profile_default_created,
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }

                // Perform initial sync on app startup
                val syncedCount = profileRepo.sync()

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
                                val syncedCount = profileRepo.sync()
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

    // TODO: Reimplement with PocketBase realtime (SSE-based)
    fun startRealtimeBookSync() {
        // Stub: PocketBase realtime sync not yet implemented
        android.util.Log.d(
                "BooxReaderApp",
                "startRealtimeBookSync - STUB: Not implemented for PocketBase yet"
        )
    }

    fun stopRealtimeBookSync() {
        // Stub: PocketBase realtime sync not yet implemented
        android.util.Log.d(
                "BooxReaderApp",
                "stopRealtimeBookSync - STUB: Not implemented for PocketBase yet"
        )
    }

    private fun uploadPendingCrashReports() {
        ErrorReporter.flushPending(applicationContext)
    }
}
