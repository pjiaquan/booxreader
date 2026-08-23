package my.hinoki.booxreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import kotlin.concurrent.Volatile

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, AiNoteEntity::class, UserEntity::class, AiProfileEntity::class],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun aiNoteDao(): AiNoteDao
    abstract fun userDao(): UserDao
    abstract fun aiProfileDao(): AiProfileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * 平台特定建置（androidMain / iosMain 提供 actual）。
         * 注意：commonMain 沒有 synchronized（JVM-only），首次呼叫的 race 可接受
         * （最壞情況多建一個 Room instance；Room 對同一檔案的多次連線是安全的）。
         */
        fun get(): AppDatabase {
            return INSTANCE ?: buildAppDatabase().also { INSTANCE = it }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            try {
                INSTANCE?.close()
            } catch (_: Exception) {}
            INSTANCE = null
        }
    }
}

/**
 * 平台特定的 Room 建置。Android：Room.databaseBuilder(context...)；
 * iOS：Room.databaseBuilder + NativeSqliteDriver。
 */
expect fun buildAppDatabase(): AppDatabase

/**
 * 平台特定的 migration 清單。
 * Android 使用 legacy API（SupportSQLiteDatabase，含 MIGRATION_12_13 的資料回填）；
 * iOS 使用 KMP API（新安裝的資料庫為 version 14，實際上不會執行 migration）。
 */
expect fun databaseMigrations(): Array<Migration>
