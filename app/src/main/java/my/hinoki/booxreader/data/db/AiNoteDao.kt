package my.hinoki.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiNoteDao {
    @Query("SELECT * FROM ai_notes ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<AiNoteEntity>

    @Query("SELECT * FROM ai_notes WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC")
    suspend fun getByBookId(bookId: String): List<AiNoteEntity>
    
    @Query("SELECT * FROM ai_notes WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): AiNoteEntity?
    
    @Query("SELECT * FROM ai_notes WHERE remoteId IS NULL")
    suspend fun getLocalOnly(): List<AiNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: AiNoteEntity): Long

    @androidx.room.Update
    suspend fun update(note: AiNoteEntity)

    @Query("SELECT * FROM ai_notes WHERE id = :id")
    suspend fun getById(id: Long): AiNoteEntity?

    @Query("SELECT * FROM ai_notes WHERE id IN (:ids) ORDER BY createdAt DESC, id DESC")
    suspend fun getByIds(ids: List<Long>): List<AiNoteEntity>

    @Query("UPDATE ai_notes SET bookId = :newBookId WHERE bookId = :oldBookId")
    suspend fun migrateBookId(oldBookId: String, newBookId: String)

    @Query("DELETE FROM ai_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ai_notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM ai_notes")
    suspend fun deleteAll()
}
