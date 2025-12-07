package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.UserSyncRepository

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
        val bookId = fileUri  // 簡單作法：直接用 uri 當主鍵

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

    suspend fun getRecent(limit: Int = 5): List<BookEntity> {
        return bookDao.getRecent(limit)
    }

    private suspend fun pushSnapshot(bookId: String) {
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull() ?: return
        try { syncRepo?.pushBook(entity) } catch (_: Exception) { }
    }
}
