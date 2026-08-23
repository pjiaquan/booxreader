package my.hinoki.booxreader.data.remote

import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.auth.TokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 驗證 shared `createApiClient(tokenProvider)` 的 Bearer auth 行為
 * （取代舊 OkHttp AuthInterceptor 的測試）。
 */
class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private var backendUrl: String = ""
    private lateinit var tokenProvider: TokenProvider

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenProvider =
                object : TokenProvider {
                        override fun getAccessToken(): String? = "test_token"
                        override fun getBackendUrl(): String = backendUrl
                }
        // We use localhost as the mock backend url to simulate a backend request
        backendUrl = mockWebServer.url("/").toString().dropLast(1)
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `adds authorization header for backend requests`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val client = createApiClient(tokenProvider)
        client.get(mockWebServer.url("/api/data").toString())

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test_token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `does not add authorization header for third-party requests`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Pretend the mock server is a third-party API while the backend URL differs
        backendUrl = "https://my-actual-backend.com"
        val client = createApiClient(tokenProvider)

        client.get(mockWebServer.url("/v1/models/gemini").toString()) // Host will be localhost

        val recordedRequest = mockWebServer.takeRequest()
        assertNull(
                "Authorization header should not be present",
                recordedRequest.getHeader("Authorization")
        )
    }

    @Test
    fun `does not add authorization header if request has SKIP_AUTH attribute`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val client = createApiClient(tokenProvider)
        client.get(mockWebServer.url("/api/auth").toString()) {
                attributes.put(skipAuthAttribute, true)
        }

        val recordedRequest = mockWebServer.takeRequest()
        assertNull(recordedRequest.getHeader("Authorization"))
    }
}
