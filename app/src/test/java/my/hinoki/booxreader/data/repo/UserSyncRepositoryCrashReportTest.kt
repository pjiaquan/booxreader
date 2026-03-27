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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // A minimal crash report fixture reused across tests.
    private val sampleReport = CrashReport(
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns a [Dispatcher] that:
     *  - Responds to auth-refresh with [authRefreshUserId] (or 401 when null).
     *  - Captures the crash_reports POST into [captureSlot][0] and returns 200.
     *  - Returns 404 for everything else.
     */
    private fun buildDispatcher(
        authRefreshUserId: String?,
        captureSlot: Array<RecordedRequest?>
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when {
                request.path == "/api/collections/users/auth-refresh" &&
                        request.method == "POST" -> {
                    if (authRefreshUserId != null) {
                        MockResponse().setResponseCode(200).setBody(
                            """{"token":"refreshed-token","record":{"id":"$authRefreshUserId"}}"""
                        )
                    } else {
                        MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
                    }
                }
                request.path == "/api/collections/crash_reports/records" &&
                        request.method == "POST" -> {
                    captureSlot[0] = request
                    MockResponse().setResponseCode(200).setBody("""{"id":"cr_1"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Test: happy path — auth-refresh succeeds, record is created
    // ---------------------------------------------------------------------------

    @Test
    fun `pushCrashReport posts payload to crash_reports when auth-refresh succeeds`() =
        runBlocking {
            val captureSlot = arrayOfNulls<RecordedRequest>(1)
            server.dispatcher = buildDispatcher(
                authRefreshUserId = "server_user_abc",
                captureSlot = captureSlot
            )

            val repo = UserSyncRepository(
                context = context,
                baseUrl = server.url("/").toString(),
                tokenManager = tokenManager
            )

            val success = repo.pushCrashReport(sampleReport)
            assertTrue("Expected crash report upload to succeed", success)

            val request = captureSlot[0]
            assertNotNull("Expected POST request to crash_reports", request)

            // Auth header must carry the token
            assertEquals("Bearer test-token", request!!.getHeader("Authorization"))

            // Verify every field in the body
            val body = JSONObject(request.body.readUtf8())
            assertEquals("1.2.3", body.getString("appVersion"))
            assertEquals("34", body.getString("androidVersion"))
            assertEquals("Google Pixel 8", body.getString("deviceModel"))
            assertEquals("java.lang.RuntimeException: boom", body.getString("stackTrace"))
            assertEquals("Boom", body.getString("message"))
            assertEquals(1735689600000L, body.getLong("timestamp"))

            // user must be the server-confirmed ID from auth-refresh, NOT a stale local ID
            assertEquals(
                "user field must come from auth-refresh, not stale cache",
                "server_user_abc",
                body.getString("user")
            )
        }

    // ---------------------------------------------------------------------------
    // Test: auth-refresh fails → upload is skipped (no crash_reports POST sent)
    // ---------------------------------------------------------------------------

    @Test
    fun `pushCrashReport skips upload when auth-refresh fails`() = runBlocking {
        val captureSlot = arrayOfNulls<RecordedRequest>(1)
        server.dispatcher = buildDispatcher(
            authRefreshUserId = null,   // simulate expired / invalid token
            captureSlot = captureSlot
        )

        val repo = UserSyncRepository(
            context = context,
            baseUrl = server.url("/").toString(),
            tokenManager = tokenManager
        )

        val success = repo.pushCrashReport(sampleReport)
        assertFalse("Expected upload to be skipped when auth-refresh fails", success)
        assertNull(
            "crash_reports POST must NOT be sent when userId cannot be confirmed",
            captureSlot[0]
        )
    }

    // ---------------------------------------------------------------------------
    // Test: no token at all → upload is skipped immediately (no HTTP calls)
    // ---------------------------------------------------------------------------

    @Test
    fun `pushCrashReport skips upload when access token is absent`() = runBlocking {
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn(null)

        val captureSlot = arrayOfNulls<RecordedRequest>(1)
        server.dispatcher = buildDispatcher(
            authRefreshUserId = "someone",
            captureSlot = captureSlot
        )

        val repo = UserSyncRepository(
            context = context,
            baseUrl = server.url("/").toString(),
            tokenManager = tokenManager
        )

        val success = repo.pushCrashReport(sampleReport)
        assertFalse("Expected upload to be skipped without a token", success)
        assertNull("No HTTP requests should have been made", captureSlot[0])
    }

    // ---------------------------------------------------------------------------
    // Test: stacktrace is capped at 50 000 characters
    // ---------------------------------------------------------------------------

    @Test
    fun `pushCrashReport truncates stacktrace to 50000 chars`() = runBlocking {
        val captureSlot = arrayOfNulls<RecordedRequest>(1)
        server.dispatcher = buildDispatcher(
            authRefreshUserId = "user_xyz",
            captureSlot = captureSlot
        )

        val repo = UserSyncRepository(
            context = context,
            baseUrl = server.url("/").toString(),
            tokenManager = tokenManager
        )

        val longTrace = "X".repeat(100_000)
        val result = repo.pushCrashReport(sampleReport.copy(stacktrace = longTrace))

        assertTrue(result)
        val body = JSONObject(captureSlot[0]!!.body.readUtf8())
        assertEquals(
            "stackTrace must be capped at 50 000 chars",
            50_000,
            body.getString("stackTrace").length
        )
    }
}
