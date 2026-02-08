package my.hinoki.booxreader.data.repo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.testutils.TestEpubGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `pushBook restores deleted remote record even when remote updatedAt is newer`() = runBlocking {
        val epub = File(context.cacheDir, "sync-undelete.epub")
        TestEpubGenerator.createMinimalEpub(epub)

        var metadataPatchSeen = false
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
                                            {"items":[{"id":"rec_2","bookId":"book_2","updatedAt":2000,"deleted":true,"storagePath":"books/user_1/book_2/old.epub"}],"page":1,"perPage":1,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                        }

                        if (request.path == "/api/collections/books/records/rec_2" &&
                                        request.method == "PATCH"
                        ) {
                            val contentType = request.getHeader("Content-Type").orEmpty()
                            if (contentType.startsWith("multipart/form-data")) {
                                multipartUploadSeen = true
                                return MockResponse()
                                        .setResponseCode(200)
                                        .setBody("""{"id":"rec_2","bookFile":"uploaded-readd.epub"}""")
                            }

                            val body = request.body.readUtf8()
                            if (body.contains("\"deleted\":false")) {
                                metadataPatchSeen = true
                            }
                            if (body.contains("\"storagePath\"")) {
                                storagePathPatched = true
                            }
                            return MockResponse().setResponseCode(200).setBody("""{"id":"rec_2"}""")
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
                        bookId = "book_2",
                        title = "Sync Undelete",
                        fileUri = Uri.fromFile(epub).toString(),
                        lastLocatorJson = null,
                        lastOpenedAt = 1_000L
                )

        val ok = repo.pushBook(book, uploadFile = true, contentResolver = context.contentResolver)

        assertTrue("Expected pushBook to succeed", ok)
        assertTrue("Expected metadata PATCH to restore deleted=false", metadataPatchSeen)
        assertTrue("Expected multipart upload when restoring deleted remote record", multipartUploadSeen)
        assertTrue("Expected storagePath patch after upload", storagePathPatched)
    }

    @Test
    fun `ensureRemoteBookFilePresent reuploads when remote file probe is missing`() = runBlocking {
        val epub = File(context.cacheDir, "sync-probe-missing.epub")
        TestEpubGenerator.createMinimalEpub(epub)

        var probeSeen = false
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
                                            {"items":[{"id":"rec_3","bookId":"book_3","updatedAt":2000,"deleted":false,"storagePath":"rec_3/missing.epub"}],"page":1,"perPage":1,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                        }

                        if (request.path == "/api/files/books/rec_3/missing.epub" &&
                                        request.method == "HEAD"
                        ) {
                            probeSeen = true
                            return MockResponse().setResponseCode(404)
                        }

                        if (request.path == "/api/collections/books/records/rec_3" &&
                                        request.method == "PATCH"
                        ) {
                            val contentType = request.getHeader("Content-Type").orEmpty()
                            if (contentType.startsWith("multipart/form-data")) {
                                multipartUploadSeen = true
                                return MockResponse()
                                        .setResponseCode(200)
                                        .setBody("""{"id":"rec_3","bookFile":"uploaded-restored.epub"}""")
                            }

                            val body = request.body.readUtf8()
                            if (body.contains("\"storagePath\"")) {
                                storagePathPatched = true
                            }
                            return MockResponse().setResponseCode(200).setBody("""{"id":"rec_3"}""")
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
                        bookId = "book_3",
                        title = "Probe Missing",
                        fileUri = Uri.fromFile(epub).toString(),
                        lastLocatorJson = null,
                        lastOpenedAt = 1_000L
                )

        val ok = repo.ensureRemoteBookFilePresent(book, context.contentResolver)

        assertTrue("Expected remote probe to run", probeSeen)
        assertTrue("Expected ensureRemoteBookFilePresent to succeed", ok)
        assertTrue("Expected multipart upload after missing-file probe", multipartUploadSeen)
        assertTrue("Expected storagePath patch after reupload", storagePathPatched)
    }

    @Test
    fun `pullProgress writes remote locator into local book`() = runBlocking {
        val bookId = "book_progress_1"
        val remoteLocator = """{"locations":{"progression":0.64}}"""
        AppDatabase.get(context)
                .bookDao()
                .insert(
                        BookEntity(
                                bookId = bookId,
                                title = "Progress Book",
                                fileUri = "pocketbase://$bookId",
                                lastLocatorJson = null,
                                lastOpenedAt = 1_000L
                        )
                )

        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/api/collections/progress/records?") == true &&
                                        request.method == "GET"
                        ) {
                            return MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[{"id":"p1","bookId":"$bookId","locatorJson":${
                                                gsonQuoted(remoteLocator)
                                            },"updatedAt":2000}],"page":1,"perPage":1,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
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

        val pulled = repo.pullProgress(bookId)
        val local = AppDatabase.get(context).bookDao().getByIds(listOf(bookId)).firstOrNull()

        assertEquals(remoteLocator, pulled)
        assertEquals(remoteLocator, local?.lastLocatorJson)
        assertEquals(2_000L, local?.lastOpenedAt)
    }

    @Test
    fun `pullAllProgress restores missing local progress and skips stale remote`() = runBlocking {
        val newerLocalBookId = "book_progress_newer_local"
        val missingLocalBookId = "book_progress_missing_local"
        val localLocator = """{"locations":{"progression":0.9}}"""
        val remoteLocatorMissing = """{"locations":{"progression":0.5}}"""
        val remoteLocatorStale = """{"locations":{"progression":0.2}}"""

        val dao = AppDatabase.get(context).bookDao()
        dao.insert(
                BookEntity(
                        bookId = newerLocalBookId,
                        title = "Newer Local",
                        fileUri = "pocketbase://$newerLocalBookId",
                        lastLocatorJson = localLocator,
                        lastOpenedAt = 5_000L
                )
        )
        dao.insert(
                BookEntity(
                        bookId = missingLocalBookId,
                        title = "Missing Local",
                        fileUri = "pocketbase://$missingLocalBookId",
                        lastLocatorJson = null,
                        lastOpenedAt = 1_000L
                )
        )

        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/api/collections/progress/records?") == true &&
                                        request.method == "GET"
                        ) {
                            return MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[
                                            {"id":"p2","bookId":"$missingLocalBookId","locatorJson":${gsonQuoted(remoteLocatorMissing)},"updatedAt":2000},
                                            {"id":"p3","bookId":"$newerLocalBookId","locatorJson":${gsonQuoted(remoteLocatorStale)},"updatedAt":2000}
                                            ],"page":1,"perPage":100,"totalItems":2,"totalPages":1}
                                            """.trimIndent()
                                    )
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

        val merged = repo.pullAllProgress()
        val updatedMissing = dao.getByIds(listOf(missingLocalBookId)).firstOrNull()
        val untouchedNewer = dao.getByIds(listOf(newerLocalBookId)).firstOrNull()

        assertEquals(1, merged)
        assertEquals(remoteLocatorMissing, updatedMissing?.lastLocatorJson)
        assertEquals(2_000L, updatedMissing?.lastOpenedAt)
        assertEquals(localLocator, untouchedNewer?.lastLocatorJson)
        assertEquals(5_000L, untouchedNewer?.lastOpenedAt)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }

    private fun gsonQuoted(raw: String): String {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
