package my.hinoki.booxreader.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import kotlin.OptIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.databinding.ActivityReaderBinding
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.ui.bookmarks.BookmarkListActivity
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.ui.notes.AiNoteDetailActivity
import my.hinoki.booxreader.ui.notes.AiNoteListActivity
import my.hinoki.booxreader.ui.reader.nativev2.NativeNavigatorFragment
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
    private var pendingLocatorFromIntent: Locator? = null
    private var searchJob: Job? = null

    // Activity local state for UI interaction
    private var pageTapEnabled: Boolean = true
    private var pageSwipeEnabled: Boolean = true
    private var touchSlop: Int = 0
    private var currentFontSize: Int = 150
    private var currentFontWeight: Int = 400
    private var pageAnimationEnabled: Boolean = false
    private var showPageIndicator: Boolean = true
    private var currentContrastMode: ContrastMode = ContrastMode.NORMAL
    private var refreshJob: Job? = null
    private val refreshDelayMs = 250L
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
    private val settingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode != RESULT_OK) return@registerForActivityResult

                val action = result.data?.getStringExtra(ReaderSettingsActivity.EXTRA_ACTION)
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val settings = ReaderSettings.fromPrefs(prefs)
                applyReaderSettings(settings)
                nativeNavigatorFragment?.setChineseConversionEnabled(
                        settings.convertToTraditionalChinese
                )
                requestEinkRefresh()

                when (action) {
                    ReaderSettingsActivity.ACTION_ADD_BOOKMARK -> addBookmarkFromCurrentPosition()
                    ReaderSettingsActivity.ACTION_SHOW_BOOKMARKS -> openBookmarkList()
                }
            }

    private val gestureDetector by lazy {
        GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Native Reader Selection Handling - if selector has selection or menu is
                        // visible, clear it
                        if (nativeNavigatorFragment?.hasSelection() == true ||
                                        nativeNavigatorFragment?.isSelectionMenuVisible() == true
                        ) {
                            nativeNavigatorFragment?.clearSelection()
                            nativeNavigatorFragment?.hideSelectionMenu()
                            return true
                        }

                        // Page navigation
                        if (pageTapEnabled) {
                            val width = binding.root.width
                            val x = e.x
                            if (width > 0) {
                                if (x < width * 0.3f) {
                                    goPageBackward()
                                    binding.root.postDelayed(
                                            { binding.root.postInvalidateOnAnimation() },
                                            pageNavigationRefreshDelayMs
                                    )
                                    return true
                                } else if (x > width * 0.7f) {
                                    goPageForward()
                                    binding.root.postDelayed(
                                            { binding.root.postInvalidateOnAnimation() },
                                            pageNavigationRefreshDelayMs
                                    )
                                    return true
                                }
                            }
                        }
                        return false
                    }

                    override fun onFling(
                            e1: MotionEvent?,
                            e2: MotionEvent,
                            velocityX: Float,
                            velocityY: Float
                    ): Boolean {
                        if (!pageSwipeEnabled) return false

                        // Only handle horizontal swipes
                        if (kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY)) {
                            val minVelocity = 100f // Minimum velocity for a swipe
                            if (kotlin.math.abs(velocityX) > minVelocity) {
                                if (velocityX > 0) {
                                    // Swipe right -> go backward
                                    goPageBackward()
                                } else {
                                    // Swipe left -> go forward
                                    goPageForward()
                                }
                                binding.root.postDelayed(
                                        { binding.root.postInvalidateOnAnimation() },
                                        pageNavigationRefreshDelayMs
                                )
                                return true
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
        currentFontSize = prefs.getInt("text_size", 140)
        pageTapEnabled = prefs.getBoolean("page_tap_enabled", true)
        pageSwipeEnabled = prefs.getBoolean("page_swipe_enabled", true)
        pageAnimationEnabled = prefs.getBoolean("page_animation_enabled", false)
        showPageIndicator = prefs.getBoolean("show_page_indicator", true)

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
                applyContrastMode(currentContrastMode)
                nativeNavigatorFragment?.setPageIndicatorVisible(showPageIndicator)
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

        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(
                    ReaderSettingsActivity.newIntent(this, viewModel.currentBookKey.value)
            )
        }
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

        pendingLocatorFromIntent?.let { locator ->
            nativeFrag.go(locator)
            pendingLocatorFromIntent = null
        }

        // Standard E-Ink refresh request only
        binding.root.post { requestEinkRefresh() }
    }

    private fun requestEinkRefresh(full: Boolean = false, immediate: Boolean = false) {
        refreshJob?.cancel()

        if (immediate) {
            binding.root.postInvalidateOnAnimation()
            return
        }

        refreshJob =
                lifecycleScope.launch {
                    delay(refreshDelayMs)
                    binding.root.postInvalidateOnAnimation()
                }
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
        applyFontSize(currentFontSize)
        applyContrastMode(currentContrastMode)
    }

    override fun onResume() {
        super.onResume()
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
                nativeNavigatorFragment?.go(locator)
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
                        nativeNavigatorFragment?.go(locator)
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

        fun isFootnoteLike(link: Link): Boolean {
            val href = link.href.toString()
            if (!href.contains("#")) return false
            val title = link.title?.trim().orEmpty()
            if (title.isEmpty()) return true
            return title.all { it.isDigit() }
        }

        fun walk(links: List<Link>, depth: Int) {
            links.forEach { link ->
                if (isFootnoteLike(link)) return@forEach
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
        val btnManageMagicTags = dialogView.findViewById<Button>(R.id.btnManageMagicTags)

        // dialogView is a ScrollView, so we need to get its child LinearLayout
        val layout =
                (dialogView as? android.view.ViewGroup)?.getChildAt(0) as?
                        android.widget.LinearLayout

        val btnAiProfiles = Button(this).apply { text = "AI Profiles (Switch Model/API)" }

        layout?.addView(btnAiProfiles, 2)

        btnManageMagicTags.setOnClickListener { showMagicTagManager() }

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
                        applyContrastMode(ContrastMode.NORMAL)
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
                        applyContrastMode(ContrastMode.DARK)
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
                        applyContrastMode(ContrastMode.SEPIA)
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
        val switchPageIndicator =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchPageIndicator
                )

        val switchConvertChinese =
                dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(
                        R.id.switchConvertChinese
                )

        etServerUrl.setText(readerSettings.serverBaseUrl)
        etApiKey.setText(readerSettings.apiKey)
        switchPageTap.isChecked = readerSettings.pageTapEnabled
        switchPageSwipe.isChecked = readerSettings.pageSwipeEnabled
        switchPageAnimation.isChecked = readerSettings.pageAnimationEnabled
        switchPageIndicator.isChecked = readerSettings.showPageIndicator
        switchConvertChinese.isChecked = readerSettings.convertToTraditionalChinese
        cbCustomExport.isChecked = readerSettings.exportToCustomUrl
        etCustomExportUrl.setText(readerSettings.exportCustomUrl)
        etCustomExportUrl.isEnabled = readerSettings.exportToCustomUrl
        cbLocalExport.isChecked = readerSettings.exportToLocalDownloads

        switchConvertChinese.setOnCheckedChangeListener { _, isChecked ->
            val currentSettings = ReaderSettings.fromPrefs(prefs)
            val updatedSettings =
                    currentSettings.copy(
                            convertToTraditionalChinese = isChecked,
                            updatedAt = System.currentTimeMillis()
                    )
            updatedSettings.saveTo(prefs)
            nativeNavigatorFragment?.setChineseConversionEnabled(isChecked)

            val message = if (isChecked) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (isChecked) {
                val probe =
                        my.hinoki.booxreader.data.util.ChineseConverter.toTraditional("简体中文")
                                .toString()
                if (probe == "简体中文") {
                    Toast.makeText(this, "簡體轉繁體字庫載入失敗，可能無法轉換", Toast.LENGTH_LONG).show()
                }
            }
        }

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

        val contrastMode =
                ContrastMode.values().getOrNull(readerSettings.contrastMode) ?: ContrastMode.NORMAL
        applySettingsDialogTheme(dialogView, contrastMode)

        val dialog =
                AlertDialog.Builder(this)
                        .setView(dialogView)
                        .setPositiveButton("Close", null)
                        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val currentSettings = ReaderSettings.fromPrefs(prefs)
                val newUrlRaw = etServerUrl.text.toString().trim()
                val newApiKey = etApiKey.text.toString().trim()
                val newPageTap = switchPageTap.isChecked
                val newPageSwipe = switchPageSwipe.isChecked
                val newPageAnimation = switchPageAnimation.isChecked
                val newShowPageIndicator = switchPageIndicator.isChecked
                val newConvertChinese = switchConvertChinese.isChecked
                val useCustomExport = cbCustomExport.isChecked
                val customExportUrlRaw = etCustomExportUrl.text.toString().trim()
                val exportToLocal = cbLocalExport.isChecked
                val newTextSize = seekBarTextSize.progress + 50

                val normalizedBaseUrl =
                        if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw)
                        else currentSettings.serverBaseUrl
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

                // Check if Chinese conversion setting changed
                val conversionChanged =
                        newConvertChinese != currentSettings.convertToTraditionalChinese

                // Update settings object
                val updatedSettings =
                        currentSettings.copy(
                                serverBaseUrl = normalizedBaseUrl,
                                apiKey = newApiKey,
                                pageTapEnabled = newPageTap,
                                pageSwipeEnabled = newPageSwipe,
                                pageAnimationEnabled = newPageAnimation,
                                showPageIndicator = newShowPageIndicator,
                                convertToTraditionalChinese = newConvertChinese,
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
                if (updatedSettings.language != currentSettings.language) {
                    val intent =
                            Intent(
                                    applicationContext,
                                    my.hinoki.booxreader.ui.welcome.WelcomeActivity::class.java
                            )
                    intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }
                pageAnimationEnabled = updatedSettings.pageAnimationEnabled
                pageSwipeEnabled = updatedSettings.pageSwipeEnabled
                showPageIndicator = updatedSettings.showPageIndicator
                nativeNavigatorFragment?.setPageIndicatorVisible(showPageIndicator)

                applyFontSize(newTextSize)
                nativeNavigatorFragment?.setFontSize(newTextSize)

                // Reload content if Chinese conversion setting changed
                if (conversionChanged) {
                    nativeNavigatorFragment?.setChineseConversionEnabled(newConvertChinese)
                    val message = if (newConvertChinese) "已啟用簡體轉繁體" else "已停用簡體轉繁體"
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }

                if (normalizedBaseUrl != currentSettings.serverBaseUrl) {
                    Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
                }
                if (newApiKey != currentSettings.apiKey) {
                    Toast.makeText(this, "API Key updated", Toast.LENGTH_SHORT).show()
                }

                pushSettingsToCloud()
                dialog.dismiss()
            }
        }

        btnAiProfiles.setOnClickListener {
            dialog.dismiss()
            my.hinoki.booxreader.ui.settings.AiProfileListActivity.open(this@ReaderActivity)
        }

        /* Removed redundant specific listeners as we save all on close now for simplicity and consistency */

        btnSettingsAddBookmark.setOnClickListener { addBookmarkFromCurrentPosition() }
        btnSettingsShowBookmarks.setOnClickListener { openBookmarkList() }

        // Switch listeners update UI state but save happens on Close
        switchPageTap.setOnCheckedChangeListener { _, isChecked -> pageTapEnabled = isChecked }
        switchPageSwipe.setOnCheckedChangeListener { _, isChecked -> pageSwipeEnabled = isChecked }
        switchPageAnimation.setOnCheckedChangeListener { _, isChecked ->
            pageAnimationEnabled = isChecked
            nativeNavigatorFragment?.setPageAnimationEnabled(isChecked)
        }
        switchPageIndicator.setOnCheckedChangeListener { _, isChecked ->
            showPageIndicator = isChecked
            nativeNavigatorFragment?.setPageIndicatorVisible(isChecked)
        }

        dialog.show()
    }

    private fun applySettingsDialogTheme(root: View, mode: ContrastMode) {
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
                    ContrastMode.DARK -> Color.parseColor("#F2F5FA")
                    ContrastMode.SEPIA -> Color.parseColor("#5B4636")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }
        val hintColor =
                ColorUtils.setAlphaComponent(
                        textColor,
                        if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 190
                        else 140
                )
        val dividerColor =
                ColorUtils.setAlphaComponent(
                        textColor,
                        if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 90
                        else 60
                )
        val buttonColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#E0E0E0")
                    ContrastMode.DARK -> Color.parseColor("#2B323C")
                    ContrastMode.SEPIA -> Color.parseColor("#D9C5A3")
                    ContrastMode.HIGH_CONTRAST -> Color.DKGRAY
                }

        root.setBackgroundColor(backgroundColor)

        fun applyToView(view: View) {
            if (view is ViewGroup && view.background != null) {
                view.setBackgroundColor(backgroundColor)
            }
            when (view) {
                is EditText -> {
                    view.setTextColor(textColor)
                    view.setHintTextColor(hintColor)
                }
                is Button -> {
                    view.setTextColor(textColor)
                    view.backgroundTintList = ColorStateList.valueOf(buttonColor)
                }
                is android.widget.CompoundButton -> {
                    view.setTextColor(textColor)
                }
                is TextView -> {
                    view.setTextColor(textColor)
                }
                is SeekBar -> {
                    view.progressTintList = ColorStateList.valueOf(textColor)
                    view.thumbTintList = ColorStateList.valueOf(textColor)
                }
                else -> {
                    val height = view.layoutParams?.height ?: 0
                    if (height in 1..2) {
                        view.setBackgroundColor(dividerColor)
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applyToView(view.getChildAt(i))
                }
            }
        }

        applyToView(root)
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

    private data class MagicTagRow(
            val id: String?,
            val titleInput: EditText,
            val contentInput: EditText,
            val container: android.view.View
    )

    private fun showMagicTagManager() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val settings = ReaderSettings.fromPrefs(prefs)

        val rows = mutableListOf<MagicTagRow>()
        val contentLayout =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 16)
                }

        val btnAdd =
                Button(this).apply { text = getString(R.string.action_manage_magic_tags) + " +" }
        contentLayout.addView(btnAdd)

        val scrollView = android.widget.ScrollView(this).apply { addView(contentLayout) }

        fun addRow(tag: MagicTag?) {
            val rowContainer =
                    android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(0, 16, 0, 16)
                    }

            val titleInput =
                    EditText(this).apply {
                        hint = "Tag Name"
                        setText(tag?.label.orEmpty())
                    }
            val contentInput =
                    EditText(this).apply {
                        hint = "Tag Content"
                        setText(tag?.content?.ifBlank { tag.label }.orEmpty())
                        minLines = 2
                    }
            val btnDelete = Button(this).apply { text = "Delete" }

            rowContainer.addView(titleInput)
            rowContainer.addView(contentInput)
            rowContainer.addView(btnDelete)

            val row = MagicTagRow(tag?.id, titleInput, contentInput, rowContainer)
            rows.add(row)
            contentLayout.addView(rowContainer)

            btnDelete.setOnClickListener {
                rows.remove(row)
                contentLayout.removeView(rowContainer)
            }
        }

        settings.magicTags.forEach { addRow(it) }

        btnAdd.setOnClickListener { addRow(null) }

        val dialog =
                androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.action_manage_magic_tags))
                        .setView(scrollView)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    ?.setOnClickListener {
                        Log.d("MagicTags", "Save clicked; rows=${rows.size}")
                        val seen = mutableSetOf<String>()
                        val updatedTags =
                                rows.mapIndexedNotNull { index, row ->
                                    val title = row.titleInput.text.toString().trim()
                                    val content =
                                            row.contentInput.text.toString().trim().ifBlank {
                                                title
                                            }
                                    if (title.isBlank()) {
                                        if (content.isNotBlank()) {
                                            Log.w(
                                                    "MagicTags",
                                                    "Validation failed: empty name with content"
                                            )
                                            Toast.makeText(
                                                            this,
                                                            getString(
                                                                    R.string
                                                                            .magic_tag_invalid_empty_name
                                                            ),
                                                            Toast.LENGTH_SHORT
                                                    )
                                                    .show()
                                            return@setOnClickListener
                                        }
                                        return@mapIndexedNotNull null
                                    }
                                    if (!seen.add(title)) {
                                        Log.w(
                                                "MagicTags",
                                                "Validation failed: duplicate name=$title"
                                        )
                                        Toast.makeText(
                                                        this,
                                                        getString(
                                                                R.string.magic_tag_invalid_duplicate
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        return@setOnClickListener
                                    }
                                    val id = row.id ?: "custom-${System.currentTimeMillis()}-$index"
                                    MagicTag(
                                            id = id,
                                            label = title,
                                            content = content,
                                            description = content
                                    )
                                }
                        val updatedSettings =
                                settings.copy(
                                        magicTags = updatedTags,
                                        updatedAt = System.currentTimeMillis()
                                )
                        Log.d("MagicTags", "Persisting tags count=${updatedTags.size}")
                        updatedSettings.saveTo(prefs)
                        val magicTagsJson = Gson().toJson(updatedTags)
                        prefs.edit()
                                .putString("magic_tags", magicTagsJson)
                                .putLong("settings_updated_at", updatedSettings.updatedAt)
                                .apply()
                        val persisted = ReaderSettings.fromPrefs(prefs).magicTags
                        val persistedSignature =
                                persisted.joinToString("|") {
                                    "${it.id}:${it.label}:${it.content}:${it.role}"
                                }
                        val updatedSignature =
                                updatedTags.joinToString("|") {
                                    "${it.id}:${it.label}:${it.content}:${it.role}"
                                }
                        Log.d("MagicTags", "Persisted signature len=${persistedSignature.length}")
                        if (persistedSignature != updatedSignature) {
                            Log.e("MagicTags", "Persist failed: signature mismatch")
                            Toast.makeText(
                                            this,
                                            getString(R.string.magic_tag_save_failed),
                                            Toast.LENGTH_LONG
                                    )
                                    .show()
                            return@setOnClickListener
                        }
                        pushSettingsToCloud()
                        Toast.makeText(
                                        this,
                                        getString(R.string.action_manage_magic_tags) + " OK",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        dialog.dismiss()
                    }
        }

        dialog.show()
    }

    private fun applyReaderSettings(settings: ReaderSettings) {
        pageTapEnabled = settings.pageTapEnabled
        pageSwipeEnabled = settings.pageSwipeEnabled
        pageAnimationEnabled = settings.pageAnimationEnabled
        showPageIndicator = settings.showPageIndicator

        // Apply page animation setting to fragment
        nativeNavigatorFragment?.setPageAnimationEnabled(pageAnimationEnabled)
        nativeNavigatorFragment?.setPageIndicatorVisible(showPageIndicator)

        // 載入對比模式
        val contrastMode =
                ContrastMode.values().getOrNull(settings.contrastMode) ?: ContrastMode.NORMAL
        currentContrastMode = contrastMode

        currentFontSize = settings.textSize
        currentFontWeight = 400

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

        nativeNavigatorFragment?.view?.post { binding.root.postInvalidateOnAnimation() }
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
                    ContrastMode.DARK -> Color.parseColor("#F2F5FA")
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
            button.imageTintList = ColorStateList.valueOf(textColor)
        }

        @Suppress("DEPRECATION")
        run {
            window.decorView.setBackgroundColor(backgroundColor)
            window.statusBarColor = backgroundColor
            window.navigationBarColor = backgroundColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        Log.d(
                "ReaderActivity",
                "Applying theme: $mode, textColor: ${Integer.toHexString(textColor)}, buttonColor: ${Integer.toHexString(buttonColor)}"
        )
        nativeNavigatorFragment?.setThemeColors(backgroundColor, textColor, buttonColor)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        val useLightIcons = ColorUtils.calculateLuminance(backgroundColor) > 0.5
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
        val bottomBarRect = Rect()
        binding.bottomBar.getGlobalVisibleRect(bottomBarRect)
        if (bottomBarRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            return super.dispatchTouchEvent(ev)
        }

        // Check if touch is within selection menu bounds - if so, don't process for page navigation
        if (nativeNavigatorFragment?.isPointInSelectionMenu(ev.rawX, ev.rawY) == true) {
            // Let the menu handle the touch, but don't process for gestures
            return super.dispatchTouchEvent(ev)
        }

        // Dispatch to child views

        // 處理全局觸摸事件，優先文字選取
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshJob?.cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {}
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
                val navigator = nativeNavigatorFragment
                if (navigator != null) {
                    navigator.go(locator)
                } else {
                    pendingLocatorFromIntent = locator
                }
            }
        }
    }
}
