package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.BookProgressUpdate
import my.hinoki.booxreader.data.core.CrashReport
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.ApiKey
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
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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

        internal val prefs = prefs
        internal val syncPrefs = syncPrefs
        internal val reporter = reporter
        internal val logger = logger
        internal val tokenManager = tokenProvider
        internal val db = AppDatabase.get()
        internal val io = ioDispatcher
        internal val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        internal val pocketBaseUrl = (baseUrl ?: tokenManager.getBackendUrl()).trimEnd('/')

        /** Ktor client（Phase 2 漸進轉換；手動加 Bearer，行為與舊版一致）。 */
        internal val ktorClient = createApiClient()

        /** 閱讀進度同步（自本類別抽出的 progress 叢集，實作見 ProgressSync.kt）。 */
        private val progressSync by lazy { ProgressSync(this) }

        /** 書本 / 書檔同步（自本類別抽出的 book 叢集，實作見 BookSync.kt）。 */
        private val bookSync by lazy { BookSync(this) }

        /** 設定同步（實作見 SettingsSync.kt）。 */
        private val settingsSync by lazy { SettingsSync(this) }

        /** 書籤同步（實作見 BookmarkSync.kt）。 */
        private val bookmarkSync by lazy { BookmarkSync(this) }

        /** 畫線 / 註記同步（實作見 AnnotationSync.kt）。 */
        private val annotationSync by lazy { AnnotationSync(this) }

        /** AI 筆記同步（實作見 AiNoteSync.kt）。 */
        private val aiNoteSync by lazy { AiNoteSync(this) }

        /** AI 設定檔同步（實作見 ProfileSync.kt）。 */
        private val profileSync by lazy { ProfileSync(this) }

        /** 每日摘要 email 寄送（自本類別抽出的 email 叢集，見 DailySummaryEmailSender）。 */
        private val dailySummaryEmailSender by lazy {
                DailySummaryEmailSender(
                        pocketBaseUrl = pocketBaseUrl,
                        client = ktorClient,
                        io = io,
                        accessToken = { tokenManager.getAccessToken() },
                        refreshAuthSession = { refreshAuthSessionIfPossible(it) },
                        currentUserId = { getUserId() }
                )
        }

        /** 對後端主機的請求加上 Bearer header（Ktor 版）。 */
        internal fun io.ktor.client.request.HttpRequestBuilder.authIfBackend(resolvedUrl: String) {
                if (resolvedUrl.startsWith(pocketBaseUrl)) {
                        val token = tokenManager.getAccessToken() ?: ""
                        if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
        }

        @Volatile internal var cachedUserId: String? = null

        // --- Helper Methods ---
        internal suspend fun fetchAllItems(
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
        internal suspend fun getUserId(): String? {
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
        ): CheckResult = dailySummaryEmailSender.send(toEmail, subject, body)

        /**
         * PocketBase auth tokens can expire/rotate. Refresh once before mail dispatch so
         * routerAdd/custom routes can resolve e.auth consistently.
         */
        internal suspend fun refreshAuthSessionIfPossible(pocketBaseRoot: String): String? {
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
        internal suspend fun executeBackendRequest(
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
                                try {
                                        db.userDao().clearAllUsers()
                                } catch (e: Exception) {
                                        logger.e("UserSyncRepository", "Failed to clear users on 401", e)
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


        // --- Settings Sync（實作見 SettingsSync.kt） ---

        suspend fun pullSettingsIfNewer(): ReaderSettings? = settingsSync.pullSettingsIfNewer()

        suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromStorage(prefs)) =
                settingsSync.pushSettings(settings)


        // --- Progress Sync（實作見 ProgressSync.kt） ---

        fun getCachedProgress(bookId: String): String? = progressSync.getCachedProgress(bookId)

        fun cacheProgress(
                bookId: String,
                locatorJson: String,
                updatedAt: Long = currentEpochMillis()
        ) = progressSync.cacheProgress(bookId, locatorJson, updatedAt)

        suspend fun pullProgress(bookId: String): String? = progressSync.pullProgress(bookId)

        suspend fun pushProgress(bookId: String, locatorJson: String, bookTitle: String? = null) =
                progressSync.pushProgress(bookId, locatorJson, bookTitle)

        // --- Book Sync（實作見 BookSync.kt） ---

        suspend fun pushBook(book: BookEntity, uploadFile: Boolean = false): Boolean =
                bookSync.pushBook(book, uploadFile)

        suspend fun ensureRemoteBookFilePresent(book: BookEntity): Boolean =
                bookSync.ensureRemoteBookFilePresent(book)

        suspend fun softDeleteBook(bookId: String): Boolean = bookSync.softDeleteBook(bookId)

        suspend fun pushLocalBooks(): Int = bookSync.pushLocalBooks()

        suspend fun pullBooks(): Int = bookSync.pullBooks()

        suspend fun downloadPendingRemoteBooks(): Int = bookSync.downloadPendingRemoteBooks()


        // --- Bookmark Sync（實作見 BookmarkSync.kt） ---

        suspend fun pullBookmarks(bookId: String? = null): Int = bookmarkSync.pullBookmarks(bookId)

        suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? =
                bookmarkSync.pushBookmark(entity)

        // --- Annotation Sync（實作見 AnnotationSync.kt） ---

        suspend fun pullAnnotations(bookId: String? = null): Int =
                annotationSync.pullAnnotations(bookId)

        suspend fun pushAnnotation(entity: AnnotationEntity): AnnotationEntity? =
                annotationSync.pushAnnotation(entity)

        suspend fun deleteAnnotation(remoteId: String): Boolean =
                annotationSync.deleteAnnotation(remoteId)


        // --- Note Sync（實作見 AiNoteSync.kt） ---

        suspend fun pushAiNote(note: AiNoteEntity): String? = aiNoteSync.pushAiNote(note)

        suspend fun pullNotes(): Int = aiNoteSync.pullNotes()

        suspend fun deleteAiNote(remoteId: String): Boolean = aiNoteSync.deleteAiNote(remoteId)


        // --- Profile Sync（實作見 ProfileSync.kt） ---

        suspend fun pushAiProfile(profile: AiProfileEntity): String? = profileSync.pushAiProfile(profile)

        suspend fun pushProfile(profile: AiProfileEntity): String? = profileSync.pushProfile(profile)

        suspend fun pullAiProfiles(): Int = profileSync.pullAiProfiles()

        suspend fun deleteAiProfile(remoteId: String): Boolean = profileSync.deleteAiProfile(remoteId)


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

        suspend fun pullAllProgress(): Int = progressSync.pullAllProgress()

        suspend fun pullProfiles(): Int =
                withContext(io) {
                        pullAiProfiles()
                }

        /**
         * Bug 4 fix: Push reading progress for all local books that have a saved position.
         * Call this BEFORE pullAllProgress() so that Device B's pulled data always reflects
         * the latest position from all other devices.
         */
        suspend fun pushAllLocalProgress(): Int = progressSync.pushAllLocalProgress()

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
        suspend fun ensureAllLocalBooksUploaded(): Int = bookSync.ensureAllLocalBooksUploaded()

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


        /**
         * 決定要上傳到伺服器的 API key（SettingsSync 與 ProfileSync 共用，因此留在 host）。
         *
         * 預設（syncAiApiKeys = false）上傳空字串：金鑰只留在本機、並經 Android Keystore 加密，
         * 避免伺服器端持有使用者的明文金鑰。使用者可在設定中明確開啟同步。
         */
        internal fun apiKeyForUpload(plainKey: String): String =
                if (ReaderSettings.fromStorage(prefs).syncAiApiKeys) plainKey else ""

        internal fun accessToken(): String? = tokenManager.getAccessToken()
}


/** 通用 Any -> JsonElement 轉換（取代 Gson 的動態序列化）。 */
