package my.hinoki.booxreader.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.UserEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.remote.createApiClient

/** Handles user authentication via PocketBase REST API. */
class AuthRepository(private val context: Context, private val tokenManager: TokenManager) {
        private val userDao = AppDatabase.get().userDao()
        private val json = Json { ignoreUnknownKeys = true }
        private val pocketBaseUrl = BuildConfig.POCKETBASE_URL.trimEnd('/')

        private val httpClient: HttpClient = createApiClient()

        suspend fun login(email: String, password: String): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val response =
                                        httpClient.post(
                                                "$pocketBaseUrl/api/collections/users/auth-with-password"
                                        ) {
                                                contentType(ContentType.Application.Json)
                                                setBody(
                                                        jsonBody(
                                                                "identity" to email,
                                                                "password" to password
                                                        )
                                                )
                                        }
                                val responseBody = response.bodyAsText()

                                if (!response.status.isSuccess()) {
                                        Log.e(
                                                "AuthRepository",
                                                "Login failed with code: ${response.status.value}"
                                        )
                                        throw Exception("Login failed: ${response.status.value}")
                                }

                                val authData =
                                        json.decodeFromString<PocketBaseAuthResponse>(responseBody)
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
                                                avatarUrl = resolveAvatarUrl(record)
                                        )
                                userDao.insertUser(user)
                                user
                        }
                }

        suspend fun register(email: String, password: String, name: String?): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val response =
                                        httpClient.post(
                                                "$pocketBaseUrl/api/collections/users/records"
                                        ) {
                                                contentType(ContentType.Application.Json)
                                                setBody(
                                                        jsonBody(
                                                                "email" to email,
                                                                "password" to password,
                                                                "passwordConfirm" to password,
                                                                "name" to (name ?: "")
                                                        )
                                                )
                                        }
                                val responseBody = response.bodyAsText()

                                if (!response.status.isSuccess()) {
                                        Log.e(
                                                "AuthRepository",
                                                "Registration failed with code: ${response.status.value}"
                                        )
                                        throw Exception(
                                                "Registration failed: ${response.status.value}"
                                        )
                                }

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
                                val syncRepo = createUserSyncRepository(context, tokenManager = tokenManager)
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
                                val response =
                                        httpClient.post(
                                                "$pocketBaseUrl/api/collections/users/request-verification"
                                        ) {
                                                contentType(ContentType.Application.Json)
                                                setBody(jsonBody("email" to email))
                                        }

                                if (!response.status.isSuccess()) {
                                        Log.e(
                                                "AuthRepository",
                                                "Resend verification failed with code: ${response.status.value}"
                                        )
                                        throw Exception(
                                                "Failed to resend verification: ${response.status.value}"
                                        )
                                }
                        }
                }

        suspend fun requestPasswordReset(email: String): Result<Unit> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val response =
                                        httpClient.post(
                                                "$pocketBaseUrl/api/collections/users/request-password-reset"
                                        ) {
                                                contentType(ContentType.Application.Json)
                                                setBody(jsonBody("email" to email))
                                        }

                                if (!response.status.isSuccess()) {
                                        Log.e(
                                                "AuthRepository",
                                                "Request password reset failed with code: ${response.status.value}"
                                        )
                                        throw Exception(
                                                "Failed to send reset email: ${response.status.value}"
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

        suspend fun updateProfile(displayName: String, avatarUri: Uri?): Result<UserEntity> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                val currentUser = getCurrentUser() ?: throw Exception("User not found")
                                val token =
                                        tokenManager
                                                .getAccessToken()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: throw Exception("Not authenticated")
                                val trimmedName = displayName.trim()
                                if (trimmedName.isBlank()) {
                                        throw IllegalArgumentException("Display name is required")
                                }

                                val url =
                                        "$pocketBaseUrl/api/collections/users/records/${currentUser.userId}"

                                if (avatarUri == null) {
                                        return@runCatching patchUserAndCache(url, token) {
                                                contentType(ContentType.Application.Json)
                                                setBody(jsonBody("name" to trimmedName))
                                        }
                                }

                                val tempFile = copyUriToTempFile(avatarUri)
                                try {
                                        val contentType =
                                                context.contentResolver.getType(avatarUri)
                                                        ?: "application/octet-stream"
                                        val form =
                                                formData {
                                                        append("name", trimmedName)
                                                        append(
                                                                "avatar",
                                                                tempFile.readBytes(),
                                                                Headers.build {
                                                                        append(
                                                                                HttpHeaders.ContentType,
                                                                                contentType
                                                                        )
                                                                        append(
                                                                                HttpHeaders.ContentDisposition,
                                                                                "filename=\"${tempFile.name}\""
                                                                        )
                                                                }
                                                        )
                                                }
                                        patchUserAndCache(url, token) {
                                                setBody(MultiPartFormDataContent(form))
                                        }
                                } finally {
                                        runCatching { tempFile.delete() }
                                }
                        }
                }

        suspend fun changePassword(
                currentPassword: String,
                newPassword: String
        ): Result<Unit> =
                withContext(Dispatchers.IO) {
                        runCatching {
                                if (newPassword.length < 8) {
                                        throw IllegalArgumentException(
                                                "Password must be at least 8 characters"
                                        )
                                }
                                val currentUser = getCurrentUser() ?: throw Exception("User not found")
                                val token =
                                        tokenManager
                                                .getAccessToken()
                                                ?.takeIf { it.isNotBlank() }
                                                ?: throw Exception("Not authenticated")

                                val response =
                                        httpClient.patch(
                                                "$pocketBaseUrl/api/collections/users/records/${currentUser.userId}"
                                        ) {
                                                header("Authorization", "Bearer $token")
                                                contentType(ContentType.Application.Json)
                                                setBody(
                                                        jsonBody(
                                                                "oldPassword" to currentPassword,
                                                                "password" to newPassword,
                                                                "passwordConfirm" to newPassword
                                                        )
                                                )
                                        }

                                if (!response.status.isSuccess()) {
                                        Log.e(
                                                "AuthRepository",
                                                "changePassword failed with code: ${response.status.value}"
                                        )
                                        throw Exception(
                                                "Failed to change password: ${response.status.value}"
                                        )
                                }

                                // Password change rotates auth credentials in PocketBase.
                                // Re-login with new password to keep local token valid.
                                login(currentUser.email, newPassword).getOrThrow()
                                Unit
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
                                        val response =
                                                httpClient.post(
                                                        "$pocketBaseUrl/api/collections/users/auth-refresh"
                                                ) {
                                                        header(
                                                                "Authorization",
                                                                "Bearer $token"
                                                        )
                                                        contentType(ContentType.Application.Json)
                                                        setBody("{}")
                                                }
                                        val responseBody = response.bodyAsText()

                                        if (!response.status.isSuccess()) {
                                                Log.w(
                                                        "AuthRepository",
                                                        "getCurrentUser auth-refresh failed with code: ${response.status.value}"
                                                )
                                                return@runCatching null
                                        }

                                        val authData =
                                                json.decodeFromString<PocketBaseAuthResponse>(responseBody)
                                        val record = authData.record ?: return@runCatching null
                                        tokenManager.saveAccessToken(authData.token)

                                        val user =
                                                UserEntity(
                                                        userId = record.id,
                                                        email = record.email ?: "",
                                                        displayName = record.name,
                                                        avatarUrl = resolveAvatarUrl(record)
                                                )
                                        userDao.insertUser(user)
                                        user
                                }
                                .getOrElse {
                                        Log.e("AuthRepository", "getCurrentUser failed", it)
                                        null
                                }
                }

        /** PATCH 個人資料並更新本地 user cache（Ktor 版，取代 OkHttp Request 版本）。 */
        private suspend fun patchUserAndCache(
                url: String,
                token: String,
                configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit
        ): UserEntity {
                val response =
                        httpClient.patch(url) {
                                header("Authorization", "Bearer $token")
                                configure()
                        }
                val responseBody = response.bodyAsText()
                if (!response.status.isSuccess()) {
                        Log.e(
                                "AuthRepository",
                                "Profile update failed with code: ${response.status.value}"
                        )
                        throw Exception("Profile update failed: ${response.status.value}")
                }
                val record =
                        json.decodeFromString<PocketBaseUserRecord>(responseBody)
                                ?: throw Exception("Invalid profile update response")
                val fallbackEmail = userDao.getUser().first()?.email.orEmpty()
                val user =
                        UserEntity(
                                userId = record.id,
                                email = record.email?.takeIf { it.isNotBlank() } ?: fallbackEmail,
                                displayName = record.name,
                                avatarUrl = resolveAvatarUrl(record)
                        )
                userDao.insertUser(user)
                return user
        }

        private fun resolveAvatarUrl(record: PocketBaseUserRecord): String? {
                val rawAvatar = record.avatar?.trim().orEmpty()
                if (rawAvatar.isBlank()) {
                        return null
                }
                if (
                        rawAvatar.startsWith("http://") ||
                                rawAvatar.startsWith("https://") ||
                                rawAvatar.startsWith("content://") ||
                                rawAvatar.startsWith("file://")
                ) {
                        return rawAvatar
                }

                val fileName = rawAvatar.substringAfterLast('/')
                val encodedUserId = encodePath(record.id)
                val encodedFile = encodePath(fileName)
                return "$pocketBaseUrl/api/files/users/$encodedUserId/$encodedFile"
        }

        private fun encodePath(value: String): String =
                value.split('/').joinToString("/") {
                        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
                }

        private fun copyUriToTempFile(uri: Uri): File {
                val cacheDir = File(context.cacheDir, "avatar_uploads").apply { mkdirs() }
                val fallbackName = "avatar_${System.currentTimeMillis()}.jpg"
                val displayName =
                        queryDisplayName(uri)
                                ?.substringAfterLast('/')
                                ?.ifBlank { fallbackName }
                                ?: fallbackName
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(cacheDir, safeName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output ->
                                input.copyTo(output)
                        }
                }
                        ?: throw Exception("Failed to read selected avatar")

                if (!target.exists() || target.length() <= 0L) {
                        throw Exception("Selected avatar is empty")
                }
                return target
        }

        private fun queryDisplayName(uri: Uri): String? {
                return runCatching {
                                context.contentResolver
                                        .query(
                                                uri,
                                                arrayOf(OpenableColumns.DISPLAY_NAME),
                                                null,
                                                null,
                                                null
                                        )
                                        ?.use { cursor ->
                                                if (!cursor.moveToFirst()) {
                                                        return@use null
                                                }
                                                val index =
                                                        cursor.getColumnIndex(
                                                                OpenableColumns.DISPLAY_NAME
                                                        )
                                                if (index >= 0) cursor.getString(index) else null
                                        }
                        }
                        .getOrNull()
        }
}

// Response data classes for PocketBase API
@kotlinx.serialization.Serializable
private data class PocketBaseAuthResponse(
        @kotlinx.serialization.SerialName("token") val token: String,
        @kotlinx.serialization.SerialName("record") val record: PocketBaseUserRecord?
)

@kotlinx.serialization.Serializable
private data class PocketBaseUserRecord(
        @kotlinx.serialization.SerialName("id") val id: String,
        @kotlinx.serialization.SerialName("email") val email: String?,
        @kotlinx.serialization.SerialName("name") val name: String?,
        @kotlinx.serialization.SerialName("avatar") val avatar: String?,
        @kotlinx.serialization.SerialName("verified") val verified: Boolean = false
)


/** Build a JSON body from string pairs (replaces Gson mapOf toJson). */
private fun jsonBody(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()

