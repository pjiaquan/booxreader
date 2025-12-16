package my.hinoki.booxreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, AiNoteEntity::class, UserEntity::class, AiProfileEntity::class],
    version = 12,
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

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `users` (" +
                            "`userId` TEXT NOT NULL, " +
                            "`email` TEXT NOT NULL, " +
                            "`displayName` TEXT, " +
                            "`avatarUrl` TEXT, " +
                            "PRIMARY KEY(`userId`))"
                )
            }
        }
        
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `bookTitle` TEXT")
            }
        }
        
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `remoteId` TEXT")
                database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE `ai_notes` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `remoteId` TEXT")
                database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE `bookmarks` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_profiles` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`modelName` TEXT NOT NULL, " +
                            "`apiKey` TEXT NOT NULL, " +
                            "`serverBaseUrl` TEXT NOT NULL, " +
                            "`systemPrompt` TEXT NOT NULL, " +
                            "`userPromptTemplate` TEXT NOT NULL, " +
                            "`useStreaming` INTEGER NOT NULL, " +
                            "`remoteId` TEXT, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, " +
                            "`isSynced` INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `temperature` REAL NOT NULL DEFAULT 0.7")
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `maxTokens` INTEGER NOT NULL DEFAULT 4096")
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `topP` REAL NOT NULL DEFAULT 1.0")
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `frequencyPenalty` REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `presencePenalty` REAL NOT NULL DEFAULT 0.0")
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `assistantRole` TEXT NOT NULL DEFAULT 'assistant'")
            }
        }
        
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `enableGoogleSearch` INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `books` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `books` ADD COLUMN `deletedAt` INTEGER")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "boox_reader.db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                // .fallbackToDestructiveMigration() // REMOVED: unsafe for production
                .build().also { INSTANCE = it }
            }
        }
    }
}
