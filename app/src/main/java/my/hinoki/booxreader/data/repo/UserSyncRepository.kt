package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import okio.BufferedSink
import okio.source

/**
 * Syncs user-specific data (settings, reading progress, AI notes) to Supabase so it can
 * roam across devices. Falls back to no-ops when the user is not signed in.
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
        private val supabaseUrl = (baseUrl ?: BuildConfig.SUPABASE_URL).trimEnd('/')
        private val supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY
        private val supabaseRestUrl = "$supabaseUrl/rest/v1"
        private val supabaseStorageUrl = "$supabaseUrl/storage/v1"
        private val gson: Gson =
                GsonBuilder()
                        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                        .create()
        private val httpClient: OkHttpClient =
                if (appContext is my.hinoki.booxreader.BooxReaderApp) {
                        appContext.okHttpClient
                } else {
                        OkHttpClient.Builder()
                                .connectTimeout(30, TimeUnit.SECONDS)
                                .readTimeout(60, TimeUnit.SECONDS)
                                .writeTimeout(60, TimeUnit.SECONDS)
                                .build()
                }
        private val emptyJsonBody =
                "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        private val emptyOctetStreamBody =
                ByteArray(0).toRequestBody("application/octet-stream".toMediaType())
        @Volatile private var cachedUserId: String? = null

        // --- Public API ---

        suspend fun pullSettingsIfNewer(): ReaderSettings? =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "settings",
                                        query =
                                                mapOf(
                                                        "select" to "*",
                                                        "user_id" to "eq.$userId",
                                                        "limit" to "1"
                                                )
                                )
                                        ?: return@withContext null
                        val listType =
                                object : TypeToken<List<SupabaseReaderSettings>>() {}.type
                        val remote = gson.fromJson<List<SupabaseReaderSettings>>(response, listType)
                                .firstOrNull() ?: return@withContext null

                        // Map remote string ID to local long ID
                        var localProfileId = -1L
                        var activeProfile: AiProfileEntity? = null

                        if (!remote.activeProfileId.isNullOrBlank()) {
                                val profile =
                                        db.aiProfileDao().getByRemoteId(remote.activeProfileId)
                                if (profile != null) {
                                        localProfileId = profile.id
                                        activeProfile = profile
                                }
                        }

                        val local = ReaderSettings.fromPrefs(prefs)

                        if (remote.updatedAt > local.updatedAt) {
                                var mergedSettings = remote.toLocal(local, localProfileId)

                                // If we switched to a profile (or the remote says we are on a
                                // profile),
                                // we must apply that profile's values to the local settings
                                // immediately.
                                // otherwise the app will have the new ID but old cached API
                                // key/model.
                                if (activeProfile != null) {
                                        mergedSettings =
                                                mergedSettings.copy(
                                                        apiKey = activeProfile.apiKey,
                                                        aiModelName = activeProfile.modelName,
                                                        serverBaseUrl = activeProfile.serverBaseUrl,
                                                        aiSystemPrompt = activeProfile.systemPrompt,
                                                        aiUserPromptTemplate =
                                                                activeProfile.userPromptTemplate,
                                                        useStreaming = activeProfile.useStreaming,
                                                        enableGoogleSearch =
                                                                activeProfile.enableGoogleSearch,
                                                        temperature = activeProfile.temperature,
                                                        maxTokens = activeProfile.maxTokens,
                                                        topP = activeProfile.topP,
                                                        frequencyPenalty =
                                                                activeProfile.frequencyPenalty,
                                                        presencePenalty =
                                                                activeProfile.presencePenalty,
                                                        assistantRole = activeProfile.assistantRole
                                                )
                                }

                                mergedSettings.saveTo(prefs)
                                return@withContext mergedSettings
                        } else {
                                return@withContext null
                        }
                }

        suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromPrefs(prefs)) {
                val userId = requireUserId() ?: return

                // Map local long ID to remote string ID
                var remoteProfileId: String? = null
                if (settings.activeProfileId > 0) {
                        val profile = db.aiProfileDao().getById(settings.activeProfileId)
                        remoteProfileId = profile?.remoteId
                }

                val payload =
                        SupabaseReaderSettings.fromLocal(settings, remoteProfileId)
                                .copy(userId = userId, updatedAt = System.currentTimeMillis())

                withContext(io) {
                        supabaseRestRequest(
                                method = "POST",
                                path = "settings",
                                query = mapOf("on_conflict" to "user_id"),
                                body = payload,
                                prefer = "resolution=merge-duplicates"
                        )
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

        suspend fun pullProgress(bookId: String) =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "progress",
                                        query =
                                                mapOf(
                                                        "select" to "*",
                                                        "user_id" to "eq.$userId",
                                                        "book_id" to "eq.$bookId",
                                                        "limit" to "1"
                                                )
                                )
                                        ?: return@withContext null
                        val listType =
                                object : TypeToken<List<SupabaseProgress>>() {}.type
                        val remote =
                                gson.fromJson<List<SupabaseProgress>>(response, listType)
                                        .firstOrNull() ?: return@withContext null


                        if (remote.locatorJson.isBlank()) return@withContext null

                        val localTs = prefs.getLong(progressTimestampKey(bookId), 0)
                        if (remote.updatedAt > localTs) {
                                cacheProgress(bookId, remote.locatorJson, remote.updatedAt)
                                runCatching {
                                        db.bookDao()
                                                .updateProgress(
                                                        bookId,
                                                        remote.locatorJson,
                                                        remote.updatedAt
                                                )
                                }
                        } else {
                        }
                        remote.locatorJson
                }

        suspend fun pushProgress(bookId: String, locatorJson: String, bookTitle: String? = null) =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext
                        val now = System.currentTimeMillis()
                        val payload =
                                SupabaseProgress(
                                        userId = userId,
                                        bookId = bookId,
                                        bookTitle = bookTitle,
                                        locatorJson = locatorJson,
                                        updatedAt = now
                                )
                        supabaseRestRequest(
                                method = "POST",
                                path = "progress",
                                query = mapOf("on_conflict" to "user_id,book_id"),
                                body = payload,
                                prefer = "resolution=merge-duplicates"
                        )
                        cacheProgress(bookId, locatorJson, now)
                }

        suspend fun pushBook(
                book: BookEntity,
                uploadFile: Boolean = false,
                contentResolver: android.content.ContentResolver? = null
        ) =
                withContext(io) {

                        val userId = requireUserId()
                        if (userId == null) {
                                return@withContext
                        }

                        val uploadInfo =
                                if (uploadFile) {
                                        uploadBookFileIfNeeded(book, userId, contentResolver)
                                } else {
                                        null
                                }

                        val payload =
                                SupabaseBook(
                                        userId = userId,
                                        bookIdLocal = book.bookId,
                                        title = book.title ?: book.bookId,
                                        fileUri = book.fileUri,
                                        lastLocator = book.lastLocatorJson,
                                        lastOpenedAt = toIsoTimestamp(book.lastOpenedAt),
                                        isDeleted = book.deleted,
                                        deletedAt = book.deletedAt?.let { toIsoTimestamp(it) }
                                )


                        val existingId = findBookIdByLocalId(userId, book.bookId)
                        if (existingId == null) {
                                supabaseRestRequest(
                                        method = "POST",
                                        path = "books",
                                        body = payload
                                )
                        } else {
                                supabaseRestRequest(
                                        method = "PATCH",
                                        path = "books",
                                        query = mapOf("id" to "eq.$existingId"),
                                        body = payload
                                )
                        }

                        if (uploadInfo == null && uploadFile) {
                        } else {
                        }
                }

        suspend fun softDeleteBook(bookId: String): Boolean =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext true
                        val existingId = findBookIdByLocalId(userId, bookId)
                        val updates =
                                SupabaseBook(
                                        userId = userId,
                                        bookIdLocal = bookId,
                                        title = bookId,
                                        isDeleted = true,
                                        deletedAt = toIsoTimestamp(System.currentTimeMillis())
                                )
                        if (existingId != null) {
                                supabaseRestRequest(
                                        method = "PATCH",
                                        path = "books",
                                        query = mapOf("id" to "eq.$existingId"),
                                        body = updates
                                )
                        }
                        deleteBookFile(userId, bookId)
                        true
                }

        suspend fun pullBooks(): Int =
                withContext(io) {
                        val userId = requireUserId()
                        if (userId == null) {
                                return@withContext 0
                        }


                        // Push any pending deletions first
                        pushPendingDeletes()

                        val dao = db.bookDao()
                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "books",
                                        query = mapOf("select" to "*", "user_id" to "eq.$userId")
                                )
                                        ?: return@withContext 0
                        val listType = object : com.google.gson.reflect.TypeToken<List<SupabaseBook>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseBook>>(response, listType)

                        var updatedCount = 0
                        var downloadedCount = 0

                        remotes.forEach { remote ->
                                if (remote.bookIdLocal.isBlank()) return@forEach
                                val bookId = remote.bookIdLocal
                                val existing = dao.getByIds(listOf(bookId)).firstOrNull()
                                val remoteLastOpened = fromIsoTimestamp(remote.lastOpenedAt)

                                if (remote.isDeleted) {
                                        dao.deleteById(bookId)
                                        updatedCount++
                                        return@forEach
                                }

                                var fileUri = remote.fileUri ?: ""
                                val localUri = ensureBookFileAvailable(bookId, null)
                                if (localUri != null) {
                                        fileUri = localUri.toString()
                                        downloadedCount++
                                }

                                if (existing == null) {
                                        dao.insert(
                                                BookEntity(
                                                        bookId = bookId,
                                                        title = remote.title,
                                                        fileUri = fileUri,
                                                        lastLocatorJson = remote.lastLocator,
                                                        lastOpenedAt = remoteLastOpened,
                                                        deleted = false,
                                                        deletedAt = null
                                                )
                                        )
                                        updatedCount++
                                } else if (remoteLastOpened > existing.lastOpenedAt) {
                                        dao.update(
                                                existing.copy(
                                                        title = remote.title,
                                                        fileUri = fileUri,
                                                        lastLocatorJson = remote.lastLocator,
                                                        lastOpenedAt = remoteLastOpened
                                                )
                                        )
                                        updatedCount++
                                } else if (localUri != null && fileUri != existing.fileUri) {
                                        dao.update(existing.copy(fileUri = fileUri))
                                        updatedCount++
                                }
                        }

                        updatedCount
                }

        suspend fun pullBook(bookId: String): Boolean =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext false
                        val dao = db.bookDao()

                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "books",
                                        query =
                                                mapOf(
                                                        "select" to "*",
                                                        "user_id" to "eq.$userId",
                                                        "book_id_local" to "eq.$bookId",
                                                        "limit" to "1"
                                                )
                                )
                                        ?: return@withContext false
                        val listType = object : TypeToken<List<SupabaseBook>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseBook>>(response, listType)
                        val remote = remotes.firstOrNull() ?: return@withContext false

                        val existing = dao.getByIds(listOf(bookId)).firstOrNull()
                        val remoteLastOpened = fromIsoTimestamp(remote.lastOpenedAt)

                        if (remote.isDeleted) {
                                dao.deleteById(bookId)
                                return@withContext true
                        }

                        var fileUri = remote.fileUri ?: ""
                        val localUri = ensureBookFileAvailable(bookId, null)
                        if (localUri != null) {
                                fileUri = localUri.toString()
                        }

                        if (existing == null) {
                                dao.insert(
                                        BookEntity(
                                                bookId = bookId,
                                                title = remote.title,
                                                fileUri = fileUri,
                                                lastLocatorJson = remote.lastLocator,
                                                lastOpenedAt = remoteLastOpened,
                                                deleted = false,
                                                deletedAt = null
                                        )
                                )
                        } else if (remoteLastOpened > existing.lastOpenedAt) {
                                dao.update(
                                        existing.copy(
                                                title = remote.title,
                                                fileUri = fileUri,
                                                lastLocatorJson = remote.lastLocator,
                                                lastOpenedAt = remoteLastOpened
                                        )
                                )
                        } else if (localUri != null && fileUri != existing.fileUri) {
                                dao.update(existing.copy(fileUri = fileUri))
                        }
                        true
                }


        private suspend fun pushPendingDeletes() {
                val dao = db.bookDao()
                val pending = dao.getPendingDeletes()
                if (pending.isEmpty()) return

                pending.forEach { book ->
                        val success = softDeleteBook(book.bookId)
                        if (success) {
                                dao.deleteById(book.bookId) // Hard delete now that cloud is updated
                        } else {
                        }
                }
        }

        /** 掃描 Supabase Storage 內的書籍，若本地沒有可讀檔案則下載並建立/更新資料庫紀錄 */
        private suspend fun syncStorageBooks(deletedBookIds: Set<String> = emptySet()): Int =
                withContext(io) {
                        // Supabase storage listing is optional; books are synced via table data.
                        0
                }

        private suspend fun uploadBookFileIfNeeded(
                book: BookEntity,
                userId: String,
                contentResolver: android.content.ContentResolver? = null
        ): UploadedBookInfo? =
                withContext(io) {
                        val uri = Uri.parse(book.fileUri)
                        val resolver = contentResolver ?: appContext.contentResolver
                        val localMeta =
                                readLocalFileMeta(uri, resolver)
                                        ?: run {
                                                return@withContext null
                                        }
                        val storagePath = bookStoragePath(userId, book.bookId)
                        if (storagePath.isBlank()) return@withContext null

                        // Check if file exists and size matches to avoid redundant upload
                        val headResponse = supabaseStorageRequest(method = "HEAD", path = "object/$storagePath")
                        val remoteSize = headResponse?.use {
                            if (it.isSuccessful) {
                                it.header("Content-Length")?.toLongOrNull() ?: -1L
                            } else {
                                -1L
                            }
                        } ?: -1L

                        if (remoteSize == localMeta.size) {
                             return@withContext UploadedBookInfo(storagePath, localMeta.size, localMeta.checksum)
                        }

                        val token =
                                accessToken() ?: run {
                                        cachedUserId = null
                                        return@withContext null
                                }
                        
                        val requestBody =
                                object : RequestBody() {
                                        override fun contentType() =
                                                "application/epub+zip".toMediaType()

                                        override fun contentLength(): Long = localMeta.size

                                        override fun writeTo(sink: BufferedSink) {
                                                resolver.openInputStream(uri)?.use { input ->
                                                        sink.writeAll(input.source())
                                                } ?: throw IllegalStateException("Unable to read local file")
                                        }
                                }
                        val request =
                                Request.Builder()
                                        .url("$supabaseStorageUrl/object/$storagePath")
                                        .header("apikey", supabaseAnonKey)
                                        .header("Authorization", "Bearer $token")
                                        .header("x-upsert", "true")
                                        .put(requestBody)
                                        .build()

                        httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                        val errorBody = response.body?.string()
                                        return@withContext null
                                }
                        }

                        UploadedBookInfo(storagePath, localMeta.size, localMeta.checksum)
                }

        private fun bookStoragePath(userId: String, bookId: String): String {
                if (bookId.isBlank()) return ""
                val safeId =
                        Base64.encodeToString(
                                bookId.toByteArray(Charsets.UTF_8),
                                Base64.URL_SAFE or Base64.NO_WRAP
                        )
                return "$STORAGE_BUCKET/users/$userId/books/$safeId.epub"
        }

        private suspend fun deleteBookFile(userId: String, bookId: String) {
                val storagePath = bookStoragePath(userId, bookId)
                if (storagePath.isBlank()) return
                supabaseStorageRequest(method = "DELETE", path = "object/$storagePath")
                        ?.use { }
        }

        /** Download EPUB file from Supabase Storage to local storage */
        suspend fun downloadBookFile(bookId: String, storagePath: String? = null): Uri? =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val resolvedPath = storagePath ?: bookStoragePath(userId, bookId)
                        if (resolvedPath.isBlank()) return@withContext null


                        val response =
                                supabaseStorageRequest(
                                        method = "GET",
                                        path = "object/$resolvedPath"
                                )
                                        ?: return@withContext null
                        response.use { resp ->
                                if (!resp.isSuccessful) {
                                        return@withContext null
                                }
                                val bodyStream = resp.body?.byteStream() ?: return@withContext null

                                val filesDir =
                                        appContext.getExternalFilesDir("books")
                                                ?: appContext.filesDir
                                val booksDir = File(filesDir, "downloaded")
                                if (!booksDir.exists()) {
                                        booksDir.mkdirs()
                                }

                                val safeId =
                                        android.util.Base64.encodeToString(
                                                bookId.toByteArray(Charsets.UTF_8),
                                                android.util.Base64.URL_SAFE or
                                                        android.util.Base64.NO_WRAP
                                        )
                                val fileName = "book_${safeId}_${System.currentTimeMillis()}.epub"
                                val localFile = File(booksDir, fileName)

                                localFile.outputStream().use { output ->
                                        bodyStream.copyTo(output)
                                }

                                if (localFile.exists() && localFile.length() > 0) {
                                        val uri =
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                        androidx.core.content.FileProvider.getUriForFile(
                                                                appContext,
                                                                "${appContext.packageName}.fileprovider",
                                                                localFile
                                                        )
                                                } else {
                                                        Uri.fromFile(localFile)
                                                }

                                        val cacheDir = appContext.cacheDir
                                        val cacheFile = File(cacheDir, "book_${bookId}.epub")
                                        localFile.copyTo(cacheFile, overwrite = true)

                                        return@withContext uri
                                }
                        }
                        null
                }

        private fun readLocalFileMeta(uri: Uri, contentResolver: android.content.ContentResolver): LocalFileMeta? {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                try {
                        contentResolver.openInputStream(uri)?.use { input ->
                                val buffer = ByteArray(8_192)
                                while (true) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        digest.update(buffer, 0, read)
                                        size += read
                                }
                        } ?: run {
                                return null
                        }
                } catch (e: Exception) {
                        return null
                }
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                return LocalFileMeta(size = size, checksum = checksum)
        }

        suspend fun ensureBookFileAvailable(
                bookId: String,
                storagePath: String? = null,
                originalUri: String? = null
        ): Uri? =
                withContext(io) {

                        // First, check if the original URI is still accessible
                        if (!originalUri.isNullOrBlank()) {
                                try {
                                        val uri = Uri.parse(originalUri)
                                        val resolver = appContext.contentResolver
                                        resolver.openInputStream(uri)?.use { input ->
                                                return@withContext uri
                                        }
                                } catch (e: Exception) {
                                }
                        }

                        // Check multiple possible locations for the book file
                        val possibleLocations = mutableListOf<File>()

                        // 1. Check cache directory (quick access)
                        val cacheDir = appContext.cacheDir
                        val cacheFile = File(cacheDir, "book_${bookId}.epub")
                        possibleLocations.add(cacheFile)

                        // 2. Check downloaded books directory (permanent storage)
                        val filesDir =
                                appContext.getExternalFilesDir("books") ?: appContext.filesDir
                        val booksDir = File(filesDir, "downloaded")
                        if (booksDir.exists()) {
                                booksDir.listFiles()?.forEach { file ->
                                        if (file.name.contains(bookId) &&
                                                        file.name.endsWith(".epub")
                                        ) {
                                                possibleLocations.add(file)
                                        }
                                }
                        }

                        // 3. Check for any file with bookId in name
                        val externalFilesDir = appContext.getExternalFilesDir(null)
                        if (externalFilesDir != null) {
                                externalFilesDir.walk().maxDepth(3).forEach { file ->
                                        if (file.isFile &&
                                                        file.name.contains(bookId) &&
                                                        file.name.endsWith(".epub")
                                        ) {
                                                possibleLocations.add(file)
                                        }
                                }
                        }

                        // Check all possible locations
                        for (file in possibleLocations) {
                                if (file.exists() && file.length() > 0) {

                                        // Create URI using FileProvider for security
                                        return@withContext if (Build.VERSION.SDK_INT >=
                                                        Build.VERSION_CODES.N
                                        ) {
                                                androidx.core.content.FileProvider.getUriForFile(
                                                        appContext,
                                                        "${appContext.packageName}.fileprovider",
                                                        file
                                                )
                                        } else {
                                                Uri.fromFile(file)
                                        }
                                }
                        }


                        // Download from storage
                        val result = downloadBookFile(bookId, storagePath)
                        if (result != null) {
                        } else {
                        }
                        return@withContext result
                }

        suspend fun pushNote(note: AiNoteEntity): AiNoteEntity? =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val now = System.currentTimeMillis()
                        val payload =
                                SupabaseAiNote(
                                        id = note.remoteId,
                                        userId = userId,
                                        bookId = note.bookId,
                                        bookTitle = note.bookTitle,
                                        originalText =
                                                note.originalText?.takeIf { it.isNotBlank() }
                                                        ?: AiNoteSerialization.originalTextFromMessages(note.messages),
                                        aiResponse =
                                                note.aiResponse?.takeIf { it.isNotBlank() }
                                                        ?: AiNoteSerialization.aiResponseFromMessages(note.messages),
                                        locator = parseLocatorJson(note.locatorJson),
                                        createdAt = toIsoTimestamp(note.createdAt),
                                        updatedAt = toIsoTimestamp(now)
                                )

                        val remoteId =
                                if (note.remoteId.isNullOrBlank()) {
                                        val response =
                                                supabaseRestRequest(
                                                        method = "POST",
                                                        path = "ai_notes",
                                                        body = payload,
                                                        prefer = "return=representation"
                                                )
                                                        ?: return@withContext null
                                        val listType =
                                                object : com.google.gson.reflect.TypeToken<List<SupabaseAiNote>>() {}.type
                                        val created =
                                                gson.fromJson<List<SupabaseAiNote>>(response, listType)
                                                        .firstOrNull()
                                        created?.id
                                } else {
                                        supabaseRestRequest(
                                                method = "PATCH",
                                                path = "ai_notes",
                                                query = mapOf("id" to "eq.${note.remoteId}"),
                                                body = payload
                                        )
                                        note.remoteId
                                }
                                        ?: return@withContext null

                        val updated = note.copy(remoteId = remoteId, updatedAt = now)
                        val dao = db.aiNoteDao()
                        if (updated.id == 0L) {
                                val newId = dao.insert(updated)
                                updated.copy(id = newId)
                        } else {
                                dao.update(updated)
                                updated
                        }
                }

