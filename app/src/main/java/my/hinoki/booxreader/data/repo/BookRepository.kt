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

    /**
     * 用 fileUri 當 key，若不存在就建立一筆新的 BookEntity
     */
    suspend fun getOrCreateByUri(fileUri: String, title: String?): BookEntity {
        val existing = bookDao.getByUri(fileUri)
        if (existing != null) {
            try { syncRepo?.pushBook(existing, uploadFile = true) } catch (_: Exception) { }
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
        try { syncRepo?.pushBook(book, uploadFile = true) } catch (_: Exception) { }
        return book
    }

    /**
     * 更新閱讀進度（最後 locator + 開啟時間）
     */
    suspend fun updateProgress(bookId: String, locatorJson: String) {
        val now = System.currentTimeMillis()
        bookDao.updateProgress(bookId, locatorJson, now)
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
        bookDao.deleteById(bookId)
        // 不同步刪除到遠端以避免意外刪除跨裝置資料，僅本地清除
    }

    suspend fun markCompleted(bookId: String) {
        // 標記為已讀完：寫入全書進度 100%
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull() ?: return

        // 使用書籍來源或 fallback URI 建立 Locator
        val href = Url(entity.fileUri) ?: Url("progress://$bookId") ?: return
        val locator = Locator(
            href = href,
            mediaType = MediaType.EPUB,
            locations = Locator.Locations(
                progression = 1.0,
                totalProgression = 1.0
            )
        )
        val json = LocatorJsonHelper.toJson(locator) ?: return
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
