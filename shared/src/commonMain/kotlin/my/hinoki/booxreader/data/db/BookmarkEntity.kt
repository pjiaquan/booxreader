package my.hinoki.booxreader.data.db

import my.hinoki.booxreader.data.platform.currentEpochMillis
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null, // Remote doc id
    val bookId: String,
    val locatorJson: String,
    val createdAt: Long,
    val isSynced: Boolean = false,
    val updatedAt: Long = currentEpochMillis()
)
