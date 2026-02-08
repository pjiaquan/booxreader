package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.core.CrashReport
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

// Data class for PocketBase list responses
data class PocketBaseListResponse(
        val items: List<Map<String, Any>>,
        val page: Int = 1,
        val perPage: Int = 30,
        val totalItems: Int = 0,
        val totalPages: Int = 0
)

// Data class for check results
data class CheckResult(val ok: Boolean, val message: String?)

/**
 * Syncs user-specific data to PocketBase REST API. Implements push/pull operations for settings,
 * progress, books, bookmarks, notes, and profiles.
 */
class UserSyncRepository(
        context: Context,
        baseUrl: String? = null,
        tokenManager: TokenManager? = null
) {
        private val appContext = context.applicationContext
        private val prefs: SharedPreferences =
                context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        private val syncPrefs: SharedPreferences =
                context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        private val db = AppDatabase.get(context)
        private val io = Dispatchers.IO
        private val tokenManager = tokenManager ?: TokenManager(appContext)
        private val gson = Gson()
        private val pocketBaseUrl = (baseUrl ?: BuildConfig.POCKETBASE_URL).trimEnd('/')

        private val httpClient =
                OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()

        @Volatile private var cachedUserId: String? = null

        // --- Helper Methods ---
        private suspend fun fetchAllItems(
                collection: String,
                filterParam: String,
                sortParam: String? = null,
                perPage: Int = 100
        ): List<Map<String, Any>> =
                withContext(io) {
                        val items = mutableListOf<Map<String, Any>>()
                        var page = 1
                        while (true) {
                                val sortQuery =
                                        if (sortParam.isNullOrBlank()) "" else "&sort=$sortParam"
                                val url =
                                        "$pocketBaseUrl/api/collections/$collection/records?filter=$filterParam&page=$page&perPage=$perPage$sortQuery"
                                val request = buildAuthenticatedRequest(url).get().build()
                                val responseBody = executeRequest(request)
                                val response =
                                        gson.fromJson(
                                                responseBody,
                                                PocketBaseListResponse::class.java
                                        )
                                if (response.items.isEmpty()) {
                                        break
                                }
                                items.addAll(response.items)
                                if (response.totalPages <= page) {
                                        break
                                }
                                page++
                        }
                        items
                }

        /** Get the current user ID from the database. Returns null if no user is logged in. */
        private suspend fun getUserId(): String? {
                cachedUserId?.let {
                        return it
                }

                val user = db.userDao().getUser().first()
                cachedUserId = user?.userId
                return cachedUserId
        }

        /** Build an authenticated request with PocketBase auth token. */
        private suspend fun buildAuthenticatedRequest(url: String): Request.Builder {
                val token = tokenManager.getAccessToken() ?: ""
                return Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        }

        /**
         * Execute a request and return the response body as a string. Throws exception if request
         * fails.
         */
        private fun executeRequest(request: Request): String {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                        Log.e("UserSyncRepository", "Request failed: ${response.code} $body")
                        throw Exception("PocketBase request failed: ${response.code}")
                }

                return body
        }

        private fun longValue(value: Any?): Long {
                return when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull() ?: 0L
                        else -> 0L
                }
        }

        private fun parseMagicTags(raw: Any?, fallback: List<MagicTag>): List<MagicTag> {
                if (raw == null) return fallback

                return runCatching {
                                val type = object : TypeToken<List<MagicTag>>() {}.type
                                when (raw) {
                                        is String -> gson.fromJson<List<MagicTag>>(raw, type)
                                        else -> gson.fromJson<List<MagicTag>>(gson.toJson(raw), type)
                                } ?: fallback
                        }
                        .getOrElse {
                                Log.w("UserSyncRepository", "parseMagicTags failed, using fallback", it)
                                fallback
                        }
        }

        /** Parse settings from PocketBase JSON response. */
        private fun parseSettingsFromJson(
                json: Map<String, Any>,
                fallbackMagicTags: List<MagicTag>
        ): ReaderSettings {
                return ReaderSettings(
                        pageTapEnabled = json["pageTapEnabled"] as? Boolean ?: true,
                        pageSwipeEnabled = json["pageSwipeEnabled"] as? Boolean ?: true,
                        contrastMode = (json["contrastMode"] as? Double)?.toInt() ?: 0,
                        convertToTraditionalChinese =
                                json["convertToTraditionalChinese"] as? Boolean ?: true,
                        serverBaseUrl = json["serverBaseUrl"] as? String ?: "",
                        exportToCustomUrl = json["exportToCustomUrl"] as? Boolean ?: false,
                        exportCustomUrl = json["exportCustomUrl"] as? String ?: "",
                        exportToLocalDownloads = json["exportToLocalDownloads"] as? Boolean
                                        ?: false,
                        apiKey = json["apiKey"] as? String ?: "",
                        aiModelName = json["aiModelName"] as? String ?: "deepseek-chat",
                        aiSystemPrompt = json["aiSystemPrompt"] as? String ?: "",
                        aiUserPromptTemplate = json["aiUserPromptTemplate"] as? String ?: "%s",
                        temperature = json["temperature"] as? Double ?: 0.7,
                        maxTokens = (json["maxTokens"] as? Double)?.toInt() ?: 4096,
                        topP = json["topP"] as? Double ?: 1.0,
                        frequencyPenalty = json["frequencyPenalty"] as? Double ?: 0.0,
                        presencePenalty = json["presencePenalty"] as? Double ?: 0.0,
                        assistantRole = json["assistantRole"] as? String ?: "assistant",
                        enableGoogleSearch = json["enableGoogleSearch"] as? Boolean ?: true,
                        useStreaming = json["useStreaming"] as? Boolean ?: false,
                        pageAnimationEnabled = json["pageAnimationEnabled"] as? Boolean ?: false,
                        showPageIndicator = json["showPageIndicator"] as? Boolean ?: true,
                        language = json["language"] as? String ?: "system",
                        activeProfileId = longValue(json["activeProfileId"]).takeIf { it != 0L } ?: -1L,
                        updatedAt = longValue(json["updatedAt"]).takeIf { it > 0L }
                                        ?: System.currentTimeMillis(),
                        magicTags =
                                parseMagicTags(
                                        raw = json["magicTags"] ?: json["magic_tags"],
                                        fallback = fallbackMagicTags
                                )
                )
        }

        // --- Settings Sync ---

        /**
         * Pull settings from PocketBase if remote is newer than local. Returns the settings if
         * pulled, null if local is up to date or on error.
         */
        suspend fun pullSettingsIfNewer(): ReaderSettings? =
                withContext(io) {
                        try {
                                val userId =
                                        getUserId()
                                                ?: run {
                                                        Log.w(
                                                                "UserSyncRepository",
                                                                "pullSettingsIfNewer - No user logged in"
                                                        )
                                                        return@withContext null
                                                }

                                val items =
                                        fetchAllItems(
                                                "settings",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                if (items.isEmpty()) {
                                        Log.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - No remote settings found"
                                        )
                                        return@withContext null
                                }

                                val remoteSettings =
                                        items.maxByOrNull {
                                                longValue(it["updatedAt"])
                                        }
                                                ?: return@withContext null
                                val remoteUpdatedAt = longValue(remoteSettings["updatedAt"])
                                val localSettings = ReaderSettings.fromPrefs(prefs)

                                if (remoteUpdatedAt > localSettings.updatedAt) {
                                        // Remote is newer, update local
                                        val updated =
                                                parseSettingsFromJson(
                                                        remoteSettings,
                                                        fallbackMagicTags = localSettings.magicTags
                                                )
                                        updated.saveTo(prefs)
                                        Log.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - Settings pulled and saved"
                                        )
                                        updated
                                } else {
                                        Log.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - Local settings are up to date"
                                        )
                                        null
                                }
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullSettingsIfNewer failed", e)
                                null
                        }
                }

        /** Push current settings to PocketBase. Creates a new record or updates existing one. */
        suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromPrefs(prefs)) =
                withContext(io) {
                        try {
                                val userId =
                                        getUserId()
                                                ?: run {
                                                        Log.w(
                                                                "UserSyncRepository",
                                                                "pushSettings - No user logged in"
                                                        )
                                                        return@withContext
                                                }

                                // First check if settings record exists
                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/settings/records?filter=(user='$userId')"
                                val checkRequest = buildAuthenticatedRequest(checkUrl).get().build()
                                val checkBody = executeRequest(checkRequest)
                                val checkResponse =
                                        gson.fromJson(checkBody, PocketBaseListResponse::class.java)

                                val baseSettingsData =
                                        mapOf(
                                                "user" to userId,
                                                "pageTapEnabled" to settings.pageTapEnabled,
                                                "pageSwipeEnabled" to settings.pageSwipeEnabled,
                                                "contrastMode" to settings.contrastMode,
                                                "convertToTraditionalChinese" to
                                                        settings.convertToTraditionalChinese,
                                                "serverBaseUrl" to settings.serverBaseUrl,
                                                "exportToCustomUrl" to settings.exportToCustomUrl,
                                                "exportCustomUrl" to settings.exportCustomUrl,
                                                "exportToLocalDownloads" to
                                                        settings.exportToLocalDownloads,
                                                "apiKey" to settings.apiKey,
                                                "aiModelName" to settings.aiModelName,
                                                "aiSystemPrompt" to settings.aiSystemPrompt,
                                                "aiUserPromptTemplate" to
                                                        settings.aiUserPromptTemplate,
                                                "temperature" to settings.temperature,
                                                "maxTokens" to settings.maxTokens,
                                                "topP" to settings.topP,
                                                "frequencyPenalty" to settings.frequencyPenalty,
                                                "presencePenalty" to settings.presencePenalty,
                                                "assistantRole" to settings.assistantRole,
                                                "enableGoogleSearch" to settings.enableGoogleSearch,
                                                "useStreaming" to settings.useStreaming,
                                                "pageAnimationEnabled" to
                                                        settings.pageAnimationEnabled,
                                                "showPageIndicator" to settings.showPageIndicator,
                                                "language" to settings.language,
                                                "activeProfileId" to settings.activeProfileId,
                                                "updatedAt" to System.currentTimeMillis()
                                        )
                                val settingsDataWithMagicTags =
                                        baseSettingsData + ("magicTags" to settings.magicTags)

                                fun toBody(data: Map<String, Any>) =
                                        gson.toJson(data).toRequestBody("application/json".toMediaType())

                                if (checkResponse.items.isNotEmpty()) {
                                        // Update existing record
                                        val recordId =
                                                checkResponse.items[0].get("id") as? String
                                                        ?: return@withContext
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/settings/records/$recordId"
                                        try {
                                                val updateRequest =
                                                        buildAuthenticatedRequest(updateUrl)
                                                                .patch(toBody(settingsDataWithMagicTags))
                                                                .build()
                                                executeRequest(updateRequest)
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings updated with magicTags"
                                                )
                                        } catch (e: Exception) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - update with magicTags failed, retrying without magicTags",
                                                        e
                                                )
                                                val fallbackRequest =
                                                        buildAuthenticatedRequest(updateUrl)
                                                                .patch(toBody(baseSettingsData))
                                                                .build()
                                                executeRequest(fallbackRequest)
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings updated without magicTags fallback"
                                                )
                                        }
                                } else {
                                        // Create new record
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/settings/records"
                                        try {
                                                val createRequest =
                                                        buildAuthenticatedRequest(createUrl)
                                                                .post(toBody(settingsDataWithMagicTags))
                                                                .build()
                                                executeRequest(createRequest)
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings created with magicTags"
                                                )
                                        } catch (e: Exception) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - create with magicTags failed, retrying without magicTags",
                                                        e
                                                )
                                                val fallbackRequest =
                                                        buildAuthenticatedRequest(createUrl)
                                                                .post(toBody(baseSettingsData))
                                                                .build()
                                                executeRequest(fallbackRequest)
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings created without magicTags fallback"
                                                )
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushSettings failed", e)
                        }
                }

        fun getCachedProgress(bookId: String): String? {
                return prefs.getString(progressKey(bookId), null)
        }

        fun cacheProgress(
                bookId: String,
                locatorJson: String,
                updatedAt: Long = System.currentTimeMillis()
        ) {
                prefs.edit()
                        .putString(progressKey(bookId), locatorJson)
                        .putLong(progressTimestampKey(bookId), updatedAt)
                        .apply()
        }
        // --- Progress Sync ---

        suspend fun pullProgress(bookId: String): String? =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext null

                                val url =
                                        "$pocketBaseUrl/api/collections/progress/records?filter=(user='$userId'%26%26bookId='$bookId')"
                                val request = buildAuthenticatedRequest(url).get().build()
                                val responseBody = executeRequest(request)

                                val response =
                                        gson.fromJson(
                                                responseBody,
                                                PocketBaseListResponse::class.java
                                        )
                                if (response.items.isEmpty()) {
                                        Log.d(
                                                "UserSyncRepository",
                                                "pullProgress - No remote progress found for $bookId"
                                        )
                                        return@withContext null
                                }

                                val locatorJson = response.items[0]["locatorJson"] as? String
                                Log.d(
                                        "UserSyncRepository",
                                        "pullProgress - Progress pulled for $bookId"
                                )
                                locatorJson
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullProgress failed for $bookId", e)
                                null
                        }
                }

        suspend fun pushProgress(bookId: String, locatorJson: String, bookTitle: String? = null) =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext

                                // Check if progress record exists
                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/progress/records?filter=(user='$userId'%26%26bookId='$bookId')"
                                val checkRequest = buildAuthenticatedRequest(checkUrl).get().build()
                                val checkBody = executeRequest(checkRequest)
                                val checkResponse =
                                        gson.fromJson(checkBody, PocketBaseListResponse::class.java)

                                val progressData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to bookId,
                                                "bookTitle" to (bookTitle ?: ""),
                                                "locatorJson" to locatorJson,
                                                "updatedAt" to System.currentTimeMillis()
                                        )

                                val requestBody =
                                        gson.toJson(progressData)
                                                .toRequestBody("application/json".toMediaType())

                                if (checkResponse.items.isNotEmpty()) {
                                        // Update existing record
                                        val recordId =
                                                checkResponse.items[0]["id"] as? String
                                                        ?: return@withContext
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/progress/records/$recordId"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                        Log.d(
                                                "UserSyncRepository",
                                                "pushProgress - Progress updated for $bookId"
                                        )
                                } else {
                                        // Create new record
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/progress/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        executeRequest(createRequest)
                                        Log.d(
                                                "UserSyncRepository",
                                                "pushProgress - Progress created for $bookId"
                                        )
                                }
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushProgress failed for $bookId", e)
                        }
                }

        suspend fun pushBook(
                book: BookEntity,
                uploadFile: Boolean = false,
                contentResolver: android.content.ContentResolver? = null
        ): Boolean =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext false
                                val now = System.currentTimeMillis()
                                val localUpdatedAt = maxOf(book.lastOpenedAt, book.deletedAt ?: 0L)
                                val payloadUpdatedAt =
                                        if (localUpdatedAt > 0L) localUpdatedAt else now
                                var storagePath =
                                        if (book.fileUri.startsWith("pocketbase://")) {
                                                normalizeStoragePath(
                                                        book.fileUri.removePrefix("pocketbase://")
                                                )
                                        } else {
                                                null
                                        }

                                val bookData =
                                        mutableMapOf<String, Any?>(
                                                "user" to userId,
                                                "bookId" to book.bookId,
                                                "title" to (book.title ?: ""),
                                                "storagePath" to storagePath,
                                                // bookId is SHA-256 of file content in this app.
                                                "fileHash" to book.bookId,
                                                "deleted" to book.deleted,
                                                "deletedAt" to book.deletedAt,
                                                "updatedAt" to payloadUpdatedAt
                                        )

                                if (bookData["storagePath"] == null) {
                                        bookData.remove("storagePath")
                                }
                                if (bookData["deletedAt"] == null) {
                                        bookData.remove("deletedAt")
                                }

                                val requestBody =
                                        gson.toJson(bookData)
                                                .toRequestBody("application/json".toMediaType())

                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/books/records?filter=(user='$userId'%26%26bookId='${book.bookId}')&perPage=1"
                                val checkRequest = buildAuthenticatedRequest(checkUrl).get().build()
                                val checkBody = executeRequest(checkRequest)
                                val checkResponse =
                                        gson.fromJson(checkBody, PocketBaseListResponse::class.java)
                                val existingItem = checkResponse.items.firstOrNull()
                                var recordId = existingItem?.get("id") as? String

                                if (existingItem != null) {
                                        val remoteUpdatedAt =
                                                (existingItem["updatedAt"] as? Double)
                                                        ?.toLong()
                                                        ?: 0L
                                        if (!book.deleted && remoteUpdatedAt > payloadUpdatedAt) {
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "pushBook - Skip stale local update for ${book.bookId}"
                                                )
                                                return@withContext true
                                        }

                                        val safeRecordId = recordId ?: return@withContext false
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/books/records/$safeRecordId"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/books/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        val createBody = executeRequest(createRequest)
                                        val created =
                                                gson.fromJson(createBody, Map::class.java) as
                                                        Map<String, Any>
                                        recordId = created["id"] as? String
                                }

                                if (uploadFile && contentResolver != null) {
                                        val uploadStoragePath =
                                                tryUploadBookFile(
                                                        recordId = recordId,
                                                        book = book,
                                                        contentResolver = contentResolver
                                                )
                                        if (!uploadStoragePath.isNullOrBlank() &&
                                                        uploadStoragePath != storagePath &&
                                                        recordId != null
                                        ) {
                                                storagePath = uploadStoragePath
                                                updateBookStoragePath(
                                                        recordId = recordId,
                                                        storagePath = uploadStoragePath
                                                )
                                        }
                                }

                                Log.d("UserSyncRepository", "pushBook - Synced book ${book.bookId}")
                                true
                        } catch (e: Exception) {
                                Log.e(
                                        "UserSyncRepository",
                                        "pushBook failed for ${book.bookId}",
                                        e
                                )
                                false
                        }
                }

        suspend fun softDeleteBook(bookId: String): Boolean =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext false
                                val now = System.currentTimeMillis()
                                val deleteData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to bookId,
                                                "deleted" to true,
                                                "deletedAt" to now,
                                                "updatedAt" to now
                                        )
                                val requestBody =
                                        gson.toJson(deleteData)
                                                .toRequestBody("application/json".toMediaType())

                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/books/records?filter=(user='$userId'%26%26bookId='$bookId')&perPage=1"
                                val checkRequest = buildAuthenticatedRequest(checkUrl).get().build()
                                val checkBody = executeRequest(checkRequest)
                                val checkResponse =
                                        gson.fromJson(checkBody, PocketBaseListResponse::class.java)

                                if (checkResponse.items.isNotEmpty()) {
                                        val recordId =
                                                checkResponse.items[0]["id"] as? String
                                                        ?: return@withContext false
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/books/records/$recordId"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/books/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        executeRequest(createRequest)
                                }

                                Log.d(
                                        "UserSyncRepository",
                                        "softDeleteBook - Synced deletion for $bookId"
                                )
                                true
                        } catch (e: Exception) {
                                Log.e(
                                        "UserSyncRepository",
                                        "softDeleteBook failed for $bookId",
                                        e
                                )
                                false
                        }
                }

        suspend fun pushLocalBooks(): Int =
                withContext(io) {
                        try {
                                val localBooks = db.bookDao().getAllBooks()
                                var syncedCount = 0
                                for (book in localBooks) {
                                        val synced = pushBook(book, uploadFile = false)
                                        if (synced) {
                                                syncedCount++
                                        }
                                }

                                val pendingDeletes = db.bookDao().getPendingDeletes()
                                for (deletedBook in pendingDeletes) {
                                        val deleted = softDeleteBook(deletedBook.bookId)
                                        if (deleted) {
                                                db.bookDao().deleteById(deletedBook.bookId)
                                                syncedCount++
                                        }
                                }

                                Log.d(
                                        "UserSyncRepository",
                                        "pushLocalBooks - Synced $syncedCount local books/deletes"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushLocalBooks failed", e)
                                0
                        }
                }

        suspend fun pullBooks(): Int =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext 0

                                val items =
                                        fetchAllItems(
                                                "books",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                var syncedCount = 0

                                for (item in items) {
                                        val bookId = item["bookId"] as? String ?: continue
                                        val title = item["title"] as? String
                                        val storagePath = item["storagePath"] as? String
                                        val deleted = item["deleted"] as? Boolean ?: false
                                        val updatedAt =
                                                (item["updatedAt"] as? Double)?.toLong()
                                                        ?: System.currentTimeMillis()

                                        if (deleted) {
                                                db.bookDao().deleteById(bookId)
                                                continue
                                        }

                                        // Check if book exists locally
                                        val existingBook =
                                                db.bookDao().getByIds(listOf(bookId)).firstOrNull()

                                        if (existingBook == null) {
                                                // New book from cloud
                                                val remoteFileUri =
                                                        storagePath
                                                                ?.takeIf { it.isNotBlank() }
                                                                ?.let { "pocketbase://$it" }
                                                                ?: "pocketbase://$bookId"
                                                val newBook =
                                                        BookEntity(
                                                                bookId = bookId,
                                                                title = title ?: "Untitled",
                                                                fileUri = remoteFileUri,
                                                                lastLocatorJson = null,
                                                                lastOpenedAt = updatedAt,
                                                                deleted = false
                                                        )
                                                db.bookDao().insert(newBook)
                                                syncedCount++
                                        } else {
                                                val remoteFileUri =
                                                        storagePath
                                                                ?.takeIf { it.isNotBlank() }
                                                                ?.let { "pocketbase://$it" }
                                                val shouldUpdateTitle =
                                                        title != null && existingBook.title != title
                                                val shouldUpdateFileUri =
                                                        remoteFileUri != null &&
                                                                existingBook.fileUri.startsWith(
                                                                        "pocketbase://"
                                                                ) &&
                                                                existingBook.fileUri != remoteFileUri

                                                if (shouldUpdateTitle || shouldUpdateFileUri) {
                                                        val updatedBook =
                                                                existingBook.copy(
                                                                        title =
                                                                                if (shouldUpdateTitle) {
                                                                                        title
                                                                                } else {
                                                                                        existingBook
                                                                                                .title
                                                                                },
                                                                        fileUri =
                                                                                if (shouldUpdateFileUri) {
                                                                                        remoteFileUri
                                                                                } else {
                                                                                        existingBook
                                                                                                .fileUri
                                                                                }
                                                                )
                                                        db.bookDao().insert(updatedBook)
                                                        syncedCount++
                                                }
                                        }
                                }

                                Log.d("UserSyncRepository", "pullBooks - Synced $syncedCount books")
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullBooks failed", e)
                                0
                        }
                }

        // --- Bookmark Sync ---

        suspend fun pullBookmarks(bookId: String? = null): Int =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext 0

                                val filterParam =
                                        if (bookId != null) {
                                                "(user='$userId'%26%26bookId='$bookId')"
                                        } else {
                                                "(user='$userId')"
                                        }

                                val items =
                                        fetchAllItems(
                                                "bookmarks",
                                                filterParam,
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                var syncedCount = 0

                                for (item in items) {
                                        val remoteId = item["id"] as? String ?: continue
                                        val bookmarkBookId = item["bookId"] as? String ?: continue
                                        val locatorJson = item["locatorJson"] as? String ?: continue
                                        val createdAt =
                                                (item["createdAt"] as? String)?.let {
                                                        // Parse PocketBase timestamp if needed
                                                        System.currentTimeMillis()
                                                }
                                                        ?: System.currentTimeMillis()

                                        val bookmark =
                                                BookmarkEntity(
                                                        remoteId = remoteId,
                                                        bookId = bookmarkBookId,
                                                        locatorJson = locatorJson,
                                                        createdAt = createdAt,
                                                        isSynced = true
                                                )

                                        db.bookmarkDao().insert(bookmark)
                                        syncedCount++
                                }

                                Log.d(
                                        "UserSyncRepository",
                                        "pullBookmarks - Synced $syncedCount bookmarks"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullBookmarks failed", e)
                                0
                        }
                }

        suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext null

                                val bookmarkData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to entity.bookId,
                                                "locatorJson" to entity.locatorJson,
                                                "createdAt" to entity.createdAt,
                                                "updatedAt" to System.currentTimeMillis()
                                        )

                                val requestBody =
                                        gson.toJson(bookmarkData)
                                                .toRequestBody("application/json".toMediaType())

                                val result =
                                        if (entity.remoteId != null) {
                                                // Update existing bookmark
                                                val updateUrl =
                                                        "$pocketBaseUrl/api/collections/bookmarks/records/${entity.remoteId}"
                                                val updateRequest =
                                                        buildAuthenticatedRequest(updateUrl)
                                                                .patch(requestBody)
                                                                .build()
                                                val responseBody = executeRequest(updateRequest)
                                                val response =
                                                        gson.fromJson(
                                                                responseBody,
                                                                Map::class.java
                                                        ) as
                                                                Map<String, Any>

                                                entity.copy(
                                                        remoteId = response["id"] as? String
                                                                        ?: entity.remoteId,
                                                        isSynced = true
                                                )
                                        } else {
                                                // Create new bookmark
                                                val createUrl =
                                                        "$pocketBaseUrl/api/collections/bookmarks/records"
                                                val createRequest =
                                                        buildAuthenticatedRequest(createUrl)
                                                                .post(requestBody)
                                                                .build()
                                                val responseBody = executeRequest(createRequest)
                                                val response =
                                                        gson.fromJson(
                                                                responseBody,
                                                                Map::class.java
                                                        ) as
                                                                Map<String, Any>

                                                entity.copy(
                                                        remoteId = response["id"] as? String,
                                                        isSynced = true
                                                )
                                        }

                                Log.d("UserSyncRepository", "pushBookmark - Bookmark synced")
                                result
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushBookmark failed", e)
                                null
                        }
                }

        // --- Note Sync ---

        suspend fun pushAiNote(note: AiNoteEntity): String? =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext null

                                val noteData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to (note.bookId ?: ""),
                                                "bookTitle" to (note.bookTitle ?: ""),
                                                "messages" to note.messages,
                                                "originalText" to (note.originalText ?: ""),
                                                "aiResponse" to (note.aiResponse ?: ""),
                                                "locatorJson" to (note.locatorJson ?: ""),
                                                "createdAt" to note.createdAt,
                                                "updatedAt" to System.currentTimeMillis()
                                        )

                                val requestBody =
                                        gson.toJson(noteData)
                                                .toRequestBody("application/json".toMediaType())

                                val syncedRemoteId =
                                        if (!note.remoteId.isNullOrBlank()) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records/${note.remoteId}"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                                note.remoteId
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        val createBody = executeRequest(createRequest)
                                        val created =
                                                gson.fromJson(createBody, Map::class.java) as
                                                        Map<String, Any>
                                        created["id"] as? String
                                } ?: return@withContext null

                                Log.d("UserSyncRepository", "pushAiNote - Note synced")
                                syncedRemoteId
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushAiNote failed", e)
                                null
                        }
                }

        suspend fun pullNotes(): Int =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext 0

                                val items =
                                        fetchAllItems(
                                                "ai_notes",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                var syncedCount = 0

                                for (item in items) {
                                        val remoteId = item["id"] as? String ?: continue
                                        val note =
                                                AiNoteEntity(
                                                        remoteId = remoteId,
                                                        bookId = item["bookId"] as? String,
                                                        bookTitle = item["bookTitle"] as? String,
                                                        messages = item["messages"] as? String
                                                                        ?: "",
                                                        originalText =
                                                                item["originalText"] as? String,
                                                        aiResponse = item["aiResponse"] as? String,
                                                        locatorJson =
                                                                item["locatorJson"] as? String,
                                                        createdAt =
                                                                (item["createdAt"] as? Double)
                                                                        ?.toLong()
                                                                        ?: System.currentTimeMillis(),
                                                        updatedAt =
                                                                (item["updatedAt"] as? Double)
                                                                        ?.toLong()
                                                                        ?: System.currentTimeMillis()
                                                )

                                        val existing = db.aiNoteDao().getByRemoteId(remoteId)
                                        if (existing == null) {
                                                db.aiNoteDao().insert(note)
                                                syncedCount++
                                        } else if (note.updatedAt > existing.updatedAt) {
                                                db.aiNoteDao().update(note.copy(id = existing.id))
                                                syncedCount++
                                        }
                                }
                                cleanupDuplicateNotes()

                                Log.d("UserSyncRepository", "pullNotes - Synced $syncedCount notes")
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullNotes failed", e)
                                0
                        }
                }

        suspend fun deleteAiNote(remoteId: String): Boolean =
                withContext(io) {
                        try {
                                val url =
                                        "$pocketBaseUrl/api/collections/ai_notes/records/$remoteId"
                                val request = buildAuthenticatedRequest(url).delete().build()
                                executeRequest(request)
                                Log.d("UserSyncRepository", "deleteAiNote - Note deleted")
                                true
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "deleteAiNote failed", e)
                                false
                        }
                }

        private suspend fun cleanupDuplicateNotes() {
                val notes = db.aiNoteDao().getAll()
                val seen = HashSet<String>(notes.size)
                val duplicateIds = ArrayList<Long>()
                for (note in notes) {
                        val key =
                                listOf(
                                                note.remoteId.orEmpty(),
                                                note.bookId.orEmpty(),
                                                note.originalText.orEmpty(),
                                                note.aiResponse.orEmpty(),
                                                note.messages,
                                                note.locatorJson.orEmpty()
                                        )
                                        .joinToString("\u0001")
                        if (!seen.add(key)) {
                                duplicateIds.add(note.id)
                        }
                }
                if (duplicateIds.isEmpty()) return
                duplicateIds.forEach { id -> db.aiNoteDao().deleteById(id) }
                Log.d(
                        "UserSyncRepository",
                        "cleanupDuplicateNotes - Removed ${duplicateIds.size} duplicate notes"
                )
        }

        // --- Profile Sync ---

        suspend fun pushAiProfile(profile: AiProfileEntity): String? =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext null

                                val profileData =
                                        mapOf(
                                                "user" to userId,
                                                "name" to profile.name,
                                                "modelName" to profile.modelName,
                                                "apiKey" to profile.apiKey,
                                                "serverBaseUrl" to profile.serverBaseUrl,
                                                "systemPrompt" to profile.systemPrompt,
                                                "userPromptTemplate" to profile.userPromptTemplate,
                                                "useStreaming" to profile.useStreaming,
                                                "temperature" to profile.temperature,
                                                "maxTokens" to profile.maxTokens,
                                                "topP" to profile.topP,
                                                "frequencyPenalty" to profile.frequencyPenalty,
                                                "presencePenalty" to profile.presencePenalty,
                                                "assistantRole" to profile.assistantRole,
                                                "enableGoogleSearch" to profile.enableGoogleSearch,
                                                "extraParamsJson" to
                                                        (profile.extraParamsJson ?: ""),
                                                "updatedAt" to System.currentTimeMillis()
                                        )

                                val requestBody =
                                        gson.toJson(profileData)
                                                .toRequestBody("application/json".toMediaType())

                                val syncedRemoteId =
                                        if (!profile.remoteId.isNullOrBlank()) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_profiles/records/${profile.remoteId}"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                                profile.remoteId
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/ai_profiles/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        val createBody = executeRequest(createRequest)
                                        val created =
                                                gson.fromJson(createBody, Map::class.java) as
                                                        Map<String, Any>
                                        created["id"] as? String
                                } ?: return@withContext null

                                Log.d("UserSyncRepository", "pushAiProfile - Profile synced")
                                syncedRemoteId
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushAiProfile failed", e)
                                null
                        }
                }

        suspend fun pushProfile(profile: AiProfileEntity): String? = pushAiProfile(profile)

        suspend fun pullAiProfiles(): Int =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext 0

                                val items =
                                        fetchAllItems(
                                                "ai_profiles",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                var syncedCount = 0

                                for (item in items) {
                                        val remoteId = item["id"] as? String ?: continue
                                        val profile =
                                                AiProfileEntity(
                                                        remoteId = remoteId,
                                                        name = item["name"] as? String ?: "",
                                                        modelName = item["modelName"] as? String
                                                                        ?: "",
                                                        apiKey = item["apiKey"] as? String ?: "",
                                                        serverBaseUrl =
                                                                item["serverBaseUrl"] as? String
                                                                        ?: "",
                                                        systemPrompt =
                                                                item["systemPrompt"] as? String
                                                                        ?: "",
                                                        userPromptTemplate =
                                                                item["userPromptTemplate"] as?
                                                                        String
                                                                        ?: "",
                                                        useStreaming =
                                                                item["useStreaming"] as? Boolean
                                                                        ?: false,
                                                        temperature = item["temperature"] as? Double
                                                                        ?: 0.7,
                                                        maxTokens =
                                                                (item["maxTokens"] as? Double)
                                                                        ?.toInt()
                                                                        ?: 4096,
                                                        topP = item["topP"] as? Double ?: 1.0,
                                                        frequencyPenalty =
                                                                item["frequencyPenalty"] as? Double
                                                                        ?: 0.0,
                                                        presencePenalty =
                                                                item["presencePenalty"] as? Double
                                                                        ?: 0.0,
                                                        assistantRole =
                                                                item["assistantRole"] as? String
                                                                        ?: "assistant",
                                                        enableGoogleSearch =
                                                                item["enableGoogleSearch"] as?
                                                                        Boolean
                                                                        ?: true,
                                                        extraParamsJson =
                                                                item["extraParamsJson"] as? String,
                                                        createdAt =
                                                                (item["createdAt"] as? Double)
                                                                        ?.toLong()
                                                                        ?: System.currentTimeMillis(),
                                                        updatedAt =
                                                                (item["updatedAt"] as? Double)
                                                                        ?.toLong()
                                                                        ?: System.currentTimeMillis(),
                                                        isSynced = true
                                                )

                                        val existing = db.aiProfileDao().getByRemoteId(remoteId)
                                        if (existing == null) {
                                                db.aiProfileDao().insert(profile)
                                                syncedCount++
                                        } else if (profile.updatedAt > existing.updatedAt) {
                                                db.aiProfileDao().insert(profile.copy(id = existing.id))
                                                syncedCount++
                                        }
                                }

                                Log.d(
                                        "UserSyncRepository",
                                        "pullAiProfiles - Synced $syncedCount profiles"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullAiProfiles failed", e)
                                0
                        }
                }

        suspend fun deleteAiProfile(remoteId: String): Boolean =
                withContext(io) {
                        try {
                                val url =
                                        "$pocketBaseUrl/api/collections/ai_profiles/records/$remoteId"
                                val request = buildAuthenticatedRequest(url).delete().build()
                                executeRequest(request)
                                Log.d("UserSyncRepository", "deleteAiProfile - Profile deleted")
                                true
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "deleteAiProfile failed", e)
                                false
                        }
                }

        suspend fun pushCrashReport(report: CrashReport): Boolean =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "pushCrashReport - STUB: Not implemented for PocketBase yet"
                        )
                        false
                }

        suspend fun pullAllProgress(): Int =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "pullAllProgress - STUB: Not implemented for PocketBase yet"
                        )
                        0
                }

        suspend fun pullProfiles(): Int =
                withContext(io) {
                        pullAiProfiles()
                }

        suspend fun ensureBookFileAvailable(
                bookId: String,
                storagePath: String? = null,
                originalUri: String? = null,
                downloadIfNeeded: Boolean = true
        ): android.net.Uri? =
                withContext(io) {
                        try {
                                val original =
                                        originalUri
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { Uri.parse(it) }
                                if (original != null && isUriReadable(original)) {
                                        return@withContext original
                                }

                                val cachedFile = localBookCacheFile(bookId)
                                if (cachedFile.exists() && cachedFile.length() > 0L) {
                                        return@withContext Uri.fromFile(cachedFile)
                                }

                                if (!downloadIfNeeded) {
                                        return@withContext null
                                }

                                var recordId: String? = null
                                var effectiveStoragePath = normalizeStoragePath(storagePath)
                                if (effectiveStoragePath.isNullOrBlank()) {
                                        val userId = getUserId() ?: return@withContext null
                                        val remoteRecord = fetchBookRecord(userId, bookId)
                                        if (remoteRecord == null) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "ensureBookFileAvailable - No remote record for $bookId"
                                                )
                                                return@withContext null
                                        }
                                        if (remoteRecord["deleted"] as? Boolean == true) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "ensureBookFileAvailable - Remote record is deleted for $bookId"
                                                )
                                                return@withContext null
                                        }
                                        recordId = remoteRecord["id"] as? String
                                        effectiveStoragePath =
                                                resolveStoragePathFromRecord(remoteRecord)
                                }

                                if (effectiveStoragePath.isNullOrBlank()) {
                                        Log.w(
                                                "UserSyncRepository",
                                                "ensureBookFileAvailable - Missing storagePath for $bookId"
                                        )
                                        return@withContext null
                                }

                                val downloadUrl =
                                        buildDownloadUrl(
                                                storagePath = effectiveStoragePath,
                                                recordId = recordId
                                        )
                                                ?: return@withContext null

                                val downloaded =
                                        downloadRemoteFile(
                                                url = downloadUrl,
                                                target = cachedFile
                                        )
                                if (!downloaded) {
                                        return@withContext null
                                }

                                val localUri = Uri.fromFile(cachedFile)
                                db.bookDao().getByIds(listOf(bookId)).firstOrNull()?.let { local ->
                                        if (local.fileUri.startsWith("pocketbase://")) {
                                                db.bookDao()
                                                        .insert(local.copy(fileUri = localUri.toString()))
                                        }
                                }
                                localUri
                        } catch (e: Exception) {
                                Log.e(
                                        "UserSyncRepository",
                                        "ensureBookFileAvailable failed for $bookId",
                                        e
                                )
                                null
                        }
                }

        suspend fun ensureStorageBucketReady(): CheckResult =
                withContext(io) {
                        try {
                                val userId =
                                        getUserId()
                                                ?: return@withContext CheckResult(
                                                        ok = false,
                                                        message = "No logged-in user"
                                                )
                                if (accessToken().isNullOrBlank()) {
                                        return@withContext CheckResult(
                                                ok = false,
                                                message = "Missing auth token"
                                        )
                                }

                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/books/records?filter=(user='$userId')&perPage=1"
                                val checkRequest = buildAuthenticatedRequest(checkUrl).get().build()
                                executeRequest(checkRequest)

                                val cacheDir = localBooksCacheDir()
                                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                                        return@withContext CheckResult(
                                                ok = false,
                                                message =
                                                        "Failed to create local cache dir: ${cacheDir.absolutePath}"
                                        )
                                }
                                val probe = File(cacheDir, ".probe")
                                probe.writeText("ok")
                                val probeOk = probe.exists() && probe.readText() == "ok"
                                probe.delete()
                                if (!probeOk) {
                                        return@withContext CheckResult(
                                                ok = false,
                                                message = "Local cache dir is not writable"
                                        )
                                }

                                CheckResult(
                                        ok = true,
                                        message =
                                                "Storage ready (remote books collection + local cache)"
                                )
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "ensureStorageBucketReady failed", e)
                                CheckResult(ok = false, message = e.message ?: "Storage check failed")
                        }
                }

        suspend fun runStorageSelfTest(): CheckResult =
                withContext(io) {
                        try {
                                val bucketReady = ensureStorageBucketReady()
                                if (!bucketReady.ok) {
                                        return@withContext bucketReady
                                }

                                val userId = getUserId() ?: return@withContext bucketReady
                                val remoteBooks =
                                        fetchAllItems(
                                                "books",
                                                "(user='$userId'%26%26deleted=false)",
                                                sortParam = "-updatedAt",
                                                perPage = 20
                                        )
                                val withRemoteFile =
                                        remoteBooks.firstOrNull {
                                                !resolveStoragePathFromRecord(it).isNullOrBlank()
                                        }

                                if (withRemoteFile != null) {
                                        val remoteBookId = withRemoteFile["bookId"] as? String
                                        val remoteStorage = resolveStoragePathFromRecord(withRemoteFile)
                                        if (!remoteBookId.isNullOrBlank() &&
                                                        !remoteStorage.isNullOrBlank()
                                        ) {
                                                val uri =
                                                        ensureBookFileAvailable(
                                                                bookId = remoteBookId,
                                                                storagePath = remoteStorage,
                                                                downloadIfNeeded = true
                                                        )
                                                if (uri != null) {
                                                        return@withContext CheckResult(
                                                                ok = true,
                                                                message =
                                                                        "Download test passed for book $remoteBookId"
                                                        )
                                                }
                                                return@withContext CheckResult(
                                                        ok = false,
                                                        message =
                                                                "Download test failed for remote book $remoteBookId"
                                                )
                                        }
                                }

                                val localCandidate =
                                        db.bookDao().getAllBooks().firstOrNull { entity ->
                                                try {
                                                        isUriReadable(Uri.parse(entity.fileUri))
                                                } catch (_: Exception) {
                                                        false
                                                }
                                        }

                                if (localCandidate != null) {
                                        val pushed =
                                                pushBook(
                                                        book = localCandidate,
                                                        uploadFile = true,
                                                        contentResolver = appContext.contentResolver
                                                )
                                        if (!pushed) {
                                                return@withContext CheckResult(
                                                        ok = false,
                                                        message =
                                                                "Upload metadata test failed for local book ${localCandidate.bookId}"
                                                )
                                        }
                                        val refreshed = fetchBookRecord(userId, localCandidate.bookId)
                                        val storage = resolveStoragePathFromRecord(refreshed)
                                        if (storage.isNullOrBlank()) {
                                                return@withContext CheckResult(
                                                        ok = false,
                                                        message =
                                                                "Upload path missing after test. Configure a PocketBase file field on books and keep storagePath updated."
                                                )
                                        }
                                        return@withContext CheckResult(
                                                ok = true,
                                                message =
                                                        "Upload path test passed for ${localCandidate.bookId}"
                                        )
                                }

                                CheckResult(
                                        ok = true,
                                        message =
                                                "Storage checks passed (connectivity + cache). No eligible upload/download sample found."
                                )
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "runStorageSelfTest failed", e)
                                CheckResult(ok = false, message = e.message ?: "Self-test failed")
                        }
                }

        fun clearLocalUserData() {
                db.clearAllTables()
                prefs.edit().clear().apply()
                syncPrefs.edit().clear().apply()
                clearSyncedBookCache()
                clearPersistedBookUriPermissions()
                cachedUserId = null
        }

        // --- Private Helpers ---

        private fun localBooksCacheDir(): File = File(appContext.filesDir, "synced_books")

        private fun clearSyncedBookCache() {
                val cacheDir = localBooksCacheDir()
                if (!cacheDir.exists()) return
                runCatching { cacheDir.deleteRecursively() }
                        .onFailure { Log.w("UserSyncRepository", "Failed to clear synced_books", it) }
        }

        private fun clearPersistedBookUriPermissions() {
                val resolver = appContext.contentResolver
                resolver.persistedUriPermissions.forEach { permission ->
                        val modeFlags =
                                (if (permission.isReadPermission)
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        else 0) or
                                        (if (permission.isWritePermission)
                                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                else 0)
                        if (modeFlags == 0) return@forEach
                        runCatching {
                                        resolver.releasePersistableUriPermission(
                                                permission.uri,
                                                modeFlags
                                        )
                                }
                                .onFailure {
                                        Log.w(
                                                "UserSyncRepository",
                                                "Failed to revoke persisted URI permission: ${permission.uri}",
                                                it
                                        )
                                }
                }
        }

        private fun localBookCacheFile(bookId: String): File {
                val safeBookId = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                return File(localBooksCacheDir(), "$safeBookId.epub")
        }

        private fun isUriReadable(uri: Uri): Boolean {
                if (uri.scheme.equals("pocketbase", ignoreCase = true)) {
                        return false
                }
                return try {
                        appContext.contentResolver.openInputStream(uri)?.use { true } ?: false
                } catch (_: Exception) {
                        false
                }
        }

        private fun normalizeStoragePath(path: String?): String? {
                if (path.isNullOrBlank()) {
                        return null
                }
                val normalized = path.trim().removePrefix("pocketbase://").trim()
                return normalized.takeIf { it.isNotBlank() }
        }

        private suspend fun fetchBookRecord(
                userId: String,
                bookId: String
        ): Map<String, Any>? {
                val filter = "(user='$userId'%26%26bookId='$bookId')"
                val url =
                        "$pocketBaseUrl/api/collections/books/records?filter=$filter&perPage=1"
                val request = buildAuthenticatedRequest(url).get().build()
                val responseBody = executeRequest(request)
                val response = gson.fromJson(responseBody, PocketBaseListResponse::class.java)
                return response.items.firstOrNull()
        }

        private suspend fun updateBookStoragePath(recordId: String, storagePath: String) {
                val payload =
                        mapOf(
                                "storagePath" to storagePath,
                                "updatedAt" to System.currentTimeMillis()
                        )
                val requestBody =
                        gson.toJson(payload).toRequestBody("application/json".toMediaType())
                val url = "$pocketBaseUrl/api/collections/books/records/$recordId"
                val request = buildAuthenticatedRequest(url).patch(requestBody).build()
                executeRequest(request)
        }

        private suspend fun tryUploadBookFile(
                recordId: String?,
                book: BookEntity,
                contentResolver: android.content.ContentResolver
        ): String? {
                if (recordId.isNullOrBlank() || book.deleted) {
                        return null
                }

                val sourceUri =
                        runCatching { Uri.parse(book.fileUri) }
                                .getOrNull()
                                ?.takeIf { isUriReadable(it) }
                                ?: return null

                val uploadDir = File(appContext.cacheDir, "book_uploads")
                if (!uploadDir.exists()) {
                        uploadDir.mkdirs()
                }
                val tmpFile = File(uploadDir, "${book.bookId}.epub.tmp")

                try {
                        contentResolver.openInputStream(sourceUri)?.use { input ->
                                FileOutputStream(tmpFile).use { output ->
                                        input.copyTo(output)
                                }
                        } ?: return null

                        if (!tmpFile.exists() || tmpFile.length() <= 0L) {
                                return null
                        }

                        val displayName =
                                (queryDisplayName(sourceUri) ?: "${book.bookId}.epub")
                                        .substringAfterLast('/')
                        val sanitizedBaseName =
                                displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val nonEmptyBaseName =
                                sanitizedBaseName.ifBlank { "${book.bookId}.epub" }
                        val cleanName =
                                if (nonEmptyBaseName.lowercase(Locale.US).endsWith(".epub")) {
                                        nonEmptyBaseName
                                } else {
                                        "$nonEmptyBaseName.epub"
                                }
                        val mediaType = "application/epub+zip".toMediaType()
                        val fieldCandidates = listOf("bookFile", "file", "epubFile", "epub", "asset")
                        val uploadUrl = "$pocketBaseUrl/api/collections/books/records/$recordId"

                        for (field in fieldCandidates) {
                                val multipartBody =
                                        MultipartBody.Builder()
                                                .setType(MultipartBody.FORM)
                                                .addFormDataPart(
                                                        field,
                                                        cleanName,
                                                        tmpFile.asRequestBody(mediaType)
                                                )
                                                .build()

                                val request =
                                        buildAuthenticatedRequest(uploadUrl).patch(multipartBody).build()

                                httpClient.newCall(request).execute().use { response ->
                                        val body = response.body?.string().orEmpty()
                                        if (!response.isSuccessful) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "tryUploadBookFile - field=$field failed code=${response.code}"
                                                )
                                        } else {
                                                val payload =
                                                        runCatching {
                                                                gson.fromJson(body, Map::class.java)
                                                                        as? Map<String, Any>
                                                        }
                                                                .getOrNull()
                                                val uploadedFileName =
                                                        extractUploadedFileName(
                                                                payload,
                                                                fieldName = field,
                                                                fallback = cleanName
                                                        )
                                                if (!uploadedFileName.isNullOrBlank()) {
                                                        return "$recordId/$uploadedFileName"
                                                }
                                        }
                                }
                        }
                } catch (e: Exception) {
                        Log.e("UserSyncRepository", "tryUploadBookFile failed", e)
                } finally {
                        runCatching { tmpFile.delete() }
                }
                return null
        }

        private fun queryDisplayName(uri: Uri): String? {
                return runCatching {
                                appContext.contentResolver
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

        private fun extractUploadedFileName(
                record: Map<String, Any>?,
                fieldName: String,
                fallback: String? = null
        ): String? {
                if (record == null) {
                        return fallback
                }

                val value = record[fieldName]
                when (value) {
                        is String -> {
                                if (value.isNotBlank()) {
                                        return value.substringAfterLast('/')
                                }
                        }
                        is List<*> -> {
                                val firstFile =
                                        value.firstOrNull { it is String && it.isNotBlank() } as?
                                                String
                                if (!firstFile.isNullOrBlank()) {
                                        return firstFile.substringAfterLast('/')
                                }
                        }
                }

                return fallback
        }

        private fun resolveStoragePathFromRecord(record: Map<String, Any>?): String? {
                if (record == null) {
                        return null
                }

                val direct = normalizeStoragePath(record["storagePath"] as? String)
                if (!direct.isNullOrBlank()) {
                        return direct
                }

                val recordId = record["id"] as? String ?: return null
                val fileFieldCandidates = listOf("bookFile", "file", "epubFile", "epub", "asset")
                for (field in fileFieldCandidates) {
                        val fileName = extractUploadedFileName(record, field)
                        if (!fileName.isNullOrBlank()) {
                                return "$recordId/$fileName"
                        }
                }
                return null
        }

        private fun buildDownloadUrl(storagePath: String, recordId: String?): String? {
                val normalized = normalizeStoragePath(storagePath) ?: return null
                if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                        return normalized
                }
                if (normalized.startsWith("/")) {
                        return "$pocketBaseUrl$normalized"
                }

                val clean = normalized.removePrefix("books/")
                val parts = clean.split('/').filter { it.isNotBlank() }
                if (parts.size >= 2) {
                        val rid = urlEncodePath(parts.first())
                        val fileName = urlEncodePath(parts.drop(1).joinToString("/"))
                        return "$pocketBaseUrl/api/files/books/$rid/$fileName"
                }

                if (parts.size == 1 && !recordId.isNullOrBlank()) {
                        val rid = urlEncodePath(recordId)
                        val fileName = urlEncodePath(parts.first())
                        return "$pocketBaseUrl/api/files/books/$rid/$fileName"
                }

                return null
        }

        private fun urlEncodePath(value: String): String =
                value.split('/').joinToString("/") {
                        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
                }

        private suspend fun downloadRemoteFile(url: String, target: File): Boolean {
                return try {
                        target.parentFile?.mkdirs()
                        val requestBuilder =
                                if (url.startsWith(pocketBaseUrl)) {
                                        buildAuthenticatedRequest(url)
                                } else {
                                        Request.Builder().url(url)
                                }
                        val request = requestBuilder.get().build()
                        httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                        Log.w(
                                                "UserSyncRepository",
                                                "downloadRemoteFile failed code=${response.code} url=$url"
                                        )
                                        return false
                                }

                                val body = response.body ?: return false
                                val tmpFile = File(target.parentFile, "${target.name}.part")
                                body.byteStream().use { input ->
                                        FileOutputStream(tmpFile).use { output ->
                                                input.copyTo(output)
                                        }
                                }

                                if (target.exists()) {
                                        target.delete()
                                }
                                if (!tmpFile.renameTo(target)) {
                                        tmpFile.copyTo(target, overwrite = true)
                                        tmpFile.delete()
                                }
                                target.exists() && target.length() > 0L
                        }
                } catch (e: Exception) {
                        Log.e("UserSyncRepository", "downloadRemoteFile failed for $url", e)
                        false
                }
        }

        private fun progressKey(bookId: String) = "progress_$bookId"
        private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

        private fun accessToken(): String? = tokenManager.getAccessToken()
}
