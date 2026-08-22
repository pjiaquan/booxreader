package my.hinoki.booxreader.data.db

import androidx.room.ColumnInfo

data class BookProgressUpdate(
    @ColumnInfo(name = "bookId") val bookId: String,
    @ColumnInfo(name = "lastLocatorJson") val lastLocatorJson: String,
    @ColumnInfo(name = "lastOpenedAt") val lastOpenedAt: Long
)
