package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.prefs.TokenManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserSyncRepositoryPerformanceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun benchmarkDeleteSingleVsBatch() = runBlocking {
        // Insert a bunch of dummy notes
        val numNotes = 1000
        val noteIds = mutableListOf<Long>()
        for (i in 0 until numNotes) {
            val id = db.aiNoteDao().insert(
                AiNoteEntity(
                    bookId = "book1",
                    messages = "messages_$i",
                    originalText = "text",
                    aiResponse = "response",
                    locatorJson = "locator",
                    remoteId = "remote"
                )
            )
            noteIds.add(id)
        }

        System.err.println("Total notes in DB before delete: ${db.aiNoteDao().getAll().size}")

        // Measure single deletes
        val singleTime = measureTimeMillis {
            noteIds.forEach { id -> db.aiNoteDao().deleteById(id) }
        }

        System.err.println("Single deletes time for $numNotes notes: ${singleTime}ms")

        // Now setup again for batch delete
        noteIds.clear()
        for (i in 0 until numNotes) {
            val id = db.aiNoteDao().insert(
                AiNoteEntity(
                    bookId = "book1",
                    messages = "messages_$i",
                    originalText = "text",
                    aiResponse = "response",
                    locatorJson = "locator",
                    remoteId = "remote"
                )
            )
            noteIds.add(id)
        }

        System.err.println("Total notes in DB before batch delete: ${db.aiNoteDao().getAll().size}")

        // Measure batch delete
        val batchTime = measureTimeMillis {
            db.aiNoteDao().deleteByIds(noteIds)
        }

        System.err.println("Batch delete time for $numNotes notes: ${batchTime}ms")

        System.err.println("IMPROVEMENT: ${singleTime - batchTime}ms (${String.format("%.2f", (singleTime - batchTime).toFloat() / singleTime * 100)}%)")
    }
}
