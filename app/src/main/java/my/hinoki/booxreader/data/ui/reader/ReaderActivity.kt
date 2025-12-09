package my.hinoki.booxreader.data.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import my.hinoki.booxreader.R
import my.hinoki.booxreader.core.eink.EInkHelper
import my.hinoki.booxreader.databinding.ActivityReaderBinding
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.data.ui.notes.AiNoteListActivity
import my.hinoki.booxreader.data.ui.notes.AiNoteDetailActivity
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.ui.bookmarks.BookmarkListActivity
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

import kotlin.OptIn

import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.AiNoteRepository
import java.util.zip.ZipInputStream

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val syncRepo by lazy { UserSyncRepository(applicationContext) }
    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as my.hinoki.booxreader.BooxReaderApp
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(
                    app,
                    BookRepository(app, syncRepo),
                    BookmarkRepository(app, app.okHttpClient, syncRepo),
                    AiNoteRepository(app, app.okHttpClient, syncRepo),
                    syncRepo
                ) as T
            }
        }
    }
    
    private var navigatorFragment: EpubNavigatorFragment? = null
    private var currentActionMode: ActionMode? = null

    // Activity local state for UI interaction
    private var pageTapEnabled: Boolean = true
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var currentFontSize: Int = 150
    private var currentFontWeight: Int = 400
    private var booxBatchRefreshEnabled: Boolean = true
    private var booxFastModeEnabled: Boolean = true
    private var refreshJob: Job? = null
    private val booxRefreshDelayMs = 200L
    private val selectionGuardDelayMs = 100L

    // 簡化的選擇狀態管理
    private enum class SelectionState {
        IDLE,      // 閒置
        GUARDING,  // 防誤觸保護中
        SELECTING, // 正在選擇
        MENU_OPEN  // 菜單已打開
    }

    private var selectionState = SelectionState.IDLE
    private var selectionGuardJob: Job? = null
    private var pendingDecorations: List<Decoration>? = null
    private var selectionStartX = 0f
    private var selectionStartY = 0f

    private val REQ_BOOKMARK = 1001
    private val PREFS_NAME = "reader_prefs"

    private val gestureDetector by lazy {
        androidx.core.view.GestureDetectorCompat(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Enable fast mode on scroll to ensure smooth movement
                if (booxFastModeEnabled && !isSelectionFlowActive()) {
                    EInkHelper.enableFastMode(binding.root)
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 1. 優先處理選擇相關的點擊
                // 如果有活動的ActionMode（選擇菜單已打開），優先關閉它
                if (currentActionMode != null) {
                    currentActionMode?.finish()
                    lifecycleScope.launch {
                        try { navigatorFragment?.clearSelection() } catch (_: Exception) {}
                    }
                    return true
                }

                // 2. 如果正在選擇過程中（包括防誤觸保護、正在拖動選擇、菜單已打開），不處理頁面切換
                if (isSelectionFlowActive()) {
                    return true
                }

                // 3. 頁面導航（僅在沒有選擇活動時）
                if (pageTapEnabled) {
                    val width = binding.root.width
                    val x = e.x
                    if (width > 0) {
                        // 30-40-30 規則
                        if (x < width * 0.3f) {
                            navigatorFragment?.goBackward()
                            return true
                        } else if (x > width * 0.7f) {
                            navigatorFragment?.goForward()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun isSelectionFlowActive(): Boolean {
        return selectionState != SelectionState.IDLE
    }

    private fun startSelectionGuard() {
        selectionGuardJob?.cancel()
        selectionState = SelectionState.GUARDING
        navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(true)

        // 180ms 後如果沒有開始選擇，則釋放
        selectionGuardJob = lifecycleScope.launch {
            delay(selectionGuardDelayMs)
            if (selectionState == SelectionState.GUARDING) {
                selectionState = SelectionState.IDLE
                navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun onSelectionStarted() {
        selectionGuardJob?.cancel()
        selectionState = SelectionState.SELECTING
    }

    private fun onActionModeCreated() {
        selectionState = SelectionState.MENU_OPEN
    }

    private fun onSelectionFinished() {
        selectionState = SelectionState.IDLE
        navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(false)
    }


    private fun flushPendingDecorations() {
        pendingDecorations?.let { pending ->
            lifecycleScope.launch {
                navigatorFragment?.applyDecorations(pending, "ai_notes")
            }
        }
        pendingDecorations = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tapOverlay.bringToFront()

        val bookUri = intent.data
        if (bookUri == null) {
            Toast.makeText(this, "No book URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupObservers()
        setupUI()

        if (savedInstanceState == null) {
            viewModel.openBook(bookUri)
        } else {
             // Re-attach if fragment exists
            navigatorFragment = supportFragmentManager.findFragmentById(R.id.readerContainer) as? EpubNavigatorFragment
            if (navigatorFragment == null) {
                viewModel.openBook(bookUri)
            }
        }
    }

    private fun setupUI() {
        // Load prefs
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedSize = prefs.getInt("font_size", 150)
        currentFontSize = if (savedSize == 100) 150 else savedSize
        currentFontWeight = prefs.getInt("font_weight", 400).coerceIn(300, 900)
        pageTapEnabled = prefs.getBoolean("page_tap_enabled", true)
        booxBatchRefreshEnabled = prefs.getBoolean("boox_batch_refresh", true)
        booxFastModeEnabled = prefs.getBoolean("boox_fast_mode", true)

        // Fetch cloud settings (best effort) and apply if newer
        lifecycleScope.launch {
            val remote = syncRepo.pullSettingsIfNewer()
            if (remote != null) {
                applyReaderSettings(remote)
            }
        }

        binding.btnAinote.setOnClickListener {
            val key = viewModel.currentBookKey.value
            if (key != null) {
                AiNoteListActivity.open(this, key)
            }
        }

        binding.btnAddBookmark.visibility = android.view.View.GONE

        binding.btnShowBookmarks.setOnClickListener {
            publishCurrentSelection()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.tapOverlay.visibility = android.view.View.GONE
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.publication.collectLatest {
                if (it != null) {
                    // Load saved locator
                    val key = viewModel.currentBookKey.value
                    val savedJson = key?.let { syncRepo.getCachedProgress(it) }
                    
                    val initialLocator = LocatorJsonHelper.fromJson(savedJson)
                    initNavigator(it, initialLocator)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.decorations.collectLatest {
                if (isSelectionFlowActive()) {
                    pendingDecorations = it
                    return@collectLatest
                }
                pendingDecorations = null
                navigatorFragment?.applyDecorations(it, "ai_notes")
            }
        }

        lifecycleScope.launch {
            viewModel.toastMessage.collect {
                Toast.makeText(this@ReaderActivity, it, Toast.LENGTH_SHORT).show()
            }
        }
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigateToNote.collect { target ->
                    AiNoteDetailActivity.open(
                        this@ReaderActivity,
                        target.noteId,
                        autoStreamText = target.autoStreamText
                    )
                }
            }
        }
    }
    
    private fun initNavigator(publication: Publication, initialLocator: Locator?) {
        // Avoid re-creating if already set up
        if (navigatorFragment != null) return

        val factory = EpubNavigatorFactory(publication)
        val config = EpubNavigatorFragment.Configuration {
            selectionActionModeCallback = this@ReaderActivity.selectionActionModeCallback
        }

        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            listener = null,
            configuration = config
        )

        if (supportFragmentManager.findFragmentById(R.id.readerContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.readerContainer, EpubNavigatorFragment::class.java, null)
                .commitNow()
        }
        
        binding.tapOverlay.bringToFront()
        navigatorFragment = supportFragmentManager.findFragmentById(R.id.readerContainer) as? EpubNavigatorFragment

        // Optimize WebView: Disable overscroll and apply e-ink tuning
        navigatorFragment?.view?.post {
            disableWebViewOverscroll(navigatorFragment?.view)
            applyBooxWebViewSettings(navigatorFragment?.view)
            setupWebViewSelectionListener(navigatorFragment?.view)
        }

        applyFontSize(currentFontSize)
        applyFontWeight(currentFontWeight)
        observeLocatorUpdates()
        setupDecorationListener()

        // Refresh E-Ink
        binding.root.post { requestEinkRefresh() }
    }

    private fun disableWebViewOverscroll(view: View?) {
        if (view is android.webkit.WebView) {
            view.overScrollMode = View.OVER_SCROLL_NEVER
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableWebViewOverscroll(view.getChildAt(i))
            }
        }
    }

    private fun applyBooxWebViewSettings(view: View?) {
        if (!EInkHelper.isBoox()) return
        if (view is android.webkit.WebView) {
            view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
            view.overScrollMode = android.view.View.OVER_SCROLL_NEVER
            view.isHapticFeedbackEnabled = false
            view.settings.apply {
                displayZoomControls = false
                builtInZoomControls = false
                setSupportZoom(false)
                mediaPlaybackRequiresUserGesture = true
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyBooxWebViewSettings(view.getChildAt(i))
            }
        }
    }

    private fun setupWebViewSelectionListener(view: View?) {
        if (view is android.webkit.WebView) {
            // 恢復 WebView 的默認長按行為
            view.setOnLongClickListener { v ->
                // 觸覺反饋
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                // 標記選擇已開始
                onSelectionStarted()
                false // 返回 false 讓 WebView 繼續處理
            }

            // 監聽觸摸事件來管理選擇狀態
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 記錄觸摸開始位置
                        selectionStartX = event.x
                        selectionStartY = event.y
                        // 啟動選擇保護，防止誤觸
                        if (selectionState == SelectionState.IDLE) {
                            startSelectionGuard()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 檢查是否在拖動（可能是在選擇文字）
                        val dx = abs(event.x - selectionStartX)
                        val dy = abs(event.y - selectionStartY)
                        if ((dx > touchSlop || dy > touchSlop) && selectionState == SelectionState.GUARDING) {
                            // 用戶在拖動選擇文字，標記為選擇中
                            onSelectionStarted()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 觸摸結束時，如果還在 guarding 狀態且沒有開始選擇，則恢復空閒狀態
                        if (selectionState == SelectionState.GUARDING) {
                            onSelectionFinished()
                        }
                    }
                }
                false // 返回 false 讓 WebView 繼續處理
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupWebViewSelectionListener(view.getChildAt(i))
            }
        }
    }

    private fun applyFontWeightViaWebView(view: View?, weight: Int) {
        if (view is android.webkit.WebView) {
            val safeWeight = weight.coerceIn(300, 900)
            val js = """
                (function() {
                  const w = ${safeWeight};
                  let style = document.getElementById('boox-font-weight-style');
                  if (!style) {
                    style = document.createElement('style');
                    style.id = 'boox-font-weight-style';
                    document.head.appendChild(style);
                  }
                  style.textContent = 'body, p, span, div { font-weight: ' + w + ' !important; }';
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontWeightViaWebView(view.getChildAt(i), weight)
            }
        }
    }
    
    private fun applyReaderCss(view: View?) {
        if (view is android.webkit.WebView) {
            val css = """
                p, div, li, h1, h2, h3, h4, h5, h6, blockquote {
                    font-size: 1rem !important;
                }
                aside[epub\\:type~="footnote"],
                section[epub\\:type~="footnote"],
                nav[epub\\:type~="footnotes"],
                .footnote, .note {
                  font-size: 0.8em !important;
                  line-height: 1.5 !important;
                }
                a[epub\\:type~="noteref"], sup, sub {
                  font-size: 0.9em !important;
                  line-height: 1.2 !important;
                }

            """.trimIndent()
            val js = """
                (function() {
                  let style = document.getElementById('boox-reader-style');
                  if (!style) {
                    style = document.createElement('style');
                    style.id = 'boox-reader-style';
                    document.head.appendChild(style);
                  }
                  style.textContent = `${css}`;
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyReaderCss(view.getChildAt(i))
            }
        }
    }

    private fun applySelectionFriendlyCss(view: View?) {
        if (view is android.webkit.WebView) {
            val css = """
                * {
                    -webkit-user-select: text !important;
                    user-select: text !important;
                }
                img, .no-select {
                    -webkit-user-select: none !important;
                    user-select: none !important;
                }
                ::selection {
                    background-color: rgba(0, 0, 0, 0.3) !important;
                }
                ::-moz-selection {
                    background-color: rgba(0, 0, 0, 0.3) !important;
                }
                /* 限制選擇範圍 - 通過限制高亮區域 */
                * {
                    -webkit-touch-callout: default !important;
                }
            """.trimIndent()

            val js = """
                var style = document.createElement('style');
                style.textContent = `$css`;
                document.head.appendChild(style);
            """.trimIndent()

            view.evaluateJavascript(js, null)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applySelectionFriendlyCss(view.getChildAt(i))
            }
        }
    }
    private fun requestEinkRefresh(full: Boolean = false, immediate: Boolean = false) {
        if (!EInkHelper.isBoox()) return
        if (isSelectionFlowActive()) {
            // 在選擇過程中，延遲刷新或使用更輕量的刷新
            if (immediate) {
                // 如果是立即刷新，使用部分刷新減少閃爍
                EInkHelper.refresh(binding.root, full = false)
            }
            return
        }
        refreshJob?.cancel()
        if (immediate || !booxBatchRefreshEnabled) {
            EInkHelper.refresh(binding.root, full)
            return
        }
        refreshJob = lifecycleScope.launch {
            delay(booxRefreshDelayMs)
            EInkHelper.refresh(binding.root, full)
        }
    }

    private fun applyFontZoomLikeNeoReader(view: View?, sizePercent: Int) {
        if (view is android.webkit.WebView) {
            view.settings.textZoom = sizePercent
            // Also reset any zoom styles that might have been injected
            val js = """
                (function() {
                  document.documentElement.style.zoom = '';
                  document.body.style.zoom = '';
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyFontZoomLikeNeoReader(view.getChildAt(i), sizePercent)
            }
        }
    }
    
    @OptIn(FlowPreview::class)
    private fun observeLocatorUpdates() {
        val navigator = navigatorFragment ?: return
        
        lifecycleScope.launch {
            navigator.currentLocator
                .sample(1500)
                .collectLatest {
                    if (isSelectionFlowActive()) return@collectLatest // avoid reflows/progress writes during selection
                    // Re-apply styles after page/layout changes so the style persists across navigations and restarts
                    applyFontSize(currentFontSize)
                    applyFontWeightViaWebView(navigatorFragment?.view, currentFontWeight)
                    applyReaderCss(navigatorFragment?.view)
                    applySelectionFriendlyCss(navigatorFragment?.view)

                    val json = LocatorJsonHelper.toJson(it) ?: return@collectLatest
                    val key = viewModel.currentBookKey.value ?: return@collectLatest

                    // Local Prefs
                    syncRepo.cacheProgress(key, json)
                    
                    // ViewModel (Server/DB)
                    viewModel.saveProgress(json)

                     val bookId = intent.data?.toString() 
                     if (bookId != null) {
                         viewModel.saveProgressToDb(bookId, json)
                     }

                    requestEinkRefresh()
                }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgressImmediate()
    }

    private fun saveCurrentProgressImmediate() {
        val navigator = navigatorFragment ?: return
        val key = viewModel.currentBookKey.value ?: return
        val locator = navigator.currentLocator.value
        val json = LocatorJsonHelper.toJson(locator) ?: return

        syncRepo.cacheProgress(key, json)
            
        viewModel.saveProgress(json)
        val bookId = intent.data?.toString()
        if (bookId != null) {
            viewModel.saveProgressToDb(bookId, json)
        }
    }

    private fun setupDecorationListener() {
        navigatorFragment?.addDecorationListener("ai_notes", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val noteId = my.hinoki.booxreader.data.reader.DecorationHandler.extractNoteIdFromDecorationId(event.decoration.id)
                return if (noteId != null) {
                    AiNoteDetailActivity.open(this@ReaderActivity, noteId)
                    true
                } else {
                    false
                }
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BOOKMARK && resultCode == RESULT_OK && data != null) {
            val json = data.getStringExtra(BookmarkListActivity.EXTRA_LOCATOR_JSON)
            val locator = LocatorJsonHelper.fromJson(json)
            if (locator != null) {
                navigatorFragment?.go(locator)
                requestEinkRefresh()
            }
        }
    }

    // --- UI Actions ---

    private fun publishCurrentSelection() {
        lifecycleScope.launch {
            val selection = navigatorFragment?.currentSelection()
            val text = selection?.locator?.text?.highlight
            val locatorJson = LocatorJsonHelper.toJson(selection?.locator)

            if (text.isNullOrBlank()) {
                Toast.makeText(this@ReaderActivity, "No selection", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                navigatorFragment?.clearSelection()
            } catch (_: Exception) {}

            val sanitized = withContext(Dispatchers.Default) {
                text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                    .replace(Regex("\\s+"), " ")
                    .take(4000)
            }

            if (sanitized.isNotBlank()) {
                viewModel.postTextToServer(sanitized, locatorJson)
            }
        }
    }
    
    private fun openBookmarkList() {
        val key = viewModel.currentBookKey.value ?: return
        BookmarkListActivity.openForResult(this, key, REQ_BOOKMARK)
    }

    private fun addBookmarkFromCurrentPosition() {
        val navigator = navigatorFragment ?: return
        viewModel.addBookmark(navigator.currentLocator.value)
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reader_settings, null)
        val tvFontSize = dialogView.findViewById<TextView>(R.id.tvFontSize)
        val btnDecrease = dialogView.findViewById<Button>(R.id.btnDecreaseFont)
        val btnIncrease = dialogView.findViewById<Button>(R.id.btnIncreaseFont)
        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks = dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val switchPageTap = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val tvFontWeight = dialogView.findViewById<TextView>(R.id.tvFontWeight)
        val seekFontWeight = dialogView.findViewById<SeekBar>(R.id.seekFontWeight)
        val switchUseStreaming = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchUseStreaming)

        // Add Security Buttons
        val layout = dialogView as? android.widget.LinearLayout
        if (layout != null) {
            // Verify Hash Button
            val btnVerify = Button(this).apply {
                text = "Verify File Hash"
                setOnClickListener { showFileInfo() }
            }
            
            // Safety Scan Button
            val btnScan = Button(this).apply {
                text = "Run Safety Scan (Scripts)"
                setOnClickListener { runSafetyScan() }
            }
            
            val booxTitle = TextView(this).apply {
                text = "Boox / E-Ink"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 16, 0, 8)
            }

            val switchBatch = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = "Batch refresh (reduce flashing)"
                isChecked = booxBatchRefreshEnabled
                setOnCheckedChangeListener { _, checked ->
                    booxBatchRefreshEnabled = checked
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("boox_batch_refresh", checked)
                        .apply()
                }
            }

            val switchFast = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = "Auto A2 during interaction"
                isChecked = booxFastModeEnabled
                setOnCheckedChangeListener { _, checked ->
                    booxFastModeEnabled = checked
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("boox_fast_mode", checked)
                        .apply()
                }
            }

            val btnFullRefresh = Button(this).apply {
                text = "Full refresh now"
                setOnClickListener { requestEinkRefresh(full = true, immediate = true) }
            }

            layout.addView(btnVerify, layout.childCount - 2)
            layout.addView(btnScan, layout.childCount - 2)
            layout.addView(booxTitle, layout.childCount - 2)
            layout.addView(switchBatch, layout.childCount - 2)
            layout.addView(switchFast, layout.childCount - 2)
            layout.addView(btnFullRefresh, layout.childCount - 2)
        }

        tvFontSize.text = "$currentFontSize%"
        switchPageTap.isChecked = pageTapEnabled
        tvFontWeight.text = currentFontWeight.toString()
        seekFontWeight.max = 600 // represents 300-900
        seekFontWeight.progress = currentFontWeight - 300
        seekFontWeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val weight = 300 + progress
                tvFontWeight.text = weight.toString()
                applyFontWeight(weight)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load current URL
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUrl = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
        etServerUrl.setText(currentUrl)
        val streamingEnabled = prefs.getBoolean("use_streaming", false)
        switchUseStreaming.isChecked = streamingEnabled

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close") { _, _ ->
                // Save URL on close
                val newUrl = etServerUrl.text.toString().trim()
                // Only save/toast if URL is valid and CHANGED
                if (newUrl.isNotEmpty() && newUrl != currentUrl) {
                    prefs.edit().putString("server_base_url", newUrl).apply()
                    Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
                }
                pushSettingsToCloud()
            }
            .create()

        val fontStep = 10

        btnDecrease.setOnClickListener {
            if (currentFontSize > 50) {
                currentFontSize = (currentFontSize - fontStep).coerceAtLeast(50)
                tvFontSize.text = "$currentFontSize%"
                applyFontSize(currentFontSize)
            }
        }

        btnIncrease.setOnClickListener {
            if (currentFontSize < 500) {
                currentFontSize = (currentFontSize + fontStep).coerceAtMost(500)
                tvFontSize.text = "$currentFontSize%"
                applyFontSize(currentFontSize)
            }
        }

        btnSettingsAddBookmark.setOnClickListener { addBookmarkFromCurrentPosition() }
        btnSettingsShowBookmarks.setOnClickListener { openBookmarkList() }

        switchPageTap.setOnCheckedChangeListener { _, isChecked ->
            pageTapEnabled = isChecked
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("page_tap_enabled", isChecked).apply()
        }

        switchUseStreaming.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_streaming", isChecked).apply()
            val msg = if (isChecked) "Streaming enabled (/ws)" else "Streaming disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun runSafetyScan() {
        val uri = intent.data ?: return
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Scanning...")
            .setMessage("Checking for scripts and executable content.")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var threatsFound = mutableListOf<String>()
                val inputStream = contentResolver.openInputStream(uri)
                
                if (inputStream != null) {
                    ZipInputStream(inputStream).use {
                        var entry = it.nextEntry
                        while (entry != null) {
                            val name = entry.name.lowercase()
                            
                            // 1. Check extensions
                            if (name.endsWith(".js") || name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".sh")) {
                                threatsFound.add("Suspicious file type: ${entry.name}")
                            }
                            
                            // 2. Check content for <script> tags (basic check for HTML/XHTML)
                            if (!entry.isDirectory && (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm"))) {
                                // Read first 4KB or enough to find tags. 
                                // Note: Deep scanning entire files is slow. We'll scan reasonable chunks.
                                val buffer = ByteArray(8192)
                                val len = it.read(buffer)
                                if (len > 0) {
                                    val content = String(buffer, 0, len)
                                    if (content.contains("<script", ignoreCase = true) || content.contains("javascript:", ignoreCase = true)) {
                                        threatsFound.add("Script tag/link found in: ${entry.name}")
                                    }
                                }
                            }
                            
                            it.closeEntry()
                            entry = it.nextEntry
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (threatsFound.isEmpty()) {
                        AlertDialog.Builder(this@ReaderActivity)
                            .setTitle("Scan Complete")
                            .setMessage("✅ No obvious threats found.\n\nChecked for:\n- .js/.exe files\n- <script> tags in HTML")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val message = "⚠️ Potential Threats Detected:\n\n" + threatsFound.joinToString("\n")
                        AlertDialog.Builder(this@ReaderActivity)
                            .setTitle("Security Warning")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@ReaderActivity, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFileInfo() {
        val uri = intent.data ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                    inputStream.close()
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@ReaderActivity)
                            .setTitle("File Security Verification")
                            .setMessage("SHA-256 Checksum:\n\n$hash\n\nPlease verify this hash against a trusted source to ensure the file has not been tampered with.")
                            .setPositiveButton("Copy") { _, _ ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("SHA-256 Hash", hash)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@ReaderActivity, "Hash copied", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ReaderActivity, "Failed to verify file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyFontSize(sizePercent: Int) {
        currentFontSize = sizePercent
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("font_size", sizePercent).apply()
        
        val navigator = navigatorFragment ?: return
        
        // Use textZoom for more reliable font scaling, especially with CJK fonts.
        applyFontZoomLikeNeoReader(navigator.view, sizePercent)

        // Still apply other styles
        navigatorFragment?.view?.post {
            applyFontWeight(currentFontWeight)
            applyReaderCss(navigatorFragment?.view)
            applySelectionFriendlyCss(navigatorFragment?.view)
        }
    }

    private fun applyReaderSettings(settings: ReaderSettings) {
        currentFontSize = settings.fontSize
        currentFontWeight = settings.fontWeight
        pageTapEnabled = settings.pageTapEnabled
        booxBatchRefreshEnabled = settings.booxBatchRefresh
        booxFastModeEnabled = settings.booxFastMode
        applyFontSize(currentFontSize)
        applyFontWeight(currentFontWeight)
        applyReaderCss(navigatorFragment?.view)
        applySelectionFriendlyCss(navigatorFragment?.view)
    }
    
    private fun pushSettingsToCloud() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lifecycleScope.launch {
            syncRepo.pushSettings(ReaderSettings.fromPrefs(prefs))
        }
    }

    private fun applyFontWeight(weight: Int) {
        currentFontWeight = weight.coerceIn(300, 900)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("font_weight", currentFontWeight).apply()

        navigatorFragment?.view?.post {
            applyFontWeightViaWebView(navigatorFragment?.view, currentFontWeight)
        }
    }

    // --- Action Mode ---
    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            currentActionMode = mode
            onSelectionStarted()

            // 確保在 UI 線程更新狀態
            binding.root.post {
                onActionModeCreated()
            }

            // 觸覺反饋
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // 添加調試日誌
            android.util.Log.d("ReaderActivity", "ActionMode created - selectionState: $selectionState")

            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (mode == null || menu == null) return false

            // 清除並重新添加菜單項
            menu.clear()

            // 添加菜單項，確保有圖標和正確的顯示方式
            menu.add(Menu.NONE, 998, 1, "複製")
                .setIcon(android.R.drawable.ic_menu_edit)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

            menu.add(Menu.NONE, 999, 2, "發佈")
                .setIcon(android.R.drawable.ic_menu_share)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

            menu.add(Menu.NONE, 1000, 3, "Google Maps")
                .setIcon(android.R.drawable.ic_menu_mapmode)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

            // 添加調試日誌
            android.util.Log.d("ReaderActivity", "onPrepareActionMode called")

            // 延遲檢查選擇，避免在選擇還未準備好時就關閉菜單
            lifecycleScope.launch {
                delay(50) // 給選擇一點時間準備（從100ms減少到50ms）

                val selection = navigatorFragment?.currentSelection()
                val hasSelection = !selection?.locator?.text?.highlight.isNullOrBlank()
                android.util.Log.d("ReaderActivity", "Selection check - hasSelection: $hasSelection")

                if (!hasSelection && currentActionMode != null) {
                    android.util.Log.d("ReaderActivity", "No selection found, finishing ActionMode")
                    currentActionMode?.finish()
                } else if (hasSelection) {
                    android.util.Log.d("ReaderActivity", "Selection found: ${selection?.locator?.text?.highlight?.take(50)}...")
                }
            }

            return true
        }
        
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            if (item?.itemId == 998) {
                lifecycleScope.launch {
                    val selection = navigatorFragment?.currentSelection()
                    val text = selection?.locator?.text?.highlight
                    if (!text.isNullOrBlank()) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Book Text", text)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@ReaderActivity, "已複製", Toast.LENGTH_SHORT).show()
                        navigatorFragment?.clearSelection()
                        mode?.finish()
                    } else {
                        Toast.makeText(this@ReaderActivity, "No text selected", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            if (item?.itemId == 999) {
                lifecycleScope.launch {
                    try {
                        android.util.Log.d("ReaderActivity", "Publish action triggered from context menu")
                        val selection = navigatorFragment?.currentSelection()
                        val text = selection?.locator?.text?.highlight
                        val locatorJson = LocatorJsonHelper.toJson(selection?.locator)
                        
                        if (!text.isNullOrBlank()) {
                            val trimmed = text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                            viewModel.postTextToServer(trimmed, locatorJson)
                            
                            // Clear selection and dismiss menu
                            navigatorFragment?.clearSelection()
                            mode?.finish()
                        } else {
                            android.util.Log.e("ReaderActivity", "Selection text is null or blank")
                            Toast.makeText(this@ReaderActivity, "No text selected", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                         android.util.Log.e("ReaderActivity", "Error publishing selection", e)
                    }
                }
                return true
            }

            if (item?.itemId == 1000) {
                lifecycleScope.launch {
                    val selection = navigatorFragment?.currentSelection()
                    val text = selection?.locator?.text?.highlight
                    
                    if (!text.isNullOrBlank()) {
                        val trimmed = text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                        val gmmIntentUri = Uri.parse("geo:0,0?q=${Uri.encode(trimmed)}")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps")
                        
                        try {
                            startActivity(mapIntent)
                        } catch (e: Exception) {
                            // Fallback to browser or other map apps if Google Maps is not installed
                             val fallbackUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(trimmed)}")
                             val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri)
                             try {
                                 startActivity(fallbackIntent)
                             } catch (e2: Exception) {
                                 Toast.makeText(this@ReaderActivity, "無法開啟地圖: ${e2.message}", Toast.LENGTH_SHORT).show()
                             }
                        }
                        
                        navigatorFragment?.clearSelection()
                        mode?.finish()
                    } else {
                        Toast.makeText(this@ReaderActivity, "No text selected", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode?) {
            android.util.Log.d("ReaderActivity", "onDestroyActionMode called - selectionState: $selectionState")
            currentActionMode = null
            onSelectionFinished()
            flushPendingDecorations()
        }
    }

    // --- Touch Handling ---
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 忽略底部欄的觸摸
        val bottomBarRect = android.graphics.Rect()
        binding.bottomBar.getGlobalVisibleRect(bottomBarRect)
        if (bottomBarRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            return super.dispatchTouchEvent(ev)
        }

        // 如果正在選擇過程中，優先讓 WebView 處理觸摸事件
        if (isSelectionFlowActive()) {
            // 但仍然需要處理一些全局觸摸事件
            when (ev.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 觸摸結束時恢復正常模式
                    if (booxFastModeEnabled) {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        // 非選擇狀態下的觸摸事件處理
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshJob?.cancel()
                // 如果当前是空闲状态，启动选择保护
                if (selectionState == SelectionState.IDLE) {
                    startSelectionGuard()
                }
                gestureDetector.onTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                gestureDetector.onTouchEvent(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (booxFastModeEnabled) {
                    EInkHelper.restoreQualityMode(binding.root)
                }
                gestureDetector.onTouchEvent(ev)
            }
            else -> {
                gestureDetector.onTouchEvent(ev)
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    companion object {
        fun open(context: Context, bookUri: Uri) {
            val intent = Intent(context, ReaderActivity::class.java).apply {
                data = bookUri
            }
            context.startActivity(intent)
        }
    }
}
