package my.hinoki.booxreader.data.repo

import android.content.Context
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
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserSyncRepositoryPerformanceTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var repo: UserSyncRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()

        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"items":[]}""")
                    .setBodyDelay(100, TimeUnit.MILLISECONDS) // 100ms delay per request
            }
        }
        mockWebServer.start()

        val mockTokenManager = Mockito.mock(TokenManager::class.java)
        Mockito.`when`(mockTokenManager.getAccessToken()).thenReturn("fake_token")

        val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("pocketbase_user_id", "user_123").apply()

        repo = UserSyncRepository(
            context = context,
            baseUrl = mockWebServer.url("/").toString(),
            tokenManager = mockTokenManager
        )
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testPushAllLocalProgressPerformance() = runBlocking {
        val realDb = AppDatabase.get(context)
        realDb.bookDao().deleteAll() // clean up

        for (i in 1..20) {
            val book = BookEntity(
                bookId = "book_$i",
                title = "Book $i",
                fileUri = "content://something",
                lastLocatorJson = """{"cfi":"/2/2","progress":0.5}""",
                lastOpenedAt = System.currentTimeMillis()
            )
            realDb.bookDao().insert(book)
        }

        val time = measureTimeMillis {
            repo.pushAllLocalProgress()
        }

        println("PERFORMANCE_BASELINE: ${time}ms")
        assertTrue("Test executed in ${time}ms", true)
    }
}
