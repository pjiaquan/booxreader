package my.hinoki.booxreader.data.repo

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 書籤同步（自 `UserSyncRepository` 抽出的 bookmark 叢集，約 150 行）。
 *
 * `UserSyncRepository` 保留同名的薄 delegate，因此 `:app` 的呼叫點完全不變。
 */

internal class BookmarkSync(private val host: UserSyncRepository) {

    suspend fun pullBookmarks(bookId: String? = null): Int =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext 0

                        val filterParam =
                                if (bookId != null) {
                                        "(user='$userId'%26%26bookId='$bookId')"
                                } else {
                                        "(user='$userId')"
                                }

                        val items =
                                host.fetchAllItems(
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
                                cachedBookmarks.putAll(host.db.bookmarkDao().getByRemoteIds(chunk).associateBy { it.remoteId!! })
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
                                host.db.withTransactionCompat {
                                        // ⚡ Bolt Performance: Room automatically iterates list parameters for @Insert batch operations.
                                        // Skipping .chunked() avoids unnecessary collection allocations and keeps it in a single statement.
                                        host.db.bookmarkDao().insertBatch(bookmarksToInsert)
                                }
                        }

                        host.logger.d(
                                "UserSyncRepository",
                                "pullBookmarks - Synced $syncedCount bookmarks"
                        )
                        syncedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullBookmarks failed", e)
                        0
                }
        }

    suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext null

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
                                                "${host.pocketBaseUrl}/api/collections/bookmarks/records/${entity.remoteId}"
                                        val responseBody = host.executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        val response =
                                                host.json.parseToJsonElement(responseBody).jsonObject

                                        entity.copy(
                                                remoteId = response["id"]?.jsonPrimitive?.contentOrNull
                                                                ?: entity.remoteId,
                                                isSynced = true
                                        )
                                } else {
                                        // Create new bookmark
                                        val createUrl =
                                                "${host.pocketBaseUrl}/api/collections/bookmarks/records"
                                        val responseBody = host.executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        val response =
                                                host.json.parseToJsonElement(responseBody).jsonObject

                                        entity.copy(
                                                remoteId = response["id"]?.jsonPrimitive?.contentOrNull,
                                                isSynced = true
                                        )
                                }

                        host.logger.d("UserSyncRepository", "pushBookmark - Bookmark synced")
                        result
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushBookmark failed", e)
                        null
                }
        }
}
