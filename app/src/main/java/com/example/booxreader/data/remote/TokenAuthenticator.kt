package com.example.booxreader.data.remote

import com.example.booxreader.data.prefs.TokenManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(private val tokenManager: TokenManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val refreshToken = tokenManager.getRefreshToken() ?: return null

        synchronized(this) {
            val currentAccessToken = tokenManager.getAccessToken()
            
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            
            // If token changed while we waited, retry with new token
            if (currentAccessToken != null && currentAccessToken != requestToken) {
                 return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccessToken")
                    .build()
            }

            // TODO: Implement actual network call to /refresh endpoint
            // val refreshedToken = api.refreshToken(refreshToken).execute().body()?.token
            val refreshedToken: String? = null 

            if (refreshedToken != null) {
                tokenManager.saveAccessToken(refreshedToken)
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $refreshedToken")
                    .build()
            } else {
                // Refresh failed (e.g. refresh token expired), logout needed
                // tokenManager.clearTokens() // Optional: clear explicitly or let UI handle 401
                return null
            }
        }
    }
}
