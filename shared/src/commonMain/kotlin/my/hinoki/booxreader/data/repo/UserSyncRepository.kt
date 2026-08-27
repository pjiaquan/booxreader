package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.BookProgressUpdate
import my.hinoki.booxreader.data.core.CrashReport
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.db.clearAllTablesCompat
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.auth.TokenProvider
import my.hinoki.booxreader.data.core.Logger
import my.hinoki.booxreader.data.core.Reporter
import my.hinoki.booxreader.data.settings.KeyValueStorage
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.patch
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import my.hinoki.booxreader.data.remote.createApiClient
import kotlin.concurrent.Volatile
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.platform.ioDispatcher
import my.hinoki.booxreader.data.platform.platformFiles

// Data class for PocketBase list responses
@kotlinx.serialization.Serializable
data class PocketBaseListResponse(
        val items: List<kotlinx.serialization.json.JsonObject>,
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
        tokenProvider: TokenProvider,
        baseUrl: String? = null,
        prefs: KeyValueStorage,
        syncPrefs: KeyValueStorage,
        reporter: Reporter,
        logger: Logger
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

        private val prefs = prefs
        private val syncPrefs = syncPrefs
        private val reporter = reporter
        private val logger = logger
        private val tokenManager = tokenProvider
        private val db = AppDatabase.get()
        private val io = ioDispatcher
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val pocketBaseUrl = (baseUrl ?: tokenManager.getBackendUrl()).trimEnd('/')

        /** Ktor client（Phase 2 漸進轉換；手動加 Bearer，行為與舊版一致）。 */
        private val ktorClient = createApiClient()

        /** 對後端主機的請求加上 Bearer header（Ktor 版）。 */
        private fun io.ktor.client.request.HttpRequestBuilder.authIfBackend(resolvedUrl: String) {
                if (resolvedUrl.startsWith(pocketBaseUrl)) {
                        val token = tokenManager.getAccessToken() ?: ""
                        if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
        }

        @Volatile private var cachedUserId: String? = null

        // --- Helper Methods ---
        private suspend fun fetchAllItems(
                collection: String,
                filterParam: String,
                sortParam: String? = null,
                perPage: Int = 100
        ): List<kotlinx.serialization.json.JsonObject> =
                withContext(io) {
                        val items = mutableListOf<kotlinx.serialization.json.JsonObject>()
                        var page = 1
                        while (true) {
                                val sortQuery =
                                        if (sortParam.isNullOrBlank()) "" else "&sort=$sortParam"
                                val url =
                                        "$pocketBaseUrl/api/collections/$collection/records?filter=$filterParam&page=$page&perPage=$perPage$sortQuery"
                                                                val responseBody = executeBackendRequest(url)
                                val response =
                                        json.decodeFromString<PocketBaseListResponse>(responseBody)
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
                                                mapToJsonString(
                                                        mapOf(
                                                                "to" to listOf(email),
                                                                "subject" to subject,
                                                                "html" to htmlBody,
                                                                "text" to body
                                                        )
                                                )
                                        val response =
                                                ktorClient.post("$pocketBaseRoot/api/mails/send") {
                                                        header(
                                                                "Authorization",
                                                                "Bearer ${tokenManager.getAccessToken().orEmpty()}"
                                                        )
                                                        contentType(ContentType.Application.Json)
                                                        setBody(payload)
                                                }
                                        val responseBody = response.bodyAsText().trim()
                                        directStatusCode = response.status.value
                                        if (response.status.isSuccess()) {
                                                return@withContext CheckResult(
                                                        true,
                                                        "sent via /api/mails/send"
                                                )
                                        }
                                        lastError =
                                                "direct mail failed (${response.status.value})"
                                        if (responseBody.isNotEmpty()) {
                                                lastError += ": $responseBody"
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
                                                        mapToJsonString(
                                                                mapOf(
                                                                        "toEmail" to email,
                                                                        "subject" to subject,
                                                                        "body" to body
                                                                )
                                                        )
                                                val response =
                                                        ktorClient.post(
                                                                "$pocketBaseRoot$routePath"
                                                        ) {
                                                                header(
                                                                        "Authorization",
                                                                        "Bearer ${tokenManager.getAccessToken().orEmpty()}"
                                                                )
                                                                contentType(ContentType.Application.Json)
                                                                setBody(payload)
                                                        }
                                                val responseBody = response.bodyAsText().trim()
                                                customRouteStatusCodes += response.status.value
                                                if (response.status.isSuccess()) {
                                                        return@withContext CheckResult(
                                                                true,
                                                                "sent via $routePath"
                                                        )
                                                }
                                                lastError =
                                                        "custom route $routePath failed (${response.status.value})"
                                                if (responseBody.isNotEmpty()) {
                                                        lastError += ": $responseBody"
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
                                        mapToJsonString(
                                                mapOf(
                                                        "user" to userId,
                                                        "toEmail" to email,
                                                        "subject" to subject,
                                                        "body" to body,
                                                        "category" to "ai_note_daily_summary",
                                                        "status" to "pending"
                                                )
                                        )
                                try {
                                        val response =
                                                ktorClient.post(
                                                        "$pocketBaseRoot/api/collections/$collection/records"
                                                ) {
                                                        header(
                                                                "Authorization",
                                                                "Bearer ${tokenManager.getAccessToken().orEmpty()}"
                                                        )
                                                        contentType(ContentType.Application.Json)
                                                        setBody(queuePayload)
                                                }
                                        val responseBody = response.bodyAsText().trim()
                                        queueStatusCodes += response.status.value
                                        if (response.status.isSuccess()) {
                                                return@withContext CheckResult(
                                                        true,
                                                        "queued via $collection"
                                                )
                                        }
                                        val queueError =
                                                "queue $collection failed (${response.status.value})"
                                        if (responseBody.isNotEmpty()) {
                                                lastError = "$queueError: $responseBody"
                                        } else {
                                                lastError = queueError
                                        }
                                        if (response.status.value != 404 &&
                                                        firstNon404QueueError == null
                                        ) {
                                                firstNon404QueueError = lastError
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

        /**
         * PocketBase auth tokens can expire/rotate. Refresh once before mail dispatch so
         * routerAdd/custom routes can resolve e.auth consistently.
         */
        private suspend fun refreshAuthSessionIfPossible(pocketBaseRoot: String): String? {
                val token = tokenManager.getAccessToken()?.trim().orEmpty()
                if (token.isBlank()) return null

                return try {
                        val response =
                                ktorClient.post("$pocketBaseRoot/api/collections/users/auth-refresh") {
                                        header("Authorization", "Bearer $token")
                                        contentType(ContentType.Application.Json)
                                        setBody("{}")
                                }
                        if (!response.status.isSuccess()) {
                                logger.w(
                                        "UserSyncRepository",
                                        "refreshAuthSessionIfPossible failed: ${response.status.value}"
                                )
                                return null
                        }
                        val payload =
                                runCatching {
                                                json.decodeFromString<kotlinx.serialization.json.JsonObject>(
                                                        response.bodyAsText()
                                                )
                                        }
                                        .getOrNull()
                                ?: return@refreshAuthSessionIfPossible null
                        val refreshedToken =
                                        payload["token"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                                if (refreshedToken.isNotBlank()) {
                                        tokenManager.saveAccessToken(refreshedToken)
                                }
                                val refreshedUserId =
                                        (payload["record"] as? kotlinx.serialization.json.JsonObject)
                                                ?.get("id")
                                                ?.jsonPrimitive
                                                ?.contentOrNull
                                                ?.trim()
                                                .orEmpty()
                                if (refreshedUserId.isNotBlank()) {
                                        cachedUserId = refreshedUserId
                                }
                                refreshedUserId.ifBlank { null }
                } catch (e: Exception) {
                        logger.w("UserSyncRepository", "refreshAuthSessionIfPossible error", e)
                        null
                }
        }

        /**
         * 執行後端請求（自動加 Bearer header）並回傳 body 字串（Ktor 版）。
         * 保留原本 executeRequest 的 401 清除 session 與錯誤回報行為。
         */
        private suspend fun executeBackendRequest(
                url: String,
                reportError: Boolean = true,
                configure: HttpRequestBuilder.() -> Unit = {}
        ): String {
                val response =
                        ktorClient.request(url) {
                                val token = tokenManager.getAccessToken() ?: ""
                                if (token.isNotBlank()) header("Authorization", "Bearer $token")
                                configure()
                        }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) {
                        val message = "Request failed: ${response.status.value} $url"
                        logger.e("UserSyncRepository", message)

                        if (response.status.value == 401) {
                                logger.w("UserSyncRepository", "Received 401 Unauthorized, clearing local session")
                                tokenManager.clearTokens()
                                kotlinx.coroutines.runBlocking {
                                        try {
                                                db.userDao().clearAllUsers()
                                        } catch (e: Exception) {
                                                logger.e("UserSyncRepository", "Failed to clear users on 401", e)
                                        }
                                }
                                cachedUserId = null
                        }

                        if (reportError) {
                                reporter.report("UserSyncRepository.executeRequest",
                                        message
                                )
                        }
                        throw Exception("PocketBase request failed: ${response.status.value}")
                }
                return body
        }


        private fun longValue(value: Any?): Long {
                return when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull() ?: 0L
                        is kotlinx.serialization.json.JsonPrimitive ->
                                value.content.toLongOrNull() ?: 0L
                        else -> 0L
                }
        }

        private fun parseMagicTags(raw: Any?, fallback: List<MagicTag>): List<MagicTag> {
                if (raw == null) return fallback

                return runCatching {
                                when (raw) {
                                        is kotlinx.serialization.json.JsonPrimitive ->
                                                json.decodeFromString<List<MagicTag>>(raw.content)
                                        else -> json.decodeFromString<List<MagicTag>>(raw.toString())
                                } ?: fallback
                        }
                        .getOrElse {
                                logger.w("UserSyncRepository", "parseMagicTags failed, using fallback", it)
                                fallback
                        }
        }

        private fun latestSettingsRecord(items: List<kotlinx.serialization.json.JsonObject>): kotlinx.serialization.json.JsonObject? {
                return items.maxByOrNull { longValue(it["updatedAt"]) }
        }

        private fun escapeHtmlForEmail(raw: String): String {
                return raw
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
        }

        /** Parse settings from PocketBase JSON response. */
        private fun parseSettingsFromJson(
                json: kotlinx.serialization.json.JsonObject,
                fallbackMagicTags: List<MagicTag>
        ): ReaderSettings {
                return ReaderSettings(
                        pageTapEnabled = json["pageTapEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        pageSwipeEnabled = json["pageSwipeEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        contrastMode = (json["contrastMode"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 0,
                        convertToTraditionalChinese =
                                json["convertToTraditionalChinese"]?.jsonPrimitive?.booleanOrNull ?: true,
                        serverBaseUrl = json["serverBaseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                        exportToCustomUrl = json["exportToCustomUrl"]?.jsonPrimitive?.booleanOrNull ?: false,
                        exportCustomUrl = json["exportCustomUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                        exportToLocalDownloads = json["exportToLocalDownloads"]?.jsonPrimitive?.booleanOrNull
                                        ?: false,
                        apiKey = json["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                        aiModelName = json["aiModelName"]?.jsonPrimitive?.contentOrNull ?: "deepseek-chat",
                        aiSystemPrompt = json["aiSystemPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                        aiUserPromptTemplate = json["aiUserPromptTemplate"]?.jsonPrimitive?.contentOrNull ?: "%s",
                        temperature = json["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
                        maxTokens = (json["maxTokens"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 4096,
                        topP = json["topP"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                        frequencyPenalty = json["frequencyPenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        presencePenalty = json["presencePenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        assistantRole = json["assistantRole"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                        enableGoogleSearch = json["enableGoogleSearch"]?.jsonPrimitive?.booleanOrNull ?: true,
                        useStreaming = json["useStreaming"]?.jsonPrimitive?.booleanOrNull ?: false,
                        pageAnimationEnabled = json["pageAnimationEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        showPageIndicator = json["showPageIndicator"]?.jsonPrimitive?.booleanOrNull ?: true,
                        autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
                        dailySummaryEmailEnabled =
                                json["dailySummaryEmailEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                        dailySummaryEmailHour = (json["dailySummaryEmailHour"]?.jsonPrimitive?.doubleOrNull)?.toInt()
                                        ?: 21,
                        dailySummaryEmailMinute =
                                (json["dailySummaryEmailMinute"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 0,
                        dailySummaryEmailTo = json["dailySummaryEmailTo"]?.jsonPrimitive?.contentOrNull ?: "",
                        language = json["language"]?.jsonPrimitive?.contentOrNull ?: "system",
                        activeProfileId = longValue(json["activeProfileId"]).takeIf { it != 0L } ?: -1L,
                        updatedAt = longValue(json["updatedAt"]).takeIf { it > 0L }
                                        ?: currentEpochMillis(),
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
                                                        logger.w(
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
                                        logger.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - No remote settings found"
                                        )
                                        return@withContext null
                                }

                                val remoteSettings = latestSettingsRecord(items) ?: return@withContext null
                                val remoteUpdatedAt = longValue(remoteSettings["updatedAt"])
                                val localSettings = ReaderSettings.fromStorage(prefs)

                                if (remoteUpdatedAt > localSettings.updatedAt) {
                                        // Remote is newer, update local
                                        val updated =
                                                parseSettingsFromJson(
                                                        remoteSettings,
                                                        fallbackMagicTags = emptyList()
                                                )
                                        updated.saveTo(prefs)
                                        logger.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - Settings pulled and saved"
                                        )
                                        updated
                                } else {
                                        logger.d(
                                                "UserSyncRepository",
                                                "pullSettingsIfNewer - Local settings are up to date"
                                        )
                                        null
                                }
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullSettingsIfNewer failed", e)
                                null
                        }
                }

        /** Push current settings to PocketBase. Creates a new record or updates existing one. */
        suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromStorage(prefs)) =
                withContext(io) {
                        try {
                                val userId =
                                        getUserId()
                                                ?: run {
                                                        logger.w(
                                                                "UserSyncRepository",
                                                                "pushSettings - No user logged in"
                                                        )
                                                        return@withContext
                                                }

                                // First check if settings record exists
                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/settings/records?filter=(user='$userId')"
                                                                val checkBody = executeBackendRequest(checkUrl)
                                val checkResponse =
                                        json.decodeFromString<PocketBaseListResponse>(checkBody)

                                val existingSettingsRecord = latestSettingsRecord(checkResponse.items)
                                val magicTagsForUpload =
                                        settings.magicTags.map { tag ->
                                                kotlinx.serialization.json.buildJsonObject {
                                                        put("id", tag.id)
                                                        put("label", tag.label)
                                                        put("content", tag.content)
                                                        put("description", tag.description)
                                                        put("role", tag.role)
                                                }
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
                                                "updatedAt" to currentEpochMillis()
                                        )
                                val settingsDataWithMagicTags =
                                        baseSettingsData + ("magicTags" to magicTagsForUpload)

                                fun toBody(data: Map<String, Any>): String =
                                        buildJsonObject {
                                                data.forEach { (k, v) -> put(k, v.toJsonElement()) }
                                        }.toString()

                                if (checkResponse.items.isNotEmpty()) {
                                        // Update existing record
                                        val recordId =
                                                existingSettingsRecord?.get("id")?.jsonPrimitive?.contentOrNull
                                                        ?: return@withContext
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/settings/records/$recordId"
                                        try {
                                                executeBackendRequest(updateUrl) {
                                                    method = HttpMethod.Patch
                                                    contentType(ContentType.Application.Json)
                                                    setBody(toBody(settingsDataWithMagicTags))
                                                }
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings updated with magicTags"
                                                )
                                        } catch (e: Exception) {
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - update with magicTags failed, retrying without magicTags",
                                                        e
                                                )
                                                executeBackendRequest(updateUrl) {
                                                    method = HttpMethod.Patch
                                                    contentType(ContentType.Application.Json)
                                                    setBody(toBody(baseSettingsData))
                                                }
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings updated without magicTags fallback"
                                                )
                                        }
                                } else {
                                        // Create new record
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/settings/records"
                                        try {
                                                executeBackendRequest(createUrl) {
                                                    method = HttpMethod.Post
                                                    contentType(ContentType.Application.Json)
                                                    setBody(toBody(settingsDataWithMagicTags))
                                                }
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings created with magicTags"
                                                )
                                        } catch (e: Exception) {
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - create with magicTags failed, retrying without magicTags",
                                                        e
                                                )
                                                executeBackendRequest(createUrl) {
                                                    method = HttpMethod.Post
                                                    contentType(ContentType.Application.Json)
                                                    setBody(toBody(baseSettingsData))
                                                }
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "pushSettings - Settings created without magicTags fallback"
                                                )
                                        }
                                }
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushSettings failed", e)
                        }
                }

        fun getCachedProgress(bookId: String): String? {
                return prefs.getString(progressKey(bookId))
        }

        fun cacheProgress(
                bookId: String,
                locatorJson: String,
                updatedAt: Long = currentEpochMillis()
        ) {
                prefs.putString(progressKey(bookId), locatorJson)
                prefs.putLong(progressTimestampKey(bookId), updatedAt)
        }
        // --- Progress Sync ---

        suspend fun pullProgress(bookId: String): String? =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext null

                                val url =
                                        "$pocketBaseUrl/api/collections/progress/records?filter=(user='$userId'%26%26bookId='$bookId')"
                                                                val responseBody = executeBackendRequest(url)

                                val response =
                                        json.decodeFromString<PocketBaseListResponse>(responseBody)
                                if (response.items.isEmpty()) {
                                        logger.d(
                                                "UserSyncRepository",
                                                "pullProgress - No remote progress found for $bookId"
                                        )
                                        return@withContext null
                                }

                                val item = response.items[0]
                                val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull
                                val remoteUpdatedAt = parseEpochMillis(item["updatedAt"])
                                if (!locatorJson.isNullOrBlank()) {
                                        cacheProgress(bookId, locatorJson, remoteUpdatedAt)
                                        mergeRemoteProgressIntoLocalBook(
                                                bookId = bookId,
                                                locatorJson = locatorJson,
                                                remoteUpdatedAt = remoteUpdatedAt
                                        )
                                }
                                logger.d(
                                        "UserSyncRepository",
                                        "pullProgress - Progress pulled for $bookId"
                                )
                                locatorJson
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullProgress failed for $bookId", e)
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
                                                                val checkBody = executeBackendRequest(checkUrl)
                                val checkResponse =
                                        json.decodeFromString<PocketBaseListResponse>(checkBody)

                                val progressData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to bookId,
                                                "bookTitle" to (bookTitle ?: ""),
                                                "locatorJson" to locatorJson,
                                                "updatedAt" to currentEpochMillis()
                                        )

                                val requestBody =
                                        mapToJsonString(progressData)
                                                

                                if (checkResponse.items.isNotEmpty()) {
                                        // Update existing record
                                        val recordId =
                                                checkResponse.items[0]["id"]?.jsonPrimitive?.contentOrNull
                                                        ?: return@withContext
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/progress/records/$recordId"
                                        executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        logger.d(
                                                "UserSyncRepository",
                                                "pushProgress - Progress updated for $bookId"
                                        )
                                } else {
                                        // Create new record
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/progress/records"
                                        executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        logger.d(
                                                "UserSyncRepository",
                                                "pushProgress - Progress created for $bookId"
                                        )
                                }
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushProgress failed for $bookId", e)
                        }
                }

        suspend fun pushBook(
                book: BookEntity,
                uploadFile: Boolean = false
        ): Boolean =
                withContext(io) {
                        try {
                                val userId = getUserId() ?: return@withContext false
                                val now = currentEpochMillis()
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
                                        mapToJsonString(bookData)
                                                

                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/books/records?filter=(user='$userId'%26%26bookId='${book.bookId}')&perPage=1"
                                                                val checkBody = executeBackendRequest(checkUrl)
                                val checkResponse =
                                        json.decodeFromString<PocketBaseListResponse>(checkBody)
                                val existingItem = checkResponse.items.firstOrNull()
                                val remoteHasFilePath =
                                        !resolveStoragePathFromRecord(existingItem).isNullOrBlank()
                                var remoteDeleted = false
                                var recordId = existingItem?.get("id")?.jsonPrimitive?.contentOrNull

                                if (existingItem != null) {
                                        // Bug 3 fix: use parseEpochMillis() instead of Double cast
                                        // which silently fails when PocketBase returns updatedAt as a String.
                                        val remoteUpdatedAt = parseEpochMillis(existingItem["updatedAt"])
                                        remoteDeleted = existingItem["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                                        val needsFileBackfill =
                                                uploadFile &&
                                                        
                                                        (!remoteHasFilePath || remoteDeleted)
                                        if (!book.deleted &&
                                                        !remoteDeleted &&
                                                        remoteUpdatedAt > payloadUpdatedAt &&
                                                        !needsFileBackfill
                                        ) {
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "pushBook - Skip stale local update for ${book.bookId}"
                                                )
                                                return@withContext true
                                        }

                                        val safeRecordId = recordId ?: return@withContext false
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/books/records/$safeRecordId"
                                        executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/books/records"
                                        val createBody =
                                                try {
                                                        executeBackendRequest(createUrl) {
                                                                method = HttpMethod.Post
                                                                contentType(ContentType.Application.Json)
                                                                setBody(requestBody)
                                                        }
                                                } catch (e: Exception) {
                                                if (e.message?.contains("400") == true && e.message?.contains("sql: no rows in result set") == true) {
                                                        logger.w("UserSyncRepository", "pushBook - stale user ID detected, refreshing auth session and retrying")
                                                        val pocketBaseRoot = pocketBaseUrl.removeSuffix("/api")
                                                        val refreshedUserId = refreshAuthSessionIfPossible(pocketBaseRoot)
                                                        if (!refreshedUserId.isNullOrBlank() && refreshedUserId != userId) {
                                                                // Update the payload using the new valid ID
                                                                val mutableData = bookData.toMutableMap()
                                                                mutableData["user"] = refreshedUserId
                                                                val retryBody = mapToJsonString(mutableData)
                                                                executeBackendRequest(createUrl) {
                                                                    method = HttpMethod.Post
                                                                    contentType(ContentType.Application.Json)
                                                                    setBody(retryBody)
                                                                }
                                                        } else {
                                                                throw e
                                                        }
                                                } else {
                                                        throw e
                                                }
                                        }
                                        val created =
                                                json.parseToJsonElement(createBody).jsonObject
                                        recordId = created["id"]?.jsonPrimitive?.contentOrNull
                                }

                                if (uploadFile &&
                                                
                                                (!remoteHasFilePath || remoteDeleted)
                                ) {
                                        val uploadStoragePath =
                                                tryUploadBookFile(
                                                        recordId = recordId,
                                                        book = book,
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

                                logger.d("UserSyncRepository", "pushBook - Synced book ${book.bookId}")
                                true
                        } catch (e: Exception) {
                                logger.e(
                                        "UserSyncRepository",
                                        "pushBook failed for ${book.bookId}",
                                        e
                                )
                                false
                        }
                }

        suspend fun ensureRemoteBookFilePresent(book: BookEntity): Boolean =
                withContext(io) {
                        try {
                                if (book.deleted) return@withContext true
                                val userId = getUserId() ?: return@withContext false
                                val remoteRecord = fetchBookRecord(userId, book.bookId)
                                if (remoteRecord == null) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                        )
                                }

                                val recordId = remoteRecord["id"]?.jsonPrimitive?.contentOrNull
                                val remoteDeleted = remoteRecord["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                                val storagePath = resolveStoragePathFromRecord(remoteRecord)

                                if (recordId.isNullOrBlank() ||
                                                remoteDeleted ||
                                                storagePath.isNullOrBlank()
                                ) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                        )
                                }

                                val remoteUrl = buildDownloadUrl(storagePath, recordId)
                                if (remoteUrl.isNullOrBlank()) {
                                        return@withContext pushBook(
                                                book,
                                                uploadFile = true,
                                        )
                                }

                                when (probeRemoteFileState(remoteUrl)) {
                                        RemoteFileState.PRESENT -> true
                                        RemoteFileState.UNKNOWN -> {
                                                logger.d(
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
                                                        )
                                                if (uploadedStoragePath.isNullOrBlank()) {
                                                        logger.w(
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
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "ensureRemoteBookFilePresent - Reuploaded missing file for ${book.bookId}"
                                                )
                                                true
                                        }
                                }
                        } catch (e: Exception) {
                                logger.e(
                                        "UserSyncRepository",
                                        "ensureRemoteBookFilePresent failed for ${book.bookId}",
                                        e
                                )
                                reporter.report("UserSyncRepository.ensureRemoteBookFilePresent",
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
                                val now = currentEpochMillis()
                                val deleteData =
                                        mapOf(
                                                "user" to userId,
                                                "bookId" to bookId,
                                                "deleted" to true,
                                                "deletedAt" to now,
                                                "updatedAt" to now
                                        )
                                val requestBody =
                                        mapToJsonString(deleteData)
                                                

                                val checkUrl =
                                        "$pocketBaseUrl/api/collections/books/records?filter=(user='$userId'%26%26bookId='$bookId')&perPage=1"
                                                                val checkBody = executeBackendRequest(checkUrl)
                                val checkResponse =
                                        json.decodeFromString<PocketBaseListResponse>(checkBody)

                                if (checkResponse.items.isNotEmpty()) {
                                        val recordId =
                                                checkResponse.items[0]["id"]?.jsonPrimitive?.contentOrNull
                                                        ?: return@withContext false
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/books/records/$recordId"
                                        executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/books/records"
                                        executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                }

                                logger.d(
                                        "UserSyncRepository",
                                        "softDeleteBook - Synced deletion for $bookId"
                                )
                                true
                        } catch (e: Exception) {
                                logger.e(
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

                                val syncResults = coroutineScope {
                                        localBooks.map { book ->
                                                async {
                                                        pushBook(
                                                                book,
                                                                uploadFile = true
                                                        )
                                                }
                                        }.awaitAll()
                                }

                                var syncedCount = syncResults.count { it }

                                val pendingDeletes = db.bookDao().getPendingDeletes()
                                val successfullyDeletedBookIds = mutableListOf<String>()
                                for (deletedBook in pendingDeletes) {
                                        val deleted = softDeleteBook(deletedBook.bookId)
                                        if (deleted) {
                                                successfullyDeletedBookIds.add(deletedBook.bookId)
                                        }
                                }
                                if (successfullyDeletedBookIds.isNotEmpty()) {
                                        successfullyDeletedBookIds.chunked(900).forEach { chunk ->
                                                db.bookDao().deleteByIds(chunk)
                                        }
                                        syncedCount += successfullyDeletedBookIds.size
                                }

                                logger.d(
                                        "UserSyncRepository",
                                        "pushLocalBooks - Synced $syncedCount local books/deletes"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushLocalBooks failed", e)
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


                                val deletedBookIds = mutableListOf<String>()

                                // Pre-fetch existing books to avoid N+1 queries
                                val allBookIds = items.mapNotNull { it["bookId"]?.jsonPrimitive?.contentOrNull }.distinct()
                                val cachedBooks = mutableMapOf<String, BookEntity>()
                                allBookIds.chunked(900).forEach { chunk ->
                                        cachedBooks.putAll(db.bookDao().getByIds(chunk).associateBy { it.bookId })
                                }

                                val booksToInsert = mutableListOf<BookEntity>()

                                for (item in items) {
                                        val bookId = item["bookId"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val deleted = item["deleted"]?.jsonPrimitive?.booleanOrNull ?: false

                                        if (deleted) {
                                                deletedBookIds.add(bookId)
                                                continue
                                        }

                                        val title = item["title"]?.jsonPrimitive?.contentOrNull
                                        val resolvedStoragePath = resolveStoragePathFromRecord(item)


                                        // Check if book exists locally
                                        val existingBook = cachedBooks[bookId]

                                        if (existingBook == null) {
                                                // New book from cloud.
                                                // Use remote books.updatedAt as a proxy for "when was this added" so the
                                                // book appears in the recent list. For unread books, there is no progress
                                                // record yet, so this is the only timestamp available.
                                                // Note: lastOpenedAt for READ books is authoritative in the 'progress'
                                                // collection and is corrected by pullAllProgress() after this.
                                                val remoteAddedAt = parseEpochMillis(item["updatedAt"])
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
                                                                lastOpenedAt = remoteAddedAt,
                                                                deleted = false
                                                        )
                                                booksToInsert.add(newBook)
                                                cachedBooks[bookId] = newBook
                                                syncedCount++
                                        } else {
                                                val remoteFileUri =
                                                        resolvedStoragePath
                                                                ?.takeIf { it.isNotBlank() }
                                                                ?.let { "pocketbase://$it" }
                                                val shouldUpdateTitle =
                                                        title != null && existingBook.title != title
                                                // Bug 2 fix: Only update fileUri, never lastOpenedAt.
                                                // lastOpenedAt is the reading timestamp; it must only
                                                // come from the 'progress' collection via pullAllProgress().
                                                // books.updatedAt changes on every metadata push, causing
                                                // wrong recent-list ordering across devices.
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
                                                        booksToInsert.add(updatedBook)
                                                        cachedBooks[bookId] = updatedBook
                                                        syncedCount++
                                                }
                                        }
                                }

                                if (booksToInsert.isNotEmpty()) {
                                        db.withTransactionCompat {
                                                booksToInsert.chunked(900).forEach { chunk ->
                                                        db.bookDao().insertBatch(chunk)
                                                }
                                        }
                                }

                                if (deletedBookIds.isNotEmpty()) {
                                        deletedBookIds.chunked(900).forEach { chunk ->
                                                db.bookDao().deleteByIds(chunk)
                                        }
                                }

                                logger.d("UserSyncRepository", "pullBooks - Synced $syncedCount books")
                                syncedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullBooks failed", e)
                                0
                        }
                }

        suspend fun downloadPendingRemoteBooks(): Int =
                withContext(io) {
                        try {
                                val books = db.bookDao().getAllBooks()
                                var downloadedCount = 0
                                for (book in books) {
                                        if (!book.fileUri.startsWith("pocketbase://")) {
                                                continue
                                        }
                                        val cachedPath = localBookCacheFile(book.bookId)
                                        // Bug 5 fix: check for valid ZIP (EPUB) magic bytes rather
                                        // than just length > 0. A partial download leaves a non-zero
                                        // file that would be accepted silently otherwise.
                                        if (platformFiles().exists(cachedPath) &&
                                                isValidEpubFile(cachedPath)
                                        ) {
                                                continue
                                        }
                                        // Delete any corrupt/partial file so ensureBookFileAvailable
                                        // will re-download it cleanly.
                                        if (platformFiles().exists(cachedPath)) {
                                                platformFiles().delete(cachedPath)
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "downloadPendingRemoteBooks - Deleted corrupt cache for ${book.bookId}"
                                                )
                                        }
                                        val storagePath =
                                                book.fileUri
                                                        .removePrefix("pocketbase://")
                                                        .takeIf { it.contains("/") }
                                        val localUri =
                                                ensureBookFileAvailable(
                                                        book.bookId,
                                                        storagePath = storagePath,
                                                        originalUri = book.fileUri
                                                )
                                        if (localUri != null) {
                                                downloadedCount++
                                                logger.d(
                                                        "UserSyncRepository",
                                                        "downloadPendingRemoteBooks - Downloaded ${book.bookId}"
                                                )
                                        }
                                }
                                logger.d(
                                        "UserSyncRepository",
                                        "downloadPendingRemoteBooks - Downloaded $downloadedCount books"
                                )
                                downloadedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "downloadPendingRemoteBooks failed", e)
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


                                // ⚡ Bolt: Performance Optimization (Memory O(1) Cache vs Disk O(N) Write)
                                // Pre-fetch existing bookmarks via chunked IN queries and cache them in an in-memory map.
                                // This turns O(N) database operations into O(1) memory lookups, avoiding the N+1 problem.

                                val allRemoteIds = items.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.distinct()
                                val cachedBookmarks = mutableMapOf<String, BookmarkEntity>()
                                allRemoteIds.chunked(900).forEach { chunk ->
                                        cachedBookmarks.putAll(db.bookmarkDao().getByRemoteIds(chunk).associateBy { it.remoteId!! })
                                }

                                val bookmarksToInsert = mutableListOf<BookmarkEntity>()

                                for (item in items) {
                                        val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val bookmarkBookId = item["bookId"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val createdAt =
                                                (item["createdAt"]?.jsonPrimitive?.contentOrNull)?.let {
                                                        // Parse PocketBase timestamp if needed
                                                        currentEpochMillis()
                                                }
                                                        ?: currentEpochMillis()

                                        val existing = cachedBookmarks[remoteId]

                                        // ⚡ Bolt: Performance Optimization (Avoid Blind Replace)
                                        // Only insert/update if the remote record is newer or doesn't exist locally.
                                        // This prevents excessive blind REPLACE disk I/O operations from BookmarkDao.

                                        val bookmark =
                                                BookmarkEntity(
                                                        id = existing?.id ?: 0L,
                                                        remoteId = remoteId,
                                                        bookId = bookmarkBookId,
                                                        locatorJson = locatorJson,
                                                        createdAt = createdAt,
                                                        isSynced = true
                                                )


                                        if (existing == null || bookmark.updatedAt > existing.updatedAt) {
                                                bookmarksToInsert.add(bookmark)
                                                syncedCount++
                                        }
                                }

                                if (bookmarksToInsert.isNotEmpty()) {
                                        db.withTransactionCompat {
                                                bookmarksToInsert.chunked(900).forEach { chunk ->
                                                        db.bookmarkDao().insertBatch(chunk)
                                                }
                                        }
                                }

                                logger.d(
                                        "UserSyncRepository",
                                        "pullBookmarks - Synced $syncedCount bookmarks"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullBookmarks failed", e)
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
                                                "updatedAt" to currentEpochMillis()
                                        )

                                val requestBody =
                                        mapToJsonString(bookmarkData)
                                                

                                val result =
                                        if (entity.remoteId != null) {
                                                // Update existing bookmark
                                                val updateUrl =
                                                        "$pocketBaseUrl/api/collections/bookmarks/records/${entity.remoteId}"
                                                val responseBody = executeBackendRequest(updateUrl) {
                                                    method = HttpMethod.Patch
                                                    contentType(ContentType.Application.Json)
                                                    setBody(requestBody)
                                                }
                                                val response =
                                                        json.parseToJsonElement(responseBody).jsonObject

                                                entity.copy(
                                                        remoteId = response["id"]?.jsonPrimitive?.contentOrNull
                                                                        ?: entity.remoteId,
                                                        isSynced = true
                                                )
                                        } else {
                                                // Create new bookmark
                                                val createUrl =
                                                        "$pocketBaseUrl/api/collections/bookmarks/records"
                                                val responseBody = executeBackendRequest(createUrl) {
                                                    method = HttpMethod.Post
                                                    contentType(ContentType.Application.Json)
                                                    setBody(requestBody)
                                                }
                                                val response =
                                                        json.parseToJsonElement(responseBody).jsonObject

                                                entity.copy(
                                                        remoteId = response["id"]?.jsonPrimitive?.contentOrNull,
                                                        isSynced = true
                                                )
                                        }

                                logger.d("UserSyncRepository", "pushBookmark - Bookmark synced")
                                result
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushBookmark failed", e)
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
                                        logger.w(
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
                                                "updatedAt" to currentEpochMillis()
                                        )

                                val requestBody =
                                        mapToJsonString(noteData)
                                                

                                val syncedRemoteId =
                                        if (!note.remoteId.isNullOrBlank()) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records/${note.remoteId}"
                                        executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                                note.remoteId
                                } else {
                                        val createUrl =
                                                "$pocketBaseUrl/api/collections/ai_notes/records"
                                        val createBody = executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        val created =
                                                json.parseToJsonElement(createBody).jsonObject
                                        created["id"]?.jsonPrimitive?.contentOrNull
                                } ?: return@withContext null

                                logger.d("UserSyncRepository", "pushAiNote - Note synced")
                                syncedRemoteId
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushAiNote failed", e)
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

                                val allRemoteIds = items.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.distinct()
                                val cachedNotes = mutableMapOf<String, AiNoteEntity>()
                                allRemoteIds.chunked(900).forEach { chunk ->
                                        cachedNotes.putAll(db.aiNoteDao().getByRemoteIds(chunk).associateBy { it.remoteId!! })
                                }

                                for (item in items) {
                                        val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val note =
                                                AiNoteEntity(
                                                        remoteId = remoteId,
                                                        bookId = item["bookId"]?.jsonPrimitive?.contentOrNull,
                                                        bookTitle = item["bookTitle"]?.jsonPrimitive?.contentOrNull,
                                                        messages = item["messages"]?.jsonPrimitive?.contentOrNull
                                                                        ?: "",
                                                        originalText =
                                                                item["originalText"]?.jsonPrimitive?.contentOrNull,
                                                        aiResponse = item["aiResponse"]?.jsonPrimitive?.contentOrNull,
                                                        locatorJson =
                                                                item["locatorJson"]?.jsonPrimitive?.contentOrNull,
                                                        createdAt =
                                                                (item["createdAt"]?.jsonPrimitive?.doubleOrNull)
                                                                        ?.toLong()
                                                                        ?: currentEpochMillis(),
                                                        updatedAt =
                                                                (item["updatedAt"]?.jsonPrimitive?.doubleOrNull)
                                                                        ?.toLong()
                                                                        ?: currentEpochMillis()
                                                )

                                        val existing = cachedNotes[remoteId]
                                        if (existing == null) {
                                                val insertedId = db.aiNoteDao().insert(note)
                                                cachedNotes[remoteId] = note.copy(id = insertedId)
                                                syncedCount++
                                        } else if (note.updatedAt > existing.updatedAt) {
                                                db.aiNoteDao().update(note.copy(id = existing.id))
                                                cachedNotes[remoteId] = note.copy(id = existing.id)
                                                syncedCount++
                                        }
                                }
                                cleanupDuplicateNotes()

                                logger.d("UserSyncRepository", "pullNotes - Synced $syncedCount notes")
                                syncedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullNotes failed", e)
                                0
                        }
                }

        suspend fun deleteAiNote(remoteId: String): Boolean =
                withContext(io) {
                        try {
                                val url =
                                        "$pocketBaseUrl/api/collections/ai_notes/records/$remoteId"
                                                                executeBackendRequest(url) {
                                    method = HttpMethod.Delete
                                }
                                logger.d("UserSyncRepository", "deleteAiNote - Note deleted")
                                true
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "deleteAiNote failed", e)
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
                duplicateIds.chunked(900).forEach { chunk ->
                        db.aiNoteDao().deleteByIds(chunk)
                }
                logger.d(
                        "UserSyncRepository",
                        "cleanupDuplicateNotes - Removed ${duplicateIds.size} duplicate notes"
                )
        }

        private fun profileNameKey(name: String): String = name.trim().lowercase()

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

        private fun toRemoteProfile(item: kotlinx.serialization.json.JsonObject): AiProfileEntity? {
                val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: return null
                val now = currentEpochMillis()
                return AiProfileEntity(
                        remoteId = remoteId,
                        name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        modelName = item["modelName"]?.jsonPrimitive?.contentOrNull ?: "",
                        apiKey = item["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                        serverBaseUrl = item["serverBaseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                        systemPrompt = item["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                        userPromptTemplate = item["userPromptTemplate"]?.jsonPrimitive?.contentOrNull ?: "",
                        useStreaming = item["useStreaming"]?.jsonPrimitive?.booleanOrNull ?: false,
                        temperature = item["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
                        maxTokens = (item["maxTokens"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 4096,
                        topP = item["topP"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                        frequencyPenalty = item["frequencyPenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        presencePenalty = item["presencePenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        assistantRole = item["assistantRole"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                        enableGoogleSearch = item["enableGoogleSearch"]?.jsonPrimitive?.booleanOrNull ?: true,
                        extraParamsJson = item["extraParamsJson"]?.jsonPrimitive?.contentOrNull,
                        createdAt = (item["createdAt"]?.jsonPrimitive?.doubleOrNull)?.toLong() ?: now,
                        updatedAt = (item["updatedAt"]?.jsonPrimitive?.doubleOrNull)?.toLong() ?: now,
                        isSynced = true
                )
        }

        private fun applyProfileToLocalSettings(profile: AiProfileEntity) {
                val currentSettings = ReaderSettings.fromStorage(prefs)
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
                                updatedAt = currentEpochMillis()
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
                var activeProfileId = ReaderSettings.fromStorage(prefs).activeProfileId
                val deletedIds = mutableSetOf<Long>()

                for ((_, group) in grouped) {
                        val keep = group.reduce { best, next ->
                                if (shouldPreferProfile(next, best)) next else best
                        }
                        group.filter { it.id != keep.id }.forEach { duplicate ->
                                db.aiProfileDao().deleteById(duplicate.id)
                                deletedIds.add(duplicate.id)
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
                        allProfiles
                                .filter { it.id !in deletedIds && hasUsableApiKey(it.apiKey) }
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
                                                "updatedAt" to currentEpochMillis()
                                        )

                                val requestBody =
                                        mapToJsonString(profileData)
                                                

                                val syncedRemoteId =
                                        if (!profile.remoteId.isNullOrBlank()) {
                                        val updateUrl =
                                                "$pocketBaseUrl/api/collections/ai_profiles/records/${profile.remoteId}"
                                        executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
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
                                                        logger.d(
                                                                "UserSyncRepository",
                                                                "pushAiProfile - Skip overwrite for ${profile.name} because remote has usable API key"
                                                        )
                                                } else {
                                                        val updateUrl =
                                                                "$pocketBaseUrl/api/collections/ai_profiles/records/${sameNameRemote.remoteId}"
                                                        executeBackendRequest(updateUrl) {
                                                            method = HttpMethod.Patch
                                                            contentType(ContentType.Application.Json)
                                                            setBody(requestBody)
                                                        }
                                                }
                                                sameNameRemote.remoteId
                                        } else {
                                                val createUrl =
                                                        "$pocketBaseUrl/api/collections/ai_profiles/records"
                                                val createBody = executeBackendRequest(createUrl) {
                                                    method = HttpMethod.Post
                                                    contentType(ContentType.Application.Json)
                                                    setBody(requestBody)
                                                }
                                                val created =
                                                        json.parseToJsonElement(createBody).jsonObject
                                                created["id"]?.jsonPrimitive?.contentOrNull
                                        }
                                } ?: return@withContext null

                                logger.d("UserSyncRepository", "pushAiProfile - Profile synced")
                                syncedRemoteId
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushAiProfile failed", e)
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

                                logger.d(
                                        "UserSyncRepository",
                                        "pullAiProfiles - Synced $syncedCount profiles"
                                )
                                syncedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullAiProfiles failed", e)
                                0
                        }
                }

        suspend fun deleteAiProfile(remoteId: String): Boolean =
                withContext(io) {
                        try {
                                val url =
                                        "$pocketBaseUrl/api/collections/ai_profiles/records/$remoteId"
                                                                executeBackendRequest(url) {
                                    method = HttpMethod.Delete
                                }
                                logger.d("UserSyncRepository", "deleteAiProfile - Profile deleted")
                                true
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "deleteAiProfile failed", e)
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
                                // Prefer a server-confirmed userId from auth-refresh so we know the
                                // relation field value actually exists in PocketBase. A stale
                                // Room-cached ID (getUserId()) may reference a deleted/unknown user
                                // and causes PocketBase to return "sql: no rows in result set".
                                val refreshedUserId = refreshAuthSessionIfPossible(pocketBaseRoot)
                                if (refreshedUserId.isNullOrBlank()) {
                                        // Auth refresh failed — token is likely expired or invalid.
                                        // Skip uploading rather than risk a relation resolution error.
                                        logger.w(
                                                "UserSyncRepository",
                                                "pushCrashReport - skipped: could not confirm userId via auth-refresh"
                                        )
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
                                payload["user"] = refreshedUserId

                                val requestBody =
                                        mapToJsonString(payload)
                                val url = "$pocketBaseUrl/api/collections/crash_reports/records"
                                executeBackendRequest(url, reportError = false) {
                                    method = HttpMethod.Post
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                true
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushCrashReport failed", e)
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

                                val allBookIds = items.mapNotNull { it["bookId"]?.jsonPrimitive?.contentOrNull }.distinct()
                                val cachedBooks = mutableMapOf<String, BookEntity>()
                                allBookIds.chunked(900).forEach { chunk ->
                                        cachedBooks.putAll(db.bookDao().getByIds(chunk).associateBy { it.bookId })
                                }

                                val updates = mutableListOf<BookProgressUpdate>()
                                for (item in items) {
                                        val bookId = item["bookId"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull ?: continue
                                        val remoteUpdatedAt = parseEpochMillis(item["updatedAt"])
                                        cacheProgress(bookId, locatorJson, remoteUpdatedAt)

                                        val localBook = cachedBooks[bookId] ?: continue
                                        val localHasProgress = !localBook.lastLocatorJson.isNullOrBlank()
                                        val remoteIsNewerOrEqual = remoteUpdatedAt >= localBook.lastOpenedAt
                                        val shouldApply = !localHasProgress || remoteIsNewerOrEqual
                                        if (!shouldApply) {
                                                continue
                                        }
                                        if (localBook.lastLocatorJson == locatorJson && localBook.lastOpenedAt >= remoteUpdatedAt) {
                                                continue
                                        }
                                        val mergedTime = maxOf(localBook.lastOpenedAt, remoteUpdatedAt)
                                        updates.add(BookProgressUpdate(bookId, locatorJson, mergedTime))

                                        cachedBooks[bookId] = localBook.copy(
                                                lastLocatorJson = locatorJson,
                                                lastOpenedAt = mergedTime
                                        )
                                        mergedCount++
                                }
                                if (updates.isNotEmpty()) {
                                        db.withTransactionCompat {
                                                updates.chunked(900).forEach { chunk ->
                                                        db.bookDao().updateProgressBatch(chunk)
                                                }
                                        }
                                }
                                logger.d(
                                        "UserSyncRepository",
                                        "pullAllProgress - merged=$mergedCount records=${items.size}"
                                )
                                mergedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pullAllProgress failed", e)
                                0
                        }
                }

        suspend fun pullProfiles(): Int =
                withContext(io) {
                        pullAiProfiles()
                }

        /**
         * Bug 4 fix: Push reading progress for all local books that have a saved position.
         * Call this BEFORE pullAllProgress() so that Device B's pulled data always reflects
         * the latest position from all other devices.
         */
        suspend fun pushAllLocalProgress(): Int =
                withContext(io) {
                        try {
                                val books = db.bookDao().getAllBooks()
                                val pushedCount = coroutineScope {
                                        val results = books.mapNotNull { book ->
                                                val locatorJson = book.lastLocatorJson ?: return@mapNotNull null
                                                async {
                                                        try {
                                                                pushProgress(
                                                                        bookId = book.bookId,
                                                                        locatorJson = locatorJson,
                                                                        bookTitle = book.title
                                                                )
                                                                true
                                                        } catch (e: Exception) {
                                                                logger.w(
                                                                        "UserSyncRepository",
                                                                        "pushAllLocalProgress - failed for ${book.bookId}",
                                                                        e
                                                                )
                                                                false
                                                        }
                                                }
                                        }.awaitAll()
                                        results.count { it }
                                }
                                logger.d(
                                        "UserSyncRepository",
                                        "pushAllLocalProgress - Pushed $pushedCount progress records"
                                )
                                pushedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "pushAllLocalProgress failed", e)
                                0
                        }
                }

        /**
         * On startup background check: iterate every local book whose file is stored locally
         * (content:// URI). For each one, verify the server already has the file.
         * If the remote record has no storagePath, upload the file silently.
         *
         * This covers books that were added before the "upload on open" fix, or books
         * where the first upload failed due to a network error.
         *
         * Returns the number of books that were uploaded.
         */
        suspend fun ensureAllLocalBooksUploaded(): Int =
                withContext(io) {
                        try {
                                val userId = getUserId()
                                if (userId.isNullOrBlank()) {
                                        logger.d(
                                                "UserSyncRepository",
                                                "ensureAllLocalBooksUploaded - no user, skipping"
                                        )
                                        return@withContext 0
                                }

                                val books = db.bookDao().getAllBooks()
                                // Only process books with local content:// URIs — these are the
                                // ones that could potentially be missing from the server.
                                val localBooks =
                                        books.filter { book ->
                                                !book.deleted &&
                                                        !book.fileUri.startsWith("pocketbase://")
                                        }

                                if (localBooks.isEmpty()) {
                                        logger.d(
                                                "UserSyncRepository",
                                                "ensureAllLocalBooksUploaded - no local books to check"
                                        )
                                        return@withContext 0
                                }

                                logger.d(
                                        "UserSyncRepository",
                                        "ensureAllLocalBooksUploaded - checking ${localBooks.size} local books"
                                )

                                var uploadedCount = 0
                                for (book in localBooks) {
                                        try {
                                                // Check remote record for this book
                                                val checkUrl =
                                                        "$pocketBaseUrl/api/collections/books/records" +
                                                                "?filter=${urlEncodeQueryValue("bookId='${book.bookId}'")}" +
                                                                "&fields=id,storagePath,epub,file,bookFile,updatedAt"
                                                                                                val checkBody = executeBackendRequest(checkUrl, reportError = false)
                                                val checkResponse = runCatching {
                                                        json.decodeFromString<PocketBaseListResponse>(checkBody)
                                                }.getOrNull()
                                                val existingItem = checkResponse?.items?.firstOrNull()
                                                val remoteHasFile =
                                                        !resolveStoragePathFromRecord(existingItem)
                                                                .isNullOrBlank()

                                                if (remoteHasFile) {
                                                        // Server already has the file, nothing to do
                                                        logger.d(
                                                                "UserSyncRepository",
                                                                "ensureAllLocalBooksUploaded - ${book.bookId} already on server"
                                                        )
                                                        continue
                                                }

                                                // Remote has no file — upload it now
                                                logger.i(
                                                        "UserSyncRepository",
                                                        "ensureAllLocalBooksUploaded - uploading missing file for ${book.bookId}"
                                                )
                                                val synced =
                                                        pushBook(
                                                                book,
                                                                uploadFile = true
                                                        )
                                                if (synced) {
                                                        uploadedCount++
                                                        logger.i(
                                                                "UserSyncRepository",
                                                                "ensureAllLocalBooksUploaded - uploaded ${book.bookId} ('${book.title}')"
                                                        )
                                                }
                                        } catch (e: Exception) {
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "ensureAllLocalBooksUploaded - failed for ${book.bookId}",
                                                        e
                                                )
                                        }
                                }

                                logger.d(
                                        "UserSyncRepository",
                                        "ensureAllLocalBooksUploaded - uploaded $uploadedCount / ${localBooks.size} books"
                                )
                                uploadedCount
                        } catch (e: Exception) {
                                logger.e("UserSyncRepository", "ensureAllLocalBooksUploaded failed", e)
                                0
                        }
                }
        suspend fun ensureBookFileAvailable(
                bookId: String,
                storagePath: String? = null,
                originalUri: String? = null,
                downloadIfNeeded: Boolean = true
        ): String? =
                withContext(io) {
                        try {
                                val original =
                                        originalUri
                                                ?.takeIf { it.isNotBlank() }
                        if (original != null && isUriReadable(original)) {
                                return@withContext original
                        }

                                val cachedPath = localBookCacheFile(bookId)
                                if (platformFiles().exists(cachedPath) &&
                                        platformFiles().fileLength(cachedPath) > 0L
                                ) {
                                        return@withContext "file://$cachedPath"
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
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "ensureBookFileAvailable - No remote record for $bookId"
                                                )
                                                return@withContext null
                                        }
                                        if (remoteRecord["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                                                logger.w(
                                                        "UserSyncRepository",
                                                        "ensureBookFileAvailable - Remote record is deleted for $bookId"
                                                )
                                                return@withContext null
                                        }
                                        recordId = remoteRecord["id"]?.jsonPrimitive?.contentOrNull
                                        effectiveStoragePath =
                                                resolveStoragePathFromRecord(remoteRecord)
                                }

                                if (effectiveStoragePath.isNullOrBlank()) {
                                        logger.w(
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
                                                targetPath = cachedPath
                                        )
                                if (!downloaded) {
                                        return@withContext null
                                }

                                val localUri = "file://$cachedPath"
                                db.bookDao().getById(bookId)?.let { local ->
                                        if (local.fileUri.startsWith("pocketbase://")) {
                                                db.bookDao()
                                                        .insert(local.copy(fileUri = localUri))
                                        }
                                }
                                localUri
                        } catch (e: Exception) {
                                logger.e(
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
                                executeBackendRequest(checkUrl)

                                val cacheDirPath = localBooksCacheDir()
                                if (!platformFiles().exists(cacheDirPath) &&
                                        !platformFiles().mkdirs(cacheDirPath)
                                ) {
                                        return@withContext CheckResult(
                                                ok = false,
                                                message =
                                                        "Failed to create local cache dir: $cacheDirPath"
                                        )
                                }
                                val probePath = "$cacheDirPath/.probe"
                                platformFiles().writeFile(probePath, "ok".encodeToByteArray())
                                val probeOk =
                                        platformFiles().exists(probePath) &&
                                                platformFiles()
                                                        .readFile(probePath)
                                                        ?.decodeToString() == "ok"
                                platformFiles().delete(probePath)
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
                                logger.e("UserSyncRepository", "ensureStorageBucketReady failed", e)
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
                                        val remoteBookId = withRemoteFile["bookId"]?.jsonPrimitive?.contentOrNull
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
                                                        isUriReadable(entity.fileUri)
                                                } catch (_: Exception) {
                                                        false
                                                }
                                        }

                                if (localCandidate != null) {
                                        val pushed =
                                                pushBook(
                                                        book = localCandidate,
                                                        uploadFile = true,
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
                                logger.e("UserSyncRepository", "runStorageSelfTest failed", e)
                                CheckResult(ok = false, message = e.message ?: "Self-test failed")
                        }
                }

        suspend fun clearLocalUserData() {
                db.clearAllTablesCompat()
                prefs.clearAll()
                syncPrefs.clearAll()
                clearSyncedBookCache()
                cachedUserId = null
        }

        // --- Private Helpers ---

        private fun localBooksCacheDir(): String {
                val base = platformFiles().appFilesDir() ?: return "synced_books"
                return "$base/synced_books"
        }

        private fun clearSyncedBookCache() {
                val cacheDirPath = localBooksCacheDir()
                if (!platformFiles().exists(cacheDirPath)) return
                runCatching { platformFiles().delete(cacheDirPath) }
                        .onFailure {
                                logger.w(
                                        "UserSyncRepository",
                                        "Failed to clear synced_books",
                                        it
                                )
                        }
        }

        private fun localBookCacheFile(bookId: String): String {
                val safeBookId = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                return "${localBooksCacheDir()}/$safeBookId.epub"
        }

        private fun isUriReadable(uriStr: String): Boolean {
                if (uriStr.startsWith("pocketbase://", ignoreCase = true)) {
                        return false
                }
                return try {
                        platformFiles().isUriReadable(uriStr)
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
        ): kotlinx.serialization.json.JsonObject? {
                val filter = "(user='$userId'%26%26bookId='$bookId')"
                val url =
                        "$pocketBaseUrl/api/collections/books/records?filter=$filter&perPage=1"
                                val responseBody = executeBackendRequest(url)
                val response = json.decodeFromString<PocketBaseListResponse>(responseBody)
                return response.items.firstOrNull()
        }

        private suspend fun updateBookStoragePath(recordId: String, storagePath: String) {
                val payload =
                        mapOf(
                                "storagePath" to storagePath,
                                "updatedAt" to currentEpochMillis()
                        )
                val requestBody = mapToJsonString(payload)
                val url = "$pocketBaseUrl/api/collections/books/records/$recordId"
                executeBackendRequest(url) {
                    method = HttpMethod.Patch
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
        }

        private suspend fun tryUploadBookFile(
                recordId: String?,
                book: BookEntity
        ): String? {
                if (recordId.isNullOrBlank() || book.deleted) {
                        return null
                }
                val userId = getUserId()

                val sourceUri = book.fileUri.takeIf { isUriReadable(it) } ?: return null

                try {
                        val bytes =
                                platformFiles().readUriBytes(sourceUri)
                                        ?: return null
                        if (bytes.isEmpty()) {
                                return null
                        }

                        val displayName =
                                (platformFiles().contentName(sourceUri)
                                        ?: "${book.bookId}.epub")
                                        .substringAfterLast('/')
                        val sanitizedBaseName =
                                displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val nonEmptyBaseName =
                                sanitizedBaseName.ifBlank { "${book.bookId}.epub" }
                        val cleanName =
                                if (nonEmptyBaseName.lowercase().endsWith(".epub")) {
                                        nonEmptyBaseName
                                } else {
                                        "$nonEmptyBaseName.epub"
                                }
                        val uploadUrl = "$pocketBaseUrl/api/collections/books/records/$recordId"

                        for (field in BOOK_FILE_FIELD_CANDIDATES) {
                                val form =
                                        formData {
                                                append("updatedAt", currentEpochMillis().toString())
                                                if (!userId.isNullOrBlank()) {
                                                        append("user", userId)
                                                }
                                                append(
                                                        field,
                                                        bytes,
                                                        Headers.build {
                                                                append(
                                                                        HttpHeaders.ContentType,
                                                                        "application/epub+zip"
                                                                )
                                                                append(
                                                                        HttpHeaders.ContentDisposition,
                                                                        "filename=\"$cleanName\""
                                                                )
                                                        }
                                                )
                                        }

                                val response =
                                        ktorClient.patch(uploadUrl) {
                                                header(
                                                        "Authorization",
                                                        "Bearer ${tokenManager.getAccessToken().orEmpty()}"
                                                )
                                                setBody(MultiPartFormDataContent(form))
                                        }
                                val body = response.bodyAsText()
                                if (!response.status.isSuccess()) {
                                        logger.w(
                                                "UserSyncRepository",
                                                "tryUploadBookFile - field=$field failed code=${response.status.value}"
                                        )
                                } else {
                                                val payload =
                                                        runCatching {
                                                                json.parseToJsonElement(body).jsonObject
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
                } catch (e: Exception) {
                        logger.e("UserSyncRepository", "tryUploadBookFile failed", e)
                        reporter.report("UserSyncRepository.tryUploadBookFile",
                                "Failed to upload book file for ${book.bookId}",
                                e
                        )
                }
                return null
        }

        private fun extractUploadedFileName(
                record: kotlinx.serialization.json.JsonObject?,
                fieldName: String,
                fallback: String? = null
        ): String? {
                if (record == null) {
                        return fallback
                }

                val value = record[fieldName]
                when (value) {
                        is kotlinx.serialization.json.JsonPrimitive -> {
                                val text = value.contentOrNull
                                if (!text.isNullOrBlank()) {
                                        return text.substringAfterLast('/')
                                }
                        }
                        is kotlinx.serialization.json.JsonArray -> {
                                val firstFile =
                                        value
                                                .firstOrNull {
                                                        (it as? kotlinx.serialization.json.JsonPrimitive)
                                                                ?.contentOrNull
                                                                ?.isNotBlank() == true
                                                }
                                                ?.let {
                                                        (it as kotlinx.serialization.json.JsonPrimitive)
                                                                .contentOrNull
                                                }
                                if (!firstFile.isNullOrBlank()) {
                                        return firstFile.substringAfterLast('/')
                                }
                        }
                        else -> { /* 其他型別（物件等）不處理 */ }
                }

                return fallback
        }

        private fun resolveStoragePathFromRecord(record: kotlinx.serialization.json.JsonObject?): String? {
                if (record == null) {
                        return null
                }

                val direct = normalizeStoragePath(record["storagePath"]?.jsonPrimitive?.contentOrNull)
                if (!direct.isNullOrBlank()) {
                        return direct
                }

                val recordId = record["id"]?.jsonPrimitive?.contentOrNull ?: return null
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
                        it.encodeURLParameter()
                }

        private fun urlEncodeQueryValue(value: String): String =
                value.encodeURLParameter()

        private fun withFileToken(url: String, token: String): String {
                if (Regex("[?&]token=").containsMatchIn(url)) return url
                val separator = if (url.contains("?")) "&" else "?"
                return "$url${separator}token=${urlEncodeQueryValue(token)}"
        }

        private suspend fun getProtectedFileToken(): String? {
                return try {
                        val tokenUrl = "$pocketBaseUrl/api/files/token"
                        val requestBody = "{}"
                        val responseBody =
                                executeBackendRequest(tokenUrl, reportError = false) {
                                        method = HttpMethod.Post
                                        contentType(ContentType.Application.Json)
                                        setBody(requestBody)
                                }
                        val payload =
                                runCatching {
                                                json.parseToJsonElement(responseBody).jsonObject
                                        }
                                        .getOrNull()
                        payload?.get("token")?.jsonPrimitive?.contentOrNull
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
                                ktorClient.head(resolvedUrl) {
                                        authIfBackend(resolvedUrl)
                                }.status.value
                        if (headCode == 405 || headCode == 501) {
                                val getCode =
                                        ktorClient.get(resolvedUrl) {
                                                authIfBackend(resolvedUrl)
                                                header("Range", "bytes=0-0")
                                        }.status.value
                                return classify(getCode)
                        }
                        classify(headCode)
                } catch (_: Exception) {
                        RemoteFileState.UNKNOWN
                }
        }

        private suspend fun downloadRemoteFile(url: String, targetPath: String): Boolean {
                return try {
                        platformFiles().mkdirs(targetPath.substringBeforeLast('/'))
                        var resolvedUrl = url
                        if (resolvedUrl.startsWith(pocketBaseUrl)) {
                                val fileToken = getProtectedFileToken()
                                if (!fileToken.isNullOrBlank()) {
                                        resolvedUrl = withFileToken(resolvedUrl, fileToken)
                                }
                        }
                        val response = ktorClient.get(resolvedUrl) { authIfBackend(resolvedUrl) }
                        if (!response.status.isSuccess()) {
                                logger.w(
                                        "UserSyncRepository",
                                        "downloadRemoteFile failed code=${response.status.value} url=$resolvedUrl"
                                )
                                return false
                        }

                        val bytes = response.bodyAsBytes()
                        val tmpPath = "$targetPath.part"
                        if (!platformFiles().writeFile(tmpPath, bytes)) {
                                return false
                        }

                        if (platformFiles().exists(targetPath)) {
                                platformFiles().delete(targetPath)
                        }
                        if (!platformFiles().rename(tmpPath, targetPath)) {
                                platformFiles().writeFile(targetPath, bytes)
                                platformFiles().delete(tmpPath)
                        }
                        platformFiles().exists(targetPath) &&
                                platformFiles().fileLength(targetPath) > 0L
                } catch (e: Exception) {
                        logger.e("UserSyncRepository", "downloadRemoteFile failed for $url", e)
                        reporter.report("UserSyncRepository.downloadRemoteFile",
                                "Failed to download remote file: $url",
                                e
                        )
                        false
                }
        }

        private fun parseEpochMillis(value: Any?): Long {
                return when (value) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull() ?: 0L
                        is kotlinx.serialization.json.JsonPrimitive ->
                                value.content.toLongOrNull() ?: 0L
                        else -> 0L
                }
        }

        /**
         * Bug 5 fix: Validate that a file is a non-corrupt EPUB (ZIP) by checking the
         * ZIP magic bytes PK\x03\x04 at the start of the file.
         */
        private fun isValidEpubFile(path: String): Boolean {
                if (!platformFiles().exists(path) || platformFiles().fileLength(path) < 4L) {
                        return false
                }
                return try {
                        val magic = platformFiles().readFilePrefix(path, 4) ?: return false
                        magic.size == 4 &&
                                magic[0] == 0x50.toByte() && // 'P'
                                magic[1] == 0x4B.toByte() && // 'K'
                                magic[2] == 0x03.toByte() &&
                                magic[3] == 0x04.toByte()
                } catch (_: Exception) {
                        false
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
                val compactJson = mapToJsonString(compact)
                if (compactJson.length <= maxChars) return compactJson
                return mapToJsonString(
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
                        runCatching { json.parseToJsonElement(messagesJson) as? kotlinx.serialization.json.JsonArray }
                                .getOrNull()
                                ?: return null
                for (idx in messages.indices.reversed()) {
                        val obj = messages[idx] as? kotlinx.serialization.json.JsonObject ?: continue
                        val msgRole = (obj["role"]?.jsonPrimitive?.contentOrNull)?.trim()?.lowercase()
                        if (msgRole != role) continue
                        val content = (obj["content"]?.jsonPrimitive?.contentOrNull)?.trim()
                        if (!content.isNullOrBlank()) {
                                return content
                        }
                }
                return null
        }

        private suspend fun mergeRemoteProgressIntoLocalBook(
                bookId: String,
                locatorJson: String,
                remoteUpdatedAt: Long,
                localBook: BookEntity? = null
        ): Boolean {
                val local = localBook ?: db.bookDao().getById(bookId) ?: return false
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


/** 通用 Any -> JsonElement 轉換（取代 Gson 的動態序列化）。 */
private fun Any?.toJsonElement(): kotlinx.serialization.json.JsonElement =
        when (this) {
                null -> kotlinx.serialization.json.JsonNull
                is String -> kotlinx.serialization.json.JsonPrimitive(this)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(this)
                is Int -> kotlinx.serialization.json.JsonPrimitive(this)
                is Long -> kotlinx.serialization.json.JsonPrimitive(this)
                is Double -> kotlinx.serialization.json.JsonPrimitive(this)
                is Float -> kotlinx.serialization.json.JsonPrimitive(this)
                is kotlinx.serialization.json.JsonElement -> this
                is Map<*, *> ->
                        kotlinx.serialization.json.buildJsonObject {
                                forEach { (k, v) -> put(k.toString(), v.toJsonElement()) }
                        }
                is List<*> ->
                        kotlinx.serialization.json.buildJsonArray {
                                forEach { add(it.toJsonElement()) }
                        }
                else -> kotlinx.serialization.json.JsonPrimitive(toString())
        }

/** 通用 Map/List -> JSON 字串（取代 Gson toJson）。 */
private fun mapToJsonString(data: Any?): String = data.toJsonElement().toString()

