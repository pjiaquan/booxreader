package my.hinoki.booxreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, AiNoteEntity::class, UserEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun aiNoteDao(): AiNoteDao
    abstract fun userDao(): UserDao

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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "boox_reader.db"
                )
                .addMigrations(MIGRATION_3_4)
                // .fallbackToDestructiveMigration() // REMOVED: unsafe for production
                .build().also { INSTANCE = it }
            }
        }
    }
}

