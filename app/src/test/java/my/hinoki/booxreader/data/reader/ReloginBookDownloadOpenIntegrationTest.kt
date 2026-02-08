package my.hinoki.booxreader.data.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.remote.ProgressPublisher
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.testutils.TestEpubGenerator
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReloginBookDownloadOpenIntegrationTest {

    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: my.hinoki.booxreader.data.prefs.TokenManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        Dispatchers.setMain(testDispatcher)

        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { withContext(Dispatchers.IO) { AppDatabase.get(context).clearAllTables() } }
        File(context.filesDir, "synced_books").deleteRecursively()

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        if (this::server.isInitialized) {
            server.shutdown()
        }
        if (this::context.isInitialized) {
            runBlocking { withContext(Dispatchers.IO) { AppDatabase.get(context).clearAllTables() } }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `book can be downloaded and opened after relogin`() = runTest(testDispatcher) {
        val bookId = "book-abc"
        val recordId = "rec_123"
        val fileName = "remote.epub"

        // Build a valid EPUB payload served by mocked cloud storage.
        val sourceEpub = File(context.cacheDir, "remote-source.epub")
        TestEpubGenerator.createMinimalEpub(sourceEpub)
        val epubBytes = sourceEpub.readBytes()

        var downloadAuthHeader: String? = null
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
                                            {"items":[{"id":"$recordId","bookId":"$bookId","deleted":false,"bookFile":"$fileName"}],"page":1,"perPage":1,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                        }

                        if (request.path == "/api/files/books/$recordId/$fileName" &&
                                        request.method == "GET"
                        ) {
                            downloadAuthHeader = request.getHeader("Authorization")
                            return MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/epub+zip")
                                    .setBody(Buffer().write(epubBytes))
                        }

                        return MockResponse().setResponseCode(404)
                    }
                }

        val syncRepo =
                UserSyncRepository(
                        context = context,
                        baseUrl = server.url("/").toString(),
                        tokenManager = tokenManager
                )
        setCachedUserId(syncRepo, "user_1")

        // Simulate post-login state on another device: metadata exists locally, file does not.
        AppDatabase.get(context)
                .bookDao()
                .insert(
                        BookEntity(
                                bookId = bookId,
                                title = "Remote Book",
                                fileUri = "pocketbase://$bookId",
                                lastLocatorJson = null,
                                lastOpenedAt = System.currentTimeMillis()
                        )
                )

        val downloadedUri =
                syncRepo.ensureBookFileAvailable(
                        bookId = bookId,
                        storagePath = null,
                        originalUri = "pocketbase://$bookId",
                        downloadIfNeeded = true
                )

        assertNotNull("Expected book file to be downloaded", downloadedUri)
        assertEquals("Bearer test-token", downloadAuthHeader)
        assertEquals("file", downloadedUri!!.scheme)
        assertTrue(File(downloadedUri.path!!).exists())
        assertTrue(File(downloadedUri.path!!).length() > 0L)

        val persistedBook = AppDatabase.get(context).bookDao().getByIds(listOf(bookId)).firstOrNull()
        assertNotNull(persistedBook)
        assertTrue(persistedBook!!.fileUri.startsWith("file://"))

        // Verify the downloaded file is actually openable by reader pipeline.
        val bookRepo = Mockito.mock(BookRepository::class.java)
        val bookmarkRepo = Mockito.mock(BookmarkRepository::class.java)
        val aiNoteRepo = Mockito.mock(AiNoteRepository::class.java)
        val syncRepoForViewModel = Mockito.mock(UserSyncRepository::class.java)
        val progressPublisher = Mockito.mock(ProgressPublisher::class.java)

        Mockito.`when`(bookRepo.getOrCreateByUri(any(), anyOrNull())).thenReturn(persistedBook)
        Mockito.`when`(bookRepo.touchOpened(any())).thenReturn(Unit)

        val viewModel =
                ReaderViewModel(
                        app = app,
                        bookRepo = bookRepo,
                        bookmarkRepo = bookmarkRepo,
                        aiNoteRepo = aiNoteRepo,
                        syncRepo = syncRepoForViewModel,
                        progressPublisher = progressPublisher,
                        ioDispatcher = testDispatcher
                )

        val openJob = viewModel.openBook(Uri.parse(persistedBook.fileUri))
        advanceUntilIdle()
        openJob.join()

        val publication = viewModel.publication.value
        assertNotNull("Downloaded EPUB should be openable after relogin", publication)
        assertEquals("Test Book", publication?.metadata?.title)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}
