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

        val backendUrl = tokenManager.getBackendUrl().toHttpUrlOrNull()
        val isBackendRequest = originalRequest.url.host == backendUrl?.host

        val accessToken = tokenManager.getAccessToken()

        // If token exists and request is to backend, add it. Otherwise proceed without it (e.g. login/register or third-party APIs)
        val newRequest = if (accessToken != null && isBackendRequest) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}
