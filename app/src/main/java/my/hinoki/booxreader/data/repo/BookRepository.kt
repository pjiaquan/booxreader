package my.hinoki.booxreader.data.repo

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.security.MessageDigest
import kotlin.text.Charsets

class BookRepository(
    context: Context,
    private val syncRepo: UserSyncRepository? = null
) {

    private val bookDao = AppDatabase.get(context).bookDao()

    suspend fun getBook(bookId: String): BookEntity? {
        return bookDao.getByIds(listOf(bookId)).firstOrNull()
    }

    /**
     * 用 fileUri 當 key，若不存在就建立一筆新的 BookEntity
     */
    suspend fun getOrCreateByUri(fileUri: String, title: String?): BookEntity {
        val existing = bookDao.getByUri(fileUri)
        if (existing != null) {
            try { syncRepo?.pushBook(existing, uploadFile = false) } catch (_: Exception) { }
            return existing
        }

        val now = System.currentTimeMillis()
        val bookId = generateBookId(fileUri)  // 使用安全的ID，而不是完整的URI

        val book = BookEntity(
            bookId = bookId,
            title = title,
            fileUri = fileUri,
            lastLocatorJson = null,
            lastOpenedAt = now
        )
        bookDao.insert(book)
        try { syncRepo?.pushBook(book, uploadFile = false) } catch (_: Exception) { }
        return book
    }

    /**
     * 更新閱讀進度（最後 locator + 開啟時間）
     */
    suspend fun updateProgress(bookId: String, locatorJson: String) {
        val now = System.currentTimeMillis()
        bookDao.updateProgress(bookId, locatorJson, now)
        
        // Push to 'progress' collection for Web Client compatibility
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull()
        if (entity != null) {
            try {
                syncRepo?.pushProgress(
                    bookId = bookId,
                    locatorJson = locatorJson,
                    bookTitle = entity.title
                )
            } catch (e: Exception) {
                android.util.Log.e("BookRepository", "Failed to push progress: ${e.message}")
            }
        }

        // Push to 'books' collection (Snapshot)
        pushSnapshot(bookId)
    }

    suspend fun touchOpened(bookId: String) {
        val now = System.currentTimeMillis()
        bookDao.updateLastOpened(bookId, now)
        pushSnapshot(bookId)
    }

    fun getRecent(limit: Int = 5): Flow<List<BookEntity>> {
        return bookDao.getRecent(limit)
    }

    suspend fun getRecentBooksSync(limit: Int = 5): List<BookEntity> {
        return bookDao.getRecent(limit).first()
    }

    suspend fun deleteBook(bookId: String) {
        // 1. Notify cloud (Soft Delete)
        // Only delete locally if cloud update succeeds (to prevent Zombies) OR if sync is disabled (null)
        val success = syncRepo?.softDeleteBook(bookId) ?: true

        if (success) {
            // 2. Delete locally
            bookDao.deleteById(bookId)
        } else {
            android.util.Log.w("BookRepository", "Skipping local delete for $bookId because cloud soft-delete failed.")
        }
    }

    suspend fun markCompleted(bookId: String) {
        // 標記為已讀完：寫入全書進度 100%
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull() 
            ?: throw IllegalStateException("Book not found with ID: $bookId")

        // 使用書籍來源或 fallback URI 建立 Locator
        val href = Url(entity.fileUri) ?: Url("progress://$bookId") 
            ?: throw IllegalStateException("Failed to create URL for book: ${entity.title}")
            
        val locator = Locator(
            href = href,
            mediaType = MediaType.EPUB,
            locations = Locator.Locations(
                progression = 1.0,
                totalProgression = 1.0
            )
        )
        val json = LocatorJsonHelper.toJson(locator) 
            ?: throw IllegalStateException("Failed to serialize Locator to JSON")
            
        android.util.Log.d("BookRepository", "Marking book as completed: ${entity.title}, JSON: $json")
        updateProgress(bookId, json)
    }

    private suspend fun pushSnapshot(bookId: String) {
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull() ?: return
        try { syncRepo?.pushBook(entity) } catch (_: Exception) { }
    }

    /**
     * 生成安全的bookId，使用MD5哈希避免Firestore文檔ID中的非法字符
     */
    private fun generateBookId(fileUri: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(fileUri.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
