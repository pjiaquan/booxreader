package com.example.booxreader.data.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.booxreader.R
import com.example.booxreader.core.eink.EInkHelper
import com.example.booxreader.databinding.ActivityReaderBinding
import com.example.booxreader.data.db.BookEntity
import com.example.booxreader.data.repo.BookRepository
import com.example.booxreader.data.repo.BookmarkRepository
import com.example.booxreader.data.repo.AiNoteRepository
import com.example.booxreader.data.remote.HttpConfig
import com.example.booxreader.data.remote.ProgressPublisher
import com.example.booxreader.reader.LocatorJsonHelper
import com.example.booxreader.ui.bookmarks.BookmarkListActivity
import com.example.booxreader.data.ui.notes.AiNoteListActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser


import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.TimeUnit
import kotlin.OptIn

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var currentPublication: Publication? = null
    private val bookRepository by lazy { BookRepository(applicationContext) }
    private var currentBookId: String? = null
    private var pageTapEnabled: Boolean = true
    private var potentialPageTap: Boolean = false
    private var tapDownTime: Long = 0L
    private var tapDownX: Float = 0f
    private var tapDownY: Float = 0f
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    
    // Default font size: 150%
    private var currentFontSize: Int = 150
    private val httpClient by lazy { DefaultHttpClient() }

    private val assetRetriever by lazy {
        // 使用 contentResolver + httpClient（官方推薦組合）
        AssetRetriever(contentResolver, httpClient)
    }

    private val publicationOpener by lazy {
        // 目前只需要 EPUB parser，之後要支援 PDF 等再加入其他 parser
        PublicationOpener(
            publicationParser = EpubParser(),
            contentProtections = emptyList()
        )
    }

    // --- 進度儲存用 ---
    private val prefs by lazy {
        // 一個很單純的 SharedPreferences
        getSharedPreferences("reader_prefs", MODE_PRIVATE)
    }

    // 每本書一個「穩定 key」，用來在 prefs 裡當 key
    private var bookKey: String? = null


