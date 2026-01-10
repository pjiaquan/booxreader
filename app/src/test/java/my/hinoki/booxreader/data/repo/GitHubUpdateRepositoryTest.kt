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
    // Testing logic without being tied to BuildConfig.VERSION_NAME
    val current = "1.1.170"

    assertTrue(repository.isNewerVersion("v1.1.171", current))
    assertTrue(repository.isNewerVersion("1.2.0", current))
    assertTrue(repository.isNewerVersion("2.0.0", current))

    assertFalse(repository.isNewerVersion("v1.1.170", current))
    assertFalse(repository.isNewerVersion("1.1.169", current))
    assertFalse(repository.isNewerVersion("1.0.9", current))
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
