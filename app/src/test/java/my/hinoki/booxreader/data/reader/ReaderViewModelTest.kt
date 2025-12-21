package my.hinoki.booxreader.data.reader

import android.app.Application
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.remote.ProgressPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReaderViewModelTest {

    private lateinit var viewModel: ReaderViewModel
    
    @Mock private lateinit var app: Application
    @Mock private lateinit var bookRepo: BookRepository
    @Mock private lateinit var bookmarkRepo: BookmarkRepository
    @Mock private lateinit var aiNoteRepo: AiNoteRepository
    @Mock private lateinit var syncRepo: UserSyncRepository
    @Mock private lateinit var progressPublisher: ProgressPublisher
    
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        
        viewModel = ReaderViewModel(app, bookRepo, bookmarkRepo, aiNoteRepo, syncRepo, progressPublisher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveProgressToDb calls repository updateProgress`() = runTest(testDispatcher) {
        val bookId = "content://book/1"
        val json = "{\"progress\":0.5}"
        
        viewModel.saveProgressToDb(bookId, json)
        
        verify(bookRepo, timeout(1000)).updateProgress(bookId, json)
    }

    @Test
    fun `saveProgress calls publisher, sync and local repo`() = runTest(testDispatcher) {
        val bookId = "test_book_id"
        val json = "{\"locations\": {\"progression\": 0.75}}"
        
        // Use reflection to set _currentBookKey property
        val field = ReaderViewModel::class.java.getDeclaredField("_currentBookKey")
        field.isAccessible = true
        // In Kotlin, the backing field for a Flow property is the Flow object itself.
        val flowObject = field.get(viewModel)
        if (flowObject is kotlinx.coroutines.flow.MutableStateFlow<*>) {
            @Suppress("UNCHECKED_CAST")
            (flowObject as kotlinx.coroutines.flow.MutableStateFlow<String?>).value = bookId
        }

        viewModel.saveProgress(json)

        // Verify ProgressPublisher is called
        verify(progressPublisher, timeout(1000)).publishProgress(eq(bookId), eq(json))

        // Verify SyncRepo is called
        verify(syncRepo, timeout(1000)).pushProgress(eq(bookId), eq(json), anyOrNull())

        // Verify Local Repo is called
        verify(bookRepo, timeout(1000)).updateProgress(eq(bookId), eq(json))
    }

    @Test
    fun `saveProgress handles network failure gracefully`() = runTest(testDispatcher) {
        val bookId = "test_book_id"
        val json = "{}"
        
        val field = ReaderViewModel::class.java.getDeclaredField("_currentBookKey")
        field.isAccessible = true
        val flowObject = field.get(viewModel)
        if (flowObject is kotlinx.coroutines.flow.MutableStateFlow<*>) {
            @Suppress("UNCHECKED_CAST")
            (flowObject as kotlinx.coroutines.flow.MutableStateFlow<String?>).value = bookId
        }

        // Make publisher throw exception
        org.mockito.Mockito.`when`(progressPublisher.publishProgress(any(), any())).thenThrow(RuntimeException("Network error"))

        viewModel.saveProgress(json)

        // Should still try to sync to Supabase and Local DB
        verify(syncRepo, timeout(1000)).pushProgress(eq(bookId), eq(json), anyOrNull())
        verify(bookRepo, timeout(1000)).updateProgress(eq(bookId), eq(json))
    }
}
