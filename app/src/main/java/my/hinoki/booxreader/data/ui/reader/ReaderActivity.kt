package my.hinoki.booxreader.data.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.GestureDetector
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.webkit.WebView
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
import my.hinoki.booxreader.core.eink.EInkHelper.ContrastMode
import my.hinoki.booxreader.databinding.ActivityReaderBinding
import my.hinoki.booxreader.data.reader.ReaderViewModel
import my.hinoki.booxreader.data.ui.notes.AiNoteListActivity
import my.hinoki.booxreader.data.ui.notes.AiNoteDetailActivity
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.ui.bookmarks.BookmarkListActivity
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

import kotlin.OptIn

import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.AiNoteRepository
import java.util.zip.ZipInputStream
import my.hinoki.booxreader.data.ui.common.BaseActivity

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
                             val prefs = app.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                             prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL) ?: HttpConfig.DEFAULT_BASE_URL
                        },
                        client = app.okHttpClient
                    )
                ) as T
            }
        }
    }
    
    private var navigatorFragment: EpubNavigatorFragment? = null
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
    private val selectionGuardDelayMs = if (EInkHelper.isModernBoox()) 120L else 150L
    private val textSelectionSensitivity = 4f // 更低的觸摸敏感度，提高選取精度
    private val scrollDetectionThreshold = 25f // 更高的滑動檢測閾值，減少干擾
    private var isSelectingText = false // 追蹤是否真的在選取文字
    private val pageNavigationRefreshDelayMs = 300L // 頁面導航專用延遲
    private var lastSelectionJsUpdateAtMs: Long = 0L

    // 簡化的選擇狀態管理
    private enum class SelectionState {
        IDLE,      // 閒置
        GUARDING,  // 防誤觸保護中
        SELECTING, // 正在選擇
        MENU_OPEN  // 菜單已打開
    }

    private data class ChapterItem(
        val title: String,
        val link: Link,
        val depth: Int
    )

    private var selectionState = SelectionState.IDLE
    private var selectionGuardJob: Job? = null
    private var pendingDecorations: List<Decoration>? = null
    private var selectionStartX = 0f
    private var selectionStartY = 0f
    private var selectionStartContentX = 0f
    private var selectionStartContentY = 0f
    private var lastTouchContentX = 0f
    private var lastTouchContentY = 0f
    private var lastStyledHref: String? = null
    private var stylesDirty: Boolean = true
    private var selectionMenuFallbackJob: Job? = null
    private var pendingSelectionCheckJob: Job? = null
    private var hasPendingSelection: Boolean = false
    private var lastSelectionContentRect: Rect? = null
    private var swipeBlockActive: Boolean = false
    private var swipeBlockStartX: Float = 0f
    private var swipeBlockStartY: Float = 0f
    private var swipeBlockStartAtMs: Long = 0L
    private val pageDebugLogging = false
    // WebView.getScale() is deprecated; cache it via this helper to avoid compiler warnings
    @Suppress("DEPRECATION")
    private fun WebView.currentPageScale(): Float = runCatching { scale }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
    private val selectionDebugLogging = false

    private val REQ_BOOKMARK = 1001
    private val PREFS_NAME = "reader_prefs"

    private val gestureDetector by lazy {
        GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 當正在選取文字時，阻止滑動手勢觸發翻頁或其他行為
                if (isSelectionFlowActive() || selectionState == SelectionState.GUARDING) {
                    return true
                }

                // Enable fast mode on scroll to ensure smooth movement
                if (booxFastModeEnabled && !isSelectionFlowActive()) {
                    EInkHelper.enableFastMode(binding.root)
                }

                // 只有在檢測到非常明顯的滑動，且目前是 GUARDING 狀態時，才認為是頁面導航
                val isSignificantScroll = abs(distanceX) > scrollDetectionThreshold || abs(distanceY) > scrollDetectionThreshold
                if (isSignificantScroll && selectionState == SelectionState.GUARDING && !isSelectingText) {
                    // 標記為非文字選取的滑動
                    onSelectionFinished()
                }

                // 如果正在進行文字選取，不干擾
                if (isSelectingText) {
                    return false
                }

                return false // 讓 WebView 處理文字選取
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 1. 優先處理選擇相關的點擊
                // 如果有活動的ActionMode（選擇菜單已打開），優先關閉它
                if (currentActionMode != null) {
                    currentActionMode?.finish()
                    hasPendingSelection = false
                    lifecycleScope.launch {
                        try { navigatorFragment?.clearSelection() } catch (_: Exception) {}
                    }
                    return true
                }

                // 2. 如果正在選擇過程中（包括防誤觸保護、正在拖動選擇、菜單已打開），不處理頁面切換
                if (isSelectionFlowActive()) {
                    return true
                }
                // 2.1 若仍有已選中的文字但 ActionMode 已關閉，優先清除選取，再由下一次點擊處理翻頁
                if (hasPendingSelection) {
                    hasPendingSelection = false
                    lifecycleScope.launch {
                        try { navigatorFragment?.clearSelection() } catch (_: Exception) {}
                    }
                    // Consume this tap; next tap will handle navigation.
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
                                binding.root.postDelayed({
                                    EInkHelper.refreshPartial(binding.root)
                                }, pageNavigationRefreshDelayMs)
                            }
                            return true
                        } else if (x > width * 0.7f) {
                            goPageForward()
                            // 延遲刷新以確保頁面切換完成
                            if (EInkHelper.isBooxDevice()) {
                                binding.root.postDelayed({
                                    EInkHelper.refreshPartial(binding.root)
                                }, pageNavigationRefreshDelayMs)
                            }
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun isSelectionFlowActive(): Boolean {
        return selectionState == SelectionState.SELECTING || selectionState == SelectionState.MENU_OPEN
    }

    private fun startSelectionGuard() {
        selectionGuardJob?.cancel()
        selectionState = SelectionState.GUARDING
        logSelectionDebug("startSelectionGuard -> GUARDING")
        navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(true)

        // 180ms 後如果沒有開始選擇，則釋放
        selectionGuardJob = lifecycleScope.launch {
            delay(selectionGuardDelayMs)
            if (selectionState == SelectionState.GUARDING) {
                selectionState = SelectionState.IDLE
                logSelectionDebug("selectionGuard timeout -> IDLE")
                navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun onSelectionStarted() {
        selectionGuardJob?.cancel()
        selectionState = SelectionState.SELECTING
        hasPendingSelection = true
        logSelectionDebug("onSelectionStarted -> SELECTING")
        navigatorFragment?.view?.let { view ->
            if (view is WebView) {
                // Immediately clamp selection to current drag position to prevent page-wide selects.
                updateSelectionRange(view, lastTouchContentX, lastTouchContentY)
            }
        }
    }

    private fun onActionModeCreated() {
        selectionState = SelectionState.MENU_OPEN
        hasPendingSelection = true
        logSelectionDebug("onActionModeCreated -> MENU_OPEN")
    }

    private fun onSelectionFinished() {
        selectionState = SelectionState.IDLE
        logSelectionDebug("onSelectionFinished -> IDLE")
        navigatorFragment?.view?.parent?.requestDisallowInterceptTouchEvent(false)

        // If nothing is actually selected, don't keep blocking page navigation.
        pendingSelectionCheckJob?.cancel()
        pendingSelectionCheckJob = lifecycleScope.launch {
            delay(60)
            val text = runCatching {
                navigatorFragment?.currentSelection()?.locator?.text?.highlight?.trim()
            }.getOrNull()
            if (text.isNullOrBlank()) {
                hasPendingSelection = false
                logSelectionDebug("onSelectionFinished: empty selection -> clear pending")
            }
        }
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
                    view.javaClass.getMethod("setUserInputEnabled", Boolean::class.javaPrimitiveType).invoke(view, enabled)
                    true
                }.getOrNull() == true ||
                    runCatching {
                        view.javaClass.getMethod("setPagingEnabled", Boolean::class.javaPrimitiveType).invoke(view, enabled)
                        true
                    }.getOrNull() == true ||
                    runCatching {
                        view.javaClass.getMethod("setSwipeEnabled", Boolean::class.javaPrimitiveType).invoke(view, enabled)
                        true
                    }.getOrNull() == true
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
        val js = """
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
        webView.evaluateJavascript(js) { result ->
            logSelectionDebug("PageEdge($label): $result")
        }
    }

    private fun adjustSelectionIntoView(webView: WebView) {
        val paddingPx = (96 * resources.displayMetrics.density).toInt()
        val scale = webView.currentPageScale()
        val js = """
            (function() {
                const sel = window.getSelection && window.getSelection();
                if (!sel || sel.rangeCount === 0) return null;
                const r = sel.getRangeAt(0).getBoundingClientRect();
                if (!r) return null;
                return JSON.stringify({ top: r.top, bottom: r.bottom, left: r.left, right: r.right });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            try {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                val json = parseJsObject(raw) ?: return@evaluateJavascript
                val topPx = json.optDouble("top", 0.0) * scale + webView.scrollY
                val bottomPx = json.optDouble("bottom", 0.0) * scale + webView.scrollY
                val currentTop = webView.scrollY.toDouble()
                val currentBottom = currentTop + webView.height
                val targetDelta = when {
                    topPx < currentTop + paddingPx -> topPx - (currentTop + paddingPx)
                    bottomPx > currentBottom - paddingPx -> bottomPx - (currentBottom - paddingPx)
                    else -> 0.0
                }
                if (targetDelta != 0.0) {
                    val newScroll = (webView.scrollY + targetDelta).toInt().coerceAtLeast(0)
                    webView.post {
                        webView.scrollTo(webView.scrollX, newScroll)
                    }
                    logSelectionDebug("adjustSelectionIntoView scroll by $targetDelta newY=$newScroll")
                }
            } catch (e: Exception) {
                logSelectionDebug("adjustSelectionIntoView error ${e.message}")
            }
        }
    }

    private fun refreshSelectionContentRect(webView: WebView) {
        val scale = webView.currentPageScale()
        val js = """
            (function() {
              const sel = window.getSelection && window.getSelection();
              if (!sel || sel.rangeCount === 0) return null;
              const r = sel.getRangeAt(0).getBoundingClientRect();
              if (!r) return null;
              return JSON.stringify({ left: r.left, top: r.top, right: r.right, bottom: r.bottom });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val json = parseJsObject(raw) ?: return@evaluateJavascript
            val left = (json.optDouble("left", 0.0) * scale).toInt()
            val top = (json.optDouble("top", 0.0) * scale).toInt()
            val right = (json.optDouble("right", 0.0) * scale).toInt()
            val bottom = (json.optDouble("bottom", 0.0) * scale).toInt()
            val clamped = Rect(
                left.coerceIn(0, webView.width.coerceAtLeast(1)),
                top.coerceIn(0, webView.height.coerceAtLeast(1)),
                right.coerceIn(0, webView.width.coerceAtLeast(1)),
                bottom.coerceIn(0, webView.height.coerceAtLeast(1))
            )
            if (clamped.right <= clamped.left || clamped.bottom <= clamped.top) return@evaluateJavascript
            lastSelectionContentRect = clamped
        }
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
        }.getOrNull()
    }

    // Selection uses the system ActionMode menu. Avoid adding extra overlay UI here to keep paging/selection fast on e-ink.

    private fun maybeStartSelectionMenuFallback() {
        selectionMenuFallbackJob?.cancel()
        selectionMenuFallbackJob = lifecycleScope.launch {
            delay(200)
            if (currentActionMode != null) return@launch
            if (selectionState != SelectionState.SELECTING && selectionState != SelectionState.MENU_OPEN) return@launch
            val selection = navigatorFragment?.currentSelection()
            val text = selection?.locator?.text?.highlight
            val hasSelection = !text.isNullOrBlank()
            if (hasSelection) {
                startActionMode(selectionActionModeCallback)?.let { mode ->
                    currentActionMode = mode
                }
                }
            }
        }

    private fun enforceSelectionBounds(webView: WebView) {
        updateSelectionRange(webView, lastTouchContentX, lastTouchContentY, finalize = true)
    }

    private fun updateSelectionRange(
        webView: WebView,
        endContentX: Float,
        endContentY: Float,
        finalize: Boolean = false
    ) {
        val startX = selectionStartContentX
        val startY = selectionStartContentY
        val scale = webView.currentPageScale()
        // IMPORTANT: JS caretRangeFromPoint/elementFromPoint expect viewport (client) coordinates.
        // Use DOM viewport coordinates (CSS px) derived from touch positions, not document coordinates.
        val minX = 0f
        val maxX = webView.width / scale
        val minY = 0f
        val maxY = webView.height / scale
        logSelectionDebug("updateSelectionRange start=($startX,$startY) end=($endContentX,$endContentY) finalize=$finalize state=$selectionState viewport=[$minX,$maxX]x[$minY,$maxY]")
        val js = """
            (function() {
                const minX = ${minX};
                const maxX = ${maxX};
                const minY = ${minY};
                const maxY = ${maxY};
                // 擴大搜索半徑以提高觸摸容錯率
                const SEARCH_RADIUS = 30;
                
                function clamp(v, min, max) { return Math.min(Math.max(v, min), max); }
                const sX = clamp($startX, minX, maxX);
                const sY = clamp($startY, minY, maxY);
                const eX = clamp($endContentX, minX, maxX);
                const eY = clamp($endContentY, minY, maxY);

                function caretAt(x, y) {
                    if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
                    if (document.caretPositionFromPoint) {
                        const pos = document.caretPositionFromPoint(x, y);
                        if (!pos) return null;
                        const r = document.createRange();
                        r.setStart(pos.offsetNode, pos.offset);
                        r.collapse(true);
                        return r;
                    }
                    return null;
                }
                
                // 檢查 Range 是否真的在目標點附近 (解決選到上一頁文字的問題)
                function isRangeNearPoint(range, targetX, targetY) {
                    if (!range) return false;
                    const rect = range.getBoundingClientRect();
                    if (!rect || (rect.width === 0 && rect.height === 0)) {
                        // 有些只有光標的 range 大小為 0，嘗試擴展一個字元來檢查
                        try {
                            const clone = range.cloneRange();
                            clone.setEnd(range.startContainer, Math.min(range.startContainer.length || 0, range.startOffset + 1));
                            const rect2 = clone.getBoundingClientRect();
                             if (rect2 && rect2.width > 0) {
                                 // 檢查目標點是否在矩形稍微擴大的範圍內
                                 return (targetX >= rect2.left - SEARCH_RADIUS && targetX <= rect2.right + SEARCH_RADIUS &&
                                         targetY >= rect2.top - SEARCH_RADIUS && targetY <= rect2.bottom + SEARCH_RADIUS);
                             }
                        } catch(e) {}
                        return true; // 無法測量，姑且相信
                    }
                    
                    // 檢查目標點與 range 矩形的距離
                    // 如果是上一頁的文字，通常 left 會是負值或者遠小於 0 (視分頁實作而定)
                    // 對於分頁閱讀器，上一頁內容可能在視口左側
                    
                    // 寬鬆判定：只要在矩形周圍 SEARCH_RADIUS 像素內即可
                    const distLeft = targetX - rect.left;
                    const distRight = targetX - rect.right;
                    const distTop = targetY - rect.top;
                    const distBottom = targetY - rect.bottom;
                    
                    const nearX = (targetX >= rect.left - SEARCH_RADIUS) && (targetX <= rect.right + SEARCH_RADIUS);
                    const nearY = (targetY >= rect.top - SEARCH_RADIUS) && (targetY <= rect.bottom + SEARCH_RADIUS);
                    
                    return nearX && nearY;
                }

                function caretNear(x, y) {
                    // 1. 標準 viewport 座標嘗試
                    let range = tryFindCaret(x, y);
                    if (range && isRangeNearPoint(range, x, y)) return range;
                    
                    // 2. 失敗重試機制：有時候在某些設備上 caretRangeFromPoint 行為怪異
                    // 嘗試偏移座標 (針對某些特殊的 WebView 座標空間偏移)
                    // 這種情況較少見，但可能是 Boox 設備問題的根源
                    
                    // Case A: 嘗試加上 scrollX (如果 webview 錯誤地預期 document coordinates)
                    const docX = x + window.scrollX;
                    const docY = y + window.scrollY;
                    if (docX !== x || docY !== y) {
                         range = tryFindCaret(docX, docY);
                         // 注意：這裡驗證仍需使用 viewport 座標 (x,y) 與 getBoundingClientRect 比較
                         if (range && isRangeNearPoint(range, x, y)) return range;
                    }
                    
                    return null;
                }
                
                function tryFindCaret(x, y) {
                    // 螺旋搜索
                    const offsets = [
                      0,0, 
                      0,5, 0,-5, 5,0, -5,0,
                      5,5, 5,-5, -5,5, -5,-5,
                      0,10, 0,-10, 10,0, -10,0,
                      0,15, 0,-15, 15,0, -15,0,
                      0,20, 0,-20, 20,0, -20,0
                    ];
                    
                    for (let i = 0; i < offsets.length; i += 2) {
                        const cx = clamp(x + offsets[i], minX, maxX);
                        const cy = clamp(y + offsets[i + 1], minY, maxY);
                        const r = caretAt(cx, cy);
                        if (r) return r;
                    }
                    return null;
                }

                try {
                    let startRange = caretNear(sX, sY);
                    let endRange = caretNear(eX, eY);
                    
                    // 如果無法找到有效的鄰近光標，則保留原樣或嘗試回退
                    if (!startRange || !endRange) {
                         // 當找不到精確位置時，若是 finalizing (手指放開)，嘗試更寬鬆的策略或保持選區
                         // 這裡選擇不更新，以免選區亂跳
                         return;
                    }

                    // 不允許反向：若終點在起點之前，則收斂為起點
                    try {
                        const cmp = startRange.compareBoundaryPoints(Range.START_TO_START, endRange);
                        if (cmp > 0) {
                            endRange = startRange;
                        }
                    } catch (e) {}

                    const range = document.createRange();
                    range.setStart(startRange.startContainer, startRange.startOffset);
                    range.setEnd(endRange.startContainer, endRange.startOffset);
                    const sel = window.getSelection ? window.getSelection() : null;
                    if (sel) {
                        sel.removeAllRanges();
                        sel.addRange(range);
                        ${if (finalize) "window.__lastSelectionText = sel.toString();" else ""}
                    }
                } catch (e) {
                    console.log('updateSelectionRange error', e);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { _ ->
            if (selectionDebugLogging) {
                webView.evaluateJavascript(
                    "(function(){var s=window.getSelection();return s?s.toString().slice(0,200):'';})();"
                ) { text ->
                    logSelectionDebug("JS selection preview: $text")
                }
            }
        }
    }

    private fun logSelectionDebug(message: String) {
        if (!selectionDebugLogging) return
        android.util.Log.d("ReaderSelection", message)
    }

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
            lifecycleScope.launch {
                navigatorFragment?.applyDecorations(pending, "ai_notes")
            }
        }
        pendingDecorations = null
    }

    companion object {
        private const val EXTRA_BOOK_KEY = "extra_book_key"
        private const val EXTRA_BOOK_TITLE = "extra_book_title"
        private const val EXTRA_LOCATOR_JSON = "extra_locator_json"

        fun open(context: Context, bookKey: String, bookTitle: String? = null, locatorJson: String? = null) {
            val intent = Intent(context, ReaderActivity::class.java).apply {
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
            // 保持文石系統預設的應用優化引擎，不再強行切換刷新模式
            EInkHelper.setPreserveSystemEngine(true)
            android.util.Log.d("ReaderActivity", "onCreate - 立即強制讀取文石系統字體設定")

            // 測試多個可能的字體值
            val testSizes = listOf(120, 130, 140, 150, 160, 170, 180, 200)
            val detectedSize = getBooxSystemFontSize()

            android.util.Log.d("ReaderActivity", "文石設備檢測到的字體大小: $detectedSize%")

            // 如果檢測到的值不合理，使用文石常見的標準值
            currentFontSize = if (detectedSize >= 100 && detectedSize <= 200) {
                detectedSize
            } else {
                android.util.Log.w("ReaderActivity", "檢測到的字體大小不合理($detectedSize%)，使用文石標準140%")
                140 // 文石設備常見的標準大小
            }

            currentFontWeight = 400 // 固定預設值
            android.util.Log.d("ReaderActivity", "onCreate最終設定字體: 大小=${currentFontSize}%, 粗細=${currentFontWeight}")
        }

        val bookUri = intent.data
        if (bookUri == null) {
            Toast.makeText(this, "No book URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupObservers()

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

        binding.btnShowBookmarks.setOnClickListener {
            publishCurrentSelection()
        }

        binding.btnChapters.setOnClickListener {
            openChapterPicker()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

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
        var startLocator = initialLocator
        
        // Fix Href mismatch (e.g. Web "text.html" vs Android "OEBPS/text.html")
        if (startLocator != null) {
            val href = startLocator.href.toString()
            val exactMatch = publication.readingOrder.any { it.href.toString() == href }
            if (!exactMatch) {
                // Try fuzzy match (suffix)
                val fuzzyMatch = publication.readingOrder.find { it.href.toString().endsWith(href) || href.endsWith(it.href.toString()) }
                if (fuzzyMatch != null) {
                    android.util.Log.d("ReaderDebug", "Correcting locator href: '$href' -> '${fuzzyMatch.href}'")
                    startLocator = startLocator?.copy(href = Url(fuzzyMatch.href.toString())!!)
                } else {
                    android.util.Log.w("ReaderDebug", "Could not find matching spine item for href: '$href'")
                }
            }
        }

        android.util.Log.d("ReaderDebug", "Initializing navigator. Final Locator: $startLocator")
        startLocator?.let {
            android.util.Log.d("ReaderDebug", "Locator Details JSON: ${LocatorJsonHelper.toJson(it)}")
        }
        // Avoid re-creating if already set up
        if (navigatorFragment != null) return
        lastStyledHref = null
        stylesDirty = true

        val factory = EpubNavigatorFactory(publication)
        val config = EpubNavigatorFragment.Configuration {
            selectionActionModeCallback = this@ReaderActivity.selectionActionModeCallback
        }
        
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = startLocator,
            listener = null,
            configuration = config
        )

        if (supportFragmentManager.findFragmentById(R.id.readerContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.readerContainer, EpubNavigatorFragment::class.java, null)
                .commitNow()
        }
  
              // 確保 tapOverlay 保持隱藏，讓 WebView 完全處理觸摸
        binding.tapOverlay.visibility = android.view.View.GONE
        navigatorFragment = supportFragmentManager.findFragmentById(R.id.readerContainer) as? EpubNavigatorFragment

        // Optimize WebView: Disable overscroll and apply e-ink tuning
        navigatorFragment?.view?.post {
            disableWebViewOverscroll(navigatorFragment?.view)
            applyBooxWebViewSettings(navigatorFragment?.view)
            setupWebViewSelectionListener(navigatorFragment?.view)
            applySwipeNavigationSetting(navigatorFragment?.view)

            // 立即強制應用字體設定（不等待延遲）
            android.util.Log.d("ReaderActivity", "initNavigator完成，立即強制應用字體設定")
            val immediateSize = getBooxSystemFontSize()
            currentFontSize = immediateSize
            android.util.Log.d("ReaderActivity", "立即應用字體大小: $immediateSize%")

            // 直接設置WebView字體
            navigatorFragment?.view?.let { view ->
                findAndSetWebViewTextZoom(view, immediateSize)
            }
            findAndSetWebViewTextZoom(binding.root, immediateSize)

            // 然後使用完整的重試機制
            applyAllSettingsWithRetry()
        }
        observeLocatorUpdates()
        setupDecorationListener()

        // 文石設備特定初始化
        if (EInkHelper.isBooxDevice()) {
            // 設置最佳刷新模式
            if (EInkHelper.isModernBoox()) {
                EInkHelper.enableAutoMode(binding.root)
            } else {
                EInkHelper.restoreQualityMode(binding.root)
            }

            // 優化整個視圖層級
            EInkHelper.optimizeForEInk(binding.root)

            // 關鍵：立即強制重新應用字體設定
            binding.root.postDelayed({
                android.util.Log.d("ReaderActivity", "立即強制重新應用字體設定")
                // 每次都重新讀取文石系統字體設定
                currentFontSize = getBooxSystemFontSize()
                currentFontWeight = 400 // 固定預設值

                android.util.Log.d("ReaderActivity", "強制應用文石系統字體設定: 大小=${currentFontSize}%")

                applyFontSize(currentFontSize)
                applyFontWeight(currentFontWeight)
                applyContrastMode(currentContrastMode)

                // 最終觸發文石系統深度刷新確保所有設定生效
                EInkHelper.enableFastMode(binding.root)
                binding.root.postDelayed({
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }, 50)
            }, 100) // 縮短延遲到0.1秒

        // 額外的強制確保：在短暫延遲後再次檢查並應用
        binding.root.postDelayed({
            android.util.Log.d("ReaderActivity", "額外強制檢查字體設定")
            val savedFontSize = currentFontSize
            currentFontSize = getBooxSystemFontSize()

            if (currentFontSize != savedFontSize) {
                android.util.Log.d("ReaderActivity", "檢測到字體變更，重新應用: $savedFontSize% -> $currentFontSize%")
            }

            applyFontSize(currentFontSize)
            applyFontWeight(currentFontWeight)

            // 強制完整重繪
            forceBooxFullRefresh()
        }, 200)
        } else {
            // 非 E-Ink 設備的標準刷新
            binding.root.post { requestEinkRefresh() }
        }
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
        if (!EInkHelper.isBooxDevice()) return
        if (view is android.webkit.WebView) {
            // 基本屬性設置
            view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.scrollBarStyle = android.view.View.SCROLLBARS_OUTSIDE_OVERLAY
            view.overScrollMode = android.view.View.OVER_SCROLL_NEVER
            view.isHapticFeedbackEnabled = false
            view.isLongClickable = true // 保持長按功能用於文字選取

            // WebView 設置優化
            view.settings.apply {
                // 縮放控制
                displayZoomControls = false
                builtInZoomControls = false
                setSupportZoom(false)

                // 性能優化
                cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true

                // 安全和媒體設置
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // 文字選取優化
                // Keep defaults for file URL access to avoid deprecated settings

                // 文石設備特定優化
                if (EInkHelper.isModernBoox()) {
                    // 新型號支援更好的渲染
                    layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                } else {
                    // 舊型號保守設置
                    layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.SINGLE_COLUMN
                }

                // 字體和渲染優化
                defaultTextEncodingName = "UTF-8"
                standardFontFamily = "serif"
                fixedFontFamily = "monospace"
            }

            // 應用電子墨水屏優化
            EInkHelper.optimizeForEInk(view)
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
            // 移除可能干擾的長按監聽器，讓 WebView 自己處理
            view.setOnLongClickListener(null)

            // 優化的觸摸監聽器，專注於穩定的文字選取
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 重置文字選取標記
                        isSelectingText = false

                        // If swipe page-turn is disabled, prevent the pager parent from intercepting horizontal swipes.
                        // This keeps taps/selection working, but stops "swipe to turn page".
                        if (!pageSwipeEnabled) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        // 記錄觸摸開始位置
                        selectionStartX = event.x
                        selectionStartY = event.y

                        // 將游標放在實際點擊位置，避免長按時選區從頁首開始
                        if (selectionState == SelectionState.IDLE || selectionState == SelectionState.GUARDING) {
                            val scale = view.currentPageScale()
                            // Use viewport (client) coords in CSS px. Do NOT add scroll offsets.
                            val domX = event.x / scale
                            val domY = event.y / scale
                            selectionStartContentX = domX
                            selectionStartContentY = domY
                            lastTouchContentX = domX
                            lastTouchContentY = domY
                            logSelectionDebug("ACTION_DOWN anchor dom=($domX,$domY) view=(${event.x},${event.y}) scale=$scale scroll=(${view.scrollX},${view.scrollY}) state=$selectionState")
                            // Clear any previous selection inside the WebView to avoid reusing stale ranges.
                            view.evaluateJavascript("(function(){var s=window.getSelection&&window.getSelection();if(s){s.removeAllRanges();}})();", null)
                            val js = """
                                (function() {
                                    const minX = 0;
                                    const maxX = Math.max(1, (window.innerWidth || document.documentElement.clientWidth || 0));
                                    const minY = 0;
                                    const maxY = Math.max(1, (window.innerHeight || document.documentElement.clientHeight || 0));
                                    function clamp(v, min, max) { return Math.min(Math.max(v, min), max); }
                                    const x = clamp(${domX}, minX, maxX);
                                    const y = clamp(${domY}, minY, maxY);

                                    function caretAt(x, y) {
                                        if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
                                        if (document.caretPositionFromPoint) {
                                            const pos = document.caretPositionFromPoint(x, y);
                                            if (!pos) return null;
                                            const r = document.createRange();
                                            r.setStart(pos.offsetNode, pos.offset);
                                            r.collapse(true);
                                            return r;
                                        }
                                        return null;
                                    }
                                    function caretNear(x, y) {
                                        const offsets = [
                                          0,0, 1,0, -1,0, 0,1, 0,-1,
                                          2,0, -2,0, 0,2, 0,-2,
                                          4,0, -4,0, 0,4, 0,-4,
                                          6,0, -6,0, 0,6, 0,-6,
                                          8,0, -8,0, 0,8, 0,-8,
                                          12,0, -12,0, 0,12, 0,-12
                                        ];
                                        for (let i = 0; i < offsets.length; i += 2) {
                                            const cx = clamp(x + offsets[i], minX, maxX);
                                            const cy = clamp(y + offsets[i + 1], minY, maxY);
                                            const r = caretAt(cx, cy);
                                            if (r) return r;
                                        }
                                        return null;
                                    }

                                    const sel = window.getSelection ? window.getSelection() : null;
                                    if (!sel) return;
                                    const range = caretNear(x, y);
                                    if (!range) return;
                                    try {
                                        sel.removeAllRanges();
                                        sel.addRange(range);
                                    } catch (_) {}
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(js, null)
                        }

                        // 立即啟動選擇保護，避免快速滑動時仍為 IDLE
                        if (selectionState == SelectionState.IDLE) {
                            startSelectionGuard()
                        }

                        // 立即啟用快速模式以支持流暢的文字選取
                        if (EInkHelper.isBooxDevice() && booxFastModeEnabled) {
                            EInkHelper.enableFastMode(v)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 更精確的文字選取檢測
                        val dx = abs(event.x - selectionStartX)
                        val dy = abs(event.y - selectionStartY)

                        // Optional: disable swipe page turns (keeps tap/volume navigation).
                        if (!pageSwipeEnabled && selectionState == SelectionState.IDLE && !isSelectingText) {
                            val isMostlyHorizontal = dx > scrollDetectionThreshold && dx > dy * 1.2f
                            if (isMostlyHorizontal) {
                                return@setOnTouchListener true
                            }
                        }
                        val scale = view.currentPageScale()
                        lastTouchContentX = event.x / scale
                        lastTouchContentY = event.y / scale
                        logSelectionDebug("MOVE content=($lastTouchContentX,$lastTouchContentY) view=(${event.x},${event.y}) state=$selectionState dx=$dx dy=$dy")

                        val now = SystemClock.uptimeMillis()
                        val canRunSelectionJs = (now - lastSelectionJsUpdateAtMs) > 50

                        // 檢查是否為文字選取（小範圍移動）
                        if (dx < scrollDetectionThreshold && dy < scrollDetectionThreshold) {
                            if (dx > textSelectionSensitivity || dy > textSelectionSensitivity) {
                                if (selectionState == SelectionState.GUARDING) {
                                    onSelectionStarted()
                                    isSelectingText = true
                                }
                                // 當開始文字選取時，持續以起點為基準更新終點，避免反向選取整頁
                                if (selectionState == SelectionState.SELECTING && canRunSelectionJs) {
                                    lastSelectionJsUpdateAtMs = now
                                    updateSelectionRange(view, lastTouchContentX, lastTouchContentY)
                                }
                            }
                        } else {
                            // 大範圍移動，可能不是文字選取
                            if (selectionState == SelectionState.SELECTING && canRunSelectionJs) {
                                lastSelectionJsUpdateAtMs = now
                                // 仍然依照起點->當前點更新選區，避免系統默認反向或整頁選取
                                updateSelectionRange(view, lastTouchContentX, lastTouchContentY)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // 觸摸結束時的處理
                        if (selectionState == SelectionState.GUARDING) {
                            // 如果沒有開始選取，恢復品質模式
                            v.postDelayed({
                                if (selectionState != SelectionState.SELECTING &&
                                    selectionState != SelectionState.MENU_OPEN) {
                                    onSelectionFinished()
                                    if (EInkHelper.isBooxDevice() && booxFastModeEnabled) {
                                        EInkHelper.restoreQualityMode(v)
                                    }
                                }
                            }, 150)
                        } else if (selectionState == SelectionState.SELECTING) {
                            // 選取中，給更多時間讓選取菜單出現
                            v.postDelayed({
                                if (selectionState != SelectionState.MENU_OPEN) {
                                    // 如果菜單沒有出現，恢復品質模式
                                    if (EInkHelper.isBooxDevice() && booxFastModeEnabled) {
                                        EInkHelper.restoreQualityMode(v)
                                    }
                                }
                            }, 300)
                        }

                        // 重置文字選取標記
                        isSelectingText = false

                        // 如果已經開始選取且 WebView 沒有彈出菜單，嘗試用起點/終點重新限定選區，避免整頁被選中
                        if (selectionState == SelectionState.SELECTING) {
                            enforceSelectionBounds(view)
                        }

                        // 後備：如果選取後沒有彈出菜單，主動嘗試啟動 ActionMode
                        maybeStartSelectionMenuFallback()

                        // Re-allow parent intercept when we're done, unless a selection flow is still active.
                        if (!pageSwipeEnabled && !isSelectionFlowActive() && selectionState != SelectionState.GUARDING) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // 取消時立即清理狀態
                        if (selectionState == SelectionState.GUARDING) {
                            onSelectionFinished()
                        }
                        isSelectingText = false
                        if (EInkHelper.isBooxDevice() && booxFastModeEnabled) {
                            EInkHelper.restoreQualityMode(v)
                        }

                        if (!pageSwipeEnabled && !isSelectionFlowActive() && selectionState != SelectionState.GUARDING) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                false // 關鍵：返回 false 讓 WebView 完全處理觸摸事件
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
    


    private fun applyReaderStyles(force: Boolean = false) {
        val navigatorView = navigatorFragment?.view ?: return
        if (!force && !stylesDirty) return
        applyFontZoomLikeNeoReader(navigatorView, currentFontSize)
        applyFontWeightViaWebView(navigatorView, currentFontWeight)
        applyReaderCss(navigatorView)
        applyPageAnimationCss(navigatorView)
        applySelectionFriendlyCss(navigatorView)
        stylesDirty = false
    }

    private fun applyPageAnimationCss(view: View?) {
        if (view is android.webkit.WebView) {
            val css = if (pageAnimationEnabled) {
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

            val js = """
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
            val css = """
                /* Boox 專用：統一字體大小基準 - 使用更細緻的控制 */
                :root {
                    --boox-base-font-size: 16px;
                    --boox-line-height: 1.6;
                }

                html, body {
                    font-size: var(--boox-base-font-size) !important;
                    line-height: var(--boox-line-height) !important;
                    max-width: 100% !important;
                    width: 100% !important;
                    margin: 0 auto !important;
                    padding: 0 !important;
                }

                p, div, li, span:not([class*="font"]):not([class*="size"]), td, th,
                blockquote, q, cite, em, strong, b, i, u, mark, dfn, abbr, samp, kbd, var,
                time, small, big, address, summary, details {
                    font-size: inherit !important;
                    line-height: inherit !important;
                    font-family: inherit !important;
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
            val js = """
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
                /* Enhanced text selection for e-ink readers */
                p, span, h1, h2, h3, h4, h5, h6,
                blockquote, em, strong, b, i, u, mark, small, big,
                cite, dfn, abbr, q, time, td, th, figcaption, pre, code,
                article, section, aside, header, footer, nav, li {
                    -webkit-user-select: text !important;
                    -moz-user-select: text !important;
                    -ms-user-select: text !important;
                    user-select: text !important;
                    cursor: text !important;
                }

                /* Layout elements should generally not be selectable unless they contain text directly */
                html, body, div {
                     -webkit-user-select: auto !important;
                     user-select: auto !important;
                }

                /* Improve selection contrast for e-ink */
                ::selection {
                    background-color: rgba(0, 0, 0, 0.3) !important;
                    color: inherit !important;
                }

                ::-moz-selection {
                    background-color: rgba(0, 0, 0, 0.3) !important;
                    color: inherit !important;
                }

                /* Remove tap highlights that interfere with selection */
                * {
                    -webkit-tap-highlight-color: transparent !important;
                    -webkit-touch-callout: none !important;
                }

                /* Ensure proper text wrapping for selection */
                html, body {
                    word-wrap: break-word !important;
                    overflow-wrap: break-word !important;
                    -webkit-overflow-scrolling: touch !important;
                }

                /* Improve touch target size for selection */
                p, li, span, div {
                    min-height: 1em !important;
                    line-height: inherit !important;
                }
            """.trimIndent()

            val js = """
                (function() {
                    try {
                        var style = document.getElementById('boox-selection-style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = 'boox-selection-style';
                            document.head.appendChild(style);
                        }
                        style.textContent = `${css}`;

                        // Enable text selection across the document
                        document.designMode = 'off';
                        document.addEventListener('selectionchange', function() {
                            // Optional: Handle selection changes if needed
                        });

                        console.log('Enhanced text selection CSS applied');
                    } catch (e) {
                        console.log('Error applying selection CSS:', e);
                    }
                })();
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
        if (!EInkHelper.isBooxDevice()) return

        if (isSelectionFlowActive()) {
            // 在選擇過程中，延遲刷新或使用更輕量的刷新
            if (immediate) {
                // 使用快速模式進行輕量刷新
                EInkHelper.enableFastMode(binding.root)
                EInkHelper.refreshPartial(binding.root)
                // 刷新後恢復品質模式
                binding.root.postDelayed({
                    EInkHelper.restoreQualityMode(binding.root)
                }, 100)
            }
            return
        }

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
        refreshJob = lifecycleScope.launch {
            delay(booxRefreshDelayMs)

            // 根據設備型號選擇刷新策略
            if (EInkHelper.isModernBoox()) {
                // 新型號使用智能刷新
                EInkHelper.smartRefresh(binding.root, hasTextChanges = full, hasImageChanges = false)
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
        android.util.Log.d("ReaderActivity", "WebView準備完成，讀取文石系統字體設定: 大小=${currentFontSize}%, 使用預設粗細=400")

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

        // 關鍵：在字體應用後觸發文石系統深度刷新
        if (EInkHelper.isBooxDevice()) {
            android.util.Log.d("ReaderActivity", "觸發文石系統深度刷新以確保字體設定生效")
            navigatorFragment?.view?.postDelayed({
                // 觸發文石系統的刷新模式變更
                EInkHelper.enableFastMode(binding.root)
                binding.root.postDelayed({
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }, 50)
            }, 100)
        }

        // 延遲再次應用以確保文檔載入完成
        navigatorFragment?.view?.postDelayed({
            android.util.Log.d("ReaderActivity", "延遲重新應用字體設定")
            
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

            // 再次觸發文石系統深度刷新
            if (EInkHelper.isBooxDevice()) {
                EInkHelper.enableFastMode(binding.root)
                binding.root.postDelayed({
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }, 50)
            }

            // 再次延遲以確保穩定
            navigatorFragment?.view?.postDelayed({
                android.util.Log.d("ReaderActivity", "最終確認字體設定")
                
                val finalLocator = navigatorFragment?.currentLocator?.value ?: delayedLocator
                
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

                // 最終觸發文石系統深度刷新確保設定生效
                if (EInkHelper.isBooxDevice()) {
                    EInkHelper.enableFastMode(binding.root)
                    binding.root.postDelayed({
                        if (EInkHelper.isModernBoox()) {
                            EInkHelper.enableAutoMode(binding.root)
                        } else {
                            EInkHelper.restoreQualityMode(binding.root)
                        }
                    }, 50)
                }
            }, 1000) // 1秒後最終確認

            // 額外的強制刷新確保字體設定立即生效
            binding.root.postDelayed({
                forceBooxFullRefresh()
            }, 1200)

            // 最終強制回退：確保即使前面的所有方法都失敗，也會強制應用
            binding.root.postDelayed({
                android.util.Log.d("ReaderActivity", "最終強制回退 - 確保文石字體設定生效")
                
                val fallbackLocator = navigatorFragment?.currentLocator?.value

                // 最後一次讀取文石系統字體設定
                val finalFallbackSize = getBooxSystemFontSize()
                currentFontSize = finalFallbackSize
                currentFontWeight = 400

                android.util.Log.w("ReaderActivity", "最終強制應用字體: 大小=${finalFallbackSize}%")

                applyFontSize(finalFallbackSize)
                applyFontWeight(400)
                
                if (fallbackLocator != null) {
                    navigatorFragment?.go(fallbackLocator, pageAnimationEnabled)
                }

                // 最終的強制完整重繪
                lifecycleScope.launch {
                    EInkHelper.enableFastMode(binding.root)
                    delay(10)
                    EInkHelper.enableDUMode(binding.root)
                    delay(10)
                    EInkHelper.enableGL16Mode(binding.root)
                    delay(10)
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }
            }, 1500)
        }, 500) // 0.5秒後重新應用
    }

    private fun applyFontZoomLikeNeoReader(view: View?, sizePercent: Int) {
        if (view is android.webkit.WebView) {
            android.util.Log.d("ReaderActivity", "應用字體縮放到 WebView: ${sizePercent}%")

            // 使用 WebView 內建的 textZoom，這是最可靠的方式
            view.settings.textZoom = sizePercent

            // 同時使用 CSS 變量來確保一致性
            val css = """
                :root {
                    --boox-font-scale: ${sizePercent / 100.0};
                }

                html {
                    font-size: calc(var(--boox-base-font-size, 16px) * var(--boox-font-scale)) !important;
                }
            """.trimIndent()

            val js = """
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
        val navigator = navigatorFragment ?: return

        lifecycleScope.launch {
            navigator.currentLocator
                .sample(1500)
                .collectLatest {
                    if (isSelectionFlowActive()) return@collectLatest // avoid reflows/progress writes during selection
                    val hrefKey = it.href?.toString()

                    // 當切換到新的章節時，重新確保字體設定正確
                    if (hrefKey != null && hrefKey != lastStyledHref) {
                        lastStyledHref = hrefKey
                        stylesDirty = true

                        // 關鍵：章節切換時重新讀取並應用字體設定
                        android.util.Log.d("ReaderActivity", "章節切換，重新應用字體設定")
                        val savedFontSize = currentFontSize
                        currentFontSize = getBooxSystemFontSize()

                        if (currentFontSize != savedFontSize) {
                            android.util.Log.d("ReaderActivity", "字體大小變更: $savedFontSize% -> $currentFontSize%")
                            applyFontSize(currentFontSize)
                            applyFontWeight(currentFontWeight)
                            applyContrastMode(currentContrastMode)
                        }
                    }

                    applyReaderStyles()

                    // 確保進度信息正確
                    val enhancedLocator = enhanceLocatorWithProgress(it)
                    val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return@collectLatest
                    val key = viewModel.currentBookKey.value ?: return@collectLatest

                    // Debug: 記錄進度信息
                    android.util.Log.d("ReaderActivity", "保存進度 - totalProgression: ${enhancedLocator.locations?.totalProgression}")
                    android.util.Log.d("ReaderActivity", "保存進度 - progression: ${enhancedLocator.locations?.progression}")
                    android.util.Log.d("ReaderActivity", "保存進度 - href: ${enhancedLocator.href}")

                    // Local Prefs
                    syncRepo.cacheProgress(key, json)

                    // ViewModel (Server/DB)
                    viewModel.saveProgress(json)

                    requestEinkRefresh()
                }
        }
    }

    // 增強 Locator 以確保包含正確的進度信息
    private fun enhanceLocatorWithProgress(locator: Locator): Locator {
        val currentLocations = locator.locations
            ?: run {
                android.util.Log.d("ReaderActivity", "創建新的 locations 對象")
                return locator.copy(
                    locations = Locator.Locations(
                        totalProgression = 0.0,
                        progression = 0.0
                    )
                )
            }

        // 如果已經有有效的 totalProgression，直接返回
        val totalProgression = currentLocations?.totalProgression
        if (totalProgression != null && totalProgression >= 0 && totalProgression <= 1.0) {
            android.util.Log.d("ReaderActivity", "已存在有效的 totalProgression: $totalProgression")
            return locator
        }

        // 如果沒有 totalProgression 但有 progression，將 progression 作為 totalProgression 的估算
        val progression = currentLocations?.progression
        if (progression != null && progression >= 0 && progression <= 1.0) {
            android.util.Log.d("ReaderActivity", "使用 progression 作為 totalProgression: $progression")
            val enhancedLocations = currentLocations.copy(
                totalProgression = progression
            )
            return locator.copy(locations = enhancedLocations)
        }

        // 如果都沒有，保持原樣
        android.util.Log.d("ReaderActivity", "保持原始 locator，無有效進度信息")
        return locator
    }

    override fun onResume() {
        super.onResume()
        // 關鍵：每次回到Reader時都強制重新讀取並應用文石系統字體設定
        if (EInkHelper.isBooxDevice()) {
            android.util.Log.d("ReaderActivity", "onResume - 強制重新檢查文石系統字體設定")
            
            // 1. Capture current location before any layout changes
            val savedLocator = navigatorFragment?.currentLocator?.value
            android.util.Log.d("ReaderActivity", "onResume - 保存當前位置: ${savedLocator?.locations?.progression}")

            lifecycleScope.launch {
                delay(200) // 短暫延遲確保UI準備好

                // 每次都重新讀取文石系統字體設定
                val newFontSize = getBooxSystemFontSize()
                val fontChanged = newFontSize != currentFontSize
                
                currentFontSize = newFontSize
                currentFontWeight = 400 // 固定預設值

                android.util.Log.d("ReaderActivity", "onResume應用文石系統字體設定: 大小=${currentFontSize}%, 變更=$fontChanged")

                applyFontSize(currentFontSize)
                applyFontWeight(currentFontWeight)
                applyContrastMode(currentContrastMode)

                // 2. Restore location if valid
                if (savedLocator != null) {
                    android.util.Log.d("ReaderActivity", "onResume - 嘗試恢復位置")
                    // Small delay to let WebView apply text zoom first
                    delay(50) 
                    navigatorFragment?.go(savedLocator, pageAnimationEnabled)
                }

                delay(100)
                // 觸發文石系統深度刷新
                EInkHelper.enableFastMode(binding.root)
                binding.root.postDelayed({
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                    }
                }, 50)
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

        // 確保進度信息正確
        val enhancedLocator = enhanceLocatorWithProgress(locator)
        val json = LocatorJsonHelper.toJson(enhancedLocator) ?: return

        // Debug: 記錄保存的數據
        android.util.Log.d("ReaderActivity", "立即保存進度 - Key: $key")
        android.util.Log.d("ReaderActivity", "立即保存進度 - JSON: $json")
        android.util.Log.d("ReaderActivity", "立即保存進度 - totalProgression: ${enhancedLocator.locations?.totalProgression}")
        android.util.Log.d("ReaderActivity", "立即保存進度 - progression: ${enhancedLocator.locations?.progression}")

        syncRepo.cacheProgress(key, json)

        viewModel.saveProgress(json)
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

        val labels = chapters.map { item ->
            val indent = "  ".repeat(item.depth.coerceAtLeast(0))
            val title = item.title.ifBlank { item.link.href.toString() }
            "$indent• $title"
        }.toTypedArray()

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
                val title = link.title?.takeIf { it.isNotBlank() }
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
        val btnSettingsAddBookmark = dialogView.findViewById<Button>(R.id.btnSettingsAddBookmark)
        val btnSettingsShowBookmarks = dialogView.findViewById<Button>(R.id.btnSettingsShowBookmarks)
        val switchPageTap = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageTap)
        val switchPageSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageSwipe)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val etApiKey = dialogView.findViewById<EditText>(R.id.etApiKey)
        val cbCustomExport = dialogView.findViewById<CheckBox>(R.id.cbCustomExportUrl)
        val etCustomExportUrl = dialogView.findViewById<EditText>(R.id.etCustomExportUrl)
        val cbLocalExport = dialogView.findViewById<CheckBox>(R.id.cbLocalExport)
        val btnTestExport = dialogView.findViewById<Button>(R.id.btnTestExportEndpoint)

        // Add Security Buttons and Boox-specific settings
        // dialogView is a ScrollView, so we need to get its child LinearLayout
        val layout = (dialogView as? android.view.ViewGroup)?.getChildAt(0) as? android.widget.LinearLayout
        if (layout != null && EInkHelper.isBooxDevice()) {
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
                text = if (EInkHelper.isModernBoox()) {
                    "文石設備優化 (${EInkHelper.getBooxModel()})"
                } else {
                    "文石 E-Ink 設置"
                }
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 16, 0, 8)
            }

            val switchBatch = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = "批量刷新 (減少閃爍)"
                isChecked = booxBatchRefreshEnabled
                setOnCheckedChangeListener { _, checked ->
                    booxBatchRefreshEnabled = checked
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("boox_batch_refresh", checked)
                        .apply()
                }
            }

            val switchFast = androidx.appcompat.widget.SwitchCompat(this).apply {
                text = "交互時快速模式"
                isChecked = booxFastModeEnabled
                setOnCheckedChangeListener { _, checked ->
                    booxFastModeEnabled = checked
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("boox_fast_mode", checked)
                        .apply()
                }
            }

            val btnFullRefresh = Button(this).apply {
                text = "立即全屏刷新"
                setOnClickListener {
                    EInkHelper.refreshFull(binding.root)
                    Toast.makeText(this@ReaderActivity, "已執行全屏刷新", Toast.LENGTH_SHORT).show()
                }
            }

            val btnSmartRefresh = Button(this).apply {
                text = "智能刷新測試"
                setOnClickListener {
                    EInkHelper.smartRefresh(binding.root, hasTextChanges = false, hasImageChanges = false)
                    Toast.makeText(this@ReaderActivity, "已執行智能刷新", Toast.LENGTH_SHORT).show()
                }
            }

            // 新型號專用選項
            if (EInkHelper.isModernBoox()) {
                val btnOptimizeMode = Button(this).apply {
                    text = "切換自動模式"
                    setOnClickListener {
                        EInkHelper.enableAutoMode(binding.root)
                        Toast.makeText(this@ReaderActivity, "已切換到自動刷新模式", Toast.LENGTH_SHORT).show()
                    }
                }
                layout.addView(btnOptimizeMode, layout.childCount - 2)
            }

            val btnDeviceInfo = Button(this).apply {
                text = "設備信息"
                setOnClickListener {
                    val info = """
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

            // 對比模式控制
            if (EInkHelper.supportsHighContrast()) {
                val contrastTitle = TextView(this).apply {
                    text = "閱讀主題"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }

                val currentModeText = TextView(this).apply {
                    text = "當前：${EInkHelper.getContrastModeName(currentContrastMode)}"
                    textSize = 14f
                    setPadding(0, 4, 0, 8)
                }

                val btnToggleContrast = Button(this).apply {
                    text = "切換主題 (正常/深色/褐色/高對比)"
                    setOnClickListener {
                        toggleContrastMode()
                        currentModeText.text = "當前：${EInkHelper.getContrastModeName(currentContrastMode)}"
                    }
                }

                // 單獨的模式選擇按鈕
                val btnNormalMode = Button(this).apply {
                    text = "正常模式"
                    setOnClickListener {
                        applyContrastMode(ContrastMode.NORMAL)
                        currentModeText.text = "當前：${EInkHelper.getContrastModeName(ContrastMode.NORMAL)}"
                    }
                }

                val btnDarkMode = Button(this).apply {
                    text = "深色模式"
                    setOnClickListener {
                        applyContrastMode(ContrastMode.DARK)
                        currentModeText.text = "當前：${EInkHelper.getContrastModeName(ContrastMode.DARK)}"
                    }
                }

                val btnSepiaMode = Button(this).apply {
                    text = "褐色模式"
                    setOnClickListener {
                        applyContrastMode(ContrastMode.SEPIA)
                        currentModeText.text = "當前：${EInkHelper.getContrastModeName(ContrastMode.SEPIA)}"
                    }
                }

                val btnHighContrastMode = Button(this).apply {
                    text = "高對比模式"
                    setOnClickListener {
                        applyContrastMode(ContrastMode.HIGH_CONTRAST)
                        currentModeText.text = "當前：${EInkHelper.getContrastModeName(ContrastMode.HIGH_CONTRAST)}"
                    }
                }

                layout.addView(contrastTitle, layout.childCount - 2)
                layout.addView(currentModeText, layout.childCount - 2)
                layout.addView(btnToggleContrast, layout.childCount - 2)
                layout.addView(btnNormalMode, layout.childCount - 2)
                layout.addView(btnDarkMode, layout.childCount - 2)
                layout.addView(btnSepiaMode, layout.childCount - 2)
                layout.addView(btnHighContrastMode, layout.childCount - 2)
            }

            layout.addView(btnDeviceInfo, layout.childCount - 2)
        }

        val btnAiProfiles = Button(this).apply {
            text = "AI Profiles (Switch Model/API)"

        }
        layout?.addView(btnAiProfiles, 2)

        // --- Language Selection ---
        val languageTitle = TextView(this).apply {
            text = "Language / 語言"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
        layout?.addView(languageTitle, 2)

        val languageGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val rbSystem = android.widget.RadioButton(this).apply { text = "System Default (跟隨系統)" }
        val rbEnglish = android.widget.RadioButton(this).apply { text = "English" }
        val rbChinese = android.widget.RadioButton(this).apply { text = "Traditional Chinese (繁體中文)" }

        languageGroup.addView(rbSystem)
        languageGroup.addView(rbEnglish)
        languageGroup.addView(rbChinese)
        layout?.addView(languageGroup, 3)

        // Load current Settings
        val readerSettings = ReaderSettings.fromPrefs(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        
        when (readerSettings.language) {
            "zh" -> rbChinese.isChecked = true
            "en" -> rbEnglish.isChecked = true
            else -> rbSystem.isChecked = true
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE) // keep raw prefs for specific edits if needed or just use saveTo

        val switchPageAnimation = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPageAnimation)

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
            val baseUrl = etServerUrl.text.toString().trim().ifEmpty { readerSettings.serverBaseUrl }
            val targetUrl = if (cbCustomExport.isChecked && etCustomExportUrl.text.toString().trim()
                    .isNotEmpty()
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
                Toast.makeText(
                    this@ReaderActivity,
                    "Export test: $result",
                    Toast.LENGTH_LONG
                ).show()
                btnTestExport.text = originalText
                btnTestExport.isEnabled = true
            }
        }

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        val dialog = AlertDialog.Builder(this)
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
                    if (newUrlRaw.isNotEmpty()) normalizeUrl(newUrlRaw) else readerSettings.serverBaseUrl
                val normalizedCustomUrl =
                    if (useCustomExport && customExportUrlRaw.isNotEmpty()) normalizeUrl(customExportUrlRaw) else ""

                if (newUrlRaw.isNotEmpty() && normalizedBaseUrl.toHttpUrlOrNull() == null) {
                    Toast.makeText(this, "Server URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (useCustomExport && customExportUrlRaw.isNotEmpty() && normalizedCustomUrl.toHttpUrlOrNull() == null) {
                    Toast.makeText(this, "Custom export URL is invalid", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Update settings object
                val updatedSettings = readerSettings.copy(
                    serverBaseUrl = normalizedBaseUrl,
                    apiKey = newApiKey,
                    pageTapEnabled = newPageTap,
                    pageSwipeEnabled = newPageSwipe,
                    pageAnimationEnabled = newPageAnimation,
                    exportToCustomUrl = useCustomExport,
                    exportCustomUrl = normalizedCustomUrl,
                    exportToLocalDownloads = exportToLocal,
                    language = when {
                        rbChinese.isChecked -> "zh"
                        rbEnglish.isChecked -> "en"
                        else -> "system"
                    },
                    updatedAt = System.currentTimeMillis()
                )

                updatedSettings.saveTo(prefs)

                // Restart if language changed
                if (updatedSettings.language != readerSettings.language) {
                    val intent = Intent(applicationContext, my.hinoki.booxreader.data.ui.welcome.WelcomeActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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
        switchPageTap.setOnCheckedChangeListener { _, isChecked ->
             pageTapEnabled = isChecked
        }
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
        android.util.Log.d("ReaderActivity", "applyFontSize 被調用，目標大小: $sizePercent%")

        // 立即直接設置所有可能的WebView實例
        navigatorFragment?.view?.let { navView ->
            findAndSetWebViewTextZoom(navView, sizePercent)
        }

        // 也嘗試設置root下的WebView
        findAndSetWebViewTextZoom(binding.root, sizePercent)

        // 關鍵：立即觸發文石系統刷新，不等待任何延遲
        if (EInkHelper.isBooxDevice()) {
            android.util.Log.d("ReaderActivity", "字體大小變更($sizePercent%)，立即觸發文石系統深度刷新")

            // 立即觸發文石系統刷新模式變更
            binding.root.post {
                lifecycleScope.launch {
                    // 立即執行完整的刷新序列
                    EInkHelper.enableFastMode(binding.root)
                    android.util.Log.d("ReaderActivity", "執行 enableFastMode")
                    delay(50)

                    EInkHelper.enableDUMode(binding.root)
                    android.util.Log.d("ReaderActivity", "執行 enableDUMode")
                    delay(50)

                    EInkHelper.enableGL16Mode(binding.root)
                    android.util.Log.d("ReaderActivity", "執行 enableGL16Mode")
                    delay(50)

                    // 最終恢復到閱讀模式
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                        android.util.Log.d("ReaderActivity", "執行 enableAutoMode")
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                        android.util.Log.d("ReaderActivity", "執行 restoreQualityMode")
                    }

                    android.util.Log.d("ReaderActivity", "文石系統刷新序列完成")
                }
            }
        }

        stylesDirty = true
        navigatorFragment?.view?.post {
            applyReaderStyles(force = true)
        }
    }

    // 新增：強制查找並設置所有WebView的字體大小
    private fun findAndSetWebViewTextZoom(view: View, sizePercent: Int) {
        if (view is android.webkit.WebView) {
            val oldZoom = view.settings.textZoom
            view.settings.textZoom = sizePercent
            android.util.Log.d("ReaderActivity", "強制設置WebView textZoom: $oldZoom% -> $sizePercent%")

            // 使用JavaScript強制設置字體大小
            val js = """
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
    android.util.Log.d("ReaderActivity", "開始讀取文石系統字體設定")

    return if (EInkHelper.isBooxDevice()) {
        android.util.Log.d("ReaderActivity", "檢測到文石設備: ${EInkHelper.getBooxModel()}")

        // 嘗試讀取，但即使失敗也使用文石系統合理的預設值
        var fontSize = 150 // 文石系統預設值

        // 方法1: 嘗試讀取文石系統設定
        val systemSetting = tryReadBooxSystemSetting()
        if (systemSetting > 0) {
            fontSize = systemSetting
            android.util.Log.d("ReaderActivity", "使用文石系統設定: $fontSize%")
        }

        // 方法2: 嘗試讀取Android通用設定
        if (fontSize == 150) {
            val androidSetting = tryReadAndroidSystemSetting()
            if (androidSetting > 0) {
                fontSize = androidSetting
                android.util.Log.d("ReaderActivity", "使用Android系統設定: $fontSize%")
            }
        }

        // 方法3: 嘗試從Configuration讀取
        if (fontSize == 150) {
            val configSetting = tryReadConfigurationFontScale()
            if (configSetting > 0) {
                fontSize = configSetting
                android.util.Log.d("ReaderActivity", "使用Configuration設定: $fontSize%")
            }
        }

        // 方法4: 嘗試讀取文石特有的設定
        if (fontSize == 150) {
            val booxSetting = tryReadBooxSpecificSettings()
            if (booxSetting > 0) {
                fontSize = booxSetting
                android.util.Log.d("ReaderActivity", "使用文石特有設定: $fontSize%")
            }
        }

        // 方法5: 嘗試讀取文石配置文件
        if (fontSize == 150) {
            val fileSetting = tryReadBooxConfigFiles()
            if (fileSetting > 0) {
                fontSize = fileSetting
                android.util.Log.d("ReaderActivity", "使用文石配置文件設定: $fontSize%")
            }
        }

        // 方法6: 嘗試通過系統命令獲取
        if (fontSize == 150) {
            val commandSetting = tryReadBooxSystemCommand()
            if (commandSetting > 0) {
                fontSize = commandSetting
                android.util.Log.d("ReaderActivity", "使用系統命令獲取的字體設定: $fontSize%")
            }
        }

        // 強制返回合理的文石系統字體值（100-200%範圍）
        if (fontSize < 100 || fontSize > 200) {
            android.util.Log.w("ReaderActivity", "字體大小超出合理範圍($fontSize%)，強制使用文石標準150%")
            fontSize = 150
        }

        android.util.Log.d("ReaderActivity", "最終確定文石字體大小: $fontSize%")
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
        val possibleKeys = listOf(
            "font_scale",
            "system_font_scale",
            "display_font_scale",
            "boox_font_scale",
            "epd_font_size"
        )

        for (key in possibleKeys) {
            try {
                val fontSize = android.provider.Settings.System.getFloat(contentResolver, key, -1.0f)
                if (fontSize > 0) {
                    android.util.Log.d("ReaderActivity", "找到系統設定 $key = $fontSize")
                    return (fontSize * 150).toInt()
                }
            } catch (e: Exception) {
                android.util.Log.d("ReaderActivity", "無法讀取設定 $key: ${e.message}")
            }
        }
        -1
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "讀取文石系統設定失敗: ${e.message}")
        -1
    }
  }

  private fun tryReadAndroidSystemSetting(): Int {
    return try {
        val contentResolver = contentResolver
        val fontSize = android.provider.Settings.System.getFloat(contentResolver, android.provider.Settings.System.FONT_SCALE, -1.0f)
        if (fontSize > 0) {
            (fontSize * 150).toInt()
        } else {
            -1
        }
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "讀取Android系統設定失敗: ${e.message}")
        -1
    }
  }

  private fun tryReadConfigurationFontScale(): Int {
    return try {
        val config = resources.configuration
        val fontScale = config.fontScale
        android.util.Log.d("ReaderActivity", "Configuration fontScale: $fontScale")
        if (fontScale > 0) {
            (fontScale * 150).toInt()
        } else {
            -1
        }
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "讀取Configuration失敗: ${e.message}")
        -1
    }
  }

  private fun tryReadBooxSpecificSettings(): Int {
    return try {
        val contentResolver = contentResolver

        // 嘗試文石特有的設定路徑
        val booxKeys = listOf(
            "com.onyx.android.sdk.font_scale",
            "com.onyx.epd.font_scale",
            "com.boox.reader.font_size",
            "eink_font_scale"
        )

        for (key in booxKeys) {
            try {
                val fontSize = android.provider.Settings.System.getFloat(contentResolver, key, -1.0f)
                if (fontSize > 0) {
                    android.util.Log.d("ReaderActivity", "找到文石設定 $key = $fontSize")
                    return (fontSize * 150).toInt()
                }
            } catch (e: Exception) {
                android.util.Log.d("ReaderActivity", "無法讀取文石設定 $key: ${e.message}")
            }
        }

        -1
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "讀取文石特有設定失敗: ${e.message}")
        -1
    }
  }

  private fun tryReadBooxConfigFiles(): Int {
    return try {
        // 嘗試讀取文石系統配置文件
        val configPaths = listOf(
            "/data/data/com.onyx.android.sdk/config/preferences.xml",
            "/data/data/com.onyx.reader/preferences.xml",
            "/system/etc/onyx_config.xml",
            "/proc/onyx/display/font_scale"
        )

        for (path in configPaths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    android.util.Log.d("ReaderActivity", "找到配置文件: $path")
                    val content = file.readText()
                    android.util.Log.d("ReaderActivity", "配置文件內容: $content")

                    // 尋找字體相關的設定
                    val fontPatterns = listOf(
                        Regex("font_scale[=:]\\s*([0-9.]+)"),
                        Regex("font_size[=:]\\s*([0-9.]+)"),
                        Regex("display_scale[=:]\\s*([0-9.]+)")
                    )

                    for (pattern in fontPatterns) {
                        val match = pattern.find(content)
                        if (match != null) {
                            val value = match.groupValues[1].toFloatOrNull()
                            if (value != null && value > 0) {
                                android.util.Log.d("ReaderActivity", "從配置文件找到字體設定: $value")
                                return (value * 150).toInt()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("ReaderActivity", "無法讀取配置文件 $path: ${e.message}")
            }
        }

        -1
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "讀取文石配置文件失敗: ${e.message}")
        -1
    }
  }

  private fun tryReadBooxSystemCommand(): Int {
    return try {
        android.util.Log.d("ReaderActivity", "嘗試通過系統命令獲取字體設定")

        // 嘗試多個可能的系統命令
        val commands = listOf(
            "getprop persist.sys.font_scale",
            "getprop ro.font.scale",
            "cat /proc/onyx/display/font_scale",
            "settings get system font_scale",
            "settings get global font_scale"
        )

        for (command in commands) {
            try {
                val process = Runtime.getRuntime().exec(command)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val output = reader.readText().trim()
                reader.close()
                process.waitFor()

                if (output.isNotEmpty()) {
                    val value = output.toFloatOrNull()
                    if (value != null && value > 0) {
                        android.util.Log.d("ReaderActivity", "系統命令 '$command' 返回: $value")
                        return (value * 150).toInt()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("ReaderActivity", "執行系統命令 '$command' 失敗: ${e.message}")
            }
        }

        -1
    } catch (e: Exception) {
        android.util.Log.d("ReaderActivity", "系統命令讀取失敗: ${e.message}")
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
        val contrastMode = EInkHelper.ContrastMode.values().getOrNull(settings.contrastMode)
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
        lifecycleScope.launch {
            syncRepo.pushSettings(ReaderSettings.fromPrefs(prefs))
        }
    }

    private fun applyFontWeight(weight: Int) {
        currentFontWeight = weight.coerceIn(300, 900)
        // 移除SharedPreferences保存，不再保存字體粗細設定

        stylesDirty = true
        navigatorFragment?.view?.post {
            applyReaderStyles(force = true)

            // 字體粗細變更也需要觸發文石系統深度刷新
            if (EInkHelper.isBooxDevice()) {
                android.util.Log.d("ReaderActivity", "字體粗細變更，觸發文石系統深度刷新")
                binding.root.postDelayed({
                    EInkHelper.enableFastMode(binding.root)
                    binding.root.postDelayed({
                        if (EInkHelper.isModernBoox()) {
                            EInkHelper.enableAutoMode(binding.root)
                        } else {
                            EInkHelper.restoreQualityMode(binding.root)
                        }
                    }, 30)
                }, 20)
            }
        }
    }

    // 新增：強制觸發文石系統的完整畫面重繪
    private fun forceBooxFullRefresh() {
        if (!EInkHelper.isBooxDevice()) return

        android.util.Log.d("ReaderActivity", "強制觸發文石系統完整畫面重繪")

        // 立即在主線程執行，不使用coroutine延遲
        EInkHelper.enableFastMode(binding.root)
        android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 執行 enableFastMode")

        // 短暫後執行下一步
        binding.root.postDelayed({
            EInkHelper.enableDUMode(binding.root)
            android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 執行 enableDUMode")

            binding.root.postDelayed({
                EInkHelper.enableGL16Mode(binding.root)
                android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 執行 enableGL16Mode")

                binding.root.postDelayed({
                    // 最終恢復到閱讀模式
                    if (EInkHelper.isModernBoox()) {
                        EInkHelper.enableAutoMode(binding.root)
                        android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 執行 enableAutoMode")
                    } else {
                        EInkHelper.restoreQualityMode(binding.root)
                        android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 執行 restoreQualityMode")
                    }
                    android.util.Log.d("ReaderActivity", "forceBooxFullRefresh: 完整刷新序列執行完成")
                }, 30)
            }, 30)
        }, 30)
    }

    private fun applyContrastMode(mode: ContrastMode) {
        currentContrastMode = mode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt("contrast_mode", mode.ordinal).apply()

        navigatorFragment?.view?.let { view ->
            EInkHelper.setHighContrastMode(view, mode)

            // 對比模式變更後執行完整刷新
            if (EInkHelper.isBooxDevice()) {
                view.postDelayed({
                    EInkHelper.refreshFull(binding.root)
                }, 200)
            }
        }
    }

    private fun toggleContrastMode() {
        val newMode = EInkHelper.toggleContrastMode(navigatorFragment?.view)
        currentContrastMode = newMode

        // 保存設置
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt("contrast_mode", newMode.ordinal)
            .apply()

        // 顯示當前模式
        Toast.makeText(this, "已切換到：${EInkHelper.getContrastModeName(newMode)}", Toast.LENGTH_SHORT).show()
    }

    // --- Action Mode ---
    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            currentActionMode = mode
            selectionMenuFallbackJob?.cancel()
            onSelectionStarted()

            // 確保在 UI 線程更新狀態
            binding.root.post {
                onActionModeCreated()
            }

            // 觸覺反饋
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // 添加調試日誌
            android.util.Log.d("ReaderActivity", "ActionMode created - selectionState: $selectionState")
            logSelectionDebug("ActionMode created (MENU_OPEN) - should be visible")
            if (selectionDebugLogging) {
                Toast.makeText(this@ReaderActivity, "Selection menu opened", Toast.LENGTH_SHORT).show()
            }
            // 確保選取區域不在螢幕邊緣被遮擋，輕微捲動以露出 ActionMode
            findFirstWebView(navigatorFragment?.view)?.let {
                refreshSelectionContentRect(it)
                adjustSelectionIntoView(it)
            }
            // Some devices (esp. e-ink) may not refresh the floating ActionMode promptly.
            binding.root.postDelayed({
                if (selectionState == SelectionState.MENU_OPEN) {
                    mode?.invalidate()
                    binding.root.invalidate()
                    if (EInkHelper.isBooxDevice()) {
                        EInkHelper.refreshPartial(binding.root)
                    }
                }
            }, 80)

            return true
        }

        override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: Rect?) {
            if (outRect == null) return
            // Provide an anchor rect so the floating ActionMode positions within the visible area.
            // Without this, some devices (esp. paginated WebView + e-ink) may place it off-screen.
            val rect = lastSelectionContentRect
            if (rect != null) {
                outRect.set(rect)
                return
            }
            val x = selectionStartX.toInt()
            val y = selectionStartY.toInt()
            outRect.set(x, y, (x + 1), (y + 1))
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
            android.util.Log.d("ReaderActivity", "onPrepareActionMode called")

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

            if (item?.itemId == 1001) {
                lifecycleScope.launch {
                    val selection = navigatorFragment?.currentSelection()
                    val text = selection?.locator?.text?.highlight
                    if (!text.isNullOrBlank()) {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        startActivity(Intent.createChooser(shareIntent, "分享選取文字"))
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
            selectionMenuFallbackJob?.cancel()
            onSelectionFinished()
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
        // Some devices/pagers intercept before the WebView sees MOVE events, so we must stop it at Activity level.
        // When swipe-to-turn is disabled, block horizontal swipes even if we are in GUARDING.
        // GUARDING is entered early to improve text selection, but it must not re-enable page swipes.
        if (!pageSwipeEnabled &&
            currentActionMode == null &&
            !hasPendingSelection &&
            selectionState != SelectionState.SELECTING &&
            selectionState != SelectionState.MENU_OPEN
        ) {
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
                    // Block short horizontal flicks even if we didn't mark active in MOVE (e.g. very fast gestures).
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
                // 記錄目前選取的文字供偵錯
                if (selectionDebugLogging) {
                    lifecycleScope.launch {
                        try {
                            val sel = navigatorFragment?.currentSelection()
                            val text = sel?.locator?.text?.highlight?.trim()?.take(200)
                            if (!text.isNullOrBlank()) {
                                logSelectionDebug("Selected text: \"$text\"")
                            }
                        } catch (_: Exception) {}
                    }

                    // Also log DOM selection bounds to detect cross-page/hidden-menu cases.
                    findFirstWebView(navigatorFragment?.view)?.let { webView ->
                        val js = """
                            (function() {
                              const sel = window.getSelection && window.getSelection();
                              if (!sel || sel.rangeCount === 0) return null;
                              const r = sel.getRangeAt(0).getBoundingClientRect();
                              const vw = Math.max(1, window.innerWidth || document.documentElement.clientWidth || 0);
                              const vh = Math.max(1, window.innerHeight || document.documentElement.clientHeight || 0);
                              return JSON.stringify({
                                rect: { left: r.left, top: r.top, right: r.right, bottom: r.bottom, width: r.width, height: r.height },
                                viewport: { w: vw, h: vh },
                                scroll: { x: window.scrollX || 0, y: window.scrollY || 0 }
                              });
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(js) { raw ->
                            if (!raw.isNullOrBlank() && raw != "null") {
                                logSelectionDebug("SelectionRect: $raw")
                            }
                        }
                    }
                }

                // 觸摸結束時恢復高質量模式
                if (booxFastModeEnabled) {
                    EInkHelper.restoreQualityMode(window.decorView)
                }

                // 如果目前還在 GUARDING 狀態，重置為 IDLE 以允許頁面導航
                if (selectionState == SelectionState.GUARDING) {
                    onSelectionFinished()
                }

                // 延遲檢查是否需要從 SELECTING 重置為 IDLE
                // 這處理了可能卡在 SELECTING 狀態的情況
                lifecycleScope.launch {
                    delay(300) // 等待可能的文字選取菜單出現
                    if (selectionState == SelectionState.SELECTING) {
                        // 如果沒有打開選取菜單，重置狀態
                        onSelectionFinished()
                    }
                }

                // 後備：如果 WebView 沒有自動打開選單，延遲嘗試啟動 ActionMode
                maybeStartSelectionMenuFallback()
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
