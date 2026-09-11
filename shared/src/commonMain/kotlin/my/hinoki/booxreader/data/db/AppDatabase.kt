package my.hinoki.booxreader.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import kotlin.concurrent.Volatile

@ConstructedBy(AppDatabaseConstructor::class)
@TypeConverters(ApiKeyConverter::class)
@Database(
    entities = [BookEntity::class, BookmarkEntity::class, AiNoteEntity::class, UserEntity::class, AiProfileEntity::class, AnnotationEntity::class],
    version = 15,
    // 匯出 schema（shared/schemas）以利 review 與 migration 測試，見 shared/build.gradle.kts 的 room.schemaLocation。
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun aiNoteDao(): AiNoteDao
    abstract fun userDao(): UserDao
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun annotationDao(): AnnotationDao

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
            } catch (_: Exception) {
                // 關閉失敗不影響測試重置：instance 無論如何都會被清掉
            }
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
 * Room KMP 的建構子宣告。
 *
 * Room 的 KSP 會為每個平台產生對應的 `actual` 實作（Android / iOS 各自一份），
 * 讓 `Room.databaseBuilder<T>()` 在非 Android 平台也能在沒有反射的情況下建立資料庫。
 * 少了這個宣告，`kspCommonMainKotlinMetadata` 會以
 * 「The @Database class must be annotated with @ConstructedBy since the source is targeting
 * non-Android platforms」失敗。
 */
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * 平台特定的 migration 清單。
 * Android 使用 legacy API（SupportSQLiteDatabase，含 MIGRATION_12_13 的資料回填）；
 * iOS 使用 KMP API（新安裝的資料庫為 version 14，實際上不會執行 migration）。
 */
expect fun databaseMigrations(): Array<Migration>
