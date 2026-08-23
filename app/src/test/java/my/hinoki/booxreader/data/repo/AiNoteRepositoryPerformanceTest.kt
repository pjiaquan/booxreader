package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import my.hinoki.booxreader.data.db.AiNoteDao
import my.hinoki.booxreader.data.db.AiNoteEntity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis
import org.mockito.ArgumentMatchers.anyList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiNoteRepositoryPerformanceTest {

    private lateinit var dao: AiNoteDao
    private lateinit var repo: AiNoteRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dao = mock(AiNoteDao::class.java)

        repo = AiNoteRepository(
            context = context,
            syncRepo = null
        )
        // Inject mock dao using reflection to avoid AppDatabase actual calls
        val daoField = AiNoteRepository::class.java.getDeclaredField("dao")
        daoField.isAccessible = true
        daoField.set(repo, dao)
    }

    @Test
    fun benchmarkDeleteSelectedNotes() = runTest {
        val noteIds = (1..5000L).toList()

        // Mock getByIds to return entities
        `when`(dao.getByIds(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val ids = invocation.arguments[0] as List<Long>
            ids.map {
                AiNoteEntity(
                    id = it,
                    bookId = "book1",
                    messages = "[]",
                    remoteId = null
                )
            }
        }

        `when`(dao.deleteByIds(anyList())).thenReturn(900)

        // Mock getByIds for AiNoteRepository logic (which expects chunked)
        val time = measureTimeMillis {
            repo.deleteSelectedNotes(noteIds)
        }
        println("Performance measurement: deleteSelectedNotes took ${time}ms")
    }
}
