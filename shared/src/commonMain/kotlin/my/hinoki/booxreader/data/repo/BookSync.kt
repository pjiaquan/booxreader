package my.hinoki.booxreader.data.repo

import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.platform.platformFiles

/**
 * 書本 / 書檔同步（自 `UserSyncRepository` 抽出的 book 叢集，約 640 行）。
 *
 * 涵蓋推送書本記錄（含檔案上傳）、拉取遠端書本、軟刪除、補上傳，以及啟動時的
 * 「確認每本在地書本的檔案都存在於伺服器」檢查。
 *
 * `UserSyncRepository` 保留同名的薄 delegate，因此 `:app` 的呼叫點完全不變；
 * 與 storage 相關的 helper（`tryUploadBookFile` / `resolveStoragePathFromRecord` 等）
 * 以 `host.xxx(...)` 呼叫遠端儲存叢集（`RemoteBookStorage.kt`）。
 */
internal class BookSync(private val host: UserSyncRepository) {

    suspend fun pushBook(
        book: BookEntity,
        uploadFile: Boolean = false
    ): Boolean =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext false
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
                                "${host.pocketBaseUrl}/api/collections/books/records?filter=(user='$userId'%26%26bookId='${book.bookId}')&perPage=1"
                                                        val checkBody = host.executeBackendRequest(checkUrl)
                        val checkResponse =
                                host.json.decodeFromString<PocketBaseListResponse>(checkBody)
                        val existingItem = checkResponse.items.firstOrNull()
                        val remoteHasFilePath =
                                !host.resolveStoragePathFromRecord(existingItem).isNullOrBlank()
                        var remoteDeleted = false
                        var recordId = existingItem?.get("id")?.jsonPrimitive?.contentOrNull

