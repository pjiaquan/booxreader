package my.hinoki.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

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
    fun getRecent(limit: Int): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE bookId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<BookEntity>

    @Query("SELECT bookId FROM books")
    suspend fun getAllBookIds(): List<String>

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteById(bookId: String)
}
