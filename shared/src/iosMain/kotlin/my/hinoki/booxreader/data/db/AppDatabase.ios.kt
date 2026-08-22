package my.hinoki.booxreader.data.db

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

// ---------------------------------------------------------------------------
// iOS 平台實作：KMP Room + bundled SQLite driver。
// 新安裝的資料庫直接建立為 version 14，因此 migration 清單為空；
// 未來版本演進時在此補上 KMP Migration。
// ---------------------------------------------------------------------------

actual fun buildAppDatabase(): AppDatabase =
    Room.databaseBuilder<AppDatabase>("boox_reader.db")
        .setDriver(BundledSQLiteDriver())
        .build()

actual fun databaseMigrations(): Array<Migration> = emptyArray()
