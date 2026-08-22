package my.hinoki.booxreader.data.repo
import my.hinoki.booxreader.data.db.initBooxReaderDatabase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureTimeMillis
import org.mockito.Mockito

@RunWith(RobolectricTestRunner::class)
class PullBooksBenchmarkTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: TokenManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        initBooxReaderDatabase(context)
        server = MockWebServer()
        server.start()

        tokenManager = Mockito.mock(TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("dummy_token")
        Mockito.`when`(tokenManager.getRefreshToken()).thenReturn("dummy_refresh")
    }

    @After
    fun teardown() {
        server.shutdown()
        // Use the shared reset (close + null the singleton) so the next test
        // recreates the database instead of reusing a closed instance.
        AppDatabase.resetInstanceForTesting()
    }

    @Test
    fun benchmarkPullBooksDeleted() = runBlocking {
        // Generate 2000 books that are deleted
        val numBooks = 2000
        val itemsJson = (1..numBooks).joinToString(",") { i ->
            """{"id":"rec_$i","bookId":"book_$i","updatedAt":2000,"deleted":true}"""
        }
        val responseBody = """{"items":[$itemsJson],"page":1,"perPage":$numBooks,"totalItems":$numBooks,"totalPages":1}"""

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/collections/books/records?") == true) {
                    return MockResponse().setResponseCode(200).setBody(responseBody)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repo = UserSyncRepository(
            context = context,
            baseUrl = server.url("/").toString(),
            tokenManager = tokenManager
        )
        // Access private field to set userId
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, "user_1")

        // Pre-insert books in local db
        val db = AppDatabase.get()
        for (i in 1..numBooks) {
            db.bookDao().insert(BookEntity(
                bookId = "book_$i",
                title = "Book $i",
                fileUri = "pocketbase://book_$i",
                lastLocatorJson = null,
                lastOpenedAt = 1000L,
                deleted = false
            ))
        }

        // Run baseline
        val timeTaken = measureTimeMillis {
            repo.pullBooks()
        }
        println("=== BENCHMARK ===")
        println("Time taken for pullBooks with $numBooks deleted items: $timeTaken ms")
        println("=== BENCHMARK ===")
    }
}
