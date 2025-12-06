package my.hinoki.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.OnConflictStrategy

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE fileUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastLocatorJson = :locatorJson, lastOpenedAt = :time WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: String, locatorJson: String, time: Long)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE bookId = :bookId")
    suspend fun updateLastOpened(bookId: String, time: Long)

    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BookEntity>

    @Query("SELECT * FROM books WHERE bookId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<BookEntity>
}
