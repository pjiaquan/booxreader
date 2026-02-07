package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
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
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        private val pocketBaseUrl = BuildConfig.POCKETBASE_URL.trimEnd('/')

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

        /** Parse settings from PocketBase JSON response. */
        private fun parseSettingsFromJson(json: Map<String, Any>): ReaderSettings {
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
                        activeProfileId = (json["activeProfileId"] as? Double)?.toLong() ?: -1L,
                        updatedAt = (json["updatedAt"] as? Double)?.toLong()
                                        ?: System.currentTimeMillis()
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
                                                (it["updatedAt"] as? Double)?.toLong() ?: 0L
                                        }
                                                ?: return@withContext null
                                val remoteUpdatedAt =
                                        remoteSettings.get("updatedAt") as? Double ?: 0.0
                                val localSettings = ReaderSettings.fromPrefs(prefs)

                                if (remoteUpdatedAt.toLong() > localSettings.updatedAt) {
                                        // Remote is newer, update local
                                        val updated = parseSettingsFromJson(remoteSettings)
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

                                val settingsData =
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

                                val requestBody =
                                        gson.toJson(settingsData)
                                                .toRequestBody("application/json".toMediaType())

                                if (checkResponse.items.isNotEmpty()) {
                                        // Update existing record
                                        val recordId =
                                                checkResponse.items[0].get("id") as? String
                                                        ?: return@withContext
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/settings/records/$recordId"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                        Log.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings updated"
                                        )
                                } else {
                                        // Create new record
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/settings/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        executeRequest(createRequest)
                                        Log.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings created"
                                        )
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
        ) =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "pushBook - STUB: Not implemented for PocketBase yet"
                        )
                }

        suspend fun softDeleteBook(bookId: String): Boolean =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "softDeleteBook - STUB: Not implemented for PocketBase yet"
                        )
                        true
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
                                                val newBook =
                                                        BookEntity(
                                                                bookId = bookId,
                                                                title = title ?: "Untitled",
                                                                fileUri =
                                                                        "pocketbase://$storagePath", // Placeholder URI
                                                                lastLocatorJson = null,
                                                                lastOpenedAt = updatedAt,
                                                                deleted = false
                                                        )
                                                db.bookDao().insert(newBook)
                                                syncedCount++
                                        } else {
                                                // Update existing book if remote has newer info
                                                // (e.g. title)
                                                // Note: We preserve local fileUri unless it's a
                                                // placeholder
                                                if (existingBook.title != title) {
                                                        val updatedBook =
                                                                existingBook.copy(
                                                                        title = title
                                                                                        ?: existingBook
                                                                                                .title
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

        suspend fun pushAiNote(note: AiNoteEntity): Boolean =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext false

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

                                if (note.remoteId != null) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records/${note.remoteId}"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        executeRequest(createRequest)
                                }

                                Log.d("UserSyncRepository", "pushAiNote - Note synced")
                                true
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushAiNote failed", e)
                                false
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

                                        db.aiNoteDao().insert(note)
                                        syncedCount++
                                }

                                Log.d("UserSyncRepository", "pullNotes - Synced $syncedCount notes")
                                syncedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullNotes failed", e)
                                0
                        }
                }

        // --- Profile Sync ---

        suspend fun pushAiProfile(profile: AiProfileEntity): Boolean =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext false

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

                                if (profile.remoteId != null) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_profiles/records/${profile.remoteId}"
                                        val updateRequest =
                                                buildAuthenticatedRequest(updateUrl)
                                                        .patch(requestBody)
                                                        .build()
                                        executeRequest(updateRequest)
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/ai_profiles/records"
                                        val createRequest =
                                                buildAuthenticatedRequest(createUrl)
                                                        .post(requestBody)
                                                        .build()
                                        executeRequest(createRequest)
                                }

                                Log.d("UserSyncRepository", "pushAiProfile - Profile synced")
                                true
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushAiProfile failed", e)
                                false
                        }
                }

        suspend fun pushProfile(profile: AiProfileEntity) = pushAiProfile(profile)

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

                                        db.aiProfileDao().insert(profile)
                                        syncedCount++
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
                        Log.d(
                                "UserSyncRepository",
                                "ensureBookFileAvailable - STUB: Not implemented for PocketBase yet"
                        )
                        null
                }

        suspend fun ensureStorageBucketReady(): CheckResult =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "ensureStorageBucketReady - STUB: Not implemented for PocketBase yet"
                        )
                        CheckResult(ok = true, message = "Stub implementation")
                }

        suspend fun runStorageSelfTest(): CheckResult =
                withContext(io) {
                        Log.d(
                                "UserSyncRepository",
                                "runStorageSelfTest - STUB: Not implemented for PocketBase yet"
                        )
                        CheckResult(ok = true, message = "Stub implementation")
                }

        fun clearLocalUserData() {
                db.clearAllTables()
                prefs.edit().clear().apply()
                syncPrefs.edit().clear().apply()
                cachedUserId = null
        }

        // --- Private Helpers ---

        private fun requireUserId(): String? {
                cachedUserId?.let {
                        return it
                }

                // getUser() returns Flow, but we need sync access here
                // This is a stub - in a complete implementation we'd handle this better
                return null
        }

        private fun progressKey(bookId: String) = "progress_$bookId"
        private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

        private fun accessToken(): String? = tokenManager.getAccessToken()
}
