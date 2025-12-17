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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        
        viewModel = ReaderViewModel(app, bookRepo, bookmarkRepo, aiNoteRepo, syncRepo, progressPublisher)
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
        
        viewModel.openBook(uri)
        
        // 3. Wait for coroutine
        advanceUntilIdle()
        
        // 4. Verify state
        val publication = viewModel.publication.value
        assertNotNull("Publication should not be null after opening valid EPUB", publication)
        
        // 5. Verify content from our generator
        assertEquals("Test Book", publication?.metadata?.title)
        assertEquals(1, publication?.readingOrder?.size)
        assertEquals("chapter1.xhtml", publication?.readingOrder?.firstOrNull()?.href?.toString())
        
        // If we reach here, we have successfully:
        // - Generated a ZIP file
        // - Passed it to ReaderViewModel
        // - Parsed it using Readium's Streamer (which runs real code)
        // - Updated the UI state
    }
}
