package my.hinoki.booxreader.data.db

import androidx.room.RoomDatabase

/**
 * KMP 版 `RoomDatabase.withTransaction`。
 *
 * Room 2.8.4 的 commonMain 只有 Transactor-scoped 交易 API，`RoomDatabase.withTransaction`
 * 擴充只在 Android/JVM artifact 匯出（iOS klib 沒有）。此 expect/actual 讓兩端都有
 * `withTransactionCompat`：Android 為真正的 IMMEDIATE 交易；iOS 目前直接執行 block
 * （每筆 DAO 寫入仍各自原子，但非全部 or 全無——待 Room KMP 完整 API 後可升級）。
 */
expect suspend fun <R> RoomDatabase.withTransactionCompat(block: suspend () -> R): R
