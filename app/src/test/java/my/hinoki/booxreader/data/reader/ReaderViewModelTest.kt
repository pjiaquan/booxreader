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

@OptIn(ExperimentalCoroutinesApi::class)
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
        // We cannot easily set _currentBookKey without opening a book, which is complex to mock (Publication).
        // However, we can use reflection to set private state if needed, or rely on testing methods that take arguments.
        // Wait, saveProgress() relies on _currentBookKey. 
        // Let's use reflection to set _currentBookKey to test saveProgress() logic.
        
        val bookId = "test_book_id"
        val json = "{\"locations\": {\"progression\": 0.75}}"
        
        // Reflection to set _currentBookKey
        val field = ReaderViewModel::class.java.getDeclaredField("_currentBookKey")
        field.isAccessible = true
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        stateFlow.value = bookId

        viewModel.saveProgress(json)

        // Verify ProgressPublisher is called
        verify(progressPublisher, timeout(1000)).publishProgress(any(), any())

        // Verify SyncRepo is called
        verify(syncRepo, timeout(1000)).pushProgress(any(), any(), anyOrNull())

        // Verify Local Repo is called
        verify(bookRepo, timeout(1000)).updateProgress(any(), any())
    }

    @Test
    fun `saveProgress handles network failure gracefully`() = runTest(testDispatcher) {
        val bookId = "test_book_id"
        val json = "{}"
        
        val field = ReaderViewModel::class.java.getDeclaredField("_currentBookKey")
        field.isAccessible = true
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        stateFlow.value = bookId

        // Make publisher throw exception
        org.mockito.Mockito.`when`(progressPublisher.publishProgress(any(), any())).thenThrow(RuntimeException("Network error"))

        viewModel.saveProgress(json)

        // Should still try to sync to Firestore and Local DB
        verify(syncRepo, timeout(1000)).pushProgress(any(), any(), anyOrNull())
        verify(bookRepo, timeout(1000)).updateProgress(any(), any())
    }

}
