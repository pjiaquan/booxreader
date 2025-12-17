package my.hinoki.booxreader.data.reader

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.remote.ProgressPublisher
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.reader.LocatorJsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.util.concurrent.CancellationException

import android.content.Context
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.BooxReaderApp
import okhttp3.OkHttpClient

/**
 * ViewModel for the Reader screen.
 * Handles book loading, navigation state, bookmarks, and AI notes.
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
    private val progressPublisher: ProgressPublisher
) : AndroidViewModel(app) {

    private fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL) ?: HttpConfig.DEFAULT_BASE_URL
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

    private val _decorations = MutableStateFlow<List<Decoration>>(emptyList())
    val decorations: StateFlow<List<Decoration>> = _decorations.asStateFlow()

    private val _currentBookKey = MutableStateFlow<String?>(null)
    val currentBookKey: StateFlow<String?> = _currentBookKey.asStateFlow()

    // --- Internal Helpers ---
    private val httpClient by lazy { DefaultHttpClient() }
    private val assetRetriever by lazy { AssetRetriever(app.contentResolver, httpClient) }
    private val publicationOpener by lazy {
        PublicationOpener(
            publicationParser = EpubParser(),
            contentProtections = emptyList()
        )
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

    fun openBook(uri: Uri) {
        if (_publication.value != null) return // Already loaded

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = AbsoluteUrl(uri.toString())
                        ?: throw IllegalArgumentException("Invalid book URI: $uri")

                    val asset = assetRetriever.retrieve(url)
                        .getOrElse { throw IllegalStateException("Failed to retrieve asset: $it") }

                    val publication = publicationOpener.open(asset, allowUserInteraction = false)
                        .getOrElse { throw IllegalStateException("Failed to open publication: $it") }

                    publication.readingOrder.forEach { link ->
                         android.util.Log.d("ReaderDebug", "Spine Item Href: '${link.href}'")
                    }

                    val book = bookRepo.getOrCreateByUri(uri.toString(), publication.metadata.title)
                    bookRepo.touchOpened(book.bookId)
                    Pair(publication, book)
                }

                val (pub, book) = result
                val key = book.bookId
                _currentBookKey.value = key
                android.util.Log.d("ReaderDebug", "OpenBook Key: '$key'")

                // 確保新開啟的書籍檔案立即嘗試同步到雲端 Storage
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { syncRepo.pushBook(book, uploadFile = true) }
                }

                // Fetch cloud progress before emitting publication so UI can pick it up
                withContext(Dispatchers.IO) {
                    runCatching { syncRepo.pullProgress(key) }
                }

                _publication.value = pub

                // Trigger highlights load
                loadHighlights()

            } catch (e: Exception) {
                e.printStackTrace()
                _toastMessage.emit("Failed to open book: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveProgress(json: String) {
        val key = _currentBookKey.value ?: return
        val uri = _publication.value?.let { /* We might need to store URI separately if needed for updateProgress */ }
        // Note: Repository updateProgress typically needs the Book ID (URI in this app's logic)
        // We can pass the URI string or store it. For now, let's assume key or look it up.
        // The original code passed 'currentBookId' (URI) to updateProgress.
        // Let's update the repo call if possible.
        
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Publish to server (Best effort)
            try {
                progressPublisher.publishProgress(key, json)
            } catch (e: Exception) {
                // Ignore
            }

            // 2. Push to Firestore for cross-device sync (best effort)
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
            } catch (_: Exception) { }
        }
    }

    fun addBookmark(locator: Locator) {
        val key = _currentBookKey.value ?: return
        viewModelScope.launch {
            try {
                bookmarkRepo.add(key, locator)
                _toastMessage.emit("Bookmark saved")
            } catch (e: Exception) {
                _toastMessage.emit("Failed to save bookmark")
            }
        }
    }

    fun loadHighlights() {
        val key = _currentBookKey.value ?: return
        val pub = _publication.value ?: return

        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val notes = aiNoteRepo.getByBook(key)
                
                // Base decorations (Notes themselves)
                val noteDecorations = notes.mapNotNull { note ->
                    val loc = LocatorJsonHelper.fromJson(note.locatorJson) ?: return@mapNotNull null
                    Decoration(
                        id = note.id.toString(),
                        locator = loc,
                        style = Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#40FF8C00"))
                    )
                }

                // Keyword decorations (Heavy)
                val keywordDecorations = buildKeywordDecorations(pub, notes)

                if (isActive) {
                    _decorations.value = noteDecorations + keywordDecorations
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
                val noteId = withContext(Dispatchers.IO) { aiNoteRepo.add(key, text, "", locatorJson, bookTitle) }

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
                        val updated = note.copy(originalText = finalText, aiResponse = content)
                        withContext(Dispatchers.IO) { aiNoteRepo.update(updated) }
                    }
                    _toastMessage.emit("Finished")
                    loadHighlights() // Refresh
                    _navigateToNote.emit(NavigateToNote(noteId))
                } else {
                    _toastMessage.emit("Saved as draft (Network Error)")
                    loadHighlights()
                    _navigateToNote.emit(NavigateToNote(noteId))
                }
            } catch (e: Exception) {
                _toastMessage.emit("Error: ${e.message}")
            }
        }
    }

    /**
     * Optimized keyword search:
     * 1. Pre-compiles Regex.
     * 2. Checks for cancellation (yield).
     * 3. Avoids unnecessary string allocations where possible.
     */
    private suspend fun buildKeywordDecorations(
        pub: Publication,
        notes: List<AiNoteEntity>
    ): List<Decoration> = withContext(Dispatchers.IO) {
        
        val pairs = buildKeywordPairs(notes)
        if (pairs.isEmpty()) return@withContext emptyList()

        val decorations = mutableListOf<Decoration>()
        val keywordPatterns = pairs.associate { (keyword, noteId) ->
            keyword to (Regex(Regex.escape(keyword), setOf(RegexOption.IGNORE_CASE)) to noteId)
        }

        // Iterate reading order
        for (link in pub.readingOrder) {
            yield() // Allow cancellation between chapters

            val resource = pub.get(link) ?: continue
            // Warning: Reading full content into memory is still risky for huge chapters.
            // Optimization: Set a limit or try streaming if Reader supported it easily.
            val contentBytes = try { resource.read().getOrNull() } catch(e: Exception) { null } ?: continue
            val rawContent = contentBytes.toString(Charsets.UTF_8)
            
            if (rawContent.isEmpty()) continue

            // Simple stripping (approximate)
            val plainBuilder = StringBuilder(rawContent.length)
            val plainToRaw = IntArray(rawContent.length) // Map plain index to raw index
            var plainIdx = 0
            var inTag = false
            
            for (i in rawContent.indices) {
                val ch = rawContent[i]
                if (ch == '<') {
                    inTag = true
                } else if (ch == '>' && inTag) {
                    inTag = false
                } else if (!inTag) {
                    plainBuilder.append(ch)
                    plainToRaw[plainIdx++] = i
                }
            }
            val plainText = plainBuilder.toString()
            
            yield() // Allow cancellation before regex

            keywordPatterns.values.forEach { (regex, noteId) ->
                regex.findAll(plainText).forEach { match ->
                    val start = match.range.first
                    if (start < plainIdx) {
                        val rawIndex = plainToRaw[start]
                        val progression = rawIndex.toDouble() / rawContent.length.coerceAtLeast(1)
                        
                        decorations.add(Decoration(
                            id = "note_${noteId}_kw_${link.href}_${start}",
                            locator = Locator(
                                href = Url(link.href.toString())!!,
                                mediaType = link.mediaType ?: MediaType.BINARY,
                                title = link.title,
                                locations = Locator.Locations(progression = progression),
                                text = Locator.Text(highlight = match.value)
                            ),
                            style = Decoration.Style.Highlight(tint = android.graphics.Color.parseColor("#40FF8C00"))
                        ))
                    }
                }
            }
        }
        decorations
    }

    private fun buildKeywordPairs(notes: List<AiNoteEntity>): List<Pair<String, Long>> {
        val pairs = LinkedHashSet<Pair<String, Long>>()
        val splitRegex = Regex("[\\s\\p{Punct}、，。！？；：.!?;:（）()【】「」『』《》<>]+")

        notes.forEach { note ->
            val base = note.originalText.trim()
            if (base.isNotEmpty()) {
                pairs.add(base to note.id)
                splitRegex.split(base)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { pairs.add(it to note.id) }
            }
        }
        return pairs.toList()
    }
}
