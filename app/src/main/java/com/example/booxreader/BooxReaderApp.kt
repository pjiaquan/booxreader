package com.example.booxreader

import android.app.Application
import com.example.booxreader.data.prefs.TokenManager
import com.example.booxreader.data.remote.AuthInterceptor
import com.example.booxreader.data.remote.TokenAuthenticator
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
        
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(TokenAuthenticator(tokenManager))
            .build()
    }
}
