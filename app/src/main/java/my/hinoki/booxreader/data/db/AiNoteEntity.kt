package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_notes")
data class AiNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null, // Firestore doc id for cross-device sync
    val bookId: String?, // Optional: link to a book
    val bookTitle: String? = null,
    val messages: String, // JSON Array of conversation turns
    val locatorJson: String? = null, // For highlighting
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
