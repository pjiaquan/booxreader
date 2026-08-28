package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
  private lateinit var checker: my.hinoki.booxreader.data.repo.GitHubUpdateChecker
  private lateinit var context: Context

  @Before
  fun setup() {
    mockWebServer = MockWebServer()
    mockWebServer.start()

    context = ApplicationProvider.getApplicationContext()

    // 直接測試 shared 的 GitHubUpdateChecker（baseUrl 指向 mock server）
    checker =
        my.hinoki.booxreader.data.repo.GitHubUpdateChecker(
            baseUrl = mockWebServer.url("/").toString()
        )
  }

  @After
  fun tearDown() {
    mockWebServer.shutdown()
  }

  @Test
  fun `isNewerVersion compares correctly`() {
    // Testing logic without being tied to BuildConfig.VERSION_NAME
    val current = "1.1.170"

    assertTrue(checker.isNewerVersion("v1.1.171", current))
    assertTrue(checker.isNewerVersion("1.2.0", current))
    assertTrue(checker.isNewerVersion("2.0.0", current))

    assertFalse(checker.isNewerVersion("v1.1.170", current))
    assertFalse(checker.isNewerVersion("1.1.169", current))
    assertFalse(checker.isNewerVersion("1.0.9", current))
  }

  /**
   * Regression: identical versions must never trigger an update notification.
   * Previously, when numeric parsing failed the fallback `remoteVersion != currentVersion`
   * could return true for the same version, causing a permanent false-positive dialog.
   */
  @Test
  fun `isNewerVersion returns false when versions are identical`() {
    assertFalse(checker.isNewerVersion("v1.1.274", "1.1.274"))
    assertFalse(checker.isNewerVersion("1.1.274",  "1.1.274"))
    // With extra whitespace (possible in tag_name from GitHub API)
    assertFalse(checker.isNewerVersion("v1.1.274\n", "1.1.274"))
    assertFalse(checker.isNewerVersion(" v1.1.274 ", "1.1.274"))
  }

  /**
   * Regression: when version strings cannot be parsed as integers the old code fell back to
   * `remoteVersion != currentVersion`, which incorrectly returned true.
   * The fix returns false conservatively so no spurious dialog appears.
   */
  @Test
  fun `isNewerVersion returns false when versions cannot be parsed`() {
    // Pre-release suffixes break toInt()
    assertFalse(checker.isNewerVersion("v1.1.274-alpha", "1.1.274"))
    assertFalse(checker.isNewerVersion("v1.1.274-rc1",   "1.1.274"))
    // Both unparseable — must not show a dialog
    assertFalse(checker.isNewerVersion("nightly", "debug-build"))
  }

  @Test
  fun `isNewerVersion handles different segment counts correctly`() {
    // Remote has more segments
    assertTrue(checker.isNewerVersion("1.1.170.1", "1.1.170"))
    // Current has more segments — not newer
    assertFalse(checker.isNewerVersion("1.1.170", "1.1.170.1"))
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

    val release = checker.fetchLatestRelease()

    assertNotNull(release)
    assertEquals("v1.2.0", release?.tagName)
    assertEquals(1, release?.assets?.size)
    assertEquals("app-release.apk", release?.assets?.get(0)?.name)
    assertEquals(123456L, release?.assets?.get(0)?.size)
  }
}