suspend fun pullNotes(): Int =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext 0
                        val dao = db.aiNoteDao()

                        // First push any local-only notes so they get a remoteId
                        runCatching { dao.getLocalOnly().forEach { local -> pushNote(local) } }

                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "ai_notes",
                                        query = mapOf("select" to "*", "user_id" to "eq.$userId")
                                )
                                        ?: return@withContext 0
                        val listType =
                                object : com.google.gson.reflect.TypeToken<List<SupabaseAiNote>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseAiNote>>(response, listType)
                        var updatedCount = 0

                        remotes.forEach { remote ->
                                val remoteId = remote.id ?: return@forEach
                                val existing = dao.getByRemoteId(remoteId)
                                val createdAt =
                                        fromIsoTimestamp(remote.createdAt)
                                                .takeIf { it > 0 }
                                                ?: (existing?.createdAt ?: System.currentTimeMillis())
                                val updatedAt = fromIsoTimestamp(remote.updatedAt)

                                val messages =
                                        AiNoteSerialization.messagesFromOriginalAndResponse(
                                                remote.originalText,
                                                remote.aiResponse
                                        )
                                val entity =
                                        AiNoteEntity(
                                                id = existing?.id ?: 0,
                                                remoteId = remoteId,
                                                bookId = remote.bookId,
                                                bookTitle = remote.bookTitle,
                                                messages = messages,
                                                originalText = remote.originalText,
                                                aiResponse = remote.aiResponse,
                                                locatorJson = locatorToString(remote.locator),
                                                createdAt = createdAt,
                                                updatedAt = updatedAt
                                        )

                                if (existing == null) {
                                        dao.insert(entity)
                                        updatedCount++
                                } else if (updatedAt > existing.updatedAt) {
                                        dao.update(entity)
                                        updatedCount++
                                }
                        }

                        updatedCount
                }

suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val now = System.currentTimeMillis()
                        val payload =
                                SupabaseBookmark(
                                        userId = userId,
                                        bookId = entity.bookId,
                                        locator = parseLocatorJson(entity.locatorJson),
                                        createdAt = toIsoTimestamp(entity.createdAt),
                                        updatedAt = toIsoTimestamp(now)
                                )

                        val remoteId =
                                if (entity.remoteId.isNullOrBlank()) {
                                        val response =
                                                supabaseRestRequest(
                                                        method = "POST",
                                                        path = "bookmarks",
                                                        body = payload,
                                                        prefer = "return=representation"
                                                )
                                                        ?: return@withContext null
                                        val listType =
                                                object : com.google.gson.reflect.TypeToken<List<SupabaseBookmark>>() {}.type
                                        val created =
                                                gson.fromJson<List<SupabaseBookmark>>(response, listType)
                                                        .firstOrNull()
                                        created?.id
                                } else {
                                        supabaseRestRequest(
                                                method = "PATCH",
                                                path = "bookmarks",
                                                query = mapOf("id" to "eq.${entity.remoteId}"),
                                                body = payload
                                        )
                                        entity.remoteId
                                }
                                        ?: return@withContext null

                        val updated =
                                entity.copy(remoteId = remoteId, isSynced = true, updatedAt = now)
                        val dao = db.bookmarkDao()
                        if (updated.id == 0L) {
                                val newId = dao.insert(updated)
                                updated.copy(id = newId)
                        } else {
                                dao.update(updated)
                                updated
                        }
                }

