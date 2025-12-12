package my.hinoki.booxreader

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.remote.AuthInterceptor
import my.hinoki.booxreader.data.remote.TokenAuthenticator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BooxReaderApp : Application() {

    lateinit var tokenManager: TokenManager
        private set
    
    lateinit var okHttpClient: OkHttpClient
        private set
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var periodicSyncHandler: android.os.Handler? = null
    private var periodicSyncRunnable: Runnable = Runnable { }

    override fun onCreate() {
        super.onCreate()
        tokenManager = TokenManager(this)
        
        // 關鍵：在 App 全局啟動時就強制鎖定文石系統優化引擎
        // 這確保了無論哪個 Activity 啟動，都不會因為意外調用 SDK 而觸發 "自定義 (App Optimized)" 模式
        // 這會強制 "Onyx Navigation Ball" 保持在 "Default" 模式
        my.hinoki.booxreader.core.eink.EInkHelper.setPreserveSystemEngine(true)
        android.util.Log.i("BooxReaderApp", "E-Ink Optimization: Force PRESERVE_SYSTEM_ENGINE = TRUE (Global Init)")
        
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(TokenAuthenticator(tokenManager))
            .build()
        
        // Initialize automatic AI profile sync
        initializeAiProfileSync()
    }
    
    private fun initializeAiProfileSync() {
        applicationScope.launch {
            try {
                val syncRepo = UserSyncRepository(applicationContext)
                val profileRepo = AiProfileRepository(applicationContext, syncRepo)
                
                // Perform initial sync on app startup
                val syncedCount = profileRepo.sync()
                Log.i("BooxReaderApp", "Automatic AI profile sync completed: $syncedCount profiles updated")
                
                // Set up periodic sync (every 30 minutes)
                setupPeriodicSync(profileRepo)
                
            } catch (e: Exception) {
                Log.e("BooxReaderApp", "Automatic AI profile sync failed: ${e.message}", e)
                // Don't crash the app if sync fails - it's not critical
            }
        }
    }
    
    private fun setupPeriodicSync(profileRepo: AiProfileRepository) {
        // Cancel any existing periodic sync
        periodicSyncHandler?.removeCallbacks(periodicSyncRunnable)
        
        // Reset the runnable
        periodicSyncRunnable = Runnable { }
        
        // Create new handler and runnable for periodic sync
        periodicSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
        periodicSyncRunnable = object : Runnable {
            override fun run() {
                applicationScope.launch {
                    try {
                        val syncedCount = profileRepo.sync()
                        Log.i("BooxReaderApp", "Periodic AI profile sync completed: $syncedCount profiles updated")
                    } catch (e: Exception) {
                        Log.e("BooxReaderApp", "Periodic AI profile sync failed: ${e.message}", e)
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
    }
}

