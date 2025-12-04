package my.hinoki.booxreader.data.reader

import android.app.Application
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private lateinit var viewModel: ReaderViewModel
    
    @Mock private lateinit var app: Application
    @Mock private lateinit var bookRepo: BookRepository
    @Mock private lateinit var bookmarkRepo: BookmarkRepository
    @Mock private lateinit var aiNoteRepo: AiNoteRepository
    
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        
        viewModel = ReaderViewModel(app, bookRepo, bookmarkRepo, aiNoteRepo)
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
        
        // Verify that the repository was called. 
        // Since the ViewModel uses Dispatchers.IO, we use a timeout to allow the coroutine to switch and execute.
        verify(bookRepo, timeout(1000)).updateProgress(bookId, json)
    }
}

