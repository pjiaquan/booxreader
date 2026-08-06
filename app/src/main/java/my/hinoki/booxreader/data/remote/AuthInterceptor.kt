package my.hinoki.booxreader.data.remote

import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Skip auth for requests explicitly tagged
        if (originalRequest.tag(String::class.java) == "SKIP_AUTH") {
            return chain.proceed(originalRequest)
        }

        val accessToken = tokenManager.getAccessToken()

        // SECURITY: Only send token to our own backend to prevent token leakage
        // to third-party domains (e.g. when downloading external book covers)
        val backendUrlStr = tokenManager.getBackendUrl()
        val isBackendHost = try {
            val backendHost = java.net.URL(backendUrlStr).host
            originalRequest.url.host == backendHost
        } catch (e: Exception) {
            // If URL parsing fails, default to safe behavior
            false
        }

        // If token exists and request is to backend, add it. Otherwise proceed without it.
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

