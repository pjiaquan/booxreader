package my.hinoki.booxreader.data.reader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.remote.ProgressPublisher
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.R
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser

/**
 * ViewModel for the Reader screen. Handles book loading, navigation state, bookmarks, and AI notes.
 *
 * Refactored to use Constructor Injection for repositories to facilitate Unit Testing.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderViewModel(
        app: Application,
        private val bookRepo: BookRepository,
        private val bookmarkRepo: BookmarkRepository,
        private val aiNoteRepo: AiNoteRepository,
        private val syncRepo: UserSyncRepository,
        private val progressPublisher: ProgressPublisher,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(app) {

    private fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                ?: HttpConfig.DEFAULT_BASE_URL
    }

    // --- Repositories ---
    // All injected via constructor

    // --- State ---
    private val _publication = MutableStateFlow<Publication?>(null)
    val publication: StateFlow<Publication?> = _publication.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    data class NavigateToNote(val noteId: Long, val autoStreamText: String? = null)

    private val _navigateToNote = MutableSharedFlow<NavigateToNote>()
    val navigateToNote: SharedFlow<NavigateToNote> = _navigateToNote.asSharedFlow()

    private val _currentBookKey = MutableStateFlow<String?>(null)
    val currentBookKey: StateFlow<String?> = _currentBookKey.asStateFlow()

    // --- Internal Helpers ---
    private val httpClient by lazy { DefaultHttpClient() }
    private val assetRetriever by lazy { AssetRetriever(app.contentResolver, httpClient) }
    private val publicationOpener by lazy {
        PublicationOpener(publicationParser = EpubParser(), contentProtections = emptyList())
    }

    private var searchJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        closePublication()
    }

    fun closePublication() {
        _publication.value?.close()
        _publication.value = null
    }

    fun openBook(uri: Uri, contentResolver: android.content.ContentResolver? = null): Job {
        if (_publication.value != null) {
            // Already loaded
            return viewModelScope.launch {}
        }

        return viewModelScope.launch {
            _isLoading.value = true
            try {
                // Avoid main-thread IO; dispatcher is injectable for deterministic tests.
                val (pub, book) =
                        withContext(ioDispatcher) {
                            val url =
                                    AbsoluteUrl(uri.toString())
                                            ?: throw IllegalArgumentException(
                                                    "Invalid book URI: $uri"
                                            )

                            val asset =
                                    assetRetriever.retrieve(url).getOrElse {
                                        throw IllegalStateException("Failed to retrieve asset: $it")
                                    }

                            val publication =
                                    publicationOpener.open(asset, allowUserInteraction = false)
                                            .getOrElse {
                                                throw IllegalStateException(
                                                        "Failed to open publication: $it"
                                                )
                                            }

                            val book =
                                    bookRepo.getOrCreateByUri(
                                            uri.toString(),
                                            publication.metadata.title
                                    )
                            bookRepo.touchOpened(book.bookId)

                            Pair(publication, book)
                        }

                val key = book.bookId
                _currentBookKey.value = key

                // Ensure new book files are synced
                viewModelScope.launch(ioDispatcher) {
                    val result = runCatching {
                        syncRepo.pushBook(
                                book,
                                uploadFile = true,
                                contentResolver = contentResolver
                        )
                    }
                    result.onFailure { e ->
                        android.util.Log.e("ReaderViewModel", "Failed to auto-upload book", e)
                        ErrorReporter.report(
                                getApplication(),
                                "ReaderViewModel.openBook",
                                "Failed to auto-upload opened book",
                                e
                        )
                    }
                    result.onSuccess { android.util.Log.d("ReaderViewModel", "Auto-upload book success") }
                }

                // Fetch cloud progress
                withContext(ioDispatcher) { runCatching { syncRepo.pullProgress(key) } }

                _publication.value = pub

                // Trigger highlights load
                loadHighlights()
            } catch (e: Exception) {
                e.printStackTrace()
                ErrorReporter.report(
                        getApplication(),
                        "ReaderViewModel.openBook",
                        "Failed to open book: $uri",
                        e
                )
                _toastMessage.emit("Failed to open book: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun getLastSavedLocator(): Locator? {
        val key = _currentBookKey.value ?: return null
        return withContext(Dispatchers.IO) {
            val book = bookRepo.getBook(key)
            if (book?.lastLocatorJson != null) {
                LocatorJsonHelper.fromJson(book.lastLocatorJson)
            } else {
                null
            }
        }
    }

    fun saveProgress(json: String) {
        val key = _currentBookKey.value ?: return
        val uri =
                _publication.value
                        ?.let { /* We might need to store URI separately if needed for updateProgress */
                        }
        // Note: Repository updateProgress typically needs the Book ID (URI in this app's logic)
        // We can pass the URI string or store it. For now, let's assume key or look it up.
        // The original code passed 'currentBookId' (URI) to updateProgress.
        // Let's update the repo call if possible.

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Publish to server (Best effort)
            try {
                progressPublisher.publishProgress(key, json)
            } catch (e: Exception) {
                ErrorReporter.report(
                        getApplication(),
                        "ReaderViewModel.saveProgress",
                        "Failed to save progress",
                        e
                )
                // Ignore
            }

            // 2. Push to Supabase for cross-device sync (best effort)
            runCatching {
                val title = _publication.value?.metadata?.title
                syncRepo.pushProgress(key, json, bookTitle = title)
            }

            // 3. Update local DB so主頁面立即顯示最新進度
            runCatching { bookRepo.updateProgress(key, json) }
        }
    }

    fun saveProgressToDb(bookId: String, json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookRepo.updateProgress(bookId, json)
            } catch (_: Exception) {}
        }
    }

    fun addBookmark(locator: Locator) {
        val key = _currentBookKey.value ?: return
        viewModelScope.launch {
            try {
                bookmarkRepo.add(key, locator)
                _toastMessage.emit("Bookmark saved")
            } catch (e: Exception) {
                ErrorReporter.report(
                        getApplication(),
                        "ReaderViewModel.addBookmark",
                        "Failed to add bookmark",
                        e
                )
                _toastMessage.emit("Failed to save bookmark")
            }
        }
    }

    fun loadHighlights() {
        // Decorations (highlights) are not yet supported in the native reader.
        // This will be re-implemented when a native decoration system is available.
    }

    fun postTextToServer(text: String, locatorJson: String?) {
        val key = _currentBookKey.value ?: return
        viewModelScope.launch {
            try {
                _toastMessage.emit("Publishing...")
                // 0. Check existence
                val existingNote = withContext(Dispatchers.IO) { aiNoteRepo.findNoteByText(text) }
                if (existingNote != null) {
                    _toastMessage.emit("Note found!")
                    _navigateToNote.emit(NavigateToNote(existingNote.id))
                    return@launch
                }

                // 1. Save Draft
                val bookTitle = _publication.value?.metadata?.title
                val noteId =
                        withContext(Dispatchers.IO) {
                            aiNoteRepo.add(key, text, "", locatorJson, bookTitle)
                        }

                // 2. If streaming is enabled, navigate to detail to stream there.
                if (aiNoteRepo.isStreamingEnabled()) {
                    _toastMessage.emit("Streaming...")
                    _navigateToNote.emit(NavigateToNote(noteId, autoStreamText = text))
                    return@launch
                }
                // Non-streaming path: fetch immediately
                val result = withContext(Dispatchers.IO) { aiNoteRepo.fetchAiExplanation(text) }

                if (result != null) {
                    val (finalText, content) = result
                    val note = withContext(Dispatchers.IO) { aiNoteRepo.getById(noteId) }
                    if (note != null) {
                        val messages =
                                JSONArray().apply {
                                    put(JSONObject().put("role", "user").put("content", finalText))
                                    put(
                                            JSONObject()
                                                    .put("role", "assistant")
                                                    .put("content", content)
                                    )
                                }
                        val updated = note.copy(messages = messages.toString())
                        withContext(Dispatchers.IO) { aiNoteRepo.update(updated) }
                    }
                    _toastMessage.emit("Finished")
                    val credits = withContext(Dispatchers.IO) { aiNoteRepo.fetchRemainingCredits() }
                    if (credits != null) {
                        _toastMessage.emit(
                                getApplication<Application>().getString(
                                        R.string.ai_credits_left_toast,
                                        credits
                                )
                        )
                    }
                    loadHighlights() // Refresh
                    _navigateToNote.emit(NavigateToNote(noteId))
                } else {
                    _toastMessage.emit("Saved as draft (Network Error)")
                    loadHighlights()
                    _navigateToNote.emit(NavigateToNote(noteId))
                }
            } catch (e: Exception) {
                ErrorReporter.report(
                        getApplication(),
                        "ReaderViewModel.postTextToServer",
                        "Failed to post text to server",
                        e
                )
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }
}
