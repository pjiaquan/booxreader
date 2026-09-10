package my.hinoki.booxreader.data.repo

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.db.BookProgressUpdate
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 閱讀進度同步（自 `UserSyncRepository` 抽出的 progress 叢集，約 240 行）。
 *
 * 這是「公開方法」也能搬出來的做法：`UserSyncRepository` 保留同名的薄 delegate，
 * 因此 `:app` 的呼叫點完全不變，而實作與相依（`host.prefs` / `host.db` /
 * `host.executeBackendRequest`）都收斂在這裡。
 */
internal class ProgressSync(private val host: UserSyncRepository) {

    fun getCachedProgress(bookId: String): String? {
        return host.prefs.getString(progressKey(bookId))
    }

    fun cacheProgress(
        bookId: String,
        locatorJson: String,
        updatedAt: Long = currentEpochMillis()
    ) {
        host.prefs.putString(progressKey(bookId), locatorJson)
        host.prefs.putLong(progressTimestampKey(bookId), updatedAt)
    }
    // --- Progress Sync ---

    suspend fun pullProgress(bookId: String): String? =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext null

                        val url =
                                "${host.pocketBaseUrl}/api/collections/progress/records?filter=(user='$userId'%26%26bookId='$bookId')"
                                                        val responseBody = host.executeBackendRequest(url)

                        val response =
                                host.json.decodeFromString<PocketBaseListResponse>(responseBody)
                        if (response.items.isEmpty()) {
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pullProgress - No remote progress found for $bookId"
                                )
                                return@withContext null
                        }

                        val item = response.items[0]
                        val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull
                        val remoteUpdatedAt = longValue(item["updatedAt"])
                        if (!locatorJson.isNullOrBlank()) {
                                cacheProgress(bookId, locatorJson, remoteUpdatedAt)
                                mergeRemoteProgressIntoLocalBook(
                                        bookId = bookId,
                                        locatorJson = locatorJson,
                                        remoteUpdatedAt = remoteUpdatedAt
                                )
                        }
                        host.logger.d(
                                "UserSyncRepository",
                                "pullProgress - Progress pulled for $bookId"
                        )
                        locatorJson
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullProgress failed for $bookId", e)
                        null
                }
        }

    suspend fun pushProgress(bookId: String, locatorJson: String, bookTitle: String? = null) =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext

                        // Check if progress record exists
                        val checkUrl =
                                "${host.pocketBaseUrl}/api/collections/progress/records?filter=(user='$userId'%26%26bookId='$bookId')"
                                                        val checkBody = host.executeBackendRequest(checkUrl)
                        val checkResponse =
                                host.json.decodeFromString<PocketBaseListResponse>(checkBody)

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
                                        "${host.pocketBaseUrl}/api/collections/progress/records/$recordId"
                                host.executeBackendRequest(updateUrl) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pushProgress - Progress updated for $bookId"
                                )
                        } else {
                                // Create new record
                                val createUrl =
                                        "${host.pocketBaseUrl}/api/collections/progress/records"
                                host.executeBackendRequest(createUrl) {
                                    method = HttpMethod.Post
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pushProgress - Progress created for $bookId"
                                )
                        }
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushProgress failed for $bookId", e)
                }
        }

    suspend fun pullAllProgress(): Int =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext 0
                        val items =
                                host.fetchAllItems(
                                        "progress",
                                        "(user='$userId')",
                                        sortParam = "-updatedAt",
                                        perPage = 100
                                )
                        var mergedCount = 0

                        val allBookIds = items.mapNotNull { it["bookId"]?.jsonPrimitive?.contentOrNull }.distinct()
                        val cachedBooks = mutableMapOf<String, BookEntity>()
                        allBookIds.chunked(900).forEach { chunk ->
                                cachedBooks.putAll(host.db.bookDao().getByIds(chunk).associateBy { it.bookId })
                        }

                        val updates = mutableListOf<BookProgressUpdate>()
                        for (item in items) {
                                val bookId = item["bookId"]?.jsonPrimitive?.contentOrNull ?: continue
                                val locatorJson = item["locatorJson"]?.jsonPrimitive?.contentOrNull ?: continue
                                val remoteUpdatedAt = longValue(item["updatedAt"])
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
                                host.db.withTransactionCompat {
                                        // ⚡ Bolt Performance: Room automatically iterates list parameters for @Update batch operations.
                                        // Skipping .chunked() avoids unnecessary collection allocations and keeps it in a single statement.
                                        host.db.bookDao().updateProgressBatch(updates)
                                }
                        }
                        host.logger.d(
                                "UserSyncRepository",
                                "pullAllProgress - merged=$mergedCount records=${items.size}"
                        )
                        mergedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullAllProgress failed", e)
                        0
                }
        }

    /**
     * Bug 4 fix: Push reading progress for all local books that have a saved position.
     * Call this BEFORE pullAllProgress() so that Device B's pulled data always reflects
     * the latest position from all other devices.
 */
    suspend fun pushAllLocalProgress(): Int =
        withContext(host.io) {
                try {
                        val books = host.db.bookDao().getAllBooks()
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
                                                        host.logger.w(
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
                        host.logger.d(
                                "UserSyncRepository",
                                "pushAllLocalProgress - Pushed $pushedCount progress records"
                        )
                        pushedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushAllLocalProgress failed", e)
                        0
                }
        }

    private suspend fun mergeRemoteProgressIntoLocalBook(
        bookId: String,
        locatorJson: String,
        remoteUpdatedAt: Long,
        localBook: BookEntity? = null
    ): Boolean {
        val local = localBook ?: host.db.bookDao().getById(bookId) ?: return false
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
        host.db.bookDao().updateProgress(bookId, locatorJson, mergedTime)
        return true
    }

    private fun progressKey(bookId: String) = "progress_$bookId"
    private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"
}
