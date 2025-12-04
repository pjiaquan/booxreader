package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
//    @PrimaryKey(autoGenerate = true) val id: Long = 0,
//    val bookId: String,
    @PrimaryKey val bookId: String,
    val title: String?,
    val fileUri: String,
    val lastLocatorJson: String?,
    val lastOpenedAt: Long
)

