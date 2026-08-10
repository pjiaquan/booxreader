package my.hinoki.booxreader.data.remote

import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenManager: TokenManager
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        tokenManager = mock(TokenManager::class.java)

        // Mock token manager behavior
        `when`(tokenManager.getAccessToken()).thenReturn("test_token")

        // We use localhost as the mock backend url to simulate a backend request
        val baseUrl = mockWebServer.url("/").toString()
        `when`(tokenManager.getBackendUrl()).thenReturn(baseUrl.dropLast(1))

        val interceptor = AuthInterceptor(tokenManager)
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `adds authorization header for backend requests`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/data"))
            .build()

        client.newCall(request).execute()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test_token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `does not add authorization header for third-party requests`() {
        // Enqueue a response for the mock web server acting as a 3rd party
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Let's pretend the mock server is a third party API, but the TokenManager
        // reports a DIFFERENT backend URL
        `when`(tokenManager.getBackendUrl()).thenReturn("https://my-actual-backend.com")

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/models/gemini")) // Host will be localhost
            .build()

        client.newCall(request).execute()

        val recordedRequest = mockWebServer.takeRequest()
        assertNull("Authorization header should not be present", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun `does not add authorization header if request has SKIP_AUTH tag`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/auth"))
            .tag(String::class.java, "SKIP_AUTH")
            .build()

        client.newCall(request).execute()

        val recordedRequest = mockWebServer.takeRequest()
        assertNull(recordedRequest.getHeader("Authorization"))
    }
}
