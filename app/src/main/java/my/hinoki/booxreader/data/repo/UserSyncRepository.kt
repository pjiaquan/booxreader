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
import my.hinoki.booxreader.data.core.ErrorReporter
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
        private enum class RemoteFileState {
                PRESENT,
                MISSING,
                UNKNOWN
        }

        private companion object {
                val BOOK_FILE_FIELD_CANDIDATES =
                        listOf("bookFile", "file", "epubFile", "epub", "asset", "book")
                val MAIL_QUEUE_COLLECTION_CANDIDATES =
                        listOf("mail_queue", "email_queue", "outbox_emails")
                val MAIL_CUSTOM_ROUTE_CANDIDATES =
                        listOf("/boox-mail-send", "/api/boox-mail-send")
                const val AI_NOTE_TEXT_FIELD_MAX_CHARS = 5000
                const val AI_NOTE_COMPACT_MESSAGE_CHARS = 1200
        }

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

        /**
         * Send AI daily summary email through PocketBase.
         *
         * Strategy:
         * 1) Try PocketBase direct mail endpoint (/api/mails/send).
         * 2) Fallback to inserting a record into a mail queue collection for server-side hooks.
         */
        suspend fun sendDailySummaryEmail(
                toEmail: String,
                subject: String,
                body: String
        ): CheckResult =
                withContext(io) {
                        // Allow both host-root and mistakenly-configured */api base URLs.
                        val pocketBaseRoot = pocketBaseUrl.removeSuffix("/api")
                        val refreshedUserId = refreshAuthSessionIfPossible(pocketBaseRoot)
                        val email = toEmail.trim()
                        if (email.isBlank()) {
                                return@withContext CheckResult(false, "Missing recipient email")
                        }
                        val userId =
                                refreshedUserId
                                        ?: getUserId()
                                        ?: return@withContext CheckResult(
                                                false,
                                                "No logged in user"
                                        )

                        val contentType = "application/json; charset=utf-8".toMediaType()
                        var lastError: String? = null
                        var directStatusCode: Int? = null
                        val customRouteStatusCodes = mutableListOf<Int>()
                        val queueStatusCodes = mutableListOf<Int>()
                        var firstNon404QueueError: String? = null

                        // Strategy 1: PocketBase direct mail API (usually requires elevated access).
                        runCatching {
                                        val htmlBody =
                                                "<pre style=\"white-space:pre-wrap;font-family:monospace;\">${escapeHtmlForEmail(body)}</pre>"
                                        val payload =
                                                gson.toJson(
                                                        mapOf(
                                                                "to" to listOf(email),
                                                                "subject" to subject,
                                                                "html" to htmlBody,
                                                                "text" to body
                                                        )
                                                )
                                        val request =
                                                buildAuthenticatedRequest(
                                                                "$pocketBaseRoot/api/mails/send"
                                                        )
                                                        .post(payload.toRequestBody(contentType))
                                                        .build()
                                        httpClient.newCall(request).execute().use { response ->
                                                val responseBody =
                                                        response.body?.string()?.trim().orEmpty()
                                                directStatusCode = response.code
                                                if (response.isSuccessful) {
                                                        return@withContext CheckResult(
                                                                true,
                                                                "sent via /api/mails/send"
                                                        )
                                                }
                                                lastError =
                                                        "direct mail failed (${response.code})"
                                                if (responseBody.isNotEmpty()) {
                                                        lastError += ": $responseBody"
                                                }
                                        }
                                }
                                .onFailure {
                                        lastError =
                                                it.message?.takeIf { message ->
                                                        message.isNotBlank()
                                                }
                                                        ?: "direct mail request failed"
                                }

                        // Strategy 1.5: custom routerAdd endpoint in PocketBase hooks.
                        for (routePath in MAIL_CUSTOM_ROUTE_CANDIDATES) {
                                runCatching {
                                                val payload =
                                                        gson.toJson(
                                                                mapOf(
                                                                        "toEmail" to email,
                                                                        "subject" to subject,
                                                                        "body" to body
                                                                )
                                                        )
                                                val request =
                                                        buildAuthenticatedRequest(
                                                                        "$pocketBaseRoot$routePath"
                                                                )
                                                                .post(
                                                                        payload.toRequestBody(
                                                                                contentType
                                                                        )
                                                                )
                                                                .build()
                                                httpClient.newCall(request).execute().use {
                                                        response ->
                                                                val responseBody =
                                                                        response.body
                                                                                ?.string()
                                                                                ?.trim()
                                                                                .orEmpty()
                                                                customRouteStatusCodes +=
                                                                        response.code
                                                                if (response.isSuccessful) {
                                                                        return@withContext CheckResult(
                                                                                true,
                                                                                "sent via $routePath"
                                                                        )
                                                                }
                                                                lastError =
                                                                        "custom route $routePath failed (${response.code})"
                                                                if (responseBody.isNotEmpty()) {
                                                                        lastError +=
                                                                                ": $responseBody"
                                                                }
                                                        }
                                        }
                                        .onFailure {
                                                val errorMessage =
                                                        it.message?.takeIf { message ->
                                                                message.isNotBlank()
                                                        }
                                                                ?: "custom route request failed"
                                                if (lastError.isNullOrBlank()) {
                                                        lastError = errorMessage
                                                }
                                        }
                        }

                        // Strategy 2: queue record for PocketBase hook/automation mail dispatch.
                        for (collection in MAIL_QUEUE_COLLECTION_CANDIDATES) {
                                val queuePayload =
                                        gson.toJson(
                                                mapOf(
                                                        "user" to userId,
                                                        "toEmail" to email,
                                                        "subject" to subject,
                                                        "body" to body,
                                                        "category" to "ai_note_daily_summary",
                                                        "status" to "pending"
                                                )
                                        )
                                val queueRequest =
                                        try {
                                                buildAuthenticatedRequest(
                                                                "$pocketBaseRoot/api/collections/$collection/records"
                                                        )
                                                        .post(queuePayload.toRequestBody(contentType))
                                                        .build()
                                        } catch (e: Exception) {
                                                lastError = e.message ?: "queue request build failed"
                                                continue
                                        }

                                try {
                                        httpClient.newCall(queueRequest).execute().use { response ->
                                                val responseBody =
                                                        response.body?.string()?.trim().orEmpty()
                                                queueStatusCodes += response.code
                                                if (response.isSuccessful) {
                                                        return@withContext CheckResult(
                                                                true,
                                                                "queued via $collection"
                                                        )
                                                }
                                                val queueError =
                                                        "queue $collection failed (${response.code})"
                                                if (responseBody.isNotEmpty()) {
                                                        lastError = "$queueError: $responseBody"
                                                } else {
                                                        lastError = queueError
                                                }
                                                if (response.code != 404 &&
                                                                firstNon404QueueError == null
                                                ) {
                                                        firstNon404QueueError = lastError
                                                }
                                        }
                                } catch (e: Exception) {
                                        lastError =
                                                e.message?.takeIf { message ->
                                                        message.isNotBlank()
                                                }
                                                        ?: "queue $collection request failed"
                                }
                        }

                        if (firstNon404QueueError != null) {
                                lastError = firstNon404QueueError
                        }

                        val setupHint =
                                when {
                                        directStatusCode == 401 || directStatusCode == 403 ->
                                                "PocketBase /api/mails/send requires admin permission. Use mail_queue hook mode."
                                        queueStatusCodes.any { it == 401 || it == 403 } ->
                                                "PocketBase queue write denied. Check createRule for mail_queue/email_queue/outbox_emails (expect @request.auth.id != \"\")."
                                        customRouteStatusCodes.any { it == 401 || it == 403 } ->
                                                "PocketBase custom mail route denied. Check routerAdd auth handling."
                                        directStatusCode == 404 &&
                                                queueStatusCodes.all { it == 404 } &&
                                                queueStatusCodes.isNotEmpty() ->
                                                "PocketBase endpoints not found. Check POCKETBASE_URL (use host root, no /api) and create mail_queue/email_queue/outbox_emails with a send hook."
                                        customRouteStatusCodes.isNotEmpty() &&
                                                customRouteStatusCodes.all { it == 404 } ->
                                                "PocketBase custom route /boox-mail-send not found. Ensure pb_hooks/main.pb.js is deployed and loaded."
                                        queueStatusCodes.isNotEmpty() &&
                                                queueStatusCodes.all { it == 404 } ->
                                                "Queue collection not found. Create mail_queue/email_queue/outbox_emails in PocketBase."
                                        else -> null
                                }

                        CheckResult(
                                false,
                                (setupHint ?: lastError)
                                        ?: "PocketBase mail failed; /api/mails/send and mail_queue endpoints unavailable"
                        )
                }

        /** Build an authenticated request with PocketBase auth token. */
        private suspend fun buildAuthenticatedRequest(url: String): Request.Builder {
                val token = tokenManager.getAccessToken() ?: ""
                return Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
        }

        /**
         * PocketBase auth tokens can expire/rotate. Refresh once before mail dispatch so
         * routerAdd/custom routes can resolve e.auth consistently.
         */
        private fun refreshAuthSessionIfPossible(pocketBaseRoot: String): String? {
                val token = tokenManager.getAccessToken()?.trim().orEmpty()
                if (token.isBlank()) return null

                val request =
                        Request.Builder()
                                .url("$pocketBaseRoot/api/collections/users/auth-refresh")
                                .addHeader("Authorization", "Bearer $token")
                                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .build()

                return try {
                        httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                        Log.w(
                                                "UserSyncRepository",
                                                "refreshAuthSessionIfPossible failed: ${response.code}"
                                        )
                                        return null
                                }
                                val payload =
                                        runCatching {
                                                        gson.fromJson(
                                                                response.body?.charStream(),
                                                                Map::class.java
                                                        ) as? Map<*, *>
                                                }
                                                .getOrNull()
                                val refreshedToken =
                                        (payload?.get("token") as? String)?.trim().orEmpty()
                                if (refreshedToken.isNotBlank()) {
                                        tokenManager.saveAccessToken(refreshedToken)
                                }
                                val refreshedUserId =
                                        ((payload?.get("record") as? Map<*, *>)?.get("id") as? String)
                                                ?.trim()
                                                .orEmpty()
                                if (refreshedUserId.isNotBlank()) {
                                        cachedUserId = refreshedUserId
                                }
                                refreshedUserId.ifBlank { null }
                        }
                } catch (e: Exception) {
                        Log.w("UserSyncRepository", "refreshAuthSessionIfPossible error", e)
                        null
                }
        }

        /**
         * Execute a request and return the response body as a string. Throws exception if request
         * fails.
         */
        private fun executeRequest(request: Request, reportError: Boolean = true): String {
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                        val message =
                                "Request failed: ${response.code} ${request.method} ${request.url}"
                        Log.e("UserSyncRepository", "$message body=$body")
                        if (reportError) {
                                ErrorReporter.report(
                                        appContext,
                                        "UserSyncRepository.executeRequest",
                                        message
                                )
                        }
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

        private fun escapeHtmlForEmail(raw: String): String {
                return raw
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
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
                        autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
                        dailySummaryEmailEnabled =
                                json["dailySummaryEmailEnabled"] as? Boolean ?: false,
                        dailySummaryEmailHour = (json["dailySummaryEmailHour"] as? Double)?.toInt()
                                        ?: 21,
                        dailySummaryEmailMinute =
                                (json["dailySummaryEmailMinute"] as? Double)?.toInt() ?: 0,
                        dailySummaryEmailTo = json["dailySummaryEmailTo"] as? String ?: "",
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

                                val localHasMagicTagsKey = prefs.contains("magic_tags")
                                val existingSettingsRecord =
                                        checkResponse.items.firstOrNull()
                                                        as? Map<String, Any>
                                val remoteMagicTags =
                                        parseMagicTags(
                                                raw =
                                                        existingSettingsRecord?.get("magicTags")
                                                                ?: existingSettingsRecord?.get("magic_tags"),
                                                fallback = emptyList()
                                        )
                                val magicTagsForUpload =
                                        if (!localHasMagicTagsKey && remoteMagicTags.isNotEmpty()) {
                                                Log.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - local magic_tags key missing; preserving remote magicTags to avoid default overwrite"
                                                )
                                                remoteMagicTags
                                        } else {
                                                settings.magicTags
                                        }

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
                                                "dailySummaryEmailEnabled" to
                                                        settings.dailySummaryEmailEnabled,
                                                "dailySummaryEmailHour" to
                                                        settings.dailySummaryEmailHour.coerceIn(0, 23),
                                                "dailySummaryEmailMinute" to
                                                        settings.dailySummaryEmailMinute.coerceIn(0, 59),
                                                "dailySummaryEmailTo" to
                                                        settings.dailySummaryEmailTo.trim(),
                                                "language" to settings.language,
                                                "activeProfileId" to settings.activeProfileId,
                                                "updatedAt" to System.currentTimeMillis()
                                        )
                                val settingsDataWithMagicTags =
                                        baseSettingsData + ("magicTags" to magicTagsForUpload)

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

                                val item = response.items[0]
                                val locatorJson = item["locatorJson"] as? String
                                val remoteUpdatedAt = parseEpochMillis(item["updatedAt"])
                                if (!locatorJson.isNullOrBlank()) {
                                        cacheProgress(bookId, locatorJson, remoteUpdatedAt)
                                        mergeRemoteProgressIntoLocalBook(
                                                bookId = bookId,
                                                locatorJson = locatorJson,
                                                remoteUpdatedAt = remoteUpdatedAt
                                        )
                                }
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
                                val remoteHasFilePath =
                                        !resolveStoragePathFromRecord(existingItem).isNullOrBlank()
                                var remoteDeleted = false
                                var recordId = existingItem?.get("id") as? String

                                if (existingItem != null) {
                                        val remoteUpdatedAt =
                                                (existingItem["updatedAt"] as? Double)
                                                        ?.toLong()
                                                        ?: 0L
                                        remoteDeleted = existingItem["deleted"] as? Boolean ?: false
                                        val needsFileBackfill =
                                                uploadFile &&
                                                        contentResolver != null &&
                                                        (!remoteHasFilePath || remoteDeleted)
                                        if (!book.deleted &&
                                                        !remoteDeleted &&
                                                        remoteUpdatedAt > payloadUpdatedAt &&
                                                        !needsFileBackfill
                                        ) {
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

                                if (uploadFile &&
                                                contentResolver != null &&
                                                (!remoteHasFilePath || remoteDeleted)
                                ) {
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

        suspend fun ensureRemoteBookFilePresent(
                book: BookEntity,
                contentResolver: android.content.ContentResolver
        ): Boolean =
                withContext(io) {
                        try {
                                if (book.deleted) return@withContext true
                                val userId = getUserId() ?: return@withContext false
                                val remoteRecord = fetchBookRecord(userId, book.bookId)
                                if (remoteRecord == null) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                                contentResolver = contentResolver
                                        )
                                }

                                val recordId = remoteRecord["id"] as? String
                                val remoteDeleted = remoteRecord["deleted"] as? Boolean ?: false
                                val storagePath = resolveStoragePathFromRecord(remoteRecord)

                                if (recordId.isNullOrBlank() ||
                                                remoteDeleted ||
                                                storagePath.isNullOrBlank()
                                ) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                                contentResolver = contentResolver
                                        )
                                }

                                val remoteUrl = buildDownloadUrl(storagePath, recordId)
                                if (remoteUrl.isNullOrBlank()) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                                contentResolver = contentResolver
                                        )
                                }

                                when (probeRemoteFileState(remoteUrl)) {
                                        RemoteFileState.PRESENT -> true
                                        RemoteFileState.UNKNOWN -> {
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "ensureRemoteBookFilePresent - Skip reupload for unknown remote state ${book.bookId}"
                                                )
                                                true
                                        }
                                        RemoteFileState.MISSING -> {
                                                val uploadedStoragePath =
                                                        tryUploadBookFile(
                                                                recordId = recordId,
                                                                book = book,
                                                                contentResolver = contentResolver
                                                        )
                                                if (uploadedStoragePath.isNullOrBlank()) {
                                                        Log.w(
                                                                "UserSyncRepository",
                                                                "ensureRemoteBookFilePresent - Reupload failed for ${book.bookId}"
                                                        )
                                                        return@withContext false
                                                }
                                                val normalizedCurrent =
                                                        normalizeStoragePath(storagePath)
                                                if (uploadedStoragePath != normalizedCurrent) {
                                                        updateBookStoragePath(
                                                                recordId = recordId,
                                                                storagePath = uploadedStoragePath
                                                        )
                                                }
                                                Log.d(
                                                        "UserSyncRepository",
                                                        "ensureRemoteBookFilePresent - Reuploaded missing file for ${book.bookId}"
                                                )
                                                true
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e(
                                        "UserSyncRepository",
                                        "ensureRemoteBookFilePresent failed for ${book.bookId}",
                                        e
                                )
                                ErrorReporter.report(
                                        appContext,
                                        "UserSyncRepository.ensureRemoteBookFilePresent",
                                        "Failed to ensure remote file for ${book.bookId}",
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
                                        val synced =
                                                pushBook(
                                                        book,
                                                        uploadFile = true,
                                                        contentResolver = appContext.contentResolver
                                                )
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
                                        val resolvedStoragePath = resolveStoragePathFromRecord(item)
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
                                                        resolvedStoragePath
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
                                                        resolvedStoragePath
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
                                val originalTextForSync = resolveOriginalTextForSync(note)
                                val aiResponseResolved = resolveAiResponseForSync(note)
                                val aiResponseForSync =
                                        truncateForRemoteText(
                                                aiResponseResolved,
                                                AI_NOTE_TEXT_FIELD_MAX_CHARS
                                        )
                                val messagesForSync =
                                        normalizeAiNoteMessagesForSync(
                                                note = note,
                                                originalText = originalTextForSync,
                                                aiResponse = aiResponseResolved,
                                                maxChars = AI_NOTE_TEXT_FIELD_MAX_CHARS
                                        )
                                if (aiResponseResolved.length > aiResponseForSync.length ||
                                                note.messages.length > messagesForSync.length
                                ) {
                                        Log.w(
                                                "UserSyncRepository",
                                                "pushAiNote - Truncated ai note payload for PocketBase text limits (id=${note.id}, remoteId=${note.remoteId})"
                                        )
                                }

                                val noteData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to (note.bookId ?: ""),
                                                "bookTitle" to (note.bookTitle ?: ""),
                                                "messages" to messagesForSync,
                                                "originalText" to originalTextForSync,
                                                "aiResponse" to aiResponseForSync,
                                                "status" to
                                                        if (aiResponseResolved.isBlank()) {
                                                                "generating"
                                                        } else {
                                                                "done"
                                                        },
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

        private fun profileNameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

        private fun hasUsableApiKey(apiKey: String): Boolean {
                val key = apiKey.trim()
                if (key.isBlank()) return false
                if (key.equals("<YOUR_GEMINI_API_KEY>", ignoreCase = true)) return false
                return !key.startsWith("<YOUR_", ignoreCase = true)
        }

        private fun shouldPreferProfile(candidate: AiProfileEntity, current: AiProfileEntity): Boolean {
                val candidateHasKey = hasUsableApiKey(candidate.apiKey)
                val currentHasKey = hasUsableApiKey(current.apiKey)
                if (candidateHasKey != currentHasKey) return candidateHasKey
                if (candidate.updatedAt != current.updatedAt) return candidate.updatedAt > current.updatedAt
                return candidate.id > current.id
        }

        private fun toRemoteProfile(item: Map<String, Any>): AiProfileEntity? {
                val remoteId = item["id"] as? String ?: return null
                val now = System.currentTimeMillis()
                return AiProfileEntity(
                        remoteId = remoteId,
                        name = item["name"] as? String ?: "",
                        modelName = item["modelName"] as? String ?: "",
                        apiKey = item["apiKey"] as? String ?: "",
                        serverBaseUrl = item["serverBaseUrl"] as? String ?: "",
                        systemPrompt = item["systemPrompt"] as? String ?: "",
                        userPromptTemplate = item["userPromptTemplate"] as? String ?: "",
                        useStreaming = item["useStreaming"] as? Boolean ?: false,
                        temperature = item["temperature"] as? Double ?: 0.7,
                        maxTokens = (item["maxTokens"] as? Double)?.toInt() ?: 4096,
                        topP = item["topP"] as? Double ?: 1.0,
                        frequencyPenalty = item["frequencyPenalty"] as? Double ?: 0.0,
                        presencePenalty = item["presencePenalty"] as? Double ?: 0.0,
                        assistantRole = item["assistantRole"] as? String ?: "assistant",
                        enableGoogleSearch = item["enableGoogleSearch"] as? Boolean ?: true,
                        extraParamsJson = item["extraParamsJson"] as? String,
                        createdAt = (item["createdAt"] as? Double)?.toLong() ?: now,
                        updatedAt = (item["updatedAt"] as? Double)?.toLong() ?: now,
                        isSynced = true
                )
        }

        private fun applyProfileToLocalSettings(profile: AiProfileEntity) {
                val currentSettings = ReaderSettings.fromPrefs(prefs)
                currentSettings
                        .copy(
                                aiModelName = profile.modelName,
                                apiKey = profile.apiKey,
                                serverBaseUrl = profile.serverBaseUrl,
                                aiSystemPrompt = profile.systemPrompt,
                                aiUserPromptTemplate = profile.userPromptTemplate,
                                assistantRole = profile.assistantRole,
                                enableGoogleSearch = profile.enableGoogleSearch,
                                useStreaming = profile.useStreaming,
                                temperature = profile.temperature,
                                maxTokens = profile.maxTokens,
                                topP = profile.topP,
                                frequencyPenalty = profile.frequencyPenalty,
                                presencePenalty = profile.presencePenalty,
                                activeProfileId = profile.id,
                                updatedAt = System.currentTimeMillis()
                        )
                        .saveTo(prefs)
        }

        private suspend fun cleanupDuplicateProfilesAndRepairActive(): Int {
                val allProfiles = db.aiProfileDao().getAllList()
                if (allProfiles.isEmpty()) return 0

                val grouped =
                        allProfiles
                                .filter { it.name.isNotBlank() }
                                .groupBy { profileNameKey(it.name) }
                                .filterValues { it.size > 1 }
                var changedCount = 0
                var activeProfileId = ReaderSettings.fromPrefs(prefs).activeProfileId

                for ((_, group) in grouped) {
                        val keep = group.reduce { best, next ->
                                if (shouldPreferProfile(next, best)) next else best
                        }
                        group.filter { it.id != keep.id }.forEach { duplicate ->
                                db.aiProfileDao().deleteById(duplicate.id)
                                changedCount++
                                if (activeProfileId == duplicate.id) {
                                        activeProfileId = keep.id
                                }
                        }
                }

                val activeProfile =
                        if (activeProfileId > 0L) db.aiProfileDao().getById(activeProfileId) else null

                if (activeProfile != null && hasUsableApiKey(activeProfile.apiKey)) {
                        return changedCount
                }

                val fallback =
                        db.aiProfileDao()
                                .getAllList()
                                .filter { hasUsableApiKey(it.apiKey) }
                                .maxByOrNull { it.updatedAt }
                                ?: return changedCount

                applyProfileToLocalSettings(fallback)
                return changedCount + 1
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
                                        val remoteItems =
                                                fetchAllItems(
                                                        "ai_profiles",
                                                        "(user='$userId')",
                                                        sortParam = "-updatedAt",
                                                        perPage = 100
                                                )
                                        val sameNameRemote =
                                                remoteItems
                                                        .mapNotNull { toRemoteProfile(it) }
                                                        .filter {
                                                                profileNameKey(it.name) ==
                                                                        profileNameKey(profile.name)
                                                        }
                                                        .reduceOrNull { best, next ->
                                                                if (shouldPreferProfile(next, best)) next
                                                                else best
                                                        }

                                        if (sameNameRemote != null) {
                                                val keepRemoteApiKey =
                                                        !hasUsableApiKey(profile.apiKey) &&
                                                                hasUsableApiKey(sameNameRemote.apiKey)
                                                if (keepRemoteApiKey) {
                                                        Log.d(
                                                                "UserSyncRepository",
                                                                "pushAiProfile - Skip overwrite for ${profile.name} because remote has usable API key"
                                                        )
                                                } else {
                                                        val updateUrl =
                                                                "$pocketBaseUrl/api/collections/ai_profiles/records/${sameNameRemote.remoteId}"
                                                        val updateRequest =
                                                                buildAuthenticatedRequest(updateUrl)
                                                                        .patch(requestBody)
                                                                        .build()
                                                        executeRequest(updateRequest)
                                                }
                                                sameNameRemote.remoteId
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
                                        }
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

                                val selectedRemoteByName = LinkedHashMap<String, AiProfileEntity>()
                                for (item in items) {
                                        val remoteProfile = toRemoteProfile(item) ?: continue
                                        val nameKey = profileNameKey(remoteProfile.name)
                                        if (nameKey.isBlank()) continue
                                        val existing = selectedRemoteByName[nameKey]
                                        if (existing == null ||
                                                        shouldPreferProfile(
                                                                remoteProfile,
                                                                existing
                                                        )
                                        ) {
                                                selectedRemoteByName[nameKey] = remoteProfile
                                        }
                                }

                                val localProfiles = db.aiProfileDao().getAllList()
                                val localByRemoteId = mutableMapOf<String, AiProfileEntity>()
                                localProfiles.forEach { localProfile ->
                                        val remoteId = localProfile.remoteId
                                        if (!remoteId.isNullOrBlank()) {
                                                localByRemoteId[remoteId] = localProfile
                                        }
                                }
                                val localByName = mutableMapOf<String, AiProfileEntity>()
                                localProfiles.forEach { profile ->
                                        val nameKey = profileNameKey(profile.name)
                                        if (nameKey.isBlank()) return@forEach
                                        val existing = localByName[nameKey]
                                        if (existing == null || shouldPreferProfile(profile, existing)) {
                                                localByName[nameKey] = profile
                                        }
                                }

                                for ((nameKey, remoteProfile) in selectedRemoteByName) {
                                        val remoteId = remoteProfile.remoteId ?: continue
                                        val byRemote = localByRemoteId[remoteId]
                                        if (byRemote != null) {
                                                if (shouldPreferProfile(remoteProfile, byRemote)) {
                                                        val merged = remoteProfile.copy(id = byRemote.id)
                                                        db.aiProfileDao().insert(merged)
                                                        localByName[nameKey] = merged
                                                        localByRemoteId[remoteId] = merged
                                                        syncedCount++
                                                }
                                                continue
                                        }

                                        val byName = localByName[nameKey]
                                        if (byName != null) {
                                                if (shouldPreferProfile(remoteProfile, byName)) {
                                                        val merged = remoteProfile.copy(id = byName.id)
                                                        db.aiProfileDao().insert(merged)
                                                        localByName[nameKey] = merged
                                                        localByRemoteId[remoteId] = merged
                                                        syncedCount++
                                                } else if (byName.remoteId.isNullOrBlank()) {
                                                        val linked = byName.copy(remoteId = remoteId)
                                                        db.aiProfileDao().update(linked)
                                                        localByName[nameKey] = linked
                                                        localByRemoteId[remoteId] = linked
                                                        syncedCount++
                                                }
                                                continue
                                        }

                                        val insertedId = db.aiProfileDao().insert(remoteProfile)
                                        val inserted = remoteProfile.copy(id = insertedId)
                                        localByName[nameKey] = inserted
                                        localByRemoteId[remoteId] = inserted
                                        syncedCount++
                                }

                                syncedCount += cleanupDuplicateProfilesAndRepairActive()

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
                        try {
                                val token = accessToken()
                                if (token.isNullOrBlank()) {
                                        return@withContext false
                                }

                                val pocketBaseRoot = pocketBaseUrl.removeSuffix("/api")
                                val refreshedUserId = refreshAuthSessionIfPossible(pocketBaseRoot)
                                val userId =
                                        refreshedUserId
                                                ?: runCatching { getUserId() }.getOrNull()
                                if (userId.isNullOrBlank()) {
                                        return@withContext false
                                }
                                val payload =
                                        mutableMapOf<String, Any>(
                                                "appVersion" to report.appVersion,
                                                "androidVersion" to report.osVersion,
                                                "deviceModel" to
                                                        "${report.deviceManufacturer} ${report.deviceModel}"
                                                                .trim(),
                                                "stackTrace" to report.stacktrace.take(50000),
                                                "timestamp" to report.createdAt
                                        )
                                if (!report.message.isNullOrBlank()) {
                                        payload["message"] = report.message.take(4000)
                                }
                                payload["user"] = userId

                                val requestBody =
                                        gson.toJson(payload)
                                                .toRequestBody("application/json".toMediaType())
                                val url = "$pocketBaseUrl/api/collections/crash_reports/records"
                                val request =
                                        buildAuthenticatedRequest(url).post(requestBody).build()
                                executeRequest(request, reportError = false)
                                true
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pushCrashReport failed", e)
                                false
                        }
                }

        suspend fun pullAllProgress(): Int =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext 0
                                val items =
                                        fetchAllItems(
                                                "progress",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                var mergedCount = 0
                                for (item in items) {
                                        val bookId = item["bookId"] as? String ?: continue
                                        val locatorJson = item["locatorJson"] as? String ?: continue
                                        val remoteUpdatedAt = parseEpochMillis(item["updatedAt"])
                                        cacheProgress(bookId, locatorJson, remoteUpdatedAt)
                                        val merged =
                                                mergeRemoteProgressIntoLocalBook(
                                                        bookId = bookId,
                                                        locatorJson = locatorJson,
                                                        remoteUpdatedAt = remoteUpdatedAt
                                                )
                                        if (merged) {
                                                mergedCount++
                                        }
                                }
                                Log.d(
                                        "UserSyncRepository",
                                        "pullAllProgress - merged=$mergedCount records=${items.size}"
                                )
                                mergedCount
                        } catch (e: Exception) {
                                Log.e("UserSyncRepository", "pullAllProgress failed", e)
                                0
                        }
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
                                if (effectiveStoragePath == bookId) {
                                        // Placeholder path from "pocketbase://<bookId>".
                                        // It is not a downloadable file path.
                                        effectiveStoragePath = null
                                }
                                if (effectiveStoragePath.isNullOrBlank()) {
                                        effectiveStoragePath = storagePathFromPseudoUri(originalUri)
                                        if (effectiveStoragePath == bookId) {
                                                effectiveStoragePath = null
                                        }
                                }
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
                val userId = getUserId()

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
                        val uploadUrl = "$pocketBaseUrl/api/collections/books/records/$recordId"

                        for (field in BOOK_FILE_FIELD_CANDIDATES) {
                                val multipartBody =
                                        MultipartBody.Builder()
                                                .setType(MultipartBody.FORM)
                                                .addFormDataPart(
                                                        "updatedAt",
                                                        System.currentTimeMillis().toString()
                                                )
                                                .apply {
                                                        if (!userId.isNullOrBlank()) {
                                                                addFormDataPart("user", userId)
                                                        }
                                                }
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
                        ErrorReporter.report(
                                appContext,
                                "UserSyncRepository.tryUploadBookFile",
                                "Failed to upload book file for ${book.bookId}",
                                e
                        )
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
                for (field in BOOK_FILE_FIELD_CANDIDATES) {
                        val fileName = extractUploadedFileName(record, field)
                        if (!fileName.isNullOrBlank()) {
                                return "$recordId/$fileName"
                        }
                }
                return null
        }

        private fun storagePathFromPseudoUri(uri: String?): String? {
                if (uri.isNullOrBlank()) return null
                if (!uri.startsWith("pocketbase://")) return null
                return normalizeStoragePath(uri.removePrefix("pocketbase://"))
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

        private fun urlEncodeQueryValue(value: String): String =
                URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private fun withFileToken(url: String, token: String): String {
                if (Regex("[?&]token=").containsMatchIn(url)) return url
                val separator = if (url.contains("?")) "&" else "?"
                return "$url${separator}token=${urlEncodeQueryValue(token)}"
        }

        private suspend fun getProtectedFileToken(): String? {
                return try {
                        val tokenUrl = "$pocketBaseUrl/api/files/token"
                        val requestBody = "{}".toRequestBody("application/json".toMediaType())
                        val request = buildAuthenticatedRequest(tokenUrl).post(requestBody).build()
                        val responseBody = executeRequest(request, reportError = false)
                        val payload =
                                runCatching { gson.fromJson(responseBody, Map::class.java) as? Map<*, *> }
                                        .getOrNull()
                        payload?.get("token") as? String
                } catch (_: Exception) {
                        null
                }
        }

        private suspend fun probeRemoteFileState(url: String): RemoteFileState {
                return try {
                        var resolvedUrl = url
                        if (resolvedUrl.startsWith(pocketBaseUrl)) {
                                val fileToken = getProtectedFileToken()
                                if (!fileToken.isNullOrBlank()) {
                                        resolvedUrl = withFileToken(resolvedUrl, fileToken)
                                }
                        }

                        fun classify(code: Int): RemoteFileState =
                                when {
                                        code in 200..299 || code == 304 || code == 416 ->
                                                RemoteFileState.PRESENT
                                        code == 404 || code == 410 -> RemoteFileState.MISSING
                                        else -> RemoteFileState.UNKNOWN
                                }

                        val headCode =
                                httpClient
                                        .newCall(
                                                (
                                                                if (resolvedUrl.startsWith(
                                                                                pocketBaseUrl
                                                                )) {
                                                                        buildAuthenticatedRequest(
                                                                                resolvedUrl
                                                                        )
                                                                } else {
                                                                        Request.Builder().url(
                                                                                resolvedUrl
                                                                        )
                                                                }
                                                        )
                                                        .head()
                                                        .build()
                                        )
                                        .execute()
                                        .use { it.code }
                        if (headCode == 405 || headCode == 501) {
                                val getCode =
                                        httpClient
                                                .newCall(
                                                        (
                                                                        if (
                                                                                resolvedUrl.startsWith(
                                                                                        pocketBaseUrl
                                                                                )
                                                                        ) {
                                                                                buildAuthenticatedRequest(
                                                                                        resolvedUrl
                                                                                )
                                                                        } else {
                                                                                Request.Builder()
                                                                                        .url(
                                                                                                resolvedUrl
                                                                                        )
                                                                        }
                                                                )
                                                                .addHeader("Range", "bytes=0-0")
                                                                .get()
                                                                .build()
                                                )
                                                .execute()
                                                .use { it.code }
                                return classify(getCode)
                        }
                        classify(headCode)
                } catch (_: Exception) {
                        RemoteFileState.UNKNOWN
                }
        }

        private suspend fun downloadRemoteFile(url: String, target: File): Boolean {
                return try {
                        target.parentFile?.mkdirs()
                        var resolvedUrl = url
                        if (resolvedUrl.startsWith(pocketBaseUrl)) {
                                val fileToken = getProtectedFileToken()
                                if (!fileToken.isNullOrBlank()) {
                                        resolvedUrl = withFileToken(resolvedUrl, fileToken)
                                }
                        }
                        val requestBuilder =
                                if (resolvedUrl.startsWith(pocketBaseUrl)) {
                                        buildAuthenticatedRequest(resolvedUrl)
                                } else {
                                        Request.Builder().url(resolvedUrl)
                                }
                        val request = requestBuilder.get().build()
                        httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                        Log.w(
                                                "UserSyncRepository",
                                                "downloadRemoteFile failed code=${response.code} url=$resolvedUrl"
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
                        ErrorReporter.report(
                                appContext,
                                "UserSyncRepository.downloadRemoteFile",
                                "Failed to download remote file: $url",
                                e
                        )
                        false
                }
        }

        private fun parseEpochMillis(value: Any?): Long {
                return when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull() ?: System.currentTimeMillis()
                        else -> System.currentTimeMillis()
                }
        }

        private fun truncateForRemoteText(raw: String, maxChars: Int): String {
                if (raw.length <= maxChars) return raw
                val marker = "\n\n[truncated for sync]"
                val keep = (maxChars - marker.length).coerceAtLeast(0)
                if (keep == 0) return raw.take(maxChars)
                return raw.take(keep) + marker
        }

        private fun normalizeAiNoteMessagesForSync(
                note: AiNoteEntity,
                originalText: String,
                aiResponse: String,
                maxChars: Int
        ): String {
                val raw = note.messages
                if (raw.length <= maxChars) return raw

                val compact = mutableListOf<Map<String, String>>()
                val original = originalText.trim()
                val response = aiResponse.trim()

                if (original.isNotBlank()) {
                        compact +=
                                mapOf(
                                        "role" to "user",
                                        "content" to
                                                truncateForRemoteText(
                                                        original,
                                                        AI_NOTE_COMPACT_MESSAGE_CHARS
                                                )
                                )
                }
                if (response.isNotBlank()) {
                        compact +=
                                mapOf(
                                        "role" to "assistant",
                                        "content" to
                                                truncateForRemoteText(
                                                        response,
                                                        AI_NOTE_COMPACT_MESSAGE_CHARS
                                                )
                                )
                }
                if (compact.isEmpty()) {
                        compact +=
                                mapOf(
                                        "role" to "assistant",
                                        "content" to
                                                truncateForRemoteText(
                                                        raw.trim().ifBlank { "No content" },
                                                        AI_NOTE_COMPACT_MESSAGE_CHARS
                                                )
                                )
                }
                val compactJson = gson.toJson(compact)
                if (compactJson.length <= maxChars) return compactJson
                return gson.toJson(
                        listOf(
                                mapOf(
                                        "role" to "assistant",
                                        "content" to
                                                truncateForRemoteText(
                                                        "Conversation truncated for sync size limit.",
                                                        AI_NOTE_COMPACT_MESSAGE_CHARS
                                                )
                                )
                        )
                )
        }

        private fun resolveOriginalTextForSync(note: AiNoteEntity): String {
                val direct = note.originalText?.trim().orEmpty()
                if (direct.isNotBlank()) return direct
                return extractMessageContentByRole(note.messages, role = "user").orEmpty()
        }

        private fun resolveAiResponseForSync(note: AiNoteEntity): String {
                val direct = note.aiResponse?.trim().orEmpty()
                if (direct.isNotBlank()) return direct
                return extractMessageContentByRole(note.messages, role = "assistant").orEmpty()
        }

        private fun extractMessageContentByRole(messagesJson: String, role: String): String? {
                if (messagesJson.isBlank()) return null
                val messages =
                        runCatching { gson.fromJson(messagesJson, List::class.java) as? List<*> }
                                .getOrNull()
                                ?: return null
                for (idx in messages.indices.reversed()) {
                        val obj = messages[idx] as? Map<*, *> ?: continue
                        val msgRole = (obj["role"] as? String)?.trim()?.lowercase(Locale.ROOT)
                        if (msgRole != role) continue
                        val content = (obj["content"] as? String)?.trim()
                        if (!content.isNullOrBlank()) {
                                return content
                        }
                }
                return null
        }

        private suspend fun mergeRemoteProgressIntoLocalBook(
                bookId: String,
                locatorJson: String,
                remoteUpdatedAt: Long
        ): Boolean {
                val local = db.bookDao().getByIds(listOf(bookId)).firstOrNull() ?: return false
                val localHasProgress = !local.lastLocatorJson.isNullOrBlank()
                val remoteIsNewerOrEqual = remoteUpdatedAt >= local.lastOpenedAt
                val shouldApply = !localHasProgress || remoteIsNewerOrEqual
                if (!shouldApply) {
                        return false
                }
                if (local.lastLocatorJson == locatorJson && local.lastOpenedAt >= remoteUpdatedAt) {
                        return false
                }
                val mergedTime = maxOf(local.lastOpenedAt, remoteUpdatedAt)
                db.bookDao().updateProgress(bookId, locatorJson, mergedTime)
                return true
        }

        private fun progressKey(bookId: String) = "progress_$bookId"
        private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

        private fun accessToken(): String? = tokenManager.getAccessToken()
}
