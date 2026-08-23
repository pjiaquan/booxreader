package my.hinoki.booxreader.data.db

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Android：使用 room-runtime 的 withTransaction（IMMEDIATE 交易）。 */
actual suspend fun <R> RoomDatabase.withTransactionCompat(block: suspend () -> R): R =
    withTransaction(block)

/** Android：使用 room-runtime 的 clearAllTables()。 */
actual suspend fun RoomDatabase.clearAllTablesCompat() {
    clearAllTables()
}
