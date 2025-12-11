package my.hinoki.booxreader

import android.app.Application
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.remote.AuthInterceptor
import my.hinoki.booxreader.data.remote.TokenAuthenticator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BooxReaderApp : Application() {

    lateinit var tokenManager: TokenManager
        private set
    
    lateinit var okHttpClient: OkHttpClient
        private set

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
    }
}

