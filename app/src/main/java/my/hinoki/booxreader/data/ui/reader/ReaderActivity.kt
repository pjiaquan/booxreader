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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as my.hinoki.booxreader.BooxReaderApp
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(
                    app,
                    BookRepository(app),
                    BookmarkRepository(app, app.okHttpClient),
                    AiNoteRepository(app, app.okHttpClient)
                ) as T
            }
        }
    }
    
    private var navigatorFragment: EpubNavigatorFragment? = null
    private var currentActionMode: ActionMode? = null
    
    // Activity local state for UI interaction
    private var pageTapEnabled: Boolean = true
    private var potentialPageTap: Boolean = false
    private var tapDownTime: Long = 0L
    private var tapDownX: Float = 0f
    private var tapDownY: Float = 0f
    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var currentFontSize: Int = 150
    
    private val REQ_BOOKMARK = 1001
    private val PREFS_NAME = "reader_prefs"

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
        pageTapEnabled = prefs.getBoolean("page_tap_enabled", true)

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
                    val savedJson = if (key != null) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .getString("progress_$key", null)
                    } else null
                    
                    val initialLocator = LocatorJsonHelper.fromJson(savedJson)
                    initNavigator(it, initialLocator)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.decorations.collectLatest {
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
                viewModel.navigateToNote.collect { noteId ->
                    AiNoteDetailActivity.open(this@ReaderActivity, noteId)
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

        // Optimize WebView: Disable overscroll to stabilize selection boundaries
        navigatorFragment?.view?.post {
            disableWebViewOverscroll(navigatorFragment?.view)
        }

        applyFontSize(currentFontSize)
        observeLocatorUpdates()
        setupDecorationListener()
        
        // Refresh E-Ink
        binding.root.post { EInkHelper.refresh(binding.root) }
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
    
    @OptIn(FlowPreview::class)
    private fun observeLocatorUpdates() {
        val navigator = navigatorFragment ?: return
        
        lifecycleScope.launch {
            navigator.currentLocator
                .sample(1500)
                .collectLatest {
                    val json = LocatorJsonHelper.toJson(it) ?: return@collectLatest
                    val key = viewModel.currentBookKey.value ?: return@collectLatest

                    // Local Prefs
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString("progress_$key", json)
                        .apply()
                    
                    // ViewModel (Server/DB)
                    viewModel.saveProgress(json)

                     val bookId = intent.data?.toString() 
                     if (bookId != null) {
                         viewModel.saveProgressToDb(bookId, json)
                     }

                    EInkHelper.refresh(binding.root)
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

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("progress_$key", json)
            .apply()
            
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
                EInkHelper.refresh(binding.root)
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
            
            layout.addView(btnVerify, layout.childCount - 2)
            layout.addView(btnScan, layout.childCount - 2)
        }

        tvFontSize.text = "$currentFontSize%"
        switchPageTap.isChecked = pageTapEnabled

        // Load current URL
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUrl = prefs.getString("server_base_url", HttpConfig.DEFAULT_BASE_URL)
        etServerUrl.setText(currentUrl)

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
            }
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

        btnSettingsAddBookmark.setOnClickListener { addBookmarkFromCurrentPosition() }
        btnSettingsShowBookmarks.setOnClickListener { openBookmarkList() }

        switchPageTap.setOnCheckedChangeListener { _, isChecked ->
            pageTapEnabled = isChecked
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("page_tap_enabled", isChecked).apply()
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
        lifecycleScope.launch {
             try {
                val newPreferences = org.readium.r2.navigator.epub.EpubPreferences(
                    fontSize = sizePercent / 100.0,
                    publisherStyles = false,
                    lineHeight = 1.4,
                    pageMargins = 1.5
                )
                navigator.submitPreferences(newPreferences)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Action Mode ---
    private val selectionActionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            currentActionMode = mode
            // Haptic feedback to confirm selection initiation
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            
            // Use lifecycleScope to safely delay, preventing memory leaks if activity dies
            lifecycleScope.launch {
                kotlinx.coroutines.delay(120)
                if (mode != null) {
                    if (menu?.findItem(998) == null) {
                        // Order 1: Copy (First)
                        menu?.add(Menu.NONE, 998, 1, "複製")
                            ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                    if (menu?.findItem(999) == null) {
                        // Order 2: Publish (Second)
                        menu?.add(Menu.NONE, 999, 2, "發佈")
                            ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                    mode.invalidate()
                }
            }
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
        
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
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode?) {
            currentActionMode = null
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
        // Ignore touches on the bottom bar to allow button clicks
        val bottomBarRect = android.graphics.Rect()
        binding.bottomBar.getGlobalVisibleRect(bottomBarRect)
        if (bottomBarRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            return super.dispatchTouchEvent(ev)
        }

        // Always track tap coordinates for selection dismissal
        if (ev.pointerCount == 1) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Enable Fast Mode for potential drag/scroll/selection
                    EInkHelper.enableFastMode(binding.root)
                    
                    tapDownTime = ev.downTime
                    tapDownX = ev.x
                    tapDownY = ev.y
                    potentialPageTap = true 
                }
                MotionEvent.ACTION_UP -> {
                    // Restore Quality Mode when interaction ends
                    EInkHelper.restoreQualityMode(binding.root)

                    // 1. Priority: Click-outside-to-deselect (Global)
                    if (currentActionMode != null) {
                        val dx = kotlin.math.abs(ev.x - tapDownX)
                        val dy = kotlin.math.abs(ev.y - tapDownY)
                        // Removed duration check to allow slow taps/e-ink latency to still dismiss
                        
                        if (dx <= touchSlop && dy <= touchSlop) {
                            currentActionMode?.finish()
                            lifecycleScope.launch {
                                try { navigatorFragment?.clearSelection() } catch (_: Exception) {}
                            }
                            
                            // Send CANCEL to child views to ensure they reset state (e.g. cancel pending selections)
                            val cancelEvent = MotionEvent.obtain(ev)
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                            
                            return true // Consume event to prevent new selection
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    EInkHelper.restoreQualityMode(binding.root)
                }
            }
        }

        if (pageTapEnabled && ev.pointerCount == 1) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (potentialPageTap) {
                        val dx = kotlin.math.abs(ev.x - tapDownX)
                        val dy = kotlin.math.abs(ev.y - tapDownY)
                        if (dx > touchSlop || dy > touchSlop) {
                            potentialPageTap = false
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> potentialPageTap = false
                MotionEvent.ACTION_UP -> {
                    if (potentialPageTap) {
                        // (Selection dismissal already handled above if active)
                        if (currentActionMode != null) {
                             potentialPageTap = false
                        } else {
                            val duration = ev.eventTime - tapDownTime
                            // Relaxed tap timeout (500ms) to accommodate slower e-ink taps
                            if (duration <= 500) {
                                val width = binding.root.width
                                if (width > 0) {
                                    val x = ev.x
                                    // 30-40-30 Rule for Page Turning (Wider zones)
                                    if (x < width * 0.3f) {
                                        // Left 30% -> Previous Page
                                        val cancelEvent = MotionEvent.obtain(ev)
                                        cancelEvent.action = MotionEvent.ACTION_CANCEL
                                        super.dispatchTouchEvent(cancelEvent)
                                        cancelEvent.recycle()
                                        navigatorFragment?.goBackward()
                                        potentialPageTap = false
                                        return true
                                    } else if (x > width * 0.7f) {
                                        // Right 30% -> Next Page
                                        val cancelEvent = MotionEvent.obtain(ev)
                                        cancelEvent.action = MotionEvent.ACTION_CANCEL
                                        super.dispatchTouchEvent(cancelEvent)
                                        cancelEvent.recycle()
                                        navigatorFragment?.goForward()
                                        potentialPageTap = false
                                        return true
                                    }
                                    // Center 40% -> Pass through (do nothing here, let super handle it)
                                }
                            }
                        }
                    }
                    potentialPageTap = false
                }
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

