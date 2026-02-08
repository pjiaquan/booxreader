package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.core.CrashReport
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserSyncRepositoryCrashReportTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: my.hinoki.booxreader.data.prefs.TokenManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pushCrashReport posts payload to crash_reports collection`() = runBlocking {
        var capturedRequest: RecordedRequest? = null
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/api/collections/crash_reports/records" &&
                                        request.method == "POST"
                        ) {
                            capturedRequest = request
                            return MockResponse().setResponseCode(200).setBody("""{"id":"cr_1"}""")
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }

        val repo =
                UserSyncRepository(
                        context = context,
                        baseUrl = server.url("/").toString(),
                        tokenManager = tokenManager
                )
        setCachedUserId(repo, "user_123")

        val report =
                CrashReport(
                        appVersion = "1.2.3",
                        versionCode = 123,
                        osVersion = "34",
                        deviceModel = "Pixel 8",
                        deviceManufacturer = "Google",
                        stacktrace = "java.lang.RuntimeException: boom",
                        message = "Boom",
                        threadName = "main",
                        createdAt = 1735689600000L
                )

        val success = repo.pushCrashReport(report)
        assertTrue("Expected crash report upload to succeed", success)

        val request = capturedRequest
        assertNotNull("Expected POST request to crash_reports", request)
        assertEquals("Bearer test-token", request!!.getHeader("Authorization"))

        val body = JSONObject(request.body.readUtf8())
        assertEquals("1.2.3", body.getString("appVersion"))
        assertEquals("34", body.getString("androidVersion"))
        assertEquals("Google Pixel 8", body.getString("deviceModel"))
        assertEquals("java.lang.RuntimeException: boom", body.getString("stackTrace"))
        assertEquals("Boom", body.getString("message"))
        assertEquals(1735689600000L, body.getLong("timestamp"))
        assertEquals("user_123", body.getString("user"))
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}
