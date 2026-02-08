package my.hinoki.booxreader.data.repo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.testutils.TestEpubGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserSyncRepositoryBookSyncTest {

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
    fun `pushBook uploads file when remote record is newer but has no file path`() = runBlocking {
        val epub = File(context.cacheDir, "sync-backfill.epub")
        TestEpubGenerator.createMinimalEpub(epub)
        val localUpdatedAt = 1_000L

        var multipartUploadSeen = false
        var storagePathPatched = false

        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/api/collections/books/records?") == true &&
                                        request.method == "GET"
                        ) {
                            return MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[{"id":"rec_1","bookId":"book_1","updatedAt":2000}],"page":1,"perPage":1,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                        }

                        if (request.path == "/api/collections/books/records/rec_1" &&
                                        request.method == "PATCH"
                        ) {
                            val contentType = request.getHeader("Content-Type").orEmpty()
                            if (contentType.startsWith("multipart/form-data")) {
                                multipartUploadSeen = true
                                return MockResponse()
                                        .setResponseCode(200)
                                        .setBody("""{"id":"rec_1","bookFile":"uploaded.epub"}""")
                            }

                            val body = request.body.readUtf8()
                            if (body.contains("\"storagePath\"")) {
                                storagePathPatched = true
                            }
                            return MockResponse().setResponseCode(200).setBody("""{"id":"rec_1"}""")
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
        setCachedUserId(repo, "user_1")

        val book =
                my.hinoki.booxreader.data.db.BookEntity(
                        bookId = "book_1",
                        title = "Sync Backfill",
                        fileUri = Uri.fromFile(epub).toString(),
                        lastLocatorJson = null,
                        lastOpenedAt = localUpdatedAt
                )

        val ok = repo.pushBook(book, uploadFile = true, contentResolver = context.contentResolver)

        assertTrue("Expected pushBook to succeed", ok)
        assertTrue("Expected multipart upload even when remote updatedAt is newer", multipartUploadSeen)
        assertTrue("Expected storagePath to be patched after upload", storagePathPatched)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}
