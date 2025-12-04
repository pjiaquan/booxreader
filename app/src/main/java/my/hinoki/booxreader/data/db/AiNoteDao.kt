package my.hinoki.booxreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiNoteDao {
    @Query("SELECT * FROM ai_notes ORDER BY createdAt DESC")
    suspend fun getAll(): List<AiNoteEntity>

    @Query("SELECT * FROM ai_notes WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getByBookId(bookId: String): List<AiNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: AiNoteEntity): Long

    @androidx.room.Update
    suspend fun update(note: AiNoteEntity)

    @Query("SELECT * FROM ai_notes WHERE id = :id")
    suspend fun getById(id: Long): AiNoteEntity?

    @Query("SELECT * FROM ai_notes WHERE originalText = :text ORDER BY createdAt DESC LIMIT 1")
    suspend fun findLatestByOriginalText(text: String): AiNoteEntity?
}