suspend fun pullBookmarks(bookId: String? = null): Int =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext 0
                        val dao = db.bookmarkDao()

                        // Push local-only first to get remoteId
                        runCatching { dao.getLocalOnly().forEach { local -> pushBookmark(local) } }

                        val query =
                                mutableMapOf(
                                        "select" to "*",
                                        "user_id" to "eq.$userId"
                                )
                        if (!bookId.isNullOrBlank()) {
                                query["book_id"] = "eq.$bookId"
                        }

                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "bookmarks",
                                        query = query
                                )
                                        ?: return@withContext 0
                        val listType =
                                object : com.google.gson.reflect.TypeToken<List<SupabaseBookmark>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseBookmark>>(response, listType)

                        var updatedCount = 0
                        remotes.forEach { remote ->
                                val remoteId = remote.id ?: return@forEach
                                val existing = dao.getByRemoteId(remoteId)
                                val createdAt =
                                        fromIsoTimestamp(remote.createdAt)
                                                .takeIf { it > 0 }
                                                ?: (existing?.createdAt ?: System.currentTimeMillis())
                                val updatedAt = fromIsoTimestamp(remote.updatedAt)
                                val entity =
                                        BookmarkEntity(
                                                id = existing?.id ?: 0,
                                                remoteId = remoteId,
                                                bookId = remote.bookId,
                                                locatorJson = locatorToString(remote.locator) ?: "",
                                                createdAt = createdAt,
                                                isSynced = true,
                                                updatedAt = updatedAt
                                        )

                                if (existing == null) {
                                        dao.insert(entity)
                                        updatedCount++
                                } else if (updatedAt > existing.updatedAt) {
                                        dao.update(entity)
                                        updatedCount++
                                }
                        }

                        updatedCount
                }