                        if (existingItem != null) {
                                // Bug 3 fix: use longValue() instead of Double cast
                                // which silently fails when PocketBase returns updatedAt as a String.
                                val remoteUpdatedAt = longValue(existingItem["updatedAt"])
                                remoteDeleted = existingItem["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                                val needsFileBackfill =
                                        uploadFile &&
                                                
                                                (!remoteHasFilePath || remoteDeleted)
                                if (!book.deleted &&
                                                !remoteDeleted &&
                                                remoteUpdatedAt > payloadUpdatedAt &&
                                                !needsFileBackfill
                                ) {
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "pushBook - Skip stale local update for ${book.bookId}"
                                        )
                                        return@withContext true
                                }

                                val safeRecordId = recordId ?: return@withContext false
                                val updateUrl =
                                        "${host.pocketBaseUrl}/api/collections/books/records/$safeRecordId"
                                host.executeBackendRequest(updateUrl) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                        } else {
                                val createUrl =
                                        "${host.pocketBaseUrl}/api/collections/books/records"
                                val createBody =
                                        try {
                                                host.executeBackendRequest(createUrl) {
                                                        method = HttpMethod.Post
                                                        contentType(ContentType.Application.Json)
                                                        setBody(requestBody)
                                                }
                                        } catch (e: Exception) {
                                        if (e.message?.contains("400") == true && e.message?.contains("sql: no rows in result set") == true) {
                                                host.logger.w("UserSyncRepository", "pushBook - stale user ID detected, refreshing auth session and retrying")
                                                val pocketBaseRoot = host.pocketBaseUrl.removeSuffix("/api")
                                                val refreshedUserId = host.refreshAuthSessionIfPossible(pocketBaseRoot)
                                                if (!refreshedUserId.isNullOrBlank() && refreshedUserId != userId) {
                                                        // Update the payload using the new valid ID
                                                        val mutableData = bookData.toMutableMap()
                                                        mutableData["user"] = refreshedUserId
                                                        val retryBody = mapToJsonString(mutableData)
                                                        host.executeBackendRequest(createUrl) {
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
                                        host.json.parseToJsonElement(createBody).jsonObject
                                recordId = created["id"]?.jsonPrimitive?.contentOrNull
                        }

                        if (uploadFile &&
                                        
                                        (!remoteHasFilePath || remoteDeleted)
                        ) {
                                val uploadStoragePath =
                                        host.tryUploadBookFile(
                                                recordId = recordId,
                                                book = book,
                                        )
                                if (!uploadStoragePath.isNullOrBlank() &&
                                                uploadStoragePath != storagePath &&
                                                recordId != null
                                ) {
                                        storagePath = uploadStoragePath
                                        host.updateBookStoragePath(
                                                recordId = recordId,
                                                storagePath = uploadStoragePath
                                        )
                                }
                        }

                        host.logger.d("UserSyncRepository", "pushBook - Synced book ${book.bookId}")
                        true
                } catch (e: Exception) {
                        host.logger.e(
                                "UserSyncRepository",
                                "pushBook failed for ${book.bookId}",
                                e
                        )
                        false
                }
        }

    suspend fun ensureRemoteBookFilePresent(book: BookEntity): Boolean =
        withContext(host.io) {
                try {
                        if (book.deleted) return@withContext true
                        val userId = host.getUserId() ?: return@withContext false
                        val remoteRecord = host.fetchBookRecord(userId, book.bookId)
                        if (remoteRecord == null) {
                                return@withContext pushBook(
                                        book,
                                        uploadFile = true,
                                )
                        }

                        val recordId = remoteRecord["id"]?.jsonPrimitive?.contentOrNull
                        val remoteDeleted = remoteRecord["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
                        val storagePath = host.resolveStoragePathFromRecord(remoteRecord)

                        if (recordId.isNullOrBlank() ||
                                        remoteDeleted ||
                                        storagePath.isNullOrBlank()
                        ) {
                                return@withContext pushBook(
                                        book,
                                        uploadFile = true,
                                )
                        }

                        val remoteUrl = host.buildDownloadUrl(storagePath, recordId)
                        if (remoteUrl.isNullOrBlank()) {
                                return@withContext pushBook(
                                        book,
                                        uploadFile = true,
                                )
                        }

                        when (host.probeRemoteFileState(remoteUrl)) {
                                RemoteFileState.PRESENT -> true
                                RemoteFileState.UNKNOWN -> {
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "ensureRemoteBookFilePresent - Skip reupload for unknown remote state ${book.bookId}"
                                        )
                                        true
                                }
                                RemoteFileState.MISSING -> {
                                        val uploadedStoragePath =
                                                host.tryUploadBookFile(
                                                        recordId = recordId,
                                                        book = book,
                                                )
                                        if (uploadedStoragePath.isNullOrBlank()) {
                                                host.logger.w(
                                                        "UserSyncRepository",
                                                        "ensureRemoteBookFilePresent - Reupload failed for ${book.bookId}"
                                                )
                                                return@withContext false
                                        }
                                        val normalizedCurrent =
                                                normalizeStoragePath(storagePath)
                                        if (uploadedStoragePath != normalizedCurrent) {
                                                host.updateBookStoragePath(
                                                        recordId = recordId,
                                                        storagePath = uploadedStoragePath
                                                )
                                        }
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "ensureRemoteBookFilePresent - Reuploaded missing file for ${book.bookId}"
                                        )
                                        true
                                }
                        }
                } catch (e: Exception) {
                        host.logger.e(
                                "UserSyncRepository",
                                "ensureRemoteBookFilePresent failed for ${book.bookId}",
                                e
                        )
                        host.reporter.report("UserSyncRepository.ensureRemoteBookFilePresent",
                                "Failed to ensure remote file for ${book.bookId}",
                                e
                        )
                        false
                }
        }

    suspend fun softDeleteBook(bookId: String): Boolean =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext false
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
                                "${host.pocketBaseUrl}/api/collections/books/records?filter=(user='$userId'%26%26bookId='$bookId')&perPage=1"
                                                        val checkBody = host.executeBackendRequest(checkUrl)
                        val checkResponse =
                                host.json.decodeFromString<PocketBaseListResponse>(checkBody)

                        if (checkResponse.items.isNotEmpty()) {
                                val recordId =
                                        checkResponse.items[0]["id"]?.jsonPrimitive?.contentOrNull
                                                ?: return@withContext false
                                val updateUrl =
                                        "${host.pocketBaseUrl}/api/collections/books/records/$recordId"
                                host.executeBackendRequest(updateUrl) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                        } else {
                                val createUrl =
                                        "${host.pocketBaseUrl}/api/collections/books/records"
                                host.executeBackendRequest(createUrl) {
                                    method = HttpMethod.Post
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                        }

                        host.logger.d(
                                "UserSyncRepository",
                                "softDeleteBook - Synced deletion for $bookId"
                        )
                        true
                } catch (e: Exception) {
                        host.logger.e(
                                "UserSyncRepository",
                                "softDeleteBook failed for $bookId",
                                e
                        )
                        false
                }
        }

