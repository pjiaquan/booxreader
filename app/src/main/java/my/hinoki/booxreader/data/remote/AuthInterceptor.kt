package my.hinoki.booxreader.data.remote

import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Skip auth for requests explicitly tagged
        if (originalRequest.tag(String::class.java) == "SKIP_AUTH") {
            return chain.proceed(originalRequest)
        }

        val backendUrl = tokenManager.getBackendUrl()
        val expectedHost = backendUrl.toHttpUrlOrNull()?.host

        val accessToken = tokenManager.getAccessToken()
        val isBackendHost = expectedHost != null && originalRequest.url.host == expectedHost

        // If token exists, add it. Otherwise proceed without it (e.g. login/register endpoints)
        val newRequest = if (accessToken != null && isBackendHost) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}

