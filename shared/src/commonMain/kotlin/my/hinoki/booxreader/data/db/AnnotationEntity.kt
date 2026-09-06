package my.hinoki.booxreader.data.db

import my.hinoki.booxreader.data.platform.currentEpochMillis
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AnnotationStyle {
    UNDERLINE,
    DASHED,
    GRAY_FILL
}

@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null, // Remote doc id for cross-device sync
    val bookId: String,
    val locatorJson: String, // Readium Locator JSON
    val selectedText: String,
    val note: String? = null,
    val style: String = AnnotationStyle.UNDERLINE.name,
    val createdAt: Long = currentEpochMillis(),
    val updatedAt: Long = currentEpochMillis(),
    val isSynced: Boolean = false
)
