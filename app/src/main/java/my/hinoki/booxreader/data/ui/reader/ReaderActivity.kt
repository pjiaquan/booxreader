package my.hinoki.booxreader.data.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.OptIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.R
import my.hinoki.booxreader.core.eink.EInkHelper
import my.hinoki.booxreader.core.eink.EInkHelper.ContrastMode
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.ui.common.BaseActivity
import my.hinoki.booxreader.data.ui.notes.AiNoteDetailActivity
import my.hinoki.booxreader.data.ui.notes.AiNoteListActivity
import my.hinoki.booxreader.data.ui.reader.nativev2.NativeNavigatorFragment
import my.hinoki.booxreader.databinding.ActivityReaderBinding
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.ui.bookmarks.BookmarkListActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : BaseActivity() {

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
                        syncRepo,
                        my.hinoki.booxreader.data.remote.ProgressPublisher(
                                baseUrlProvider = {
                                    val prefs =
                                            app.getSharedPreferences(
                                                    "reader_prefs",
                                                    Context.MODE_PRIVATE
                                            )
                                    prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
                                            ?: HttpConfig.DEFAULT_BASE_URL
                                },
                                client = app.okHttpClient
                        )
                ) as
                        T
            }
        }
    }

    private var nativeNavigatorFragment: NativeNavigatorFragment? = null
    private var currentBookId: String? = null
    private var searchJob: Job? = null

    // Activity local state for UI interaction
    private var pageTapEnabled: Boolean = true
    private var pageSwipeEnabled: Boolean = true
    private var touchSlop: Int = 0
    private var currentFontSize: Int = 150 // 從文石系統讀取
    private var currentFontWeight: Int = 400
    private var booxBatchRefreshEnabled: Boolean = true
    private var booxFastModeEnabled: Boolean = true
    private var pageAnimationEnabled: Boolean = false
    private var currentContrastMode: ContrastMode = ContrastMode.NORMAL
    private var refreshJob: Job? = null
    private val booxRefreshDelayMs = if (EInkHelper.isModernBoox()) 150L else 250L
    private val pageNavigationRefreshDelayMs = 300L // 頁面導航專用延遲
    private val buttonColor: Int
        get() =
                when (currentContrastMode) {
                    ContrastMode.NORMAL -> Color.parseColor("#E0E0E0")
                    ContrastMode.DARK -> Color.parseColor("#333333")
                    ContrastMode.SEPIA -> Color.parseColor("#D9C5A3")
                    ContrastMode.HIGH_CONTRAST -> Color.DKGRAY
                }

    private val selectionDebugLogging = false
    private var hadNativeSelectionOnDown = false

    // Swipe Block variables
    private var swipeBlockActive = false
    private var swipeBlockStartX = 0f
    private var swipeBlockStartY = 0f
    private var swipeBlockStartAtMs = 0L

    private data class ChapterItem(val title: String, val link: Link, val depth: Int)

    private val REQ_BOOKMARK = 1001

    private val gestureDetector by lazy {
        GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Native Reader Selection Handling - if selector has selection, clear it
                        if (nativeNavigatorFragment?.hasSelection() == true) {
                            nativeNavigatorFragment?.clearSelection()
                            return true
                        }

                        // Page navigation
                        if (pageTapEnabled) {
                            val width = binding.root.width
                            val x = e.x
                            if (width > 0) {
                                if (x < width * 0.3f) {
                                    goPageBackward()
                                    if (EInkHelper.isBooxDevice()) {
                                        binding.root.postDelayed(
                                                { EInkHelper.refreshPartial(binding.root) },
                                                pageNavigationRefreshDelayMs
                                        )
                                    }
                                    return true
                                } else if (x > width * 0.7f) {
                                    goPageForward()
                                    if (EInkHelper.isBooxDevice()) {
                                        binding.root.postDelayed(
                                                { EInkHelper.refreshPartial(binding.root) },
                                                pageNavigationRefreshDelayMs
                                        )
                                    }
                                    return true
                                }
                            }
                        }
                        return false
                    }
                }
        )
    }

    // --- Selection & Navigation ---

    // Selection uses the system ActionMode menu. Avoid adding extra overlay UI here to keep
    // paging/selection fast on e-ink.

    private fun goPageForward() {
        nativeNavigatorFragment?.goForward()
    }

    private fun goPageBackward() {
        nativeNavigatorFragment?.goBackward()
    }

    companion object {
        const val PREFS_NAME = "reader_prefs"
        private const val EXTRA_BOOK_KEY = "extra_book_key"
        private const val EXTRA_BOOK_TITLE = "extra_book_title"
        private const val EXTRA_LOCATOR_JSON = "extra_locator_json"

        fun open(
                context: Context,
                bookKey: String,
                bookTitle: String? = null,
                locatorJson: String? = null
        ) {
            val intent =
                    Intent(context, ReaderActivity::class.java).apply {
                        putExtra(EXTRA_BOOK_KEY, bookKey)
                        putExtra(EXTRA_BOOK_TITLE, bookTitle)
                        if (locatorJson != null) {
                            putExtra(EXTRA_LOCATOR_JSON, locatorJson)
                        }
                        data = Uri.parse(bookKey)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
            context.startActivity(intent)
        }

        fun open(context: Context, bookUri: Uri) {
            open(context, bookUri.toString(), null, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 文石設備特定設置
        if (EInkHelper.isBooxDevice()) {
            // We now use the official Onyx SDK via EInkHelper

            currentFontWeight = 400 // 固定預設值
        }

        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Reset overlays
        // Reset overlays
        val win = window
        WindowCompat.setDecorFitsSystemWindows(win, false)
        WindowCompat.getInsetsController(win, win.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val key = intent.getStringExtra(EXTRA_BOOK_KEY)
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE)
        if (key == null) {
            finish()
            return
        }
        currentBookId = key

        setupUI()

        // Handle initial locator if present
        handleLocatorIntent(intent)

        // 關鍵：在程式啟動時立即強制讀取文石系統字體設定
        if (EInkHelper.isBooxDevice()) {
            val detectedSize = getBooxSystemFontSize()

            // 如果檢測到的值不合理，使用文石常見的標準值
            currentFontSize =
                    if (detectedSize >= 100 && detectedSize <= 200) {
                        detectedSize
                    } else {
                        140 // 文石設備常見的標準大小
                    }

            currentFontWeight = 400 // 固定預設值
        }

        val bookUri = intent.data
        if (bookUri == null) {
            Toast.makeText(this, "No book URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupObservers()

        if (savedInstanceState == null) {
            viewModel.openBook(bookUri, contentResolver)
        } else {
            // Re-attach if fragment exists
            supportFragmentManager.executePendingTransactions()
            val fragment = supportFragmentManager.findFragmentById(R.id.readerContainer)
            nativeNavigatorFragment = fragment as? NativeNavigatorFragment

            if (nativeNavigatorFragment == null) {
                viewModel.openBook(bookUri, contentResolver)
            } else {
                // Ensure the current theme is applied to the re-attached fragment
                applyContrastMode(currentContrastMode)
            }
        }
    }

    private fun setupUI() {
        // Load prefs (不包含字體大小和字體粗細)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentFontWeight = 400 // 使用預設字體粗細，不從SharedPreferences讀取
        pageTapEnabled = prefs.getBoolean("page_tap_enabled", true)
        pageSwipeEnabled = prefs.getBoolean("page_swipe_enabled", true)
        booxBatchRefreshEnabled = prefs.getBoolean("boox_batch_refresh", true)
        booxFastModeEnabled = prefs.getBoolean("boox_fast_mode", true)
        pageAnimationEnabled = prefs.getBoolean("page_animation_enabled", false)

        // 載入本地設置（包括對比模式）
        val contrastModeOrdinal = prefs.getInt("contrast_mode", ContrastMode.NORMAL.ordinal)
        currentContrastMode = ContrastMode.values()[contrastModeOrdinal]

        // Fetch cloud settings (best effort) and apply if newer
        lifecycleScope.launch {
            val remote = syncRepo.pullSettingsIfNewer()
            if (remote != null) {
                applyReaderSettings(remote)
            } else {
                // 如果沒有雲端設置，應用本地設置
                applyFontSize(currentFontSize)
                applyFontWeight(currentFontWeight)
                if (EInkHelper.supportsHighContrast()) {
                    applyContrastMode(currentContrastMode)
                }
            }
        }

        binding.btnAinote.setOnClickListener {
            val key = viewModel.currentBookKey.value
            if (key != null) {
                AiNoteListActivity.open(this, key)
            }
        }

        binding.btnAddBookmark.visibility = android.view.View.GONE

        binding.btnChapters.setOnClickListener { openChapterPicker() }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }
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
        if (nativeNavigatorFragment != null) {
            return
        }

        Log.d("ReaderActivity", "Initializing NativeNavigatorFragment")
        val nativeFrag =
                NativeNavigatorFragment().apply { setPublication(publication, initialLocator) }
        supportFragmentManager
                .beginTransaction()
                .replace(R.id.readerContainer, nativeFrag)
                .commitNow()
        nativeNavigatorFragment = nativeFrag
        Log.d("ReaderActivity", "NativeNavigatorFragment committed: $nativeNavigatorFragment")

        // Apply current theme immediately
        applyContrastMode(currentContrastMode)

        observeLocatorUpdates() // Start observing native locator

        // Standard E-Ink refresh request only
        binding.root.post { requestEinkRefresh() }
    }

    private fun requestEinkRefresh(full: Boolean = false, immediate: Boolean = false) {
        if (!EInkHelper.isBooxDevice()) return

        refreshJob?.cancel()

        if (immediate || !booxBatchRefreshEnabled) {
            // 立即刷新
            if (full) {
                EInkHelper.refreshFull(binding.root)
            } else {
                EInkHelper.refreshPartial(binding.root)
            }
            return
        }

        // 批量刷新 - 智能延遲
        refreshJob =
                lifecycleScope.launch {
                    delay(booxRefreshDelayMs)

                    // 根據設備型號選擇刷新策略
                    if (EInkHelper.isModernBoox()) {
                        // 新型號使用智能刷新
                        EInkHelper.smartRefresh(
                                binding.root,
                                hasTextChanges = full,
                                hasImageChanges = false
                        )
                    } else {
                        // 舊型號使用傳統刷新
                        if (full) {
                            EInkHelper.refreshFull(binding.root)
                        } else {
                            EInkHelper.refreshPartial(binding.root)
                        }
                    }
                }
    }

    private fun applyAllSettingsWithRetry() {
        // Capture initial location if possible
        val initialLocator = nativeNavigatorFragment?.currentLocator?.value

        // 關鍵：每次應用時都重新讀取文石系統字體設定
        currentFontSize = getBooxSystemFontSize()
        currentFontWeight = 400 // 固定使用預設字體粗細，不使用用戶設定

        // 立即應用一次
        applyFontSize(currentFontSize)
        applyFontWeight(currentFontWeight)
        applyContrastMode(currentContrastMode)

        // Restore location immediately
        if (initialLocator != null) {
            nativeNavigatorFragment?.go(initialLocator, pageAnimationEnabled)
        }

        // 延遲再次應用以確保文檔載入完成
        nativeNavigatorFragment?.view?.postDelayed(
                {

                    // Capture location again before this delayed update
                    val delayedLocator =
                            nativeNavigatorFragment?.currentLocator?.value ?: initialLocator

                    // 重新讀取文石系統字體設定
                    val newFontSize = getBooxSystemFontSize()
                    currentFontSize = newFontSize
                    currentFontWeight = 400 // 固定預設值

                    applyFontSize(currentFontSize)
                    applyFontWeight(currentFontWeight)
                    applyContrastMode(currentContrastMode)

                    // Restore location
                    if (delayedLocator != null) {
                        nativeNavigatorFragment?.go(delayedLocator, pageAnimationEnabled)
                    }

                    // 再次延遲以確保穩定
                    nativeNavigatorFragment?.view?.postDelayed(
                            {
                                val finalLocator =
                                        nativeNavigatorFragment?.currentLocator?.value
                                                ?: delayedLocator

                                // 最終確認時也重新讀取文石系統設定
                                val finalFontSize = getBooxSystemFontSize()

                                currentFontSize = finalFontSize
                                currentFontWeight = 400 // 固定預設值

                                // 如果字體沒有變化，可以跳過重應用以避免閃爍/重排，但為了保險起見還是應用，只是加上位置恢復
                                applyFontSize(currentFontSize)
                                applyFontWeight(currentFontWeight)
                                applyContrastMode(currentContrastMode)

                                if (finalLocator != null) {
                                    nativeNavigatorFragment?.go(finalLocator, pageAnimationEnabled)
                                }
                            },
                            1000
                    ) // 1秒後最終確認
                },
                500
        ) // 0.5秒後重新應用
    }

    @OptIn(FlowPreview::class)
    private fun observeLocatorUpdates() {
        val nativeNavigator = nativeNavigatorFragment ?: return
        Log.d("ReaderActivity", "observeLocatorUpdates")

        lifecycleScope.launch {
            nativeNavigator.currentLocator.sample(1500).collectLatest { locator ->
                val enhancedLocator = enhanceLocatorWithProgress(locator)
                val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return@collectLatest
                val key = viewModel.currentBookKey.value ?: return@collectLatest

                syncRepo.cacheProgress(key, json)
                viewModel.saveProgress(json)
                requestEinkRefresh()
            }
        }
    }

    // 增強 Locator 以確保包含正確的進度信息
    private fun enhanceLocatorWithProgress(locator: Locator): Locator {
        val currentLocations =
                locator.locations
                        ?: run {
                            return locator.copy(
                                    locations =
                                            Locator.Locations(
                                                    totalProgression = 0.0,
                                                    progression = 0.0
                                            )
                            )
                        }

        // 如果已經有有效的 totalProgression，直接返回
        val totalProgression = currentLocations?.totalProgression
        if (totalProgression != null && totalProgression >= 0 && totalProgression <= 1.0) {
            return locator
        }

        // 如果沒有 totalProgression 但有 progression，將 progression 作為 totalProgression 的估算
        val progression = currentLocations?.progression
        if (progression != null && progression >= 0 && progression <= 1.0) {
            val enhancedLocations = currentLocations.copy(totalProgression = progression)
            return locator.copy(locations = enhancedLocations)
        }

        // 如果都沒有，保持原樣
        return locator
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // 當系統配置（如字體大小）改變時，重新應用設定
        if (EInkHelper.isBooxDevice()) {
            applyAllSettingsWithRetry()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && EInkHelper.isBooxDevice()) {
            val currentLoc = nativeNavigatorFragment?.currentLocator?.value
            val progression = currentLoc?.locations?.progression ?: 0.0

            // 如果當前進度為0 (可能因系統Overlay導致Reader重置)，且我們確認這不是真正的新書狀態
            // 嘗試從DB恢復進度
            if (progression == 0.0) {
                lifecycleScope.launch {
                    val savedLocator = viewModel.getLastSavedLocator()
                    if (savedLocator != null && (savedLocator.locations?.progression ?: 0.0) > 0.0
                    ) {
                        // Small delay to ensure Reader is ready effectively
                        delay(50)
                        nativeNavigatorFragment?.go(savedLocator, animated = false)
                    } else {}
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 關鍵：每次回到Reader時都強制重新讀取並應用文石系統字體設定
        if (EInkHelper.isBooxDevice()) {

            // 1. Capture current location before any layout changes
            val savedLocator = nativeNavigatorFragment?.currentLocator?.value

            lifecycleScope.launch {
                delay(200) // 短暫延遲確保UI準備好

                // 每次都重新讀取文石系統字體設定
                val newFontSize = getBooxSystemFontSize()

                currentFontSize = newFontSize
                currentFontWeight = 400 // 固定預設值

                applyFontSize(currentFontSize)
                applyFontWeight(currentFontWeight)
                applyContrastMode(currentContrastMode)

                // 2. Restore location if valid
                if (savedLocator != null) {
                    // Small delay to let Reader apply settings first
                    delay(50)
                    nativeNavigatorFragment?.go(savedLocator, pageAnimationEnabled)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgressImmediate()
    }

    private fun saveCurrentProgressImmediate() {
        val key = viewModel.currentBookKey.value ?: return
        val locator = nativeNavigatorFragment?.currentLocator?.value
        if (locator == null) return

        // 確保進度信息正確
        val enhancedLocator = enhanceLocatorWithProgress(locator)
        val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return

        // Debug: 記錄保存的數據

        syncRepo.cacheProgress(key, json)

        viewModel.saveProgress(json)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BOOKMARK && resultCode == RESULT_OK && data != null) {
            val json = data.getStringExtra(BookmarkListActivity.EXTRA_LOCATOR_JSON)
            val locator = LocatorJsonHelper.fromJson(json)
            if (locator != null) {
                nativeNavigatorFragment?.go(locator, pageAnimationEnabled)
                requestEinkRefresh()
            }
        }
    }

    // --- UI Actions ---

    private fun openChapterPicker() {
        val publication = viewModel.publication.value
        val navigator = nativeNavigatorFragment
        if (publication == null || navigator == null) {
            Toast.makeText(this, "尚未載入書籍", Toast.LENGTH_SHORT).show()
            return
        }

        val chapters = collectChapters(publication)
        if (chapters.isEmpty()) {
            Toast.makeText(this, "此書沒有可用的目錄", Toast.LENGTH_SHORT).show()
            return
        }

        val labels =
                chapters
                        .map { item ->
                            val indent = "  ".repeat(item.depth.coerceAtLeast(0))
                            val title = item.title.ifBlank { item.link.href.toString() }
                            "$indent• $title"
                        }
                        .toTypedArray()

        AlertDialog.Builder(this)
                .setTitle("選擇章節")
                .setItems(labels) { _, which ->
                    val target = chapters.getOrNull(which) ?: return@setItems
                    val locator = locatorFromLink(target.link)
                    if (locator != null) {
                        nativeNavigatorFragment?.go(locator, pageAnimationEnabled)
                        requestEinkRefresh()
                    } else {
                        Toast.makeText(this, "無法開啟章節", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
    }

    private fun collectChapters(publication: Publication): List<ChapterItem> {
        val result = mutableListOf<ChapterItem>()

        fun walk(links: List<Link>, depth: Int) {
            links.forEach { link ->
                val href = link.href.toString()
                val title =
                        link.title?.takeIf { it.isNotBlank() }
                                ?: href.substringAfterLast('/').ifBlank { href }
                result += ChapterItem(title = title, link = link, depth = depth)
                if (link.children.isNotEmpty()) {
                    walk(link.children, depth + 1)
                }
            }
        }

        val toc = publication.tableOfContents
        if (toc.isNotEmpty()) {
            walk(toc, 0)
        } else {
            walk(publication.readingOrder, 0)
        }

        return result
    }

    private fun locatorFromLink(link: Link): Locator? {
        val href = Url(link.href.toString()) ?: return null
        return Locator(
                href = href,
                mediaType = MediaType.EPUB,
                title = link.title,
                locations = Locator.Locations(progression = 0.0)
        )
    }

    private fun publishCurrentSelection() {
        lifecycleScope.launch {
            val selection = nativeNavigatorFragment?.currentSelection()
            val text = selection?.locator?.text?.highlight
            val locatorJson = LocatorJsonHelper.toJson(selection?.locator)

            if (text.isNullOrBlank()) {
                Toast.makeText(this@ReaderActivity, "No selection", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                nativeNavigatorFragment?.clearSelection()
            } catch (_: Exception) {}

            val sanitized =
                    withContext(Dispatchers.Default) {
                        text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                                .replace(Regex("\\s+"), " ")
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
        val locator = nativeNavigatorFragment?.currentLocator?.value
        if (locator != null) {
            viewModel.addBookmark(locator)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reader_settings, null)
        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks =
                dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val switchPageTap =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageSwipe
                )
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        val cbLocalExport = dialogView.findViewById<CheckBox>(R.id.cbLocalExport)
        val btnTestExport = dialogView.findViewById<Button>(R.id.btnTestExportEndpoint)

        // Add Security Buttons and Boox-specific settings
        // dialogView is a ScrollView, so we need to get its child LinearLayout
        val layout =
                (dialogView as? android.view.ViewGroup)?.getChildAt(0) as?
                        android.widget.LinearLayout
        if (layout != null && EInkHelper.isBooxDevice()) {
            // Verify Hash Button
            val btnVerify =
                    Button(this).apply {
                        text = "Verify File Hash"
                        setOnClickListener { showFileInfo() }
                    }

            val booxTitle =
                    TextView(this).apply {
                        text =
                                if (EInkHelper.isModernBoox()) {
                                    "文石設備優化 (${EInkHelper.getBooxModel()})"
                                } else {
                                    "文石 E-Ink 設置"
                                }
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 16, 0, 8)
                    }

            val switchBatch =
                    androidx.appcompat.widget.SwitchCompat(this).apply {
                        text = "批量刷新 (減少閃爍)"
                        isChecked = booxBatchRefreshEnabled
                        setOnCheckedChangeListener { _, checked ->
                            booxBatchRefreshEnabled = checked
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("boox_batch_refresh", checked)
                                    .apply()
                        }
                    }

            val switchFast =
                    androidx.appcompat.widget.SwitchCompat(this).apply {
                        text = "交互時快速模式"
                        isChecked = booxFastModeEnabled
                        setOnCheckedChangeListener { _, checked ->
                            booxFastModeEnabled = checked
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("boox_fast_mode", checked)
                                    .apply()
                        }
                    }

            val btnFullRefresh =
                    Button(this).apply {
                        text = "立即全屏刷新"
                        setOnClickListener {
                            EInkHelper.refreshFull(binding.root)
                            Toast.makeText(this@ReaderActivity, "已執行全屏刷新", Toast.LENGTH_SHORT)
                                    .show()
                        }
                    }

            val btnSmartRefresh =
                    Button(this).apply {
                        text = "智能刷新測試"
                        setOnClickListener {
                            EInkHelper.smartRefresh(
                                    binding.root,
                                    hasTextChanges = false,
                                    hasImageChanges = false
                            )
                            Toast.makeText(this@ReaderActivity, "已執行智能刷新", Toast.LENGTH_SHORT)
                                    .show()
                        }
                    }

            // 新型號專用選項
            if (EInkHelper.isModernBoox()) {
                val btnOptimizeMode =
                        Button(this).apply {
                            text = "切換自動模式"
                            setOnClickListener {
                                EInkHelper.enableAutoMode(binding.root)
                                Toast.makeText(
                                                this@ReaderActivity,
                                                "已切換到自動刷新模式",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                layout.addView(btnOptimizeMode, layout.childCount - 2)
            }

            val btnDeviceInfo =
                    Button(this).apply {
                        text = "設備信息"
                        setOnClickListener {
                            val info =
                                    """
                        設備型號: ${EInkHelper.getBooxModel()}
                        是否新型號: ${if (EInkHelper.isModernBoox()) "是" else "否"}
                        支援高對比: ${if (EInkHelper.supportsHighContrast()) "是" else "否"}
                        當前主題: ${EInkHelper.getContrastModeName(currentContrastMode)}
                        當前刷新模式: ${EInkHelper.getCurrentRefreshMode(binding.root) ?: "未知"}
                        批量刷新: ${if (booxBatchRefreshEnabled) "開啟" else "關閉"}
                        快速模式: ${if (booxFastModeEnabled) "開啟" else "關閉"}
                    """.trimIndent()

                            AlertDialog.Builder(this@ReaderActivity)
                                    .setTitle("文石設備信息")
                                    .setMessage(info)
                                    .setPositiveButton("確定", null)
                                    .show()
                        }
                    }

            layout.addView(btnVerify, layout.childCount - 2)
            layout.addView(booxTitle, layout.childCount - 2)
            layout.addView(switchBatch, layout.childCount - 2)
            layout.addView(switchFast, layout.childCount - 2)
            layout.addView(btnFullRefresh, layout.childCount - 2)
            layout.addView(btnSmartRefresh, layout.childCount - 2)

            // Moved Theme settings to be available for all devices

            layout.addView(btnDeviceInfo, layout.childCount - 2)
        }

        val btnAiProfiles = Button(this).apply { text = "AI Profiles (Switch Model/API)" }

        layout?.addView(btnAiProfiles, 2)

        // --- Reading Theme ---
        val themeTitle =
                TextView(this).apply {
                    text = "Reading Theme / 閱讀主題"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
        layout?.addView(themeTitle, 3)

        // Theme Buttons Container (Horizontal)
        val themeContainer =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    weightSum = 3f
                    layoutParams =
                            android.widget.LinearLayout.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                }

        val btnNormal =
                Button(this).apply {
                    text = "Normal"
                    layoutParams =
                            android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f
                            )
                    setOnClickListener {
                        applyContrastMode(
                                my.hinoki.booxreader.core.eink.EInkHelper.ContrastMode.NORMAL
                        )
                        Toast.makeText(this@ReaderActivity, "Normal Mode", Toast.LENGTH_SHORT)
                                .show()
                    }
                }
        val btnDark =
                Button(this).apply {
                    text = "Dark"
                    layoutParams =
                            android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f
                            )
                    setOnClickListener {
                        applyContrastMode(
                                my.hinoki.booxreader.core.eink.EInkHelper.ContrastMode.DARK
                        )
                        Toast.makeText(this@ReaderActivity, "Dark Mode", Toast.LENGTH_SHORT).show()
                    }
                }
        val btnSepia =
                Button(this).apply {
                    text = "Sepia"
                    layoutParams =
                            android.widget.LinearLayout.LayoutParams(
                                    0,
                                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f
                            )
                    setOnClickListener {
                        applyContrastMode(EInkHelper.ContrastMode.SEPIA)
                        Toast.makeText(this@ReaderActivity, "Sepia Mode", Toast.LENGTH_SHORT).show()
                    }
                }

        themeContainer.addView(btnNormal)
        themeContainer.addView(btnDark)
        themeContainer.addView(btnSepia)
        layout?.addView(themeContainer, 4)

        // --- Language Selection ---
        val languageTitle =
                TextView(this).apply {
                    text = "Language / 語言"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
        layout?.addView(languageTitle, 2)

        val languageGroup =
                android.widget.RadioGroup(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

        val rbSystem = android.widget.RadioButton(this).apply { text = "System Default (跟隨系統)" }
        val rbEnglish = android.widget.RadioButton(this).apply { text = "English" }
        val rbChinese =
                android.widget.RadioButton(this).apply { text = "Traditional Chinese (繁體中文)" }

        languageGroup.addView(rbSystem)
        languageGroup.addView(rbEnglish)
        languageGroup.addView(rbChinese)
        layout?.addView(languageGroup, 3)

        // Load current Settings
        val readerSettings =
                ReaderSettings.fromPrefs(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))

        when (readerSettings.language) {
            "zh" -> rbChinese.isChecked = true
            "en" -> rbEnglish.isChecked = true
            else -> rbSystem.isChecked = true
        }
        val prefs =
                getSharedPreferences(
                        PREFS_NAME,
                        MODE_PRIVATE
                ) // keep raw prefs for specific edits if needed or just use saveTo

        val switchPageAnimation =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageAnimation
                )

        etServerUrl.setText(readerSettings.serverBaseUrl)
        etApiKey.setText(readerSettings.apiKey)
        switchPageTap.isChecked = readerSettings.pageTapEnabled
        switchPageSwipe.isChecked = readerSettings.pageSwipeEnabled
        switchPageAnimation.isChecked = readerSettings.pageAnimationEnabled
        cbCustomExport.isChecked = readerSettings.exportToCustomUrl
        etCustomExportUrl.setText(readerSettings.exportCustomUrl)
        etCustomExportUrl.isEnabled = readerSettings.exportToCustomUrl
        cbLocalExport.isChecked = readerSettings.exportToLocalDownloads

        val seekBarTextSize = dialogView.findViewById<android.widget.SeekBar>(R.id.seekBarTextSize)
        val tvTextSizeValue = dialogView.findViewById<android.widget.TextView>(R.id.tvTextSizeValue)

        seekBarTextSize.progress = readerSettings.textSize - 50
        tvTextSizeValue.text = "${readerSettings.textSize}%"

        seekBarTextSize.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                            seekBar: android.widget.SeekBar?,
                            progress: Int,
                            fromUser: Boolean
                    ) {
                        val size = progress + 50
                        tvTextSizeValue.text = "$size%"
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                }
        )

        cbCustomExport.setOnCheckedChangeListener { _, isChecked ->
            etCustomExportUrl.isEnabled = isChecked
        }
        btnTestExport.setOnClickListener {
            val app = application as my.hinoki.booxreader.BooxReaderApp
            val repo = AiNoteRepository(app, app.okHttpClient, syncRepo)
            val baseUrl =
                    etServerUrl.text.toString().trim().ifEmpty { readerSettings.serverBaseUrl }
            val targetUrl =
                    if (cbCustomExport.isChecked &&
                                    etCustomExportUrl.text.toString().trim().isNotEmpty()
                    ) {
                        etCustomExportUrl.text.toString().trim()
                    } else {
                        val trimmed = baseUrl.trimEnd('/')
                        if (trimmed.isNotEmpty()) trimmed + HttpConfig.PATH_AI_NOTES_EXPORT else ""
                    }

            if (targetUrl.isEmpty()) {
                Toast.makeText(this, "請輸入有效的 URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestExport.isEnabled = false
            val originalText = btnTestExport.text
            btnTestExport.text = "Testing..."
            lifecycleScope.launch {
                val result = repo.testExportEndpoint(targetUrl)
                Toast.makeText(this@ReaderActivity, "Export test: $result", Toast.LENGTH_LONG)
                        .show()
                btnTestExport.text = originalText
                btnTestExport.isEnabled = true
            }
        }

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        val dialog =
                AlertDialog.Builder(this)
                        .setView(dialogView)
                        .setPositiveButton("Close", null)
                        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val newUrlRaw = etServerUrl.text.toString().trim()
                val newApiKey = etApiKey.text.toString().trim()
                val newPageTap = switchPageTap.isChecked
                val newPageSwipe = switchPageSwipe.isChecked
                val newPageAnimation = switchPageAnimation.isChecked
                val useCustomExport = cbCustomExport.isChecked
                val customExportUrlRaw = etCustomExportUrl.text.toString().trim()
                val exportToLocal = cbLocalExport.isChecked
                val newTextSize = seekBarTextSize.progress + 50

                val normalizedBaseUrl =
                        if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw)
                        else readerSettings.serverBaseUrl
                val normalizedCustomUrl =
                        if (useCustomExport && customExportUrlRaw.isNotEmpty())
                                normalizeUrl(customExportUrlRaw)
                        else ""

                if (newUrlRaw.isNotEmpty() && normalizedBaseUrl.toHttpUrlOrNull() == null) {
                    Toast.makeText(this, "Server URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (useCustomExport &&
                                customExportUrlRaw.isNotEmpty() &&
                                normalizedCustomUrl.toHttpUrlOrNull() == null
                ) {
                    Toast.makeText(this, "Custom export URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Update settings object
                val updatedSettings =
                        readerSettings.copy(
                                serverBaseUrl = normalizedBaseUrl,
                                apiKey = newApiKey,
                                pageTapEnabled = newPageTap,
                                pageSwipeEnabled = newPageSwipe,
                                pageAnimationEnabled = newPageAnimation,
                                exportToCustomUrl = useCustomExport,
                                exportCustomUrl = normalizedCustomUrl,
                                exportToLocalDownloads = exportToLocal,
                                textSize = newTextSize,
                                language =
                                        when {
                                            rbChinese.isChecked -> "zh"
                                            rbEnglish.isChecked -> "en"
                                            else -> "system"
                                        },
                                updatedAt = System.currentTimeMillis()
                        )

                updatedSettings.saveTo(prefs)

                // Restart if language changed
                if (updatedSettings.language != readerSettings.language) {
                    val intent =
                            Intent(
                                    applicationContext,
                                    my.hinoki.booxreader.data.ui.welcome.WelcomeActivity::class.java
                            )
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }
                pageAnimationEnabled = updatedSettings.pageAnimationEnabled
                pageSwipeEnabled = updatedSettings.pageSwipeEnabled

                applyFontSize(newTextSize)
                nativeNavigatorFragment?.setFontSize(newTextSize)

                if (normalizedBaseUrl != readerSettings.serverBaseUrl) {
                    Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
                }
                if (newApiKey != readerSettings.apiKey) {
                    Toast.makeText(this, "API Key updated", Toast.LENGTH_SHORT).show()
                }

                pushSettingsToCloud()
                dialog.dismiss()
            }
        }

        btnAiProfiles.setOnClickListener {
            dialog.dismiss()
            my.hinoki.booxreader.data.ui.settings.AiProfileListActivity.open(this@ReaderActivity)
        }

        /* Removed redundant specific listeners as we save all on close now for simplicity and consistency */

        btnSettingsAddBookmark.setOnClickListener { addBookmarkFromCurrentPosition() }
        btnSettingsShowBookmarks.setOnClickListener { openBookmarkList() }

        // Switch listeners update UI state but save happens on Close
        switchPageTap.setOnCheckedChangeListener { _, isChecked -> pageTapEnabled = isChecked }
        switchPageSwipe.setOnCheckedChangeListener { _, isChecked -> pageSwipeEnabled = isChecked }
        switchPageAnimation.setOnCheckedChangeListener { _, isChecked ->
            pageAnimationEnabled = isChecked
        }

        dialog.show()
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
                                .setMessage(
                                        "SHA-256 Checksum:\n\n$hash\n\nPlease verify this hash against a trusted source to ensure the file has not been tampered with."
                                )
                                .setPositiveButton("Copy") { _, _ ->
                                    val clipboard =
                                            getSystemService(Context.CLIPBOARD_SERVICE) as
                                                    android.content.ClipboardManager
                                    val clip =
                                            android.content.ClipData.newPlainText(
                                                    "SHA-256 Hash",
                                                    hash
                                            )
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(
                                                    this@ReaderActivity,
                                                    "Hash copied",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                                .setNegativeButton("Close", null)
                                .show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                                    this@ReaderActivity,
                                    "Failed to verify file: ${e.message}",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }
        }
    }

    private fun getBooxSystemFontSize(): Int {
        val config = resources.configuration
        return (config.fontScale * 100).toInt()
    }

    private fun tryReadBooxSystemCommand(): Int {
        return try {

            // 嘗試多個可能的系統命令
            val commands =
                    listOf(
                            "getprop persist.sys.font_scale",
                            "getprop ro.font.scale",
                            "cat /proc/onyx/display/font_scale",
                            "settings get system font_scale",
                            "settings get global font_scale"
                    )

            for (command in commands) {
                try {
                    val process = Runtime.getRuntime().exec(command)
                    val reader =
                            java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                    val output = reader.readText().trim()
                    reader.close()
                    process.waitFor()

                    if (output.isNotEmpty()) {
                        val value = output.toFloatOrNull()
                        if (value != null && value > 0) {
                            return (value * 150).toInt()
                        }
                    }
                } catch (e: Exception) {}
            }

            -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun applyReaderSettings(settings: ReaderSettings) {
        pageTapEnabled = settings.pageTapEnabled
        pageSwipeEnabled = settings.pageSwipeEnabled
        booxBatchRefreshEnabled = settings.booxBatchRefresh
        booxFastModeEnabled = settings.booxFastMode
        pageAnimationEnabled = settings.pageAnimationEnabled

        // 載入對比模式
        val contrastMode =
                EInkHelper.ContrastMode.values().getOrNull(settings.contrastMode)
                        ?: EInkHelper.ContrastMode.NORMAL
        currentContrastMode = contrastMode

        // 字體大小使用文石系統設定
        currentFontSize = getBooxSystemFontSize()
        // 字體粗細使用預設值
        currentFontWeight = 400

        // 應用設定（字體大小從系統讀取，字體粗細使用預設）
        applyFontSize(currentFontSize)
        applyFontWeight(currentFontWeight)
        applyContrastMode(currentContrastMode)
    }

    private fun pushSettingsToCloud() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lifecycleScope.launch { syncRepo.pushSettings(ReaderSettings.fromPrefs(prefs)) }
    }

    private fun applyFontSize(sizePercent: Int) {
        nativeNavigatorFragment?.setFontSize(sizePercent)
    }

    private fun applyFontWeight(weight: Int) {
        currentFontWeight = weight.coerceIn(300, 900)
        // 移除SharedPreferences保存，不再保存字體粗細設定

        nativeNavigatorFragment?.view?.post {
            // 字體粗細變更也需要觸發文石系統深度刷新
            if (EInkHelper.isBooxDevice()) {
                binding.root.postDelayed(
                        {
                            EInkHelper.enableFastMode(binding.root)
                            binding.root.postDelayed(
                                    {
                                        if (EInkHelper.isModernBoox()) {
                                            EInkHelper.enableAutoMode(binding.root)
                                        } else {
                                            EInkHelper.restoreQualityMode(binding.root)
                                        }
                                    },
                                    30
                            )
                        },
                        20
                )
            }
        }
    }

    private fun applyContrastMode(mode: ContrastMode) {
        currentContrastMode = mode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt("contrast_mode", mode.ordinal)
                .apply()

        val backgroundColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
                    ContrastMode.DARK -> Color.parseColor("#121212")
                    ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val textColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.BLACK
                    ContrastMode.DARK -> Color.LTGRAY
                    ContrastMode.SEPIA -> Color.parseColor("#5B4636")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }

        binding.root.setBackgroundColor(backgroundColor)
        binding.readerContainer.setBackgroundColor(backgroundColor)
        binding.bottomBar.setBackgroundColor(backgroundColor)
        nativeNavigatorFragment?.view?.setBackgroundColor(backgroundColor)
        window.decorView.setBackgroundColor(backgroundColor)
        applyReaderContainerBackground(binding.readerContainer, backgroundColor)

        val buttonTint = ColorStateList.valueOf(buttonColor)
        val buttons =
                listOf(
                        binding.btnAinote,
                        binding.btnAddBookmark,
                        binding.btnShowBookmarks,
                        binding.btnChapters,
                        binding.btnSettings
                )
        buttons.forEach { button ->
            button.backgroundTintList = buttonTint
            button.setTextColor(textColor)
        }

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = backgroundColor
            window.navigationBarColor = backgroundColor
        }
        Log.d(
                "ReaderActivity",
                "Applying theme: $mode, textColor: ${Integer.toHexString(textColor)}, buttonColor: ${Integer.toHexString(buttonColor)}"
        )
        nativeNavigatorFragment?.setThemeColors(backgroundColor, textColor, buttonColor)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        val useLightIcons = mode == ContrastMode.NORMAL || mode == ContrastMode.SEPIA
        insetsController.isAppearanceLightStatusBars = useLightIcons
        insetsController.isAppearanceLightNavigationBars = useLightIcons
    }

    private fun applyReaderContainerBackground(view: View?, color: Int) {
        if (view == null) return
        view.setBackgroundColor(color)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyReaderContainerBackground(view.getChildAt(i), color)
            }
        }
    }

    // --- Selection Handling ---
    // Native reader handles selection internally.

    // --- Touch Handling ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                goPageBackward()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                goPageForward()
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

        // Dispatch to child views

        // 處理全局觸摸事件，優先文字選取
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshJob?.cancel()
                // 啟用快速模式以確保流暢的交互
                if (booxFastModeEnabled) {
                    EInkHelper.enableFastMode(window.decorView)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 觸摸結束時恢復高質量模式
                if (booxFastModeEnabled) {
                    EInkHelper.restoreQualityMode(window.decorView)
                }
            }
        }

        // 讓手勢檢測器和子 View 處理觸摸事件
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLocatorIntent(intent)
    }

    private fun handleLocatorIntent(intent: Intent?) {
        val locatorJson = intent?.getStringExtra(EXTRA_LOCATOR_JSON)
        if (!locatorJson.isNullOrBlank()) {
            val locator = LocatorJsonHelper.fromJson(locatorJson)
            if (locator != null) {
                nativeNavigatorFragment?.go(locator, animated = false)
            }
        }
    }
}