    suspend fun pushLocalBooks(): Int =
        withContext(host.io) {
                try {
                        val localBooks = host.db.bookDao().getAllBooks()

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

                        val pendingDeletes = host.db.bookDao().getPendingDeletes()
                        val successfullyDeletedBookIds = mutableListOf<String>()
                        for (deletedBook in pendingDeletes) {
                                val deleted = softDeleteBook(deletedBook.bookId)
                                if (deleted) {
                                        successfullyDeletedBookIds.add(deletedBook.bookId)
                                }
                        }
                        if (successfullyDeletedBookIds.isNotEmpty()) {
                                successfullyDeletedBookIds.chunked(900).forEach { chunk ->
                                        host.db.bookDao().deleteByIds(chunk)
                                }
                                syncedCount += successfullyDeletedBookIds.size
                        }

                        host.logger.d(
                                "UserSyncRepository",
                                "pushLocalBooks - Synced $syncedCount local books/deletes"
                        )
                        syncedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushLocalBooks failed", e)
                        0
                }
        }

    suspend fun pullBooks(): Int =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext 0

                        val items =
                                host.fetchAllItems(
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
                                cachedBooks.putAll(host.db.bookDao().getByIds(chunk).associateBy { it.bookId })
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
                                val resolvedStoragePath = host.resolveStoragePathFromRecord(item)


                                // Check if book exists locally
                                val existingBook = cachedBooks[bookId]

                                if (existingBook == null) {
                                        // New book from cloud.
                                        // Use remote books.updatedAt as a proxy for "when was this added" so the
                                        // book appears in the recent list. For unread books, there is no progress
                                        // record yet, so this is the only timestamp available.
                                        // Note: lastOpenedAt for READ books is authoritative in the 'progress'
                                        // collection and is corrected by pullAllProgress() after this.
                                        val remoteAddedAt = longValue(item["updatedAt"])
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
                                host.db.withTransactionCompat {
                                        // ⚡ Bolt Performance: Room automatically iterates list parameters for @Insert batch operations.
                                        // Skipping .chunked() avoids unnecessary collection allocations and keeps it in a single statement.
                                        host.db.bookDao().insertBatch(booksToInsert)
                                }
                        }

                        if (deletedBookIds.isNotEmpty()) {
                                deletedBookIds.chunked(900).forEach { chunk ->
                                        host.db.bookDao().deleteByIds(chunk)
                                }
                        }

                        host.logger.d("UserSyncRepository", "pullBooks - Synced $syncedCount books")
                        syncedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullBooks failed", e)
                        0
                }
        }

    suspend fun downloadPendingRemoteBooks(): Int =
        withContext(host.io) {
                try {
                        val books = host.db.bookDao().getAllBooks()
                        var downloadedCount = 0
                        for (book in books) {
                                if (!book.fileUri.startsWith("pocketbase://")) {
                                        continue
                                }
                                val cachedPath = host.localBookCacheFile(book.bookId)
                                // Bug 5 fix: check for valid ZIP (EPUB) magic bytes rather
                                // than just length > 0. A partial download leaves a non-zero
                                // file that would be accepted silently otherwise.
                                if (platformFiles().exists(cachedPath) &&
                                        host.isValidEpubFile(cachedPath)
                                ) {
                                        continue
                                }
                                // Delete any corrupt/partial file so ensureBookFileAvailable
                                // will re-download it cleanly.
                                if (platformFiles().exists(cachedPath)) {
                                        platformFiles().delete(cachedPath)
                                        host.logger.w(
                                                "UserSyncRepository",
                                                "downloadPendingRemoteBooks - Deleted corrupt cache for ${book.bookId}"
                                        )
                                }
                                val storagePath =
                                        book.fileUri
                                                .removePrefix("pocketbase://")
                                                .takeIf { it.contains("/") }
                                val localUri =
                                        host.ensureBookFileAvailable(
                                                book.bookId,
                                                storagePath = storagePath,
                                                originalUri = book.fileUri
                                        )
                                if (localUri != null) {
                                        downloadedCount++
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "downloadPendingRemoteBooks - Downloaded ${book.bookId}"
                                        )
                                }
                        }
                        host.logger.d(
                                "UserSyncRepository",
                                "downloadPendingRemoteBooks - Downloaded $downloadedCount books"
                        )
                        downloadedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "downloadPendingRemoteBooks failed", e)
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
        withContext(host.io) {
                try {
                        val userId = host.getUserId()
                        if (userId.isNullOrBlank()) {
                                host.logger.d(
                                        "UserSyncRepository",
                                        "ensureAllLocalBooksUploaded - no user, skipping"
                                )
                                return@withContext 0
                        }

                        val books = host.db.bookDao().getAllBooks()
                        // Only process books with local content:// URIs — these are the
                        // ones that could potentially be missing from the server.
                        val localBooks =
                                books.filter { book ->
                                        !book.deleted &&
                                                !book.fileUri.startsWith("pocketbase://")
                                }

                        if (localBooks.isEmpty()) {
                                host.logger.d(
                                        "UserSyncRepository",
                                        "ensureAllLocalBooksUploaded - no local books to check"
                                )
                                return@withContext 0
                        }

                        host.logger.d(
                                "UserSyncRepository",
                                "ensureAllLocalBooksUploaded - checking ${localBooks.size} local books"
                        )

                        var uploadedCount = 0
                        for (book in localBooks) {
                                try {
                                        // Check remote record for this book
                                        val checkUrl =
                                                "${host.pocketBaseUrl}/api/collections/books/records" +
                                                        "?filter=${urlEncodeQueryValue("bookId='${book.bookId}'")}" +
                                                        "&fields=id,storagePath,epub,file,bookFile,updatedAt"
                                                                                        val checkBody = host.executeBackendRequest(checkUrl, reportError = false)
                                        val checkResponse = runCatching {
                                                host.json.decodeFromString<PocketBaseListResponse>(checkBody)
                                        }.getOrNull()
                                        val existingItem = checkResponse?.items?.firstOrNull()
                                        val remoteHasFile =
                                                !host.resolveStoragePathFromRecord(existingItem)
                                                        .isNullOrBlank()

                                        if (remoteHasFile) {
                                                // Server already has the file, nothing to do
                                                host.logger.d(
                                                        "UserSyncRepository",
                                                        "ensureAllLocalBooksUploaded - ${book.bookId} already on server"
                                                )
                                                continue
                                        }

                                        // Remote has no file — upload it now
                                        host.logger.i(
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
                                                host.logger.i(
                                                        "UserSyncRepository",
                                                        "ensureAllLocalBooksUploaded - uploaded ${book.bookId} ('${book.title}')"
                                                )
                                        }
                                } catch (e: Exception) {
                                        host.logger.w(
                                                "UserSyncRepository",
                                                "ensureAllLocalBooksUploaded - failed for ${book.bookId}",
                                                e
                                        )
                                }
                        }

                        host.logger.d(
                                "UserSyncRepository",
                                "ensureAllLocalBooksUploaded - uploaded $uploadedCount / ${localBooks.size} books"
                        )
                        uploadedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "ensureAllLocalBooksUploaded failed", e)
                        0
                }
        }
}
