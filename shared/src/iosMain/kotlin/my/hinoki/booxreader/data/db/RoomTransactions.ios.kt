package my.hinoki.booxreader.data.db

import androidx.room.RoomDatabase

/**
 * iOS：Room 2.8.4 的 iOS klib 未匯出 `RoomDatabase.withTransaction`，
 * 目前直接執行 block（非原子；每筆 DAO 寫入仍各自原子）。
 * 待 Room KMP 提供完整交易 API（如 useWriterConnection/Transactor）後升級。
 */
actual suspend fun <R> RoomDatabase.withTransactionCompat(block: suspend () -> R): R = block()

/** iOS：Room klib 未匯出 clearAllTables()，改用各 DAO 的 DELETE ALL。 */
actual suspend fun RoomDatabase.clearAllTablesCompat() {
    bookDao().deleteAll()
    bookmarkDao().deleteAll()
    aiNoteDao().deleteAll()
    aiProfileDao().deleteAll()
    userDao().clearAllUsers()
}
