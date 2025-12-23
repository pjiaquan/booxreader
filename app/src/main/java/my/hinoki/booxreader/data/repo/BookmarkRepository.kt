package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.reader.LocatorJsonHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import my.hinoki.booxreader.data.remote.BookmarkPublisher

import my.hinoki.booxreader.data.remote.HttpConfig

import okhttp3.OkHttpClient
import my.hinoki.booxreader.data.repo.UserSyncRepository

class BookmarkRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val syncRepo: UserSyncRepository? = null
) {

    private val db = AppDatabase.get(context)
    private val dao = db.bookmarkDao()

    private fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL) ?: HttpConfig.DEFAULT_BASE_URL
    }

    // ✨ 新增：HTTP 發佈工具
    private val publisher = BookmarkPublisher(baseUrlProvider = { getBaseUrl(context) }, client = client)

    suspend fun getBookmarks(bookId: String): List<BookmarkEntity> =
        withContext(Dispatchers.IO) {
            runCatching { syncRepo?.pullBookmarks(bookId) }
            dao.getAll(bookId)
        }

    suspend fun add(bookId: String, locator: Locator) =
        withContext(Dispatchers.IO) {
            val json = LocatorJsonHelper.toJson(locator) ?: return@withContext

            // 1. Initial save (isSynced = false by default)
            val entity = BookmarkEntity(
                bookId = bookId,
                locatorJson = json,
                createdAt = System.currentTimeMillis(),
                isSynced = false
            )

            val insertedId = dao.insert(entity)

            // 2) 嘗試發佈到 HTTP server（失敗只寫 log，不拋出錯誤）
            try {
                // Note: The publisher might need the ID if the server needs it, 
                // but usually the server generates its own ID or uses UUID.
                // Here we send the entity as constructed.
                publisher.publishBookmark(entity)

                // 3. If successful, update the local DB to set isSynced = true
                val syncedEntity = entity.copy(id = insertedId, isSynced = true)
                dao.update(syncedEntity)
            } catch (e: Exception) {
            }

            // 4. Firestore sync (best effort)
            runCatching {
                val result = syncRepo?.pushBookmark(entity.copy(id = insertedId))
                if (result != null) {
                    dao.update(result)
                }
            }
        }

    suspend fun deleteBookmark(entity: BookmarkEntity) =
        withContext(Dispatchers.IO) {
            dao.delete(entity)
        }
}
