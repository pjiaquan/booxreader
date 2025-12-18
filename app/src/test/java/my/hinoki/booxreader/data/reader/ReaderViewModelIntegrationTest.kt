package my.hinoki.booxreader.data.reader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import my.hinoki.booxreader.data.remote.ProgressPublisher
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.testutils.TestEpubGenerator
import my.hinoki.booxreader.data.db.BookEntity
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReaderViewModelIntegrationTest {

    private lateinit var viewModel: ReaderViewModel
    private lateinit var app: Application
    
    @Mock private lateinit var bookRepo: BookRepository
    @Mock private lateinit var bookmarkRepo: BookmarkRepository
    @Mock private lateinit var aiNoteRepo: AiNoteRepository
    @Mock private lateinit var syncRepo: UserSyncRepository
    @Mock private lateinit var progressPublisher: ProgressPublisher
    
    // We use Unconfined to let coroutines run immediately in Robolectric main thread, 
    // or StandardTestDispatcher if we use runTest appropriately.
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        app = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(testDispatcher)
        
        viewModel = ReaderViewModel(
            app,
            bookRepo,
            bookmarkRepo,
            aiNoteRepo,
            syncRepo,
            progressPublisher,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `openBook parses real EPUB file and updates publication state`() = runTest(testDispatcher) {
        // 1. Generate a real internal EPUB file
        val epubFile = File(app.cacheDir, "test.epub")
        TestEpubGenerator.createMinimalEpub(epubFile)
        
        // 2. Open it via ViewModel
        // Note: ReaderViewModel.openBook takes a Uri. 
        // For local files, "file:///path" works if AssetRetriever handles it, 
        // or we need a ContentProvider. 
        // Readium's default AssetRetriever handles file:// URIs via ContentResolver or explicit check.
        // Let's rely on Uri.fromFile
        val uri = android.net.Uri.fromFile(epubFile)
        println("Testing with URI: $uri, File exists: ${epubFile.exists()}, Size: ${epubFile.length()}")
        
        // Stub bookRepo.getOrCreateByUri to return a valid BookEntity
        val mockBook = BookEntity(
            bookId = "test_book_id",
            title = "Test Book",
            fileUri = uri.toString(),
            lastLocatorJson = null,
            lastOpenedAt = System.currentTimeMillis()
        )
        // Use doReturn style for suspend functions to avoid calling them directly in when()
        // Or ensure we are in a runTest block (we are)
        `when`(bookRepo.getOrCreateByUri(any(), any())).thenReturn(mockBook)
        `when`(bookRepo.touchOpened(any())).thenReturn(Unit)
        `when`(aiNoteRepo.getByBook(any())).thenReturn(emptyList())
        
        // 3. Start collecting toasts first
        val toastMessages = mutableListOf<String>()
        val job = backgroundScope.launch {
            viewModel.toastMessage.collect { toastMessages.add(it) }
        }
        
        // 4. Open it via ViewModel
        val openJob = viewModel.openBook(uri)
        
        // 5. Wait for coroutine
        advanceUntilIdle()
        openJob.join()
        job.cancel()

        if (toastMessages.isNotEmpty()) {
            println("Toast messages: $toastMessages")
        }
        
        // 4. Verify state
        val publication = viewModel.publication.value
        assertNotNull("Publication should not be null after opening valid EPUB. Toasts: $toastMessages", publication)
        
        // 5. Verify content from our generator
        assertEquals("Test Book", publication?.metadata?.title)
        assertEquals(1, publication?.readingOrder?.size)
        val firstHref = publication?.readingOrder?.firstOrNull()?.href?.toString().orEmpty()
        assertTrue("Unexpected first readingOrder href: $firstHref", firstHref.endsWith("chapter1.xhtml"))
        
        // If we reach here, we have successfully:
        // - Generated a ZIP file
        // - Passed it to ReaderViewModel
        // - Parsed it using Readium's Streamer (which runs real code)
        // - Updated the UI state
    }
}
