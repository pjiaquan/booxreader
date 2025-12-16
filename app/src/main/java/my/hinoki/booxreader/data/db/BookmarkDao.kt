package my.hinoki.booxreader.data.db

import androidx.room.*

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getAll(bookId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BookmarkEntity): Long

    @androidx.room.Update
    suspend fun update(entity: BookmarkEntity)

    @Delete
    suspend fun delete(entity: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE remoteId IS NULL")
    suspend fun getLocalOnly(): List<BookmarkEntity>

    @Query("UPDATE bookmarks SET bookId = :newBookId WHERE bookId = :oldBookId")
    suspend fun migrateBookId(oldBookId: String, newBookId: String)
}