suspend fun pushProfile(profile: AiProfileEntity): AiProfileEntity? =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext null
                        val now = System.currentTimeMillis()
                        val sanitizedApiKey = SyncCrypto.encrypt(profile.apiKey)
                        val payload =
                                SupabaseAiProfile(
                                        userId = userId,
                                        name = profile.name,
                                        modelName = profile.modelName,
                                        apiKey = sanitizedApiKey,
                                        serverBaseUrl = profile.serverBaseUrl,
                                        systemPrompt = profile.systemPrompt,
                                        userPromptTemplate = profile.userPromptTemplate,
                                        useStreaming = profile.useStreaming,
                                        enableGoogleSearch = profile.enableGoogleSearch,
                                        temperature = profile.temperature,
                                        maxTokens = profile.maxTokens,
                                        topP = profile.topP,
                                        frequencyPenalty = profile.frequencyPenalty,
                                        presencePenalty = profile.presencePenalty,
                                        assistantRole = profile.assistantRole,
                                        createdAt = toIsoTimestamp(profile.createdAt),
                                        updatedAt = toIsoTimestamp(now)
                                )

                        val remoteId =
                                if (profile.remoteId.isNullOrBlank()) {
                                        val response =
                                                supabaseRestRequest(
                                                        method = "POST",
                                                        path = "ai_profiles",
                                                        body = payload,
                                                        prefer = "return=representation"
                                                )
                                                        ?: return@withContext null
                                        val listType =
                                                object : com.google.gson.reflect.TypeToken<List<SupabaseAiProfile>>() {}.type
                                        val created =
                                                gson.fromJson<List<SupabaseAiProfile>>(response, listType)
                                                        .firstOrNull()
                                        created?.id
                                } else {
                                        supabaseRestRequest(
                                                method = "PATCH",
                                                path = "ai_profiles",
                                                query = mapOf("id" to "eq.${profile.remoteId}"),
                                                body = payload
                                        )
                                        profile.remoteId
                                }
                                        ?: return@withContext null

                        val updated =
                                profile.copy(remoteId = remoteId, isSynced = true, updatedAt = now)
                        val dao = db.aiProfileDao()
                        if (updated.id == 0L) {
                                val newId = dao.insert(updated)
                                updated.copy(id = newId)
                        } else {
                                dao.update(updated)
                                updated
                        }
                }

