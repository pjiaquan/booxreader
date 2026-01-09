package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitHubUpdateRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var repository: GitHubUpdateRepository
    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        context = ApplicationProvider.getApplicationContext()
        repository = GitHubUpdateRepository(context)

        // Inject mock server URL into repository if needed,
        // but for unit test of version logic we don't strictly need network.
        // However, to test fetchLatestRelease we need to reflectively set the URL.
        val field = GitHubUpdateRepository::class.java.getDeclaredField("apiUrl")
        field.isAccessible = true
        field.set(repository, mockWebServer.url("/").toString())
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `isNewerVersion compares correctly`() {
        // Assume current version is 1.1.162 (as seen in build.gradle.kts)
        // If BuildConfig.VERSION_NAME is different in test, we should be aware.

        assertTrue(repository.isNewerVersion("v1.1.163"))
        assertTrue(repository.isNewerVersion("1.2.0"))
        assertTrue(repository.isNewerVersion("2.0.0"))

        assertFalse(repository.isNewerVersion("v1.1.162"))
        assertFalse(repository.isNewerVersion("1.1.161"))
        assertFalse(repository.isNewerVersion("1.0.9"))
    }

    @Test
    fun `fetchLatestRelease parses JSON correctly`() = runBlocking {
        val json =
                """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/pjiaquan/booxreader/releases/tag/v1.2.0",
              "body": "Fixed some bugs",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/pjiaquan/booxreader/releases/download/v1.2.0/app-release.apk",
                  "size": 123456
                }
              ]
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setBody(json))

        val release = repository.fetchLatestRelease()

        assertNotNull(release)
        assertEquals("v1.2.0", release?.tagName)
        assertEquals(1, release?.assets?.size)
        assertEquals("app-release.apk", release?.assets?.get(0)?.name)
        assertEquals(123456L, release?.assets?.get(0)?.size)
    }
}
