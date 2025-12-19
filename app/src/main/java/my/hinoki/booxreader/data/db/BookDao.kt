package my.hinoki.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE fileUri = :uri AND deleted = 0 LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastLocatorJson = :locatorJson, lastOpenedAt = :time WHERE bookId = :bookId")
    suspend fun updateProgress(bookId: String, locatorJson: String, time: Long)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE bookId = :bookId")
    suspend fun updateLastOpened(bookId: String, time: Long)

    @Query("SELECT * FROM books WHERE deleted = 0 ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId IN (:ids) AND deleted = 0")
    suspend fun getByIds(ids: List<String>): List<BookEntity>

    @Query("SELECT * FROM books WHERE deleted = 0")
    suspend fun getAllBooks(): List<BookEntity>

    @Query("SELECT bookId FROM books WHERE deleted = 0")
    suspend fun getAllBookIds(): List<String>

    @Query("SELECT * FROM books WHERE title = :title AND deleted = 0")
    suspend fun findByTitle(title: String): List<BookEntity>

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteById(bookId: String)

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    @Query("SELECT * FROM books WHERE deleted = 1")
    suspend fun getPendingDeletes(): List<BookEntity>
}
