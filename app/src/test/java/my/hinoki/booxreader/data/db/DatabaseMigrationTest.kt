package my.hinoki.booxreader.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room schema / migration 測試。
 *
 * 補上專案原本缺少的 migration 驗證：
 * - migration 清單必須連續（無斷層、無重疊）
 * - 最後一版 migration 必須等於資料庫目前的版本
 * - 全新安裝必須建立所有 entity 對應的資料表
 * - 14 → 15 的 migration 必須讓 Room 的 schema 驗證通過
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DatabaseMigrationTest {

    @Before
    fun setUp() {
        initBooxReaderDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        AppDatabase.resetInstanceForTesting()
    }

    @Test
    fun migrationsAreContiguousWithNoGapsOrOverlaps() {
        val sorted = databaseMigrations().sortedBy { it.startVersion }

        assertTrue("expected at least one migration", sorted.isNotEmpty())
        sorted.zipWithNext { current, next ->
            assertEquals(
                    "gap or overlap between ${current.startVersion}->${current.endVersion} " +
                            "and ${next.startVersion}->${next.endVersion}",
                    current.endVersion,
                    next.startVersion
            )
        }
    }

    @Test
    fun migrationChainReachesTheCurrentDatabaseVersion() {
        val sorted = databaseMigrations().sortedBy { it.startVersion }

        assertEquals(
                "the last migration must land on the current @Database version",
                currentDatabaseVersion(),
                sorted.last().endVersion
        )
    }

    @Test
    fun freshDatabaseContainsEveryEntityTable() {
        val tables = querySingle("SELECT name FROM sqlite_master WHERE type = 'table'")

        listOf("books", "bookmarks", "ai_notes", "users", "ai_profiles", "annotations")
                .forEach { table ->
                    assertTrue("missing table: $table (found: $tables)", tables.contains(table))
                }
    }

    @Test
    fun migration14To15RecreatesAnnotationsAndPassesRoomValidation() = runBlocking {
        // 1. 建立目前的資料庫，並記下 annotations 的欄位
        val db = AppDatabase.get()
        val expectedColumns = tableColumns(db, "annotations")
        assertTrue("annotations table should exist", expectedColumns.isNotEmpty())

        // 2. 模擬 v14 狀態：移除 annotations 並把 user_version 降回 14
        db.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE IF EXISTS annotations")
            execSQL("PRAGMA user_version = 14")
        }
        AppDatabase.resetInstanceForTesting()

        // 3. 重新開啟：Room 會執行 MIGRATION_14_15，並在開啟時驗證 schema 是否符合 v15
        val reopened = AppDatabase.get()
        reopened.annotationDao().getAll("no-such-book")

        assertEquals(expectedColumns, tableColumns(reopened, "annotations"))
    }

    // --- helpers ---

    private fun currentDatabaseVersion(): Int =
            AppDatabase.get().openHelper.readableDatabase.let { sqlite ->
                sqlite.query("PRAGMA user_version").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }

    private fun querySingle(sql: String): List<String> =
            AppDatabase.get().openHelper.readableDatabase.let { sqlite ->
                sqlite.query(sql).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
            }

    private fun tableColumns(db: AppDatabase, table: String): List<String> =
            db.openHelper.readableDatabase.let { sqlite ->
                sqlite.query("PRAGMA table_info($table)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(nameIndex))
                        }
                    }
                }
            }
}
