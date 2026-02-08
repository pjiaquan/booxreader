package my.hinoki.booxreader.data.repo

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.UserEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Handles user authentication via PocketBase REST API. */
class AuthRepository(private val context: Context, private val tokenManager: TokenManager) {
        private val userDao = AppDatabase.get(context).userDao()
        private val gson = Gson()
        private val pocketBaseUrl = BuildConfig.POCKETBASE_URL.trimEnd('/')

        private val httpClient =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()

        suspend fun login(email: String, password: String): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val requestBody =
                                        gson.toJson(
                                                        mapOf(
                                                                "identity" to email,
                                                                "password" to password
                                                        )
                                                )
                                                .toRequestBody("application/json".toMediaType())

                                val request =
                                        Request.Builder()
                                                .url(
                                                        "$pocketBaseUrl/api/collections/users/auth-with-password"
                                                )
                                                .post(requestBody)
                                                .build()

                                val response = httpClient.newCall(request).execute()
                                val responseBody = response.body?.string() ?: ""

                                if (!response.isSuccessful) {
                                        Log.e("AuthRepository", "Login failed: $responseBody")
                                        throw Exception("Login failed: ${response.code}")
                                }

                                val authData =
                                        gson.fromJson(
                                                responseBody,
                                                PocketBaseAuthResponse::class.java
                                        )
                                val record =
                                        authData.record
                                                ?: throw Exception("No user record in response")

                                // Save tokens
                                tokenManager.saveAccessToken(authData.token)
                                // PocketBase doesn't use refresh tokens in the same way - the token
                                // is long-lived

                                // Create and cache user entity
                                val user =
                                        UserEntity(
                                                userId = record.id,
                                                email = record.email ?: email,
                                                displayName = record.name,
                                                avatarUrl = record.avatar
                                        )
                                userDao.insertUser(user)
                                user
                        }
                }

        suspend fun register(email: String, password: String, name: String?): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val requestBody =
                                        gson.toJson(
                                                        mapOf(
                                                                "email" to email,
                                                                "password" to password,
                                                                "passwordConfirm" to password,
                                                                "name" to (name ?: "")
                                                        )
                                                )
                                                .toRequestBody("application/json".toMediaType())

                                val request =
                                        Request.Builder()
                                                .url("$pocketBaseUrl/api/collections/users/records")
                                                .post(requestBody)
                                                .build()

                                val response = httpClient.newCall(request).execute()
                                val responseBody = response.body?.string() ?: ""

                                if (!response.isSuccessful) {
                                        Log.e(
                                                "AuthRepository",
                                                "Registration failed: $responseBody"
                                        )
                                        throw Exception("Registration failed: ${response.code}")
                                }

                                val record =
                                        gson.fromJson(
                                                responseBody,
                                                PocketBaseUserRecord::class.java
                                        )

                                // Now login to get the auth token
                                login(email, password).getOrThrow()
                        }
                }

        suspend fun loginWithGoogle(idToken: String): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                // PocketBase OAuth2 flow is different - this would need to be
                                // implemented
                                // based on your specific OAuth2 provider setup in PocketBase
                                // For now, return an error indicating it's not yet implemented
                                throw UnsupportedOperationException(
                                        "Google OAuth login not yet implemented for PocketBase"
                                )
                        }
                }

        suspend fun logout(): Result<Unit> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val syncRepo = UserSyncRepository(context, tokenManager = tokenManager)
                                // Best-effort final upload before local wipe.
                                // This avoids "book not found" after fast logout/login cycles.
                                withTimeoutOrNull(15_000) {
                                        runCatching { syncRepo.pushLocalBooks() }
                                                .onFailure {
                                                        ErrorReporter.report(
                                                                context,
                                                                "AuthRepository.logout",
                                                                "Failed to push local books before logout",
                                                                it
                                                        )
                                                }
                                }
                                tokenManager.clearTokens()
                                syncRepo.clearLocalUserData()
                        }
                }

        suspend fun resendVerificationEmail(email: String): Result<Unit> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val requestBody =
                                        gson.toJson(mapOf("email" to email))
                                                .toRequestBody("application/json".toMediaType())

                                val request =
                                        Request.Builder()
                                                .url(
                                                        "$pocketBaseUrl/api/collections/users/request-verification"
                                                )
                                                .post(requestBody)
                                                .build()

                                val response = httpClient.newCall(request).execute()

                                if (!response.isSuccessful) {
                                        val errorBody = response.body?.string() ?: ""
                                        Log.e(
                                                "AuthRepository",
                                                "Resend verification failed: $errorBody"
                                        )
                                        throw Exception(
                                                "Failed to resend verification: ${response.code}"
                                        )
                                }
                        }
                }

        suspend fun googleLogin(idToken: String): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                Log.d(
                                        "AuthRepository",
                                        "googleLogin - STUB: Not implemented for PocketBase yet"
                                )
                                throw Exception("Google login not yet implemented for PocketBase")
                        }
                }

        suspend fun getCurrentUser(): UserEntity? =
                withContext(Dispatchers.IO) {
                        // Keep auth gate tied to token presence, but read user from local cache.
                        // Token is a JWT string and not equal to users.userId.
                        val token = tokenManager.getAccessToken() ?: return@withContext null
                        if (token.isBlank()) return@withContext null
                        userDao.getUser().first()?.let { return@withContext it }

                        // Fallback: refresh auth and restore local user cache.
                        runCatching {
                                        val requestBody =
                                                "{}".toRequestBody(
                                                        "application/json".toMediaType()
                                                )
                                        val request =
                                                Request.Builder()
                                                        .url(
                                                                "$pocketBaseUrl/api/collections/users/auth-refresh"
                                                        )
                                                        .addHeader("Authorization", "Bearer $token")
                                                        .post(requestBody)
                                                        .build()

                                        val response = httpClient.newCall(request).execute()
                                        val responseBody = response.body?.string() ?: ""

                                        if (!response.isSuccessful) {
                                                Log.w(
                                                        "AuthRepository",
                                                        "getCurrentUser auth-refresh failed: ${response.code} $responseBody"
                                                )
                                                return@runCatching null
                                        }

                                        val authData =
                                                gson.fromJson(
                                                        responseBody,
                                                        PocketBaseAuthResponse::class.java
                                                )
                                        val record = authData.record ?: return@runCatching null
                                        tokenManager.saveAccessToken(authData.token)

                                        val user =
                                                UserEntity(
                                                        userId = record.id,
                                                        email = record.email ?: "",
                                                        displayName = record.name,
                                                        avatarUrl = record.avatar
                                                )
                                        userDao.insertUser(user)
                                        user
                                }
                                .getOrElse {
                                        Log.e("AuthRepository", "getCurrentUser failed", it)
                                        null
                                }
                }
}

// Response data classes for PocketBase API
private data class PocketBaseAuthResponse(
        @SerializedName("token") val token: String,
        @SerializedName("record") val record: PocketBaseUserRecord?
)

private data class PocketBaseUserRecord(
        @SerializedName("id") val id: String,
        @SerializedName("email") val email: String?,
        @SerializedName("name") val name: String?,
        @SerializedName("avatar") val avatar: String?,
        @SerializedName("verified") val verified: Boolean = false
)
