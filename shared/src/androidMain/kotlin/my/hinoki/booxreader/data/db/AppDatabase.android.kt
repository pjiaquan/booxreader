package my.hinoki.booxreader.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization

// ---------------------------------------------------------------------------
// Android 平台實作：Room.databaseBuilder(context) + legacy Migration API。
// legacy API 支援 cursor 操作，因此 MIGRATION_12_13 保留完整的資料回填邏輯。
// ---------------------------------------------------------------------------

/** 測試或特殊環境下預先注入 Application Context（正式環境由 BooxReaderApp 初始化）。 */
fun initBooxReaderDatabase(context: Context) {
    my.hinoki.booxreader.data.platform.initPlatformContext(context)
}

actual fun buildAppDatabase(): AppDatabase {
    val context = my.hinoki.booxreader.data.platform.requireAppContext()
    return Room.databaseBuilder(context, AppDatabase::class.java, "boox_reader.db")
        .addMigrations(*databaseMigrations())
        .build()
}

actual fun databaseMigrations(): Array<Migration> =
    arrayOf(
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14
    )

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
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

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `bookTitle` TEXT")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `remoteId` TEXT")
        database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("UPDATE `ai_notes` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `remoteId` TEXT")
        database.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("UPDATE `bookmarks` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
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

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `temperature` REAL NOT NULL DEFAULT 0.7")
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `maxTokens` INTEGER NOT NULL DEFAULT 4096")
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `topP` REAL NOT NULL DEFAULT 1.0")
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `frequencyPenalty` REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `presencePenalty` REAL NOT NULL DEFAULT 0.0")
    }
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `assistantRole` TEXT NOT NULL DEFAULT 'assistant'")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `enableGoogleSearch` INTEGER NOT NULL DEFAULT 1")
    }
}

private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `books` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `books` ADD COLUMN `deletedAt` INTEGER")
    }
}

private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `originalText` TEXT")
        database.execSQL("ALTER TABLE `ai_notes` ADD COLUMN `aiResponse` TEXT")

        val cursor =
            database.query(
                "SELECT id, messages, originalText, aiResponse FROM ai_notes"
            )
        val update =
            database.compileStatement(
                "UPDATE ai_notes SET originalText = ?, aiResponse = ? WHERE id = ?"
            )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val messages = it.getString(1)
                val existingOriginal =
                    it.getString(2)?.takeIf { value -> value.isNotBlank() }
                val existingResponse =
                    it.getString(3)?.takeIf { value -> value.isNotBlank() }

                val derivedOriginal =
                    existingOriginal
                        ?: AiNoteSerialization.originalTextFromMessages(messages)
                val derivedResponse =
                    existingResponse
                        ?: AiNoteSerialization.aiResponseFromMessages(messages)

                if (derivedOriginal != existingOriginal || derivedResponse != existingResponse) {
                    if (derivedOriginal == null) {
                        update.bindNull(1)
                    } else {
                        update.bindString(1, derivedOriginal)
                    }
                    if (derivedResponse == null) {
                        update.bindNull(2)
                    } else {
                        update.bindString(2, derivedResponse)
                    }
                    update.bindLong(3, id)
                    update.executeUpdateDelete()
                    update.clearBindings()
                }
            }
        }
    }
}

private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `ai_profiles` ADD COLUMN `extraParamsJson` TEXT")
    }
}