suspend fun pullProfiles(): Int =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext 0
                        val dao = db.aiProfileDao()

                        // Push local-only profiles to get remoteId
                        runCatching { dao.getLocalOnly().forEach { local -> pushProfile(local) } }

                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "ai_profiles",
                                        query = mapOf("select" to "*", "user_id" to "eq.$userId")
                                )
                                        ?: return@withContext 0
                        val listType =
                                object : com.google.gson.reflect.TypeToken<List<SupabaseAiProfile>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseAiProfile>>(response, listType)

                        var updatedCount = 0
                        remotes.forEach { remote ->
                                val remoteId = remote.id ?: return@forEach
                                val existing = dao.getByRemoteId(remoteId)
                                val remoteUpdatedAt = fromIsoTimestamp(remote.updatedAt)
                                val createdAt =
                                        fromIsoTimestamp(remote.createdAt)
                                                .takeIf { it > 0 }
                                                ?: (existing?.createdAt ?: System.currentTimeMillis())

                                val entity =
                                        AiProfileEntity(
                                                id = existing?.id ?: 0,
                                                name = remote.name,
                                                modelName = remote.modelName ?: "",
                                                apiKey = SyncCrypto.decrypt(remote.apiKey ?: ""),
                                                serverBaseUrl = remote.serverBaseUrl ?: "",
                                                systemPrompt = remote.systemPrompt ?: "",
                                                userPromptTemplate = remote.userPromptTemplate ?: "",
                                                useStreaming = remote.useStreaming ?: false,
                                                temperature = remote.temperature ?: 0.7,
                                                maxTokens = remote.maxTokens ?: 4096,
                                                topP = remote.topP ?: 1.0,
                                                frequencyPenalty = remote.frequencyPenalty ?: 0.0,
                                                presencePenalty = remote.presencePenalty ?: 0.0,
                                                assistantRole = remote.assistantRole ?: "assistant",
                                                enableGoogleSearch = remote.enableGoogleSearch ?: true,
                                                remoteId = remoteId,
                                                createdAt = createdAt,
                                                updatedAt = remoteUpdatedAt,
                                                isSynced = true
                                        )

                                if (existing == null) {
                                        dao.insert(entity)
                                        updatedCount++
                                } else if (remoteUpdatedAt > existing.updatedAt) {
                                        dao.update(entity)
                                        updatedCount++
                                } else if (existing.updatedAt > remoteUpdatedAt) {
                                        pushProfile(existing)
                                }
                        }

                        updatedCount
                }

suspend fun pullAllProgress(): Int =
                withContext(io) {
                        val userId = requireUserId() ?: return@withContext 0

                        val response =
                                supabaseRestRequest(
                                        method = "GET",
                                        path = "progress",
                                        query = mapOf("select" to "*", "user_id" to "eq.$userId")
                                )
                                        ?: return@withContext 0
                        val listType =
                                object : com.google.gson.reflect.TypeToken<List<SupabaseProgress>>() {}.type
                        val remotes = gson.fromJson<List<SupabaseProgress>>(response, listType)


                        var updated = 0
                        remotes.forEach { remote ->
                                if (remote.bookId.isBlank() || remote.locatorJson.isBlank()) {
                                        return@forEach
                                }

                                val localTs = prefs.getLong(progressTimestampKey(remote.bookId), 0)


                                if (remote.updatedAt > localTs) {
                                        cacheProgress(remote.bookId, remote.locatorJson, remote.updatedAt)
                                        runCatching {
                                                db.bookDao()
                                                        .updateProgress(
                                                                remote.bookId,
                                                                remote.locatorJson,
                                                                remote.updatedAt
                                                        )
                                        }
                                        updated++
                                }
                        }

                        updated
                }