//    // --- 資料儲存 ---
//    private val bookRepository by lazy { BookRepository(applicationContext) }
//    private val bookmarkRepository by lazy { BookmarkRepository(applicationContext) }

    private val progressPublisher by lazy { ProgressPublisher() }

    // Navigator Fragment 參考，用來拿 currentLocator
    private var navigatorFragment: EpubNavigatorFragment? = null

    // 在 class ReaderActivity 裡新增：
    private val bookmarkRepo by lazy { BookmarkRepository(applicationContext) }
    private val aiNoteRepo by lazy { AiNoteRepository(applicationContext) }

    private val REQ_BOOKMARK = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 設置 ViewBinding (解決 Layout 不正確的問題)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tapOverlay.bringToFront()

        // 2. 獲取傳入的書籍 URI
        val bookUri = intent.data
        if (bookUri == null) {
            Toast.makeText(this, "No book URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 如果是第一次建立（不是旋轉螢幕重建），才載入書
        if (savedInstanceState == null) {
            loadBook(bookUri)
        } else {
            // 比較少見：螢幕旋轉之類重建 Activity
            bookKey = savedInstanceState.getString("book_key")
            navigatorFragment =
                supportFragmentManager.findFragmentById(R.id.readerContainer) as? EpubNavigatorFragment
            
            navigatorFragment?.let {
                observeLocatorUpdates()
                loadHighlights()
                setupDecorationListener()
            }
        }

        // 3. 解析並顯示書籍 (解決沒有內容的問題)
        loadBook(bookUri)

        // 4. 綁定按鈕事件
        binding.btnAinote.setOnClickListener {
            val key = bookKey
            if (key == null) {
                Toast.makeText(this, "No book key yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AiNoteListActivity.open(this@ReaderActivity, key)
        }

        binding.btnAddBookmark.visibility = android.view.View.GONE

        binding.btnShowBookmarks.setOnClickListener {
            publishCurrentSelection()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        initTapNavigation()

        // Load saved font size
        // If user has 100 (old default), force upgrade to 150 (new default)
        val savedSize = prefs.getInt("font_size", 150)
        currentFontSize = if (savedSize == 100) 150 else savedSize
        pageTapEnabled = prefs.getBoolean("page_tap_enabled", true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_BOOKMARK && resultCode == RESULT_OK && data != null) {
            val json = data.getStringExtra(BookmarkListActivity.EXTRA_LOCATOR_JSON)
            val locator = LocatorJsonHelper.fromJson(json)

            val navigator = navigatorFragment
            if (locator != null && navigator != null) {
                // 跳轉到書籤的位置
                try {
                    navigator.go(locator)
                    // 跳轉後刷新一次 E-Ink
                    EInkHelper.refresh(binding.root)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(
                        this,
                        "Failed to go to bookmark: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Activity 要離開畫面（切到背景 / 關閉）時，
     * 把目前閱讀位置存起來。
     */
    override fun onPause() {
        super.onPause()
        saveReadingProgress()
    }

    // ----------------------------------------------------
    // 解析 EPUB + 建立 navigator + 還原上次進度(
    // ----------------------------------------------------
    private fun loadBook(uri: Uri) {
        lifecycleScope.launch {
            try {
                // 1. IO 執行緒：打開 EPUB
                val publication = withContext(Dispatchers.IO) {
                    openPublication(uri)
                }
                currentPublication = publication
                currentBookId = uri.toString()

                // 2. 決定這本書的「穩定 key」：
                //    優先用 metadata.identifier，其次用 uri 字串
                bookKey = publication.metadata.identifier ?: uri.toString()

                // 2.5 記錄或更新最近閱讀紀錄
                withContext(Dispatchers.IO) {
                    bookRepository.getOrCreateByUri(uri.toString(), publication.metadata.title)
                    currentBookId?.let { bookRepository.touchOpened(it) }
                }

                // 3. 嘗試從 SharedPreferences 還原上次位置
                val initialLocator = loadSavedProgress()

                // 4. 建立 navigator + Fragment
                initNavigator(publication, initialLocator)

                // 5. 開啟時做一次 E-Ink refresh（Boox 比較乾淨）
                EInkHelper.refresh(binding.root)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@ReaderActivity,
                    "Failed to open book: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("book_key", bookKey)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                navigatorFragment?.goBackward()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                navigatorFragment?.goForward()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setupDecorationListener() {
        navigatorFragment?.addDecorationListener("ai_notes", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id
                val noteId = when {
                    id.startsWith("note_") -> id.removePrefix("note_").substringBefore("_").toLongOrNull()
                    else -> id.toLongOrNull()
                }
                return if (noteId != null) {
                    com.example.booxreader.data.ui.notes.AiNoteDetailActivity.open(this@ReaderActivity, noteId)
                    true
                } else {
                    false
                }
            }
        })
    }

    /** 在 IO 執行緒打開 EPUB 成 Publication */
    private suspend fun openPublication(uri: Uri): Publication =
        withContext(Dispatchers.IO) {
            val url = AbsoluteUrl(uri.toString())
                ?: throw IllegalArgumentException("Invalid book URI: $uri")

            val asset = assetRetriever
                .retrieve(url)
                .getOrElse { failure ->
                    throw IllegalStateException("Failed to retrieve asset: $failure")
                }

            publicationOpener
                .open(asset, allowUserInteraction = false)
                .getOrElse { failure ->
                    throw IllegalStateException("Failed to open publication: $failure")
                }
        }

    /** 將 Publication 放進 EpubNavigatorFragment，並掛到 readerContainer */
    private fun initNavigator(
        publication: Publication,
        initialLocator: Locator?
    ) {
        val navigatorFactory = EpubNavigatorFactory(publication)

        // 設定 Configuration 加入自訂 ActionMode Callback
        val config = EpubNavigatorFragment.Configuration {
            selectionActionModeCallback = this@ReaderActivity.selectionActionModeCallback
        }

        // 把 factory 掛給 FragmentManager
        supportFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
                initialLocator = initialLocator,
                listener = null,
                configuration = config
            )

        // 只在還沒有 fragment 的時候 attach
        if (supportFragmentManager.findFragmentById(R.id.readerContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.readerContainer, EpubNavigatorFragment::class.java, null)
                .commitNow()
        }

        // Ensure tap overlay stays above the fragment view
        binding.tapOverlay.bringToFront()

        navigatorFragment =
            supportFragmentManager.findFragmentById(R.id.readerContainer) as? EpubNavigatorFragment

        // Apply initial settings
        applyFontSize(currentFontSize)

        observeLocatorUpdates()
        loadHighlights()
        setupDecorationListener()
    }

    // ----------------------------------------------------
    // 進度儲存 / 還原（SharedPreferences）
    // ----------------------------------------------------

    /** 從 SharedPreferences 讀取上次閱讀位置 */
    private fun loadSavedProgress(): Locator? {
        val key = bookKey ?: return null
        val json = prefs.getString("progress_$key", null)
        return LocatorJsonHelper.fromJson(json)
    }

    /** 把目前閱讀位置寫回 SharedPreferences */
    private fun saveReadingProgress() {
        val key = bookKey ?: return
        val navigator = navigatorFragment ?: return

        // Readium 3.x 的 currentLocator 通常是 StateFlow<Locator>
        val locator = try {
            navigator.currentLocator.value
        } catch (e: Exception) {
            // 如果 API 版本不同，這裡可能需要改成其他方式取得 locator
            e.printStackTrace()
            return
        }

        val json = LocatorJsonHelper.toJson(locator) ?: return

        prefs.edit()
            .putString("progress_$key", json)
            .apply()

        currentBookId?.let { bookId ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    bookRepository.updateProgress(bookId, json)
                } catch (_: Exception) {
                }
            }
        }

        // 2) 背景 thread 上報到 HTTP server（最佳努力，不影響 UI）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                progressPublisher.publishProgress(key, json)
            } catch (e: Exception) {
                // 正常來說 publishProgress 自己會處理錯誤，這裡只是雙保險
                e.printStackTrace()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeLocatorUpdates() {
        val navigator = navigatorFragment ?: return
        val key = bookKey ?: return

        // 監聽 currentLocator（Readium 3.x 是 StateFlow<Locator>）
        lifecycleScope.launch {
            navigator.currentLocator
                // 每 1.5 秒取一個樣本，避免一頁內小滑動太頻繁上報
                .sample(1500)
                .collectLatest { locator ->
                    val json = LocatorJsonHelper.toJson(locator) ?: return@collectLatest

                    // 1) 本地：更新 SharedPreferences 中的閱讀進度
                    prefs.edit()
                        .putString("progress_$key", json)
                        .apply()

                    // 2) 背景：上報 HTTP 進度（最佳努力，不阻塞 UI）
                    launch(Dispatchers.IO) {
                        try {
                            progressPublisher.publishProgress(key, json)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 3) Boox E-Ink refresh（翻頁後順手刷新）
                    EInkHelper.refresh(binding.root)
                }
        }
    }

    private fun addBookmarkFromCurrentPosition() {
        val navigator = navigatorFragment
        val key = bookKey

        if (navigator == null || key == null) {
            Toast.makeText(this, "Reader not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                // Readium 3.x：currentLocator 是 StateFlow<Locator>
                val locator = navigator.currentLocator.value
                bookmarkRepo.add(key, locator)

                Toast.makeText(this@ReaderActivity, "Bookmark saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@ReaderActivity,
                    "Failed to save bookmark: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openBookmarkList() {
        val key = bookKey
        if (key == null) {
            Toast.makeText(this, "No book key yet", Toast.LENGTH_SHORT).show()
            return
        }

        BookmarkListActivity.openForResult(this, key, REQ_BOOKMARK)
    }

    private fun publishCurrentSelection() {
        lifecycleScope.launch {
            val selection = navigatorFragment?.currentSelection()
            val text = selection?.locator?.text?.highlight
            val locatorJson = LocatorJsonHelper.toJson(selection?.locator)

            if (text.isNullOrBlank()) {
                Toast.makeText(this@ReaderActivity, "No selection", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Clear selection / hide handles for smoother UX
            selection?.let {
                try {
                    navigatorFragment?.clearSelection()
                } catch (_: Exception) {
                }
            }

            val sanitized = withContext(Dispatchers.Default) {
                text
                    .replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                    .replace(Regex("\\s+"), " ")
                    .take(MAX_SELECTION_CHARS)
            }

            if (sanitized.isBlank()) {
                Toast.makeText(this@ReaderActivity, "No selection", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(this@ReaderActivity, "Publishing...", Toast.LENGTH_SHORT).show()
            postTextToServer(sanitized, locatorJson)
        }
    }

    // 自定義選取文字後的 Action Mode (新增 "發佈")
    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            // 延後一點再塞入，避免系統重建時閃爍
            binding.root.postDelayed({
                if (mode != null && menu?.findItem(999) == null) {
                    menu?.add(Menu.NONE, 999, 0, "發佈")
                        ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    mode.invalidate()
                }
            }, 120)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId == 999) {
                lifecycleScope.launch {
                    val selection = navigatorFragment?.currentSelection()
                    val text = selection?.locator?.text?.highlight
                    val locatorJson = LocatorJsonHelper.toJson(selection?.locator)
                    
                    // Close the menu immediately after capturing selection
                    mode?.finish()

                    if (!text.isNullOrBlank()) {
                        val trimmedText = text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                        Toast.makeText(this@ReaderActivity, "Publishing...", Toast.LENGTH_SHORT).show()
                        postTextToServer(trimmedText, locatorJson)
                    } else {
                        Toast.makeText(this@ReaderActivity, "No text selected", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode?) {}

        override fun onGetContentRect(
            mode: ActionMode?,
            view: android.view.View?,
            outRect: android.graphics.Rect?
        ) {
            // Let the framework fall back to default behavior to avoid flicker on some devices
            super.onGetContentRect(mode, view, outRect)
        }
    }

    private suspend fun postTextToServer(text: String, locatorJson: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                // 0. Check existence
                val existingNote = aiNoteRepo.findNoteByText(text)
                if (existingNote != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReaderActivity, "Note found!", Toast.LENGTH_SHORT).show()
                        com.example.booxreader.data.ui.notes.AiNoteDetailActivity.open(this@ReaderActivity, existingNote.id)
                    }
                    return@withContext
                }

                // 1. Save locally first (Draft)
                val key = bookKey
                val noteId = aiNoteRepo.add(key, text, "", locatorJson)

                // 2. Fetch from server
                val result = aiNoteRepo.fetchAiExplanation(text)

                if (result != null) {
                    val (finalText, content) = result
                    
                    // 3. Update DB
                    val note = aiNoteRepo.getById(noteId)
                    if (note != null) {
                         val updatedNote = note.copy(
                             originalText = finalText, 
                             aiResponse = content
                         )
                         aiNoteRepo.update(updatedNote)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReaderActivity, "Finished", Toast.LENGTH_SHORT).show()
                        loadHighlights() // Refresh highlights
                        com.example.booxreader.data.ui.notes.AiNoteDetailActivity.open(this@ReaderActivity, noteId)
                    }
                } else {
                    // Failed to fetch -> It remains a draft
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ReaderActivity, "Saved as draft (Network Error)", Toast.LENGTH_SHORT).show()
                        loadHighlights() // Refresh highlights even for drafts
                        com.example.booxreader.data.ui.notes.AiNoteDetailActivity.open(this@ReaderActivity, noteId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ReaderActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reader_settings, null)
        val tvFontSize = dialogView.findViewById<TextView>(R.id.tvFontSize)
        val btnDecrease = dialogView.findViewById<Button>(R.id.btnDecreaseFont)
        val btnIncrease = dialogView.findViewById<Button>(R.id.btnIncreaseFont)
        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks = dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val switchPageTap = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)

        tvFontSize.text = "$currentFontSize%"
        switchPageTap.isChecked = pageTapEnabled

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        btnDecrease.setOnClickListener {
            if (currentFontSize > 50) {
                currentFontSize -= 25
                tvFontSize.text = "$currentFontSize%"
                applyFontSize(currentFontSize)
            }
        }

        btnIncrease.setOnClickListener {
            if (currentFontSize < 500) {
                currentFontSize += 25
                tvFontSize.text = "$currentFontSize%"
                applyFontSize(currentFontSize)
            }
        }

        btnSettingsAddBookmark.setOnClickListener {
            addBookmarkFromCurrentPosition()
        }

        btnSettingsShowBookmarks.setOnClickListener {
            openBookmarkList()
        }

        switchPageTap.setOnCheckedChangeListener { _, isChecked ->
            pageTapEnabled = isChecked
            prefs.edit().putBoolean("page_tap_enabled", isChecked).apply()
        }

        dialog.show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pageTapEnabled && ev.pointerCount == 1) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    potentialPageTap = true
                    tapDownTime = ev.downTime
                    tapDownX = ev.x
                    tapDownY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (potentialPageTap) {
                        val dx = kotlin.math.abs(ev.x - tapDownX)
                        val dy = kotlin.math.abs(ev.y - tapDownY)
                        if (dx > touchSlop || dy > touchSlop) {
                            potentialPageTap = false
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    potentialPageTap = false
                }
                MotionEvent.ACTION_UP -> {
                    if (potentialPageTap) {
                        val duration = ev.eventTime - tapDownTime
                        if (duration <= ViewConfiguration.getTapTimeout()) {
                            val width = binding.root.width
                            if (width > 0) {
                                // Cancel child handling to avoid triggering selection
                                val cancelEvent = MotionEvent.obtain(ev)
                                cancelEvent.action = MotionEvent.ACTION_CANCEL
                                super.dispatchTouchEvent(cancelEvent)
                                cancelEvent.recycle()

                                val isRight = ev.x > width / 2f
                                if (isRight) {
                                    navigatorFragment?.goForward()
                                } else {
                                    navigatorFragment?.goBackward()
                                }
                                potentialPageTap = false
                                return true
                            }
                        }
                    }
                    potentialPageTap = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @OptIn(ExperimentalReadiumApi::class)
    private fun applyFontSize(sizePercent: Int) {
        // 1. Save to prefs
        prefs.edit().putInt("font_size", sizePercent).apply()
        
        // 2. Apply to Navigator
        val navigator = navigatorFragment
        if (navigator == null) {
            // Wait, if this is called from initNavigator, it should be fine.
            return
        }

        lifecycleScope.launch {
            try {
                val newPreferences = org.readium.r2.navigator.epub.EpubPreferences(
                    fontSize = sizePercent / 100.0,
                    publisherStyles = false,
                    lineHeight = 1.4 
                )
                navigator.submitPreferences(newPreferences)
                // Optional: Debug feedback
                // Toast.makeText(this@ReaderActivity, "Font size: $sizePercent%", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadHighlights() {
        val key = bookKey ?: return
        lifecycleScope.launch {
            val notes = aiNoteRepo.getByBook(key)
            val keywordPairs = buildKeywordPairs(notes)
            val baseDecorations = notes.mapNotNull { note ->
                val locator = LocatorJsonHelper.fromJson(note.locatorJson) ?: return@mapNotNull null
                Decoration(
                    id = note.id.toString(),
                    locator = locator,
                    style = Decoration.Style.Underline(
                        tint = android.graphics.Color.parseColor("#FF8C00") // dark orange underline
                    )
                )
            }

            val keywordDecorations = withContext(Dispatchers.IO) {
                buildKeywordDecorations(notes, keywordPairs)
            }

            navigatorFragment?.applyDecorations(baseDecorations + keywordDecorations, "ai_notes")
        }
    }

    private fun initTapNavigation() {
        binding.tapOverlay.visibility = android.view.View.GONE
    }

    private fun buildKeywordPairs(notes: List<com.example.booxreader.data.db.AiNoteEntity>): List<Pair<String, Long>> {
        val pairs = LinkedHashSet<Pair<String, Long>>()
        val splitRegex = Regex("[\\s\\p{Punct}、，。！？；：.!?;:（）()【】「」『』《》<>]+")

        notes.forEach { note ->
            val base = note.originalText.trim()
            if (base.isNotEmpty()) {
                pairs.add(base to note.id)

                // Split into tokens to catch shorter keywords (helps CJK and longer sentences)
                splitRegex.split(base)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { token ->
                        pairs.add(token to note.id)
                    }
            }
        }

        return pairs.toList()
    }

    private suspend fun buildKeywordDecorations(
        notes: List<com.example.booxreader.data.db.AiNoteEntity>,
        keywordPairs: List<Pair<String, Long>>
    ): List<Decoration> =
        withContext(Dispatchers.IO) {
            val publication = currentPublication ?: return@withContext emptyList()
            if (keywordPairs.isEmpty()) return@withContext emptyList()

            val decorations = mutableListOf<Decoration>()

            publication.readingOrder.forEach { link ->
                val resource = publication.get(link) ?: return@forEach
                val contentBytes = resource.read().getOrNull() ?: return@forEach
                val rawContent = contentBytes.toString(Charsets.UTF_8)
                if (rawContent.isEmpty()) return@forEach
                val rawLength = rawContent.length.coerceAtLeast(1)

                // Build plain text and mapping from plain index -> raw index to better align progression
                val plainBuilder = StringBuilder()
                val plainToRaw = mutableListOf<Int>()
                var inTag = false
                rawContent.forEachIndexed { idx, ch ->
                    when {
                        ch == '<' -> inTag = true
                        ch == '>' && inTag -> {
                            inTag = false
                        }
                        !inTag -> {
                            plainBuilder.append(ch)
                            plainToRaw.add(idx)
                        }
                    }
                }

                val plainText = plainBuilder.toString()
                val plainLength = plainText.length.coerceAtLeast(1)

                keywordPairs.forEach { (keyword, noteId) ->
                    if (keyword.isEmpty()) return@forEach
                    val pattern = Regex(Regex.escape(keyword), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    pattern.findAll(plainText).forEach { match ->
                        val plainIndex = match.range.first
                        val rawIndex = plainToRaw.getOrNull(plainIndex) ?: plainIndex
                        val progression = rawIndex.toDouble() / rawLength
                        val hrefUrl = Url(link.href.toString()) ?: return@forEach
                        val locator = Locator(
                            href = hrefUrl,
                            mediaType = link.mediaType ?: MediaType.BINARY,
                            title = link.title,
                            locations = Locator.Locations(progression = progression),
                            text = Locator.Text(highlight = match.value)
                        )
                        val decorationId = "note_${noteId}_kw_${link.href}_${plainIndex}"
                        decorations.add(
                            Decoration(
                                id = decorationId,
                                locator = locator,
                                style = Decoration.Style.Underline(
                                    tint = android.graphics.Color.parseColor("#FF8C00")
                                )
                            )
                        )
                    }
                }
            }

            return@withContext decorations
        }

    companion object {
        private const val MAX_SELECTION_CHARS = 4000

        fun open(context: Context, bookUri: Uri) {
            val intent = Intent(context, ReaderActivity::class.java).apply {
                data = bookUri
            }
            context.startActivity(intent)
        }
    }
}
