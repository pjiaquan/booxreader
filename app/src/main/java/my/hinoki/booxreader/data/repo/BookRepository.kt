package my.hinoki.booxreader.data.repo

import android.content.Context
import java.security.MessageDigest
import kotlin.text.Charsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

class BookRepository(private val context: Context, private val syncRepo: UserSyncRepository? = null) {

    private val bookDao = AppDatabase.get(context).bookDao()

    suspend fun getBook(bookId: String): BookEntity? {
        return bookDao.getByIds(listOf(bookId)).firstOrNull()
    }

    /** 用 fileUri 當 key，若不存在就建立一筆新的 BookEntity */
    suspend fun getOrCreateByUri(fileUri: String, title: String?): BookEntity {
        val existing = bookDao.getByUri(fileUri)
        if (existing != null) {
            try {
                syncRepo?.pushBook(existing, uploadFile = false)
            } catch (_: Exception) {}
            return existing
        }

        val now = System.currentTimeMillis()
        val bookId = generateBookId(fileUri) // 使用安全的ID，而不是完整的URI

        val book =
                BookEntity(
                        bookId = bookId,
                        title = title,
                        fileUri = fileUri,
                        lastLocatorJson = null,
                        lastOpenedAt = now
                )
        bookDao.insert(book)
        try {
            syncRepo?.pushBook(book, uploadFile = false)
        } catch (_: Exception) {}
        return book
    }

    /** 更新閱讀進度（最後 locator + 開啟時間） */
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
        // If success -> Delete locally (Hard)
        // If fail -> Delete locally (Soft) so UI updates, and sync will retry later
        val success = syncRepo?.softDeleteBook(bookId) ?: true

        if (success) {
            // 2. Delete locally
            bookDao.deleteById(bookId)
        } else {
            android.util.Log.w(
                    "BookRepository",
                    "Cloud soft-delete failed for $bookId. Marking as deleted locally."
            )
            // Soft delete locally
            val entity =
                    bookDao.getByIds(listOf(bookId))
                            .firstOrNull() // Note: getByIds now filters deleted=0, so this works
            // for active books

            // Wait, getByIds filters deleted=0. If I just updated BookDao to filter deleted=0,
            // `getByIds` returns a list. If I call it here, it will return the book if it's not yet
            // deleted.
            // But I need to define a way to get *any* book including deleted if I ever need to
            // resurrect?
            // For now, I'm finding the active book to mark it deleted.

            // Correction: getByIds now filters deleted=0.
            // But wait, if I am in `deleteBook`, I assume the book is currently visible (not
            // deleted).
            // So `getByIds` should return it.

            // However, `getByIds` takes a list.
            // Let's use a lower level Dao method? No, `getByIds` is what I have.
            // I should probably add `getById(bookId)` that ignores deleted flag if I need it,
            // but here checking if it exists as an active book is what I want.

            if (entity != null) {
                val deletedBook =
                        entity.copy(deleted = true, deletedAt = System.currentTimeMillis())
                bookDao.update(deletedBook)
            } else {
                // Try to see if it exists but is already deleted?
                // If it is already deleted, we don't need to do anything.
                // But wait, if `getByIds` filters deleted, how do I get the entity to update it?
                // `update` takes an entity. I need to fetch the existing entity first.

                // If `getByIds` returns null, it might mean the book is already deleted OR doesn't
                // exist.
                // If it doesn't exist, we can't update it.
                // So this logic seems fine.
            }
        }
    }

    suspend fun markCompleted(bookId: String) {
        // 標記為已讀完：寫入全書進度 100%
        val entity =
                bookDao.getByIds(listOf(bookId)).firstOrNull()
                        ?: throw IllegalStateException("Book not found with ID: $bookId")

        // 使用書籍來源或 fallback URI 建立 Locator
        val href =
                Url(entity.fileUri)
                        ?: Url("progress://$bookId")
                                ?: throw IllegalStateException(
                                "Failed to create URL for book: ${entity.title}"
                        )

        val locator =
                Locator(
                        href = href,
                        mediaType = MediaType.EPUB,
                        locations = Locator.Locations(progression = 1.0, totalProgression = 1.0)
                )
        val json =
                LocatorJsonHelper.toJson(locator)
                        ?: throw IllegalStateException("Failed to serialize Locator to JSON")

        android.util.Log.d(
                "BookRepository",
                "Marking book as completed: ${entity.title}, JSON: $json"
        )
        updateProgress(bookId, json)
    }

    private suspend fun pushSnapshot(bookId: String) {
        val entity = bookDao.getByIds(listOf(bookId)).firstOrNull() ?: return
        try {
            syncRepo?.pushBook(entity)
        } catch (_: Exception) {}
    }

    /** Generate safe bookId using SHA-256 of file content */
    private fun generateBookId(fileUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val uri = android.net.Uri.parse(fileUri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            } ?: run {
                // Fallback to URI hash if file open failed
                digest.update(fileUri.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Fallback to URI hash on error
            digest.update(fileUri.toByteArray(Charsets.UTF_8))
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
