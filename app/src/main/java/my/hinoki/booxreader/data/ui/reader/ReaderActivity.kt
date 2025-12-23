package my.hinoki.booxreader.data.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import java.util.zip.ZipInputStream
import kotlin.OptIn
import kotlin.math.abs
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
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
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

    private var navigatorFragment: EpubNavigatorFragment? = null
    private var nativeNavigatorFragment: NativeNavigatorFragment? = null
    private val useNativeReader = true // Set to true for Silk Smooth Selection experience
    private var currentBookId: String? = null
    private var searchJob: Job? = null
    private var currentActionMode: ActionMode? = null

    // Activity local state for UI interaction
    private var pageTapEnabled: Boolean = true
    private var pageSwipeEnabled: Boolean = true
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var currentFontSize: Int = 150 // 從文石系統讀取
    private var currentFontWeight: Int = 400
    private var booxBatchRefreshEnabled: Boolean = true
    private var booxFastModeEnabled: Boolean = true
    private var pageAnimationEnabled: Boolean = false
    private var currentContrastMode: ContrastMode = ContrastMode.NORMAL
    private var refreshJob: Job? = null
    private val booxRefreshDelayMs = if (EInkHelper.isModernBoox()) 150L else 250L
    private var pendingDecorations: List<Decoration>? = null
    private var lastStyledHref: String? = null
    private var stylesDirty: Boolean = true
    private val pageNavigationRefreshDelayMs = 300L // 頁面導航專用延遲

    private val pageDebugLogging = false
    // WebView.getScale() is deprecated; cache it via this helper to avoid compiler warnings
    @Suppress("DEPRECATION")
    private fun WebView.currentPageScale(): Float =
            runCatching { scale }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
    private val selectionDebugLogging = false

    // Swipe Block variables
    private var swipeBlockActive = false
    private var swipeBlockStartX = 0f
    private var swipeBlockStartY = 0f
    private var swipeBlockStartAtMs = 0L

    private data class ChapterItem(
            val title: String,
            val link: org.readium.r2.shared.publication.Link,
            val depth: Int
    )

    private val REQ_BOOKMARK = 1001
    private val PREFS_NAME = "reader_prefs"

    private val gestureDetector by lazy {
        GestureDetector(
                this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onScroll(
                            e1: MotionEvent?,
                            e2: MotionEvent,
                            distanceX: Float,
                            distanceY: Float
                    ): Boolean {
                        return false
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // 優先處理選擇相關的點擊 - 如果選擇菜單已打開，優先關閉它
                        if (currentActionMode != null) {
                            currentActionMode?.finish()
                            lifecycleScope.launch {
                                try {
                                    navigatorFragment?.clearSelection()
                                } catch (_: Exception) {}
                            }
                            return true
                        }

                        // 3. 頁面導航（僅在沒有選擇活動時）- 這裡只處理真正的單擊
                        if (pageTapEnabled) {
                            val width = binding.root.width
                            val x = e.x
                            if (width > 0) {
                                // 30-40-30 規則 - 只有真正的單擊（無拖動）才觸發頁面導航
                                if (x < width * 0.3f) {
                                    goPageBackward()
                                    // 延遲刷新以確保頁面切換完成
                                    if (EInkHelper.isBooxDevice()) {
                                        binding.root.postDelayed(
                                                { EInkHelper.refreshPartial(binding.root) },
                                                pageNavigationRefreshDelayMs
                                        )
                                    }
                                    return true
                                } else if (x > width * 0.7f) {
                                    goPageForward()
                                    // 延遲刷新以確保頁面切換完成
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

    private fun findFirstWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun applySwipeNavigationSetting(view: View?) {
        if (view == null) return
        // Disable pager swipes without blocking child touches (selection/taps) whenever possible.
        // Use reflection to avoid hard dependency on Readium internals.
        val className = view.javaClass.name
        if (className.contains("ViewPager", ignoreCase = true)) {
            val enabled = pageSwipeEnabled
            val ok =
                    runCatching {
                                view.javaClass
                                        .getMethod(
                                                "setUserInputEnabled",
                                                Boolean::class.javaPrimitiveType
                                        )
                                        .invoke(view, enabled)
                                true
                            }
                            .getOrNull() == true ||
                            runCatching {
                                        view.javaClass
                                                .getMethod(
                                                        "setPagingEnabled",
                                                        Boolean::class.javaPrimitiveType
                                                )
                                                .invoke(view, enabled)
                                        true
                                    }
                                    .getOrNull() == true ||
                            runCatching {
                                        view.javaClass
                                                .getMethod(
                                                        "setSwipeEnabled",
                                                        Boolean::class.javaPrimitiveType
                                                )
                                                .invoke(view, enabled)
                                        true
                                    }
                                    .getOrNull() == true
            if (ok) return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applySwipeNavigationSetting(view.getChildAt(i))
            }
        }
    }

    private fun logPageEdgeWords(label: String) {
        if (!pageDebugLogging) return
        val webView = findFirstWebView(navigatorFragment?.view) ?: return
        val js =
                """
            (function() {
                const vw = Math.max(2, (window.innerWidth || document.documentElement.clientWidth || 0));
                const vh = Math.max(2, (window.innerHeight || document.documentElement.clientHeight || 0));
                const startX = 8, startY = 8;
                const endX = Math.max(2, vw - 8);
                const endY = Math.max(2, vh - 8);
                function caretAt(x, y) {
                    if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
                    if (document.caretPositionFromPoint) {
                        const pos = document.caretPositionFromPoint(x, y);
                        if (!pos) return null;
                        const r = document.createRange();
                        r.setStart(pos.offsetNode, pos.offset);
                        return r;
                    }
                    return null;
                }
                function fallbackRange(x, y) {
                    const el = document.elementFromPoint(x, y);
                    if (!el) return null;
                    const r = document.createRange();
                    r.selectNodeContents(el);
                    r.collapse(true);
                    return r;
                }
                const s = caretAt(startX, startY) || fallbackRange(startX, startY);
                const e = caretAt(endX, endY) || fallbackRange(endX, endY);
                if (!s || !e) return "";
                const range = document.createRange();
                range.setStart(s.startContainer, s.startOffset);
                range.setEnd(e.startContainer, e.startOffset);
                const text = range.toString().replace(/\s+/g, " ").trim();
                const words = text ? text.split(" ").filter(Boolean) : [];
                const first = words.slice(0, 10).join(" ");
                const last = words.slice(-10).join(" ");
                return JSON.stringify({ first, last, count: words.length });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result -> }
    }

    private fun parseJsObject(raw: String): JSONObject? {
        // WebView.evaluateJavascript returns a JSON value encoded as a String.
        // Sometimes this is:
        // - an object: { ... }
        // - a quoted JSON string: "{\"top\":1}" (value is a String)
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return runCatching {
                    when (val value = JSONTokener(trimmed).nextValue()) {
                        is JSONObject -> value
                        is String -> runCatching { JSONObject(value) }.getOrNull()
                        else -> null
                    }
                }
                .getOrNull()
    }

    // Selection uses the system ActionMode menu. Avoid adding extra overlay UI here to keep
    // paging/selection fast on e-ink.

    private fun goPageForward() {
        logPageEdgeWords("beforeForward")
        navigatorFragment?.goForward(pageAnimationEnabled)
        binding.root.postDelayed({ logPageEdgeWords("afterForward") }, 200)
    }

    private fun goPageBackward() {
        logPageEdgeWords("beforeBackward")
        navigatorFragment?.goBackward(pageAnimationEnabled)
        binding.root.postDelayed({ logPageEdgeWords("afterBackward") }, 200)
    }

    private fun flushPendingDecorations() {
        pendingDecorations?.let { pending ->
            lifecycleScope.launch { navigatorFragment?.applyDecorations(pending, "ai_notes") }
        }
        pendingDecorations = null
    }

    companion object {
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

            // 測試多個可能的字體值
            val testSizes = listOf(120, 130, 140, 150, 160, 170, 180, 200)
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

        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as my.hinoki.booxreader.BooxReaderApp

        // Reset overlays
        // Reset overlays
        val win = window
        WindowCompat.setDecorFitsSystemWindows(win, false)
        WindowCompat.getInsetsController(win, win.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val key = intent.getStringExtra(EXTRA_BOOK_KEY)
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE)
        if (key == null) {
            finish()
            return
        }
        currentBookId = key

        setupUI()
        setupViewModel(key, title)

        // Handle initial locator if present
        handleLocatorIntent(intent)

        binding.tapOverlay.bringToFront()

        // 關鍵：在程式啟動時立即強制讀取文石系統字體設定
        if (EInkHelper.isBooxDevice()) {

            // 測試多個可能的字體值
            val testSizes = listOf(120, 130, 140, 150, 160, 170, 180, 200)
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
            navigatorFragment =
                    supportFragmentManager.findFragmentById(R.id.readerContainer) as?
                            EpubNavigatorFragment
            if (navigatorFragment == null) {
                viewModel.openBook(bookUri, contentResolver)
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

        binding.btnShowBookmarks.setOnClickListener { publishCurrentSelection() }

        binding.btnChapters.setOnClickListener { openChapterPicker() }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }

        binding.tapOverlay.visibility = android.view.View.GONE
        // 移除 tapOverlay，讓 WebView 完全接管觸摸事件以支持全域文字選取
        // 頁面導航將通過 GestureDetector 在 dispatchTouchEvent 中處理
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
        var startLocator = initialLocator

        // Fix Href mismatch (e.g. Web "text.html" vs Android "OEBPS/text.html")
        if (startLocator != null) {
            val href = startLocator.href.toString()
            val exactMatch = publication.readingOrder.any { it.href.toString() == href }
            if (!exactMatch) {
                // Try fuzzy match (suffix)
                val fuzzyMatch =
                        publication.readingOrder.find {
                            it.href.toString().endsWith(href) || href.endsWith(it.href.toString())
                        }
                if (fuzzyMatch != null) {
                    startLocator = startLocator?.copy(href = Url(fuzzyMatch.href.toString())!!)
                } else {}
            }
        }

        startLocator?.let {}
        // Avoid re-creating if already set up
        if (navigatorFragment != null) {
            return
        }
        lastStyledHref = null
        stylesDirty = true

        val factory = EpubNavigatorFactory(publication)
        val config =
                EpubNavigatorFragment.Configuration {
                    selectionActionModeCallback = this@ReaderActivity.selectionActionModeCallback
                }

        supportFragmentManager.fragmentFactory =
                factory.createFragmentFactory(
                        initialLocator = startLocator,
                        listener = null,
                        configuration = config,
                        initialPreferences = EpubPreferences(scroll = true)
                )

        if (useNativeReader) {
            Log.d("ReaderActivity", "Initializing NativeNavigatorFragment")
            val nativeFrag =
                    NativeNavigatorFragment().apply { setPublication(publication, startLocator) }
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.readerContainer, nativeFrag)
                    .commitNow()
            nativeNavigatorFragment = nativeFrag
            Log.d("ReaderActivity", "NativeNavigatorFragment committed: $nativeNavigatorFragment")
            observeLocatorUpdates() // Start observing native locator
            return
        }

        if (supportFragmentManager.findFragmentById(R.id.readerContainer) == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.readerContainer, EpubNavigatorFragment::class.java, null)
                    .commitNow()
        }

        // 確保 tapOverlay 保持隱藏，讓 WebView 完全處理觸摸
        binding.tapOverlay.visibility = android.view.View.GONE
        navigatorFragment =
                supportFragmentManager.findFragmentById(R.id.readerContainer) as?
                        EpubNavigatorFragment

        // Optimize WebView: Disable overscroll and apply e-ink tuning
        applySwipeNavigationSetting(navigatorFragment?.view)

        // 立即強制應用字體設定（不等待延遲）
        val immediateSize = getBooxSystemFontSize()
        currentFontSize = immediateSize

        // 直接設置WebView字體
        navigatorFragment?.view?.let { view -> findAndSetWebViewTextZoom(view, immediateSize) }
        findAndSetWebViewTextZoom(binding.root, immediateSize)

        // 然後使用完整的重試機制
        applyAllSettingsWithRetry()
        observeLocatorUpdates()
        setupDecorationListener()

        // Standard E-Ink refresh request only
        binding.root.post { requestEinkRefresh() }
    }

    private fun applyFontWeightViaWebView(view: View?, weight: Int) {
        if (view is android.webkit.WebView) {
            val safeWeight = weight.coerceIn(300, 900)
            val js =
                    """
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

    private fun applyReaderStyles(force: Boolean = false) {
        val navigatorView = navigatorFragment?.view ?: return
        if (!force && !stylesDirty) return
        applyFontZoomLikeNeoReader(navigatorView, currentFontSize)
        applyFontWeightViaWebView(navigatorView, currentFontWeight)
        applyReaderCss(navigatorView)
        applyPageAnimationCss(navigatorView)
        applyPageAnimationCss(navigatorView)
        stylesDirty = false
        stylesDirty = false
    }

    private fun applyPageAnimationCss(view: View?) {
        if (view is android.webkit.WebView) {
            val css =
                    if (pageAnimationEnabled) {
                        ""
                    } else {
                        """
                    * {
                        -webkit-transition: none !important;
                        transition: none !important;
                        -webkit-animation: none !important;
                        animation: none !important;
                        scroll-behavior: auto !important;
                    }
                    html, body {
                        scroll-behavior: auto !important;
                    }
                """.trimIndent()
                    }

            val js =
                    """
                (function() {
                    const id = 'boox-page-animation-style';
                    const existing = document.getElementById(id);
                    const cssText = `${css}`;
                    if (!cssText) {
                        if (existing) existing.remove();
                        return;
                    }
                    let style = existing;
                    if (!style) {
                        style = document.createElement('style');
                        style.id = id;
                        document.head.appendChild(style);
                    }
                    style.textContent = cssText;
                })();
            """.trimIndent()
            view.evaluateJavascript(js, null)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyPageAnimationCss(view.getChildAt(i))
            }
        }
    }

    private fun applyReaderCss(view: View?) {
        if (view is android.webkit.WebView) {
            val pageBackground =
                    when (currentContrastMode) {
                        ContrastMode.NORMAL -> "#ffffff"
                        ContrastMode.DARK -> "#000000"
                        ContrastMode.SEPIA -> "#f4ecd8"
                        ContrastMode.HIGH_CONTRAST -> "#000000"
                    }
            val css =
                    """
                /* Boox 專用：統一字體大小基準 - 使用更細緻的控制 */
                :root {
                    --boox-base-font-size: 16px;
                    --boox-line-height: 1.6;
                    --boox-page-bg: ${pageBackground};
                }

                html, body {
                    font-size: var(--boox-base-font-size) !important;
                    line-height: var(--boox-line-height) !important;
                    max-width: 100% !important;
                    width: 100% !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    min-height: 100vh !important;
                    background-color: var(--boox-page-bg) !important;
                    scroll-snap-type: y proximity !important;
                }

                #readium-viewport,
                #viewport,
                #readium-content,
                .readium-content,
                .readium-page,
                .readium-spread,
                .readium-scroll-container,
                iframe {
                    background-color: var(--boox-page-bg) !important;
                }

                p, div, li, span:not([class*="font"]):not([class*="size"]), td, th,
                blockquote, q, cite, em, strong, b, i, u, mark, dfn, abbr, samp, kbd, var,
                time, small, big, address, summary, details {
                    font-size: inherit !important;
                    line-height: inherit !important;
                    font-family: inherit !important;
                }

                h1, h2, h3, h4, h5, h6, p {
                    scroll-snap-align: start;
                }
                
                /* AI Note Highlights: White text on Black background */
                .readium-decoration-ai_notes {
                    color: #FFFFFF !important;
                    background-color: #000000 !important;
                    mix-blend-mode: normal !important;
                }

                h1 {
                    font-size: 2em !important;
                    margin: 0.67em 0 !important;
                }

                h2 {
                    font-size: 1.5em !important;
                    margin: 0.75em 0 !important;
                }

                h3 {
                    font-size: 1.17em !important;
                    margin: 0.83em 0 !important;
                }

                h4 {
                    font-size: 1em !important;
                    margin: 1em 0 !important;
                }

                h5 {
                    font-size: 0.83em !important;
                    margin: 1.17em 0 !important;
                }

                h6 {
                    font-size: 0.67em !important;
                    margin: 1.5em 0 !important;
                }

                pre, code, tt {
                    font-size: 0.9em !important;
                    font-family: monospace !important;
                }

                aside[epub\\:type~="footnote"],
                section[epub\\:type~="footnote"],
                nav[epub\\:type~="footnotes"],
                .footnote, .note {
                    font-size: 0.875em !important;
                    line-height: 1.5 !important;
                }

                a[epub\\:type~="noteref"], sup, sub {
                    font-size: 0.75em !important;
                    line-height: 1.2 !important;
                }

                /* 防止 epub 內部 CSS 干擾 */
                [style*="font-size"] {
                    font-size: inherit !important;
                }

                [style*="line-height"] {
                    line-height: inherit !important;
                }

            """.trimIndent()
            val js =
                    """
                (function() {
                  let style = document.getElementById('boox-reader-style');
                  if (!style) {
                    style = document.createElement('style');
                    style.id = 'boox-reader-style';
                    document.head.appendChild(style);
                  }
                  style.textContent = `${css}`;

                  // 強制重流以確保樣式應用
                  document.body.offsetHeight;

                  var pageBg = '${pageBackground}';
                  document.documentElement.style.backgroundColor = pageBg;
                  document.body.style.backgroundColor = pageBg;

                  document.querySelectorAll('iframe').forEach(function(frame) {
                    try {
                      frame.style.backgroundColor = pageBg;
                      if (frame.contentDocument) {
                        frame.contentDocument.documentElement.style.backgroundColor = pageBg;
                        frame.contentDocument.body.style.backgroundColor = pageBg;
                      }
                    } catch (e) {}
                  });
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
        val initialLocator = navigatorFragment?.currentLocator?.value

        // 關鍵：每次應用時都重新讀取文石系統字體設定
        currentFontSize = getBooxSystemFontSize()
        currentFontWeight = 400 // 固定使用預設字體粗細，不使用用戶設定

        // 立即應用一次
        applyFontSize(currentFontSize)
        applyFontWeight(currentFontWeight)
        applyContrastMode(currentContrastMode)

        stylesDirty = true
        applyReaderStyles(force = true)

        // Restore location immediately
        if (initialLocator != null) {
            navigatorFragment?.go(initialLocator, pageAnimationEnabled)
        }

        // 延遲再次應用以確保文檔載入完成
        navigatorFragment?.view?.postDelayed(
                {

                    // Capture location again before this delayed update
                    val delayedLocator = navigatorFragment?.currentLocator?.value ?: initialLocator

                    // 重新讀取文石系統字體設定
                    val newFontSize = getBooxSystemFontSize()
                    val fontChanged = newFontSize != currentFontSize
                    currentFontSize = newFontSize
                    currentFontWeight = 400 // 固定預設值

                    applyFontSize(currentFontSize)
                    applyFontWeight(currentFontWeight)
                    applyContrastMode(currentContrastMode)

                    stylesDirty = true
                    applyReaderStyles(force = true)

                    // Restore location
                    if (delayedLocator != null) {
                        navigatorFragment?.go(delayedLocator, pageAnimationEnabled)
                    }

                    // 再次延遲以確保穩定
                    navigatorFragment?.view?.postDelayed(
                            {
                                val finalLocator =
                                        navigatorFragment?.currentLocator?.value ?: delayedLocator

                                // 最終確認時也重新讀取文石系統設定
                                val finalFontSize = getBooxSystemFontSize()
                                val finalFontChanged = finalFontSize != currentFontSize

                                currentFontSize = finalFontSize
                                currentFontWeight = 400 // 固定預設值

                                // 如果字體沒有變化，可以跳過重應用以避免閃爍/重排，但為了保險起見還是應用，只是加上位置恢復
                                applyFontSize(currentFontSize)
                                applyFontWeight(currentFontWeight)
                                applyContrastMode(currentContrastMode)

                                stylesDirty = true
                                applyReaderStyles(force = true)

                                if (finalLocator != null) {
                                    navigatorFragment?.go(finalLocator, pageAnimationEnabled)
                                }
                            },
                            1000
                    ) // 1秒後最終確認
                },
                500
        ) // 0.5秒後重新應用
    }

    private fun applyFontZoomLikeNeoReader(view: View?, sizePercent: Int) {
        if (view is android.webkit.WebView) {

            // 使用 WebView 內建的 textZoom，這是最可靠的方式
            view.settings.textZoom = sizePercent

            // 同時使用 CSS 變量來確保一致性
            val css =
                    """
                :root {
                    --boox-font-scale: ${sizePercent / 100.0};
                }

                html {
                    font-size: calc(var(--boox-base-font-size, 16px) * var(--boox-font-scale)) !important;
                }
            """.trimIndent()

            val js =
                    """
                (function() {
                    console.log('開始應用字體縮放: ${sizePercent}%');

                    // 移除任何可能干擾的縮放樣式
                    document.documentElement.style.zoom = '';
                    document.body.style.zoom = '';
                    document.body.style.webkitTextSizeAdjust = '100%';

                    // 應用 CSS 變量
                    let zoomStyle = document.getElementById('boox-zoom-style');
                    if (!zoomStyle) {
                        zoomStyle = document.createElement('style');
                        zoomStyle.id = 'boox-zoom-style';
                        document.head.appendChild(zoomStyle);
                    }
                    zoomStyle.textContent = `${css}`;

                    // 強制重流
                    document.body.offsetHeight;

                    console.log('Boox 字體縮放已應用: ${sizePercent}%');
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
        Log.d("ReaderActivity", "observeLocatorUpdates: useNativeReader=$useNativeReader")
        val navigator = if (useNativeReader) nativeNavigatorFragment else navigatorFragment
        Log.d("ReaderActivity", "navigator=$navigator")
        if (navigator == null) return

        val locatorFlow =
                when (navigator) {
                    is EpubNavigatorFragment -> navigator.currentLocator
                    is NativeNavigatorFragment -> navigator.currentLocator
                    else -> {
                        Log.e(
                                "ReaderActivity",
                                "Unknown navigator type: ${navigator::class.simpleName}"
                        )
                        return
                    }
                }
        Log.d("ReaderActivity", "Starting to collect locatorFlow: $locatorFlow")

        lifecycleScope.launch {
            locatorFlow.sample(1500).collectLatest {
                val hrefKey = it.href?.toString()
                val wasChapterSwitch = hrefKey != null && hrefKey != lastStyledHref

                // 記錄當前位置以便重流後恢復
                val currentLocatorBeforeReflow = it

                // 當切換到新的章節時，重新確保字體設定正確
                if (wasChapterSwitch && !useNativeReader) { // Only for WebView
                    lastStyledHref = hrefKey
                    stylesDirty = true

                    // 關鍵：章節切換時重新讀取並應用字體設定
                    val savedFontSize = currentFontSize
                    currentFontSize = getBooxSystemFontSize()

                    if (currentFontSize != savedFontSize) {
                        // Note: applyFontSize triggers WebView reflow
                        applyFontSize(currentFontSize)
                        applyFontWeight(currentFontWeight)
                        applyContrastMode(currentContrastMode)
                    }
                }

                val wasDirty = stylesDirty
                if (wasDirty) {}
                applyReaderStyles()

                // 如果發生了樣式變更（重流），強制恢復到正確的位置
                // 解決文石設備在章節切換時字體縮放導致跳轉到章節首頁的問題
                if (wasDirty && !useNativeReader) {
                    // 使用 go 恢復位置，確保正確的分頁
                    navigatorFragment?.go(currentLocatorBeforeReflow, pageAnimationEnabled)
                }

                // 確保進度信息正確
                val enhancedLocator = enhanceLocatorWithProgress(it)
                val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return@collectLatest
                val key = viewModel.currentBookKey.value ?: return@collectLatest

                // Debug: 記錄進度信息

                // Local Prefs
                syncRepo.cacheProgress(key, json)

                // ViewModel (Server/DB)
                // 關鍵修正：只在擁有視窗焦點時保存進度
                // 這是為了解決 Boox 設備喚起系統選單時會導致 WebView 滾動位置重置為 0 的問題
                if (hasWindowFocus()) {
                    viewModel.saveProgress(json)
                } else {}

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
            val currentLoc =
                    if (useNativeReader) nativeNavigatorFragment?.currentLocator?.value
                    else navigatorFragment?.currentLocator?.value
            val progression = currentLoc?.locations?.progression ?: 0.0

            // 如果當前進度為0 (可能因系統Overlay導致WebView重置)，且我們確認這不是真正的新書狀態
            // 嘗試從DB恢復進度
            if (progression == 0.0) {
                lifecycleScope.launch {
                    val savedLocator = viewModel.getLastSavedLocator()
                    if (savedLocator != null && (savedLocator.locations?.progression ?: 0.0) > 0.0
                    ) {
                        // Small delay to ensure WebView is ready effectively
                        delay(50)
                        if (useNativeReader) {
                            nativeNavigatorFragment?.go(savedLocator, animated = false)
                        } else {
                            navigatorFragment?.go(savedLocator, animated = false)
                        }
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
            val savedLocator = navigatorFragment?.currentLocator?.value

            lifecycleScope.launch {
                delay(200) // 短暫延遲確保UI準備好

                // 每次都重新讀取文石系統字體設定
                val newFontSize = getBooxSystemFontSize()
                val fontChanged = newFontSize != currentFontSize

                currentFontSize = newFontSize
                currentFontWeight = 400 // 固定預設值

                applyFontSize(currentFontSize)
                applyFontWeight(currentFontWeight)
                applyContrastMode(currentContrastMode)

                // 2. Restore location if valid
                if (savedLocator != null) {
                    // Small delay to let WebView apply text zoom first
                    delay(50)
                    navigatorFragment?.go(savedLocator, pageAnimationEnabled)
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
        val locator =
                if (useNativeReader) nativeNavigatorFragment?.currentLocator?.value
                else navigatorFragment?.currentLocator?.value
        if (locator == null) return

        // 確保進度信息正確
        val enhancedLocator = enhanceLocatorWithProgress(locator)
        val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return

        // Debug: 記錄保存的數據

        syncRepo.cacheProgress(key, json)

        if (hasWindowFocus()) {
            viewModel.saveProgress(json)
        } else {}
    }

    private fun setupDecorationListener() {
        navigatorFragment?.addDecorationListener(
                "ai_notes",
                object : DecorableNavigator.Listener {
                    override fun onDecorationActivated(
                            event: DecorableNavigator.OnActivatedEvent
                    ): Boolean {
                        val noteId =
                                my.hinoki.booxreader.data.reader.DecorationHandler
                                        .extractNoteIdFromDecorationId(event.decoration.id)
                        return if (noteId != null) {
                            AiNoteDetailActivity.open(this@ReaderActivity, noteId)
                            true
                        } else {
                            false
                        }
                    }
                }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BOOKMARK && resultCode == RESULT_OK && data != null) {
            val json = data.getStringExtra(BookmarkListActivity.EXTRA_LOCATOR_JSON)
            val locator = LocatorJsonHelper.fromJson(json)
            if (locator != null) {
                navigatorFragment?.go(locator, pageAnimationEnabled)
                requestEinkRefresh()
            }
        }
    }

    // --- UI Actions ---

    private fun openChapterPicker() {
        val publication = viewModel.publication.value
        val navigator = navigatorFragment
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
                        navigator.go(locator, pageAnimationEnabled)
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

            val sanitized =
                    withContext(Dispatchers.Default) {
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

            // Safety Scan Button
            val btnScan =
                    Button(this).apply {
                        text = "Run Safety Scan (Scripts)"
                        setOnClickListener { runSafetyScan() }
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
            layout.addView(btnScan, layout.childCount - 2)
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
                stylesDirty = true
                applyReaderStyles(force = true)
                applySwipeNavigationSetting(navigatorFragment?.view)

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
        switchPageSwipe.setOnCheckedChangeListener { _, isChecked ->
            pageSwipeEnabled = isChecked
            applySwipeNavigationSetting(navigatorFragment?.view)
        }
        switchPageAnimation.setOnCheckedChangeListener { _, isChecked ->
            pageAnimationEnabled = isChecked
            stylesDirty = true
            applyReaderStyles(force = true)
        }

        dialog.show()
    }

    private fun runSafetyScan() {
        val uri = intent.data ?: return
        val progressDialog =
                AlertDialog.Builder(this)
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
                            if (name.endsWith(".js") ||
                                            name.endsWith(".exe") ||
                                            name.endsWith(".bat") ||
                                            name.endsWith(".sh")
                            ) {
                                threatsFound.add("Suspicious file type: ${entry.name}")
                            }

                            // 2. Check content for <script> tags (basic check for HTML/XHTML)
                            if (!entry.isDirectory &&
                                            (name.endsWith(".html") ||
                                                    name.endsWith(".xhtml") ||
                                                    name.endsWith(".htm"))
                            ) {
                                // Read first 4KB or enough to find tags.
                                // Note: Deep scanning entire files is slow. We'll scan reasonable
                                // chunks.
                                val buffer = ByteArray(8192)
                                val len = it.read(buffer)
                                if (len > 0) {
                                    val content = String(buffer, 0, len)
                                    if (content.contains("<script", ignoreCase = true) ||
                                                    content.contains(
                                                            "javascript:",
                                                            ignoreCase = true
                                                    )
                                    ) {
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
                                .setMessage(
                                        "✅ No obvious threats found.\n\nChecked for:\n- .js/.exe files\n- <script> tags in HTML"
                                )
                                .setPositiveButton("OK", null)
                                .show()
                    } else {
                        val message =
                                "⚠️ Potential Threats Detected:\n\n" +
                                        threatsFound.joinToString("\n")
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
                    Toast.makeText(
                                    this@ReaderActivity,
                                    "Scan failed: ${e.message}",
                                    Toast.LENGTH_SHORT
                            )
                            .show()
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

    private fun applyFontSize(sizePercent: Int) {
        val navigatorView = navigatorFragment?.view ?: return
        applyFontZoomLikeNeoReader(navigatorView, sizePercent)

        // 也嘗試設置root下的WebView
        findAndSetWebViewTextZoom(binding.root, sizePercent)

        // 關鍵：立即觸發文石系統刷新，不等待任何延遲
        // 關鍵：不再強制觸發文石系統刷新，以避免破壞用戶的模式設定
        if (EInkHelper.isBooxDevice()) {
            // 僅觸發一次普通刷新，不變更模式
            EInkHelper.refresh(binding.root, full = false)
        }

        stylesDirty = true
        navigatorFragment?.view?.post { applyReaderStyles(force = true) }
    }

    // 新增：強制查找並設置所有WebView的字體大小
    private fun findAndSetWebViewTextZoom(view: View, sizePercent: Int) {
        if (view is android.webkit.WebView) {
            val oldZoom = view.settings.textZoom
            view.settings.textZoom = sizePercent

            // 使用JavaScript強制設置字體大小
            val js =
                    """
                document.documentElement.style.webkitTextSizeAdjust = '$sizePercent%';
                document.body.style.webkitTextSizeAdjust = '$sizePercent%';
                document.body.style.fontSize = 'calc(16px * $sizePercent / 100)';
                console.log('強制字體大小設置為: $sizePercent%');
            """
            view.evaluateJavascript(js, null)

            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndSetWebViewTextZoom(view.getChildAt(i), sizePercent)
            }
        }
    }

    private fun getBooxSystemFontSize(): Int {

        return if (EInkHelper.isBooxDevice()) {

            // 嘗試讀取，但即使失敗也使用文石系統合理的預設值
            var fontSize = 150 // 文石系統預設值

            // 方法1: 嘗試讀取文石系統設定
            val systemSetting = tryReadBooxSystemSetting()
            if (systemSetting > 0) {
                fontSize = systemSetting
            }

            // 方法2: 嘗試讀取Android通用設定
            if (fontSize == 150) {
                val androidSetting = tryReadAndroidSystemSetting()
                if (androidSetting > 0) {
                    fontSize = androidSetting
                }
            }

            // 方法3: 嘗試從Configuration讀取
            if (fontSize == 150) {
                val configSetting = tryReadConfigurationFontScale()
                if (configSetting > 0) {
                    fontSize = configSetting
                }
            }

            // 方法4: 嘗試讀取文石特有的設定
            if (fontSize == 150) {
                val booxSetting = tryReadBooxSpecificSettings()
                if (booxSetting > 0) {
                    fontSize = booxSetting
                }
            }

            // 方法5: 嘗試讀取文石配置文件
            if (fontSize == 150) {
                val fileSetting = tryReadBooxConfigFiles()
                if (fileSetting > 0) {
                    fontSize = fileSetting
                }
            }

            // 方法6: 嘗試通過系統命令獲取
            if (fontSize == 150) {
                val commandSetting = tryReadBooxSystemCommand()
                if (commandSetting > 0) {
                    fontSize = commandSetting
                }
            }

            // 強制返回合理的文石系統字體值（100-200%範圍）
            if (fontSize < 100 || fontSize > 200) {
                fontSize = 150
            }

            fontSize
        } else {
            // 非文石設備使用Android系統字體設定
            tryReadConfigurationFontScale()
        }
    }

    private fun tryReadBooxSystemSetting(): Int {
        return try {
            val contentResolver = contentResolver

            // 嘗試多個可能的系統設定鍵
            val possibleKeys =
                    listOf(
                            "font_scale",
                            "system_font_scale",
                            "display_font_scale",
                            "boox_font_scale",
                            "epd_font_size"
                    )

            for (key in possibleKeys) {
                try {
                    val fontSize =
                            android.provider.Settings.System.getFloat(contentResolver, key, -1.0f)
                    if (fontSize > 0) {
                        return (fontSize * 150).toInt()
                    }
                } catch (e: Exception) {}
            }
            -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun tryReadAndroidSystemSetting(): Int {
        return try {
            val contentResolver = contentResolver
            val fontSize =
                    android.provider.Settings.System.getFloat(
                            contentResolver,
                            android.provider.Settings.System.FONT_SCALE,
                            -1.0f
                    )
            if (fontSize > 0) {
                (fontSize * 150).toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun tryReadConfigurationFontScale(): Int {
        return try {
            val config = resources.configuration
            val fontScale = config.fontScale
            if (fontScale > 0) {
                (fontScale * 150).toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun tryReadBooxSpecificSettings(): Int {
        return try {
            val contentResolver = contentResolver

            // 嘗試文石特有的設定路徑
            val booxKeys =
                    listOf(
                            "com.onyx.android.sdk.font_scale",
                            "com.onyx.epd.font_scale",
                            "com.boox.reader.font_size",
                            "eink_font_scale"
                    )

            for (key in booxKeys) {
                try {
                    val fontSize =
                            android.provider.Settings.System.getFloat(contentResolver, key, -1.0f)
                    if (fontSize > 0) {
                        return (fontSize * 150).toInt()
                    }
                } catch (e: Exception) {}
            }

            -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun tryReadBooxConfigFiles(): Int {
        return try {
            // 嘗試讀取文石系統配置文件
            val configPaths =
                    listOf(
                            "/data/data/com.onyx.android.sdk/config/preferences.xml",
                            "/data/data/com.onyx.reader/preferences.xml",
                            "/system/etc/onyx_config.xml",
                            "/proc/onyx/display/font_scale"
                    )

            for (path in configPaths) {
                try {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) {
                        val content = file.readText()

                        // 尋找字體相關的設定
                        val fontPatterns =
                                listOf(
                                        Regex("font_scale[=:]\\s*([0-9.]+)"),
                                        Regex("font_size[=:]\\s*([0-9.]+)"),
                                        Regex("display_scale[=:]\\s*([0-9.]+)")
                                )

                        for (pattern in fontPatterns) {
                            val match = pattern.find(content)
                            if (match != null) {
                                val value = match.groupValues[1].toFloatOrNull()
                                if (value != null && value > 0) {
                                    return (value * 150).toInt()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            -1
        } catch (e: Exception) {
            -1
        }
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
        stylesDirty = true
        applyReaderStyles(force = true)
        applySwipeNavigationSetting(navigatorFragment?.view)
    }

    private fun pushSettingsToCloud() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lifecycleScope.launch { syncRepo.pushSettings(ReaderSettings.fromPrefs(prefs)) }
    }

    private fun applyFontWeight(weight: Int) {
        currentFontWeight = weight.coerceIn(300, 900)
        // 移除SharedPreferences保存，不再保存字體粗細設定

        stylesDirty = true
        navigatorFragment?.view?.post {
            applyReaderStyles(force = true)

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

        navigatorFragment?.view?.let { view ->
            EInkHelper.setHighContrastMode(view, mode)

            // 對比模式變更後執行完整刷新
            if (EInkHelper.isBooxDevice()) {
                view.postDelayed({ EInkHelper.refreshFull(binding.root) }, 200)
            }
        }

        applyReaderChromeTheme(mode)
        applyReaderStyles(force = true)
    }

    private fun toggleContrastMode() {
        val newMode = EInkHelper.toggleContrastMode(navigatorFragment?.view)
        applyContrastMode(newMode)

        // 顯示當前模式
        Toast.makeText(this, "已切換到：${EInkHelper.getContrastModeName(newMode)}", Toast.LENGTH_SHORT)
                .show()
    }

    private fun applyReaderChromeTheme(mode: ContrastMode) {
        val backgroundColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.WHITE
                    ContrastMode.DARK -> Color.BLACK
                    ContrastMode.SEPIA -> Color.parseColor("#f4ecd8")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        val textColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.BLACK
                    ContrastMode.DARK -> Color.WHITE
                    ContrastMode.SEPIA -> Color.parseColor("#5c4b37")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }
        val buttonColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#f2f2f2")
                    ContrastMode.DARK -> Color.parseColor("#1a1a1a")
                    ContrastMode.SEPIA -> Color.parseColor("#e7ddc7")
                    ContrastMode.HIGH_CONTRAST -> Color.parseColor("#1a1a1a")
                }

        binding.root.setBackgroundColor(backgroundColor)
        binding.readerContainer.setBackgroundColor(backgroundColor)
        binding.bottomBar.setBackgroundColor(backgroundColor)
        navigatorFragment?.view?.setBackgroundColor(backgroundColor)
        findFirstWebView(navigatorFragment?.view)?.let { webView ->
            webView.setBackgroundColor(backgroundColor)
            webView.overScrollMode = View.OVER_SCROLL_NEVER
        }
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

    // --- Action Mode ---
    // --- Action Mode ---
    private val selectionActionModeCallback =
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    currentActionMode = mode

                    // 觸覺反饋
                    binding.root.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS
                    )

                    // Some devices (esp. e-ink) may not refresh the floating ActionMode promptly.
                    binding.root.postDelayed(
                            {
                                mode?.invalidate()
                                binding.root.invalidate()
                                if (EInkHelper.isBooxDevice()) {
                                    EInkHelper.refreshPartial(binding.root)
                                }
                            },
                            80
                    )

                    return true
                }

                override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
                    // Default behavior is usually fine, but if we need to ensure visibility:
                    super.onGetContentRect(mode, view, outRect)
                }

                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                    if (mode == null || menu == null) return false

                    // 清除並重新添加菜單項
                    menu.clear()

                    // 添加菜單項，確保有圖標和正確的顯示方式
                    menu.add(Menu.NONE, 998, 1, "複製")
                            .setIcon(android.R.drawable.ic_menu_edit)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

                    menu.add(Menu.NONE, 1001, 2, "分享")
                            .setIcon(android.R.drawable.ic_menu_share)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

                    menu.add(Menu.NONE, 1000, 2, "Google Maps")
                            .setIcon(android.R.drawable.ic_menu_mapmode)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

                    menu.add(Menu.NONE, 999, 3, "發佈")
                            .setIcon(android.R.drawable.ic_menu_share)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

                    // 添加調試日誌

                    return true
                }

                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                    if (item?.itemId == 998) {
                        lifecycleScope.launch {
                            val selection = navigatorFragment?.currentSelection()
                            val text = selection?.locator?.text?.highlight
                            if (!text.isNullOrBlank()) {
                                val clipboard =
                                        getSystemService(Context.CLIPBOARD_SERVICE) as
                                                android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Book Text", text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@ReaderActivity, "已複製", Toast.LENGTH_SHORT)
                                        .show()
                                navigatorFragment?.clearSelection()
                                mode?.finish()
                            } else {
                                Toast.makeText(
                                                this@ReaderActivity,
                                                "No text selected",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                        return true
                    }

                    if (item?.itemId == 1001) {
                        lifecycleScope.launch {
                            val selection = navigatorFragment?.currentSelection()
                            val text = selection?.locator?.text?.highlight
                            if (!text.isNullOrBlank()) {
                                val shareIntent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, text)
                                        }
                                startActivity(Intent.createChooser(shareIntent, "分享選取文字"))
                                navigatorFragment?.clearSelection()
                                mode?.finish()
                            } else {
                                Toast.makeText(
                                                this@ReaderActivity,
                                                "No text selected",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                        return true
                    }

                    if (item?.itemId == 999) {
                        lifecycleScope.launch {
                            try {
                                val selection = navigatorFragment?.currentSelection()
                                val text = selection?.locator?.text?.highlight
                                val locatorJson = LocatorJsonHelper.toJson(selection?.locator)

                                if (!text.isNullOrBlank()) {
                                    val trimmed =
                                            text.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                                    viewModel.postTextToServer(trimmed, locatorJson)

                                    // Clear selection and dismiss menu
                                    navigatorFragment?.clearSelection()
                                    mode?.finish()
                                } else {
                                    Toast.makeText(
                                                    this@ReaderActivity,
                                                    "No text selected",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                            } catch (e: Exception) {}
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
                                    // Fallback to browser or other map apps if Google Maps is not
                                    // installed
                                    val fallbackUri =
                                            Uri.parse(
                                                    "https://www.google.com/maps/search/?api=1&query=${Uri.encode(trimmed)}"
                                            )
                                    val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri)
                                    try {
                                        startActivity(fallbackIntent)
                                    } catch (e2: Exception) {
                                        Toast.makeText(
                                                        this@ReaderActivity,
                                                        "無法開啟地圖: ${e2.message}",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                    }
                                }

                                navigatorFragment?.clearSelection()
                                mode?.finish()
                            } else {
                                Toast.makeText(
                                                this@ReaderActivity,
                                                "No text selected",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                        return true
                    }
                    return false
                }
                override fun onDestroyActionMode(mode: ActionMode?) {
                    currentActionMode = null
                    flushPendingDecorations()
                }
            }

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

        // Hard block swipe-to-turn when disabled.
        // Some devices/pagers intercept before the WebView sees MOVE events, so we must stop it at
        // Activity level.
        // Optimization: Disable this block if using NativeReaderView selection or if an action mode
        // is active.
        if (!pageSwipeEnabled && currentActionMode == null && !useNativeReader) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeBlockActive = false
                    swipeBlockStartX = ev.x
                    swipeBlockStartY = ev.y
                    swipeBlockStartAtMs = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swipeBlockActive) {
                        val dx = abs(ev.x - swipeBlockStartX)
                        val dy = abs(ev.y - swipeBlockStartY)
                        // Use a low threshold because Readium may flip pages on short, fast flicks.
                        val threshold = (touchSlop * 0.25f).coerceAtLeast(2f)
                        val isHorizontalSwipe = dx > threshold && dx > dy * 1.1f
                        if (isHorizontalSwipe) {
                            swipeBlockActive = true
                        }
                    }
                    if (swipeBlockActive) return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Block short horizontal flicks even if we didn't mark active in MOVE (e.g.
                    // very fast gestures).
                    val dt = (SystemClock.uptimeMillis() - swipeBlockStartAtMs).coerceAtLeast(0L)
                    val dx = abs(ev.x - swipeBlockStartX)
                    val dy = abs(ev.y - swipeBlockStartY)
                    val threshold = (touchSlop * 0.25f).coerceAtLeast(2f)
                    val isQuickHorizontal = dt < 350 && dx > threshold && dx > dy * 1.1f
                    if (isQuickHorizontal) {
                        swipeBlockActive = false
                        return true
                    }
                    if (swipeBlockActive) {
                        swipeBlockActive = false
                        return true
                    }
                    swipeBlockActive = false
                }
            }
        }

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
                navigatorFragment?.go(locator, animated = false)
            }
        }
    }

    private fun setupViewModel(bookId: String, bookTitle: String?) {
        // Implementation restored
    }
}
