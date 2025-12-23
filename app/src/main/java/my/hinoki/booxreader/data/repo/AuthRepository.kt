package my.hinoki.booxreader.data.repo

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.UserEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AuthRepository(
    private val context: Context,
    private val tokenManager: TokenManager
) {
    private val userDao = AppDatabase.get(context).userDao()
    private val gson = Gson()
    private val authClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY

    suspend fun login(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val response = postJson(
                url = "$supabaseUrl/auth/v1/token?grant_type=password",
                body = mapOf("email" to email, "password" to password),
            )
            val authPayload = parseAuthResponse(response)
            cacheUser(authPayload, fallbackEmail = email)
        }
    }

    suspend fun register(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val response = postJson(
                url = "$supabaseUrl/auth/v1/signup",
                body = mapOf("email" to email, "password" to password),
            )
            val signupPayload = parseSignupResponse(response, fallbackEmail = email)
            userDao.clearAllUsers()
            tokenManager.clearTokens()
            // Return lightweight entity for UI messaging; not cached until verification + login.
            UserEntity(
                userId = signupPayload.userId,
                email = signupPayload.email,
                displayName = signupPayload.displayName,
                avatarUrl = signupPayload.avatarUrl
            )
        }
    }

    suspend fun googleLogin(idToken: String, email: String?, name: String?): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val response = postJson(
                url = "$supabaseUrl/auth/v1/token?grant_type=id_token",
                body = mapOf("provider" to "google", "id_token" to idToken),
            )
            val authPayload = parseAuthResponse(response)
            cacheUser(authPayload, fallbackEmail = email, fallbackName = name)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        userDao.clearAllUsers()
        tokenManager.clearTokens()
    }

    suspend fun resendVerification(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            postJson(
                url = "$supabaseUrl/auth/v1/resend?type=signup",
                body = mapOf("email" to email),
            )
            userDao.clearAllUsers()
            tokenManager.clearTokens()
        }
    }

    fun getCurrentUser() = userDao.getUser()

    private suspend fun cacheUser(
        payload: SupabaseAuthPayload,
        fallbackEmail: String? = null,
        fallbackName: String? = null
    ): UserEntity {
        val email = payload.user.email ?: fallbackEmail ?: error("Email not available")
        val displayName = payload.displayName ?: fallbackName ?: email.substringBefore("@")
        val accessToken = payload.accessToken ?: error("Unable to fetch access token")
        val refreshToken = payload.refreshToken ?: error("Unable to fetch refresh token")

        val entity = UserEntity(
            userId = payload.user.id,
            email = email,
            displayName = displayName,
            avatarUrl = payload.avatarUrl
        )

        userDao.clearAllUsers()
        userDao.insertUser(entity)

        tokenManager.clearTokens()
        tokenManager.saveAccessToken(accessToken)
        tokenManager.saveRefreshToken(refreshToken)

        return entity
    }

    private fun postJson(url: String, body: Map<String, String>): String {
        if (supabaseAnonKey.isBlank()) {
            error("Supabase anon key is missing. Set SUPABASE_ANON_KEY in gradle properties.")
        }

        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .tag(String::class.java, "SKIP_AUTH")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $supabaseAnonKey")
            .post(requestBody)
            .build()

        authClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = parseErrorMessage(responseBody) ?: response.message
                error(message)
            }
            return responseBody
        }
    }

    private fun parseAuthResponse(responseBody: String): SupabaseAuthPayload {
        val payload = gson.fromJson(responseBody, SupabaseAuthResponse::class.java)
        val session = payload.session
        val user = session?.user ?: payload.user ?: error(context.getString(my.hinoki.booxreader.R.string.error_user_not_found))
        val accessToken = payload.accessToken ?: session?.accessToken
        val refreshToken = payload.refreshToken ?: session?.refreshToken

        return SupabaseAuthPayload(
            user = user,
            accessToken = accessToken,
            refreshToken = refreshToken,
            displayName = user.userMetadata?.fullName ?: user.userMetadata?.name,
            avatarUrl = user.userMetadata?.avatarUrl
        )
    }

    private fun parseSignupResponse(responseBody: String, fallbackEmail: String): SupabaseSignupPayload {
        val payload = gson.fromJson(responseBody, SupabaseSignupResponse::class.java)
        val user = payload.user
        val email = user?.email ?: fallbackEmail
        val displayName = user?.userMetadata?.fullName ?: user?.userMetadata?.name ?: email.substringBefore("@")
        val userId = user?.id ?: fallbackEmail

        return SupabaseSignupPayload(
            userId = userId,
            email = email,
            displayName = displayName,
            avatarUrl = user?.userMetadata?.avatarUrl
        )
    }

    private fun parseErrorMessage(body: String): String? {
        return runCatching {
            val json = gson.fromJson(body, JsonObject::class.java)
            when {
                json.has("error_description") -> json.get("error_description").asString
                json.has("msg") -> json.get("msg").asString
                json.has("message") -> json.get("message").asString
                json.has("error") -> json.get("error").asString
                else -> null
            }
        }.getOrNull()
    }
}

private data class SupabaseAuthResponse(
    @SerializedName("access_token")
    val accessToken: String? = null,
    @SerializedName("refresh_token")
    val refreshToken: String? = null,
    val user: SupabaseUser? = null,
    val session: SupabaseSession? = null
)

private data class SupabaseSession(
    @SerializedName("access_token")
    val accessToken: String? = null,
    @SerializedName("refresh_token")
    val refreshToken: String? = null,
    val user: SupabaseUser? = null
)

private data class SupabaseUser(
    val id: String,
    val email: String? = null,
    @SerializedName("user_metadata")
    val userMetadata: SupabaseUserMetadata? = null
)

private data class SupabaseUserMetadata(
    @SerializedName("full_name")
    val fullName: String? = null,
    val name: String? = null,
    @SerializedName("avatar_url")
    val avatarUrl: String? = null
)

private data class SupabaseAuthPayload(
    val user: SupabaseUser,
    val accessToken: String?,
    val refreshToken: String?,
    val displayName: String?,
    val avatarUrl: String?
)

private data class SupabaseSignupResponse(
    val user: SupabaseUser? = null
)

private data class SupabaseSignupPayload(
    val userId: String,
    val email: String,
    val displayName: String?,
    val avatarUrl: String?
)
