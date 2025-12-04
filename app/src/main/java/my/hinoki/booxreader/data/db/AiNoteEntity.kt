package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_notes")
data class AiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String?, // Optional: link to a book
    val originalText: String,
    val aiResponse: String,
    val locatorJson: String? = null, // For highlighting
    val createdAt: Long = System.currentTimeMillis()
)

