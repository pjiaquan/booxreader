package my.hinoki.booxreader.data.remote

import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Skip auth for requests explicitly tagged
        if (originalRequest.tag(String::class.java) == "SKIP_AUTH") {
            return chain.proceed(originalRequest)
        }

        val backendUrl = tokenManager.getBackendUrl()
        val backendHost = backendUrl.toHttpUrlOrNull()?.host

        // Only attach the token if the request is going to our backend
        if (backendHost == null || originalRequest.url.host != backendHost) {
            return chain.proceed(originalRequest)
        }

        val accessToken = tokenManager.getAccessToken()

        // If token exists and isn't already attached, add it. Otherwise proceed without it (e.g. login/register endpoints)
        val newRequest = if (accessToken != null && originalRequest.header("Authorization") == null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}