private fun decodeBookIdFromStorageName(name: String?): String? {
                if (name.isNullOrBlank()) return null
                val safeId = name.substringBeforeLast(".")
                return runCatching {
                                val decoded =
                                        Base64.decode(safeId, Base64.URL_SAFE or Base64.NO_WRAP)
                                String(decoded, Charsets.UTF_8)
                        }
                        .getOrNull()
        }

        private fun hasReadableLocalCopy(entity: BookEntity?): Boolean {
                entity ?: return false
                return runCatching {
                                val uri = Uri.parse(entity.fileUri)
                                appContext.contentResolver.openInputStream(uri)?.use { true }
                                        ?: false
                        }
                        .getOrDefault(false)
        }

        private fun accessToken(): String? = tokenManager.getAccessToken()?.takeIf { it.isNotBlank() }

        suspend fun getUserId(): String? = requireUserId()

        private suspend fun requireUserId(): String? =
                withContext(io) {
                        val cached = cachedUserId
                        if (!cached.isNullOrBlank()) return@withContext cached
                        val token = accessToken() ?: return@withContext null
                        val request =
                                Request.Builder()
                                        .url("$supabaseUrl/auth/v1/user")
                                        .header("apikey", supabaseAnonKey)
                                        .header("Authorization", "Bearer $token")
                                        .get()
                                        .build()
                        val responseBody =
                                httpClient.newCall(request).execute().use { response ->
                                        val body = response.body?.string().orEmpty()
                                        if (response.isSuccessful) {
                                                return@use body
                                        }
                                        if (response.code == 401 || response.code == 403) {
                                                val refreshed = refreshAccessToken()
                                                if (refreshed) {
                                                        val retryToken = accessToken() ?: return@withContext null
                                                        val retryRequest =
                                                                request.newBuilder()
                                                                        .header("Authorization", "Bearer $retryToken")
                                                                        .build()
                                                        return@withContext httpClient.newCall(retryRequest)
                                                                .execute()
                                                                .use { retryResponse ->
                                                                        val retryBody =
                                                                                retryResponse.body?.string().orEmpty()
                                                                        if (!retryResponse.isSuccessful) {
                                                                                return@withContext null
                                                                        }
                                                                        retryBody
                                                                }
                                                }
                                        }
                                        return@withContext null
                                }
                        val user = gson.fromJson(responseBody, SupabaseAuthUser::class.java)
                        val previousUserId = syncPrefs.getString("last_user_id", null)
                        if (previousUserId.isNullOrBlank()) {
                                val hasLocalBooks =
                                        runCatching { db.bookDao().getAllBookIds().isNotEmpty() }
                                                .getOrDefault(false)
                                if (hasLocalBooks) {
                                        clearLocalUserData()
                                }
                        } else if (previousUserId != user.id) {
                                clearLocalUserData()
                        }
                        syncPrefs.edit().putString("last_user_id", user.id).apply()
                        cachedUserId = user.id
                        user.id
                }

        private fun refreshAccessToken(): Boolean {
                val refreshToken = tokenManager.getRefreshToken()?.takeIf { it.isNotBlank() } ?: return false
                if (supabaseAnonKey.isBlank()) return false
                val body = gson.toJson(mapOf("refresh_token" to refreshToken))
                val request =
                        Request.Builder()
                                .url("$supabaseUrl/auth/v1/token?grant_type=refresh_token")
                                .tag(String::class.java, "SKIP_AUTH")
                                .header("apikey", supabaseAnonKey)
                                .header("Authorization", "Bearer $supabaseAnonKey")
                                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                return runCatching {
                        httpClient.newCall(request).execute().use { response ->
                                val responseBody = response.body?.string().orEmpty()
                                if (!response.isSuccessful) {
                                        return false
                                }
                                val payload =
                                        gson.fromJson(responseBody, SupabaseSessionTokens::class.java)
                                val accessToken = payload.accessToken?.takeIf { it.isNotBlank() }
                                        ?: return false
                                tokenManager.saveAccessToken(accessToken)
                                payload.refreshToken
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { tokenManager.saveRefreshToken(it) }
                                true
                        }
                }.getOrDefault(false)
        }

        private fun supabaseRestRequest(
                method: String,
                path: String,
                query: Map<String, String> = emptyMap(),
                body: Any? = null,
                prefer: String? = null
        ): String? {
                val token = accessToken() ?: return null
                val url =
                        "$supabaseRestUrl/$path"
                                .toHttpUrl()
                                .newBuilder()
                                .apply { query.forEach { addQueryParameter(it.key, it.value) } }
                                .build()
                val requestBody =
                        body?.let {
                                gson.toJson(it)
                                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                        }
                val requestBuilder =
                        Request.Builder()
                                .url(url)
                                .header("apikey", supabaseAnonKey)
                                .header("Authorization", "Bearer $token")
                                .header("Accept", "application/json")
                if (!prefer.isNullOrBlank()) {
                        requestBuilder.header("Prefer", prefer)
                }
                when (method.uppercase()) {
                        "GET" -> requestBuilder.get()
                        "POST" -> requestBuilder.post(requestBody ?: emptyJsonBody)
                        "PATCH" -> requestBuilder.patch(requestBody ?: emptyJsonBody)
                        "DELETE" -> requestBuilder.delete()
                        else -> error("Unsupported method: $method")
                }
                return httpClient.newCall(requestBuilder.build()).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                                return null
                        }
                        responseBody
                }
        }

        private fun supabaseStorageRequest(
                method: String,
                path: String,
                body: ByteArray? = null,
                contentType: String? = null
        ): okhttp3.Response? {
                val token = accessToken() ?: return null
                val url = "$supabaseStorageUrl/$path".toHttpUrl()
                val requestBuilder =
                        Request.Builder()
                                .url(url)
                                .header("apikey", supabaseAnonKey)
                                .header("Authorization", "Bearer $token")
                val requestBody =
                        body?.toRequestBody(
                                (contentType ?: "application/octet-stream").toMediaType()
                        )
                when (method.uppercase()) {
                        "GET" -> requestBuilder.get()
                        "POST" -> requestBuilder.post(requestBody ?: emptyOctetStreamBody)
                        "PUT" -> requestBuilder.put(requestBody ?: emptyOctetStreamBody)
                        "DELETE" -> requestBuilder.delete()
                        else -> error("Unsupported method: $method")
                }
                requestBuilder.header("x-upsert", "true")
                return runCatching { httpClient.newCall(requestBuilder.build()).execute() }
                        .getOrNull()
        }

        private fun progressKey(bookId: String) = "progress_$bookId"
        private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

        private fun toIsoTimestamp(epochMillis: Long): String? {
                if (epochMillis <= 0L) return null
                return Instant.ofEpochMilli(epochMillis).toString()
        }

        private fun fromIsoTimestamp(value: String?): Long {
                if (value.isNullOrBlank()) return 0L
                return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
        }

        private fun parseLocatorJson(value: String?): JsonElement? {
                if (value.isNullOrBlank()) return null
                return runCatching { gson.fromJson(value, JsonElement::class.java) }.getOrNull()
        }

        private fun locatorToString(value: JsonElement?): String? {
                if (value == null) return null
                return runCatching { gson.toJson(value) }.getOrNull()
        }

        private fun findBookIdByLocalId(userId: String, bookIdLocal: String): String? {
                val response =
                        supabaseRestRequest(
                                method = "GET",
                                path = "books",
                                query =
                                        mapOf(
                                                "select" to "id",
                                                "user_id" to "eq.$userId",
                                                "book_id_local" to "eq.$bookIdLocal",
                                                "limit" to "1"
                                        )
                        )
                                ?: return null
                val listType = object : TypeToken<List<SupabaseBook>>() {}.type
                val records = gson.fromJson<List<SupabaseBook>>(response, listType)
                return records.firstOrNull()?.id
        }

        private suspend fun clearLocalUserData() {
                withContext(io) {
                        db.bookDao().deleteAll()
                        db.bookmarkDao().deleteAll()
                        db.aiNoteDao().deleteAll()
                        db.aiProfileDao().deleteAll()

                        val editor = prefs.edit()
                        prefs.all.keys.forEach { key ->
                                if (key.startsWith("progress_") || key.startsWith("progress_ts_")) {
                                        editor.remove(key)
                                }
                        }
                        editor.apply()
                }
        }

        companion object {
                private const val STORAGE_BUCKET = "books"
        }
}

private data class LocalFileMeta(val size: Long, val checksum: String?)

private data class UploadedBookInfo(val storagePath: String, val size: Long, val checksum: String?)

data class SupabaseAuthUser(
        val id: String = ""
)

private data class SupabaseSessionTokens(
        @SerializedName("access_token")
        val accessToken: String? = null,
        @SerializedName("refresh_token")
        val refreshToken: String? = null
)

data class SupabaseProgress(
        val id: Long? = null,
        val userId: String? = null,
        val bookId: String = "",
        val bookTitle: String? = null,
        val locatorJson: String = "",
        val updatedAt: Long = 0L,
        val createdAt: String? = null
)

data class SupabaseBook(
        val id: String? = null,
        val userId: String? = null,
        @SerializedName("book_id_local")
        val bookIdLocal: String = "",
        val title: String = "",
        @SerializedName("file_uri")
        val fileUri: String? = null,
        @SerializedName("last_locator")
        val lastLocator: String? = null,
        @SerializedName("last_opened_at")
        val lastOpenedAt: String? = null,
        @SerializedName("is_deleted")
        val isDeleted: Boolean = false,
        @SerializedName("deleted_at")
        val deletedAt: String? = null
)

data class SupabaseAiNote(
        val id: String? = null,
        @SerializedName("user_id")
        val userId: String? = null,
        @SerializedName("book_id")
        val bookId: String? = null,
        @SerializedName("book_title")
        val bookTitle: String? = null,
        @SerializedName("original_text")
        val originalText: String? = null,
        @SerializedName("ai_response")
        val aiResponse: String? = null,
        val locator: JsonElement? = null,
        @SerializedName("created_at")
        val createdAt: String? = null,
        @SerializedName("updated_at")
        val updatedAt: String? = null
)

