package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null, // Firestore doc id
    val bookId: String,
    val locatorJson: String,
    val createdAt: Long,
    val isSynced: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
