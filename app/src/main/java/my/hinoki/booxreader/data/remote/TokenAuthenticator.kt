package my.hinoki.booxreader.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

class TokenAuthenticator(private val tokenManager: TokenManager) : Authenticator {

    private val gson = Gson()
    private val authClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY

    override fun authenticate(route: Route?, response: Response): Request? {
        // Skip auth for requests explicitly tagged
        if (response.request.tag(String::class.java) == "SKIP_AUTH") {
            return null
        }
        if (responseCount(response) >= 2) {
            return null
        }
        synchronized(this) {
            val currentAccessToken = tokenManager.getAccessToken()
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentAccessToken != null && currentAccessToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccessToken")
                    .build()
            }

            val refreshToken = tokenManager.getRefreshToken() ?: return null
            val refreshed = runCatching { refreshSession(refreshToken) }.getOrNull()

            return if (refreshed != null && !refreshed.accessToken.isNullOrBlank()) {
                tokenManager.saveAccessToken(refreshed.accessToken)
                if (!refreshed.refreshToken.isNullOrBlank()) {
                    tokenManager.saveRefreshToken(refreshed.refreshToken)
                }
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${refreshed.accessToken}")
                    .build()
            } else {
                null
            }
        }
    }

    private fun refreshSession(refreshToken: String): SupabaseSessionTokens? {
        if (supabaseAnonKey.isBlank()) {
            return null
        }

        val body = gson.toJson(mapOf("refresh_token" to refreshToken))
        val request = Request.Builder()
            .url("$supabaseUrl/auth/v1/token?grant_type=refresh_token")
            .tag(String::class.java, "SKIP_AUTH")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $supabaseAnonKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        authClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val responseBody = response.body?.string().orEmpty()
            return gson.fromJson(responseBody, SupabaseSessionTokens::class.java)
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            result++
            priorResponse = priorResponse.priorResponse
        }
        return result
    }
}

private data class SupabaseSessionTokens(
    @SerializedName("access_token")
    val accessToken: String? = null,
    @SerializedName("refresh_token")
    val refreshToken: String? = null
)