data class SupabaseBookmark(
        val id: String? = null,
        val userId: String? = null,
        val bookId: String = "",
        val locator: JsonElement? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null
)

data class SupabaseAiProfile(
        val id: String? = null,
        val userId: String? = null,
        val name: String = "",
        val modelName: String? = null,
        val apiKey: String? = null,
        val serverBaseUrl: String? = null,
        val systemPrompt: String? = null,
        val userPromptTemplate: String? = null,
        val useStreaming: Boolean? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val topP: Double? = null,
        val frequencyPenalty: Double? = null,
        val presencePenalty: Double? = null,
        val assistantRole: String? = null,
        val enableGoogleSearch: Boolean? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null
)

private object SyncCrypto {
        private const val ALGORITHM_AES = "AES"
        private const val TRANSFORMATION_ECB = "AES/ECB/PKCS5Padding" // Legacy
        private const val TRANSFORMATION_GCM = "AES/GCM/NoPadding"    // New (Secure)
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        
        // Use a fixed key for simplicity in this context.
        private const val KEY_STR = "BooxReaderAiKeysSyncSecret2024!!"

        private val secretKeySpec = SecretKeySpec(KEY_STR.toByteArray(Charsets.UTF_8), ALGORITHM_AES)

        fun encrypt(input: String): String {
                if (input.isBlank()) return ""
                return try {
                        // Generate random IV
                        val iv = ByteArray(GCM_IV_LENGTH)
                        SecureRandom().nextBytes(iv)
                        
                        val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
                        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, spec)
                        
                        val encrypted = cipher.doFinal(input.toByteArray(Charsets.UTF_8))
                        
                        // Combine IV + Encrypted Data
                        val combined = ByteArray(iv.size + encrypted.size)
                        System.arraycopy(iv, 0, combined, 0, iv.size)
                        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
                        
                        Base64.encodeToString(combined, Base64.NO_WRAP)
                } catch (e: Exception) {
                        e.printStackTrace()
                        ""
                }
        }

        fun decrypt(input: String): String {
                if (input.isBlank()) return ""
                val decoded = try {
                        Base64.decode(input, Base64.NO_WRAP)
                } catch (e: Exception) {
                        return ""
                }

                // 1. Try GCM (New Format)
                try {
                        if (decoded.size > GCM_IV_LENGTH) {
                                val cipher = Cipher.getInstance(TRANSFORMATION_GCM)
                                // Extract IV
                                val iv = ByteArray(GCM_IV_LENGTH)
                                System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH)
                                
                                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, spec)
                                
                                // Decrypt only the ciphertext part
                                return String(
                                        cipher.doFinal(decoded, GCM_IV_LENGTH, decoded.size - GCM_IV_LENGTH),
                                        Charsets.UTF_8
                                )
                        }
                } catch (e: Exception) {
                        // Failed to decrypt with GCM (likely old format or wrong key), fall through to ECB
                }

                // 2. Fallback to ECB (Old Format)
                return try {
                        val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
                        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec)
                        String(cipher.doFinal(decoded), Charsets.UTF_8)
                } catch (e: Exception) {
                        e.printStackTrace()
                        ""
                }
        }
}

data class SupabaseReaderSettings(
        val userId: String? = null,
        val pageTapEnabled: Boolean = true,
        val pageSwipeEnabled: Boolean = true,
        val contrastMode: Int = 0,
        val language: String = "system",
        val serverBaseUrl: String = "",
        val apiKey: String? = null,
        val aiModelName: String = "deepseek-chat",
        val aiSystemPrompt: String = "",
        val aiUserPromptTemplate: String = "",
        val temperature: Double = 0.7,
        val maxTokens: Int = 4096,
        val topP: Double = 1.0,
        val frequencyPenalty: Double = 0.0,
        val presencePenalty: Double = 0.0,
        val assistantRole: String = "assistant",
        val enableGoogleSearch: Boolean = true,
        val useStreaming: Boolean = false,
        val pageAnimationEnabled: Boolean = false,
        val updatedAt: Long = 0L,
        val activeProfileId: String? = null
) {
        fun toLocal(local: ReaderSettings, localProfileId: Long): ReaderSettings {
                return local.copy(
                        pageTapEnabled = pageTapEnabled,
                        pageSwipeEnabled = pageSwipeEnabled,
                        contrastMode = contrastMode,
                        serverBaseUrl = serverBaseUrl,
                        apiKey = apiKey ?: local.apiKey,
                        aiModelName = aiModelName,
                        aiSystemPrompt = aiSystemPrompt,
                        aiUserPromptTemplate = aiUserPromptTemplate,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        topP = topP,
                        frequencyPenalty = frequencyPenalty,
                        presencePenalty = presencePenalty,
                        assistantRole = assistantRole,
                        enableGoogleSearch = enableGoogleSearch,
                        useStreaming = useStreaming,
                        pageAnimationEnabled = pageAnimationEnabled,
                        language = language,
                        updatedAt = updatedAt,
                        activeProfileId = localProfileId
                )
        }

        companion object {
                fun fromLocal(
                        local: ReaderSettings,
                        remoteProfileId: String?
                ): SupabaseReaderSettings {
                        return SupabaseReaderSettings(
                                pageTapEnabled = local.pageTapEnabled,
                                pageSwipeEnabled = local.pageSwipeEnabled,
                                contrastMode = local.contrastMode,
                                language = local.language,
                                serverBaseUrl = local.serverBaseUrl,
                                apiKey = local.apiKey,
                                aiModelName = local.aiModelName,
                                aiSystemPrompt = local.aiSystemPrompt,
                                aiUserPromptTemplate = local.aiUserPromptTemplate,
                                temperature = local.temperature,
                                maxTokens = local.maxTokens,
                                topP = local.topP,
                                frequencyPenalty = local.frequencyPenalty,
                                presencePenalty = local.presencePenalty,
                                assistantRole = local.assistantRole,
                                enableGoogleSearch = local.enableGoogleSearch,
                                useStreaming = local.useStreaming,
                                pageAnimationEnabled = local.pageAnimationEnabled,
                                updatedAt = local.updatedAt,
                                activeProfileId = remoteProfileId
                        )
                }
        }
}
