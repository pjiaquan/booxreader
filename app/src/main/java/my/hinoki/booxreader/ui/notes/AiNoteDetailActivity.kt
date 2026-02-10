package my.hinoki.booxreader.ui.notes

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.databinding.ActivityAiNoteDetailBinding

class AiNoteDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"

        private const val EXTRA_AUTO_STREAM_TEXT = "extra_auto_stream_text"
        private const val EXTRA_SOURCE_NOTE_ID = "extra_source_note_id"
        private const val EXTRA_LINK_DEPTH = "extra_link_depth"
        private const val MAX_BI_LINK_DEPTH = 6
        private const val RECOMMENDED_TAG_LIMIT = 3
        private const val TAG_SCORE_SIGNAL_THRESHOLD = 0.01
        private const val TAG_SCORE_LABEL_EXACT_HIT = 6.0
        private const val TAG_SCORE_KEYWORD_HIT = 1.15
        private const val TAG_SCORE_NGRAM_JACCARD_WEIGHT = 4.5
        private const val TAG_KEYWORD_MAX_COUNT = 8

        fun open(
                context: Context,
                noteId: Long,
                autoStreamText: String? = null,
                sourceNoteId: Long? = null,
                linkDepth: Int = 0
        ) {
            val intent =
                    Intent(context, AiNoteDetailActivity::class.java).apply {
                        putExtra(EXTRA_NOTE_ID, noteId)
                        if (autoStreamText != null) {
                            putExtra(EXTRA_AUTO_STREAM_TEXT, autoStreamText)
                        }
                        if (sourceNoteId != null) {
                            putExtra(EXTRA_SOURCE_NOTE_ID, sourceNoteId)
                        }
                        putExtra(EXTRA_LINK_DEPTH, linkDepth.coerceAtLeast(0))
                    }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityAiNoteDetailBinding
    private val syncRepo by lazy { UserSyncRepository(applicationContext) }
    private val repository by lazy {
        val app = applicationContext as my.hinoki.booxreader.BooxReaderApp
        AiNoteRepository(app, app.okHttpClient, syncRepo)
    }
    private var currentNote: AiNoteEntity? = null
    private var sourceNoteId: Long? = null
    private var linkDepth: Int = 0
    private val markwon by lazy {
        Markwon.builder(this)
                .usePlugin(TablePlugin.create(this))
                .usePlugin(JLatexMathPlugin.create(binding.tvAiResponse.textSize))
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                        object : AbstractMarkwonPlugin() {
                            override fun configureConfiguration(
                                    builder: MarkwonConfiguration.Builder
                            ) {
                                builder.linkResolver { _, link ->
                                    if (link.startsWith("booxreader://ai-note")) {
                                        openRelatedNoteByLink(link)
                                    } else {
                                        runCatching {
                                                    startActivity(
                                                            Intent(
                                                                    Intent.ACTION_VIEW,
                                                                    Uri.parse(link)
                                                            )
                                                    )
                                                }
                                                .onFailure {
                                                    Toast.makeText(
                                                                    this@AiNoteDetailActivity,
                                                                    getString(
                                                                            R.string
                                                                                    .action_web_search_failed
                                                                    ),
                                                                    Toast.LENGTH_SHORT
                                                            )
                                                            .show()
                                                }
                                    }
                                }
                            }
                        }
                )
                .build()
    }
    private var autoStreamText: String? = null
    private val selectionSanitizeRegex = Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$")
    private val wrappedStrongWithBoundaryPunctuationRegex =
            Regex(
                    """\*\*([“”"'‘’「」『』《》〈〉【】〔〕（）()\[\]{}])([^*\n]+?)([“”"'‘’「」『』《》〈〉【】〔〕（）()\[\]{}])\*\*"""
            )
    private var isLoading = false
    private var scrollToBottomButton: FloatingActionButton? = null
    private var streamingRenderJob: Job? = null
    private var pendingStreamingMarkdown: String? = null
    private var lastRenderAtMs: Long = 0L
    private var lastRenderedLength: Int = 0
    private var lastRenderedMarkdown: String? = null
    private var userPausedAutoScroll: Boolean = false
    private val idleScrollIconAlpha = 0.45f
    private val activeScrollIconAlpha = 1f
    private var isScrollButtonPressed = false
    private var isScrollButtonHovered = false
    private var scrollButtonHideJob: Job? = null
    private var relatedNotesJob: Job? = null
    private var lastRelatedLookupKey: String? = null
    private var selectionActionMode: ActionMode? = null
    private val settingsPrefs by lazy {
        getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val settingsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    "magic_tags" -> setupMagicTags()
                    "contrast_mode" -> applyThemeFromSettings()
                }
            }
    private var magicTagTextColor: Int = Color.BLACK
    private var magicTagBackgroundColor: Int = Color.parseColor("#E6E0D6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyActionBarPadding(binding.scrollView)
        applyThemeFromSettings()
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)

        // Handle keyboard (IME) and system bar insets manually
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = if (ime.bottom > 0) ime.bottom else systemBars.bottom)
            windowInsets
        }

        // Set custom selection action mode for TextViews
        binding.tvOriginalText.customSelectionActionModeCallback = selectionActionModeCallback
        binding.tvAiResponse.customSelectionActionModeCallback = selectionActionModeCallback
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        autoStreamText = intent.getStringExtra(EXTRA_AUTO_STREAM_TEXT)
        val sourceId = intent.getLongExtra(EXTRA_SOURCE_NOTE_ID, -1L)
        sourceNoteId = sourceId.takeIf { it > 0L && it != noteId }
        linkDepth = intent.getIntExtra(EXTRA_LINK_DEPTH, 0).coerceAtLeast(0)
        if (noteId == -1L) {
            Toast.makeText(this, getString(R.string.ai_note_invalid_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadNote(noteId)
        setupMagicTags()

        binding.btnPublish.setOnClickListener {
            val note = currentNote ?: return@setOnClickListener
            publishNote(note)
        }

        binding.btnRepublishSelection.setOnClickListener {
            val note = currentNote ?: return@setOnClickListener
            rePublishSelection(note)
        }

        binding.btnFollowUp.setOnClickListener {
            val note = currentNote ?: return@setOnClickListener
            val question = binding.etFollowUp.text.toString().trim()
            if (question.isEmpty()) {
                Toast.makeText(
                                this,
                                getString(R.string.ai_note_follow_up_hint_input),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                return@setOnClickListener
            }
            sendFollowUp(note, question)
        }
        binding.btnBackToLinkedNote.setOnClickListener {
            val fromId = sourceNoteId ?: return@setOnClickListener
            val currentId = currentNote?.id ?: return@setOnClickListener
            if (fromId == currentId) return@setOnClickListener
            val nextDepth = (linkDepth + 1).coerceAtMost(MAX_BI_LINK_DEPTH)
            AiNoteDetailActivity.open(
                    this@AiNoteDetailActivity,
                    fromId,
                    sourceNoteId = currentId,
                    linkDepth = nextDepth
            )
        }

        // 初始化快速滾動到底按鈕
        scrollToBottomButton = findViewById(R.id.btnScrollToBottom)
        scrollToBottomButton?.setOnClickListener { scrollToBottom() }
        scrollToBottomButton?.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isScrollButtonPressed = true
                    updateScrollButtonAppearance()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isScrollButtonPressed = false
                    updateScrollButtonAppearance()
                }
            }
            false
        }
        scrollToBottomButton?.setOnHoverListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                    isScrollButtonHovered = true
                    updateScrollButtonAppearance()
                }
                android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                    isScrollButtonHovered = false
                    updateScrollButtonAppearance()
                }
            }
            false
        }
        updateScrollButtonAppearance()

        // 設置滾動監聽來控制按鈕顯示/隱藏
        setupScrollListener()
    }

    override fun onResume() {
        super.onResume()
        applyThemeFromSettings()
        setupMagicTags()
    }

    override fun onDestroy() {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        relatedNotesJob?.cancel()
        super.onDestroy()
    }

    private fun applyThemeFromSettings() {
        val settings = ReaderSettings.fromPrefs(settingsPrefs)
        val mode = ContrastMode.values().getOrNull(settings.contrastMode) ?: ContrastMode.NORMAL
        applyContrastMode(mode)
    }

    private fun applyContrastMode(mode: ContrastMode) {
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
        val secondaryTextColor = ColorUtils.setAlphaComponent(textColor, 170)
        val hintColor = ColorUtils.setAlphaComponent(textColor, 140)

        magicTagTextColor = textColor
        magicTagBackgroundColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#E6E0D6")
                    ContrastMode.DARK -> Color.parseColor("#1F1F1F")
                    ContrastMode.SEPIA -> Color.parseColor("#E6D9BE")
                    ContrastMode.HIGH_CONTRAST -> Color.parseColor("#202020")
                }

        binding.root.setBackgroundColor(backgroundColor)
        binding.scrollView.setBackgroundColor(backgroundColor)
        binding.llInputArea.setBackgroundColor(backgroundColor)

        binding.tvOriginalLabel.setTextColor(textColor)
        binding.tvResponseLabel.setTextColor(textColor)
        binding.tvOriginalText.setTextColor(textColor)
        binding.tvAiResponse.setTextColor(textColor)
        binding.tvAiModelInfo.setTextColor(secondaryTextColor)
        binding.tvAiDisclaimer.setTextColor(secondaryTextColor)
        binding.tvAiInputDisclaimer.setTextColor(secondaryTextColor)
        binding.tvRelatedNotesLabel.setTextColor(textColor)
        binding.tvRelatedNotesHint.setTextColor(secondaryTextColor)
        binding.tvRelatedNotesStatus.setTextColor(secondaryTextColor)
        binding.tvAutoScrollHint.setTextColor(secondaryTextColor)
        binding.etFollowUp.setTextColor(textColor)
        binding.etFollowUp.setHintTextColor(hintColor)

        updateMagicTagStyles()

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

    private fun updateMagicTagStyles() {
        for (i in 0 until binding.cgMagicTags.childCount) {
            val view = binding.cgMagicTags.getChildAt(i)
            if (view is Chip) {
                view.setTextColor(magicTagTextColor)
                view.chipBackgroundColor = ColorStateList.valueOf(magicTagBackgroundColor)
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN && selectionActionMode != null) {
            val insideOriginal = isTouchInsideView(binding.tvOriginalText, ev)
            val insideResponse = isTouchInsideView(binding.tvAiResponse, ev)
            if (!insideOriginal && !insideResponse) {
                clearTextSelection()
                selectionActionMode?.finish()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private val selectionActionModeCallback =
            object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(
                        mode: android.view.ActionMode?,
                        menu: android.view.Menu?
                ): Boolean {
                    selectionActionMode = mode
                    menu?.add(android.view.Menu.NONE, 999, 0, getString(R.string.action_publish))
                    menu?.add(
                            android.view.Menu.NONE,
                            1000,
                            1,
                            getString(R.string.action_publish_follow_up)
                    )
                    menu?.add(
                            android.view.Menu.NONE,
                            1001,
                            2,
                            getString(R.string.action_map_search)
                    )
                    menu?.add(
                            android.view.Menu.NONE,
                            1002,
                            3,
                            getString(R.string.action_google_search)
                    )
                    return true
                }

                override fun onPrepareActionMode(
                        mode: android.view.ActionMode?,
                        menu: android.view.Menu?
                ): Boolean {
                    return false
                }

                override fun onActionItemClicked(
                        mode: android.view.ActionMode?,
                        item: android.view.MenuItem?
                ): Boolean {
                    if (item?.itemId == 999) {
                        handleSelectionAction { selectedText ->
                            createAndPublishNewNote(selectedText)
                        }
                        return true
                    }
                    if (item?.itemId == 1000) {
                        handleSelectionAction { selectedText ->
                            promptFollowUpPublish(selectedText)
                        }
                        return true
                    }
                    if (item?.itemId == 1001) {
                        handleSelectionAction { selectedText -> openMapSearch(selectedText) }
                        return true
                    }
                    if (item?.itemId == 1002) {
                        handleSelectionAction { selectedText -> openWebSearch(selectedText) }
                        return true
                    }
                    return false
                }

                override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                    selectionActionMode = null
                }
            }

    private fun clearTextSelection() {
        if (binding.tvOriginalText.hasSelection()) {
            val text = binding.tvOriginalText.text
            if (text is Spannable) {
                Selection.removeSelection(text)
            }
            binding.tvOriginalText.clearFocus()
            binding.tvOriginalText.cancelLongPress()
            binding.tvOriginalText.clearComposingText()
        }
        if (binding.tvAiResponse.hasSelection()) {
            val text = binding.tvAiResponse.text
            if (text is Spannable) {
                Selection.removeSelection(text)
            }
            binding.tvAiResponse.clearFocus()
            binding.tvAiResponse.cancelLongPress()
            binding.tvAiResponse.clearComposingText()
        }
    }

    private fun isTouchInsideView(view: View, event: android.view.MotionEvent): Boolean {
        val rect = android.graphics.Rect()
        view.getGlobalVisibleRect(rect)
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        return rect.contains(x, y)
    }

    private fun handleSelectionAction(onSelected: (String) -> Unit) {
        val tv =
                if (binding.tvOriginalText.hasSelection()) binding.tvOriginalText
                else binding.tvAiResponse

        if (tv.isFocused && tv.hasSelection()) {
            val min = tv.selectionStart
            val max = tv.selectionEnd
            val selectedText = tv.text.subSequence(min, max).toString()

            if (selectedText.isNotBlank()) {
                val trimmedText = selectedText.replace(selectionSanitizeRegex, "")
                if (trimmedText.isNotBlank()) {
                    onSelected(trimmedText)
                } else {
                    Toast.makeText(
                                    this,
                                    getString(R.string.action_selection_empty),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }

            // Clear focus to reduce accidental double-selections
            tv.clearFocus()
            tv.cancelLongPress()
        }
    }

    private fun createAndPublishNewNote(text: String) {

        lifecycleScope.launch {
            // 0. Check existence
            val existingNote = repository.findNoteByText(text)
            if (existingNote != null) {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_note_found),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, existingNote.id)
                return@launch
            }

            Toast.makeText(
                            this@AiNoteDetailActivity,
                            getString(R.string.ai_note_publishing_selection),
                            Toast.LENGTH_SHORT
                    )
                    .show()

            // 1. Save Draft
            // Use current note's bookId if available
            val bookId = currentNote?.bookId
            val newNoteId =
                    repository.add(
                            bookId = bookId,
                            originalText = text,
                            aiResponse = "",
                            bookTitle = currentNote?.bookTitle
                    )

            // 2. Fetch
            val useStreaming = repository.isStreamingEnabled()
            if (useStreaming) {
                val note = repository.getById(newNoteId)
                if (note != null) {
                    currentNote = note
                    updateUI(note)
                    startStreaming(note, text)
                }
                return@launch
            }

            val result = repository.fetchAiExplanation(text)

            if (result != null) {
                val (serverText, content) = result
                val note = repository.getById(newNoteId)
                if (note != null) {
                    val updatedNote = updateNoteFromStrings(note, serverText, content)
                    repository.update(updatedNote)
                }
                showRemainingCreditsToast()
                // Open the NEW note
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, newNoteId)
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_saved_draft_network_error),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                // Open the NEW draft
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, newNoteId)
            }
        }
    }

    private fun loadNote(noteId: Long) {
        lifecycleScope.launch {
            val note = repository.getById(noteId)
            if (note != null) {
                currentNote = note
                updateUI(note)
                val shouldAutoStream = autoStreamText != null && getAiResponse(note).isBlank()
                if (shouldAutoStream) {
                    autoStreamText?.let { text ->
                        binding.btnPublish.isEnabled = false
                        binding.btnPublish.text = getString(R.string.ai_note_streaming)
                        setLoading(true)
                        startStreaming(note, text)
                    }
                } else {
                    triggerRelatedNotesLookup(note, force = true)
                }
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_not_found),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                finish()
            }
        }
    }

    private fun updateUI(
            note: AiNoteEntity,
            scrollToQuestionHeader: Boolean = false,
            preserveScroll: Boolean = true
    ) {
        // Capture current scroll to avoid jumping when we re-render markdown
        val previousScrollY =
                if (!scrollToQuestionHeader && preserveScroll) {
                    binding.scrollView.scrollY
                } else {
                    null
                }

        val originalText = normalizeMarkdownForDisplay(getOriginalText(note))
        markwon.setMarkdown(binding.tvOriginalText, originalText)

        val aiResponse = normalizeMarkdownForDisplay(getAiResponse(note))

        if (aiResponse.isBlank()) {
            binding.tvAiResponse.text = getString(R.string.ai_note_draft_status)
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnPublish.isEnabled = true
            binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            binding.btnRepublishSelection.isEnabled = false
            clearRelatedNotes()
            setLoading(false)
        } else {
            markwon.setMarkdown(binding.tvAiResponse, aiResponse)
            binding.btnPublish.visibility = View.GONE
            binding.btnRepublishSelection.isEnabled = true
        }

        // Update AI model info and disclaimer
        val settings =
                my.hinoki.booxreader.data.settings.ReaderSettings.fromPrefs(
                        getSharedPreferences(
                                my.hinoki.booxreader.data.settings.ReaderSettings.PREFS_NAME,
                                Context.MODE_PRIVATE
                        )
                )
        binding.tvAiModelInfo.text =
                getString(R.string.ai_note_model_info_format, settings.aiModelName)
        binding.tvAiDisclaimer.text = getString(R.string.ai_note_model_disclaimer)

        if (!note.locatorJson.isNullOrBlank() && !note.bookId.isNullOrBlank()) {
            binding.btnGoToPage.visibility = View.VISIBLE
            binding.btnGoToPage.setOnClickListener {
                my.hinoki.booxreader.ui.reader.ReaderActivity.open(
                        this@AiNoteDetailActivity,
                        note.bookId,
                        note.bookTitle,
                        note.locatorJson
                )
            }
        } else {
            binding.btnGoToPage.visibility = View.GONE
        }
        val shouldShowBackToLinked =
                sourceNoteId != null && sourceNoteId != note.id && linkDepth < MAX_BI_LINK_DEPTH
        binding.btnBackToLinkedNote.visibility =
                if (shouldShowBackToLinked) View.VISIBLE else View.GONE

        if (scrollToQuestionHeader) {
            binding.scrollView.post {
                val layout = binding.tvAiResponse.layout ?: return@post
                val content = binding.tvAiResponse.text.toString()
                var idx = content.lastIndexOf("Q:")
                if (idx < 0) {
                    idx = content.length
                }
                val line = layout.getLineForOffset(idx.coerceAtMost(content.length))
                val y = binding.tvAiResponse.top + layout.getLineTop(line)
                binding.scrollView.smoothScrollTo(0, y)
                // 滾動完成後檢查按鈕狀態
                checkScrollPosition()
            }
        } else if (previousScrollY != null) {
            binding.scrollView.post {
                binding.scrollView.scrollTo(0, previousScrollY)
                // 滾動完成後檢查按鈕狀態
                checkScrollPosition()
            }
        } else {
            // UI更新完成後檢查按鈕狀態
            binding.scrollView.post { checkScrollPosition() }
        }
    }

    private fun publishNote(note: AiNoteEntity) {
        val savedScrollY = currentScrollY()
        binding.btnPublish.isEnabled = false
        binding.btnPublish.text = getString(R.string.ai_note_publishing)
        binding.btnRepublishSelection.isEnabled = false
        setLoading(true)

        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()
            val originalText = getOriginalText(note)
            if (useStreaming) {
                startStreaming(note, originalText, savedScrollY)
                binding.btnPublish.text = getString(R.string.ai_note_streaming)
                return@launch
            }

            val result = repository.fetchAiExplanation(originalText)
            if (result != null) {
                val (serverText, content) = result
                val updatedNote = updateNoteFromStrings(note, serverText, content)
                repository.update(updatedNote)
                currentNote = updatedNote
                updateUI(updatedNote)
                triggerRelatedNotesLookup(updatedNote, force = true)
                showRemainingCreditsToast()
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_published),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_publish_failed),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            }
            binding.btnRepublishSelection.isEnabled = true
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    private fun sendFollowUp(note: AiNoteEntity, question: String, magicTag: MagicTag? = null) {
        binding.btnFollowUp.isEnabled = false
        binding.btnFollowUp.text = getString(R.string.ai_note_follow_up_publishing)
        setLoading(true)

        lifecycleScope.launch {
            try {
                val useStreaming = repository.isStreamingEnabled()
                val currentAiResponse = getAiResponse(note)
                val result =
                        if (useStreaming) {
                            repository.continueConversationStreaming(note, question, magicTag) {
                                    partial ->
                                val separator = if (currentAiResponse.isBlank()) "" else "\n\n"
                                val preview =
                                        currentAiResponse +
                                                separator +
                                                "---\nQ: " +
                                                question +
                                                "\n\n" +
                                                partial
                                renderStreamingMarkdown(preview, isFollowUp = true)
                                // restoreScrollIfJumped(savedScrollY)
                            }
                        } else {
                            repository.continueConversation(note, question, magicTag)
                        }
                clearStreamingRenderer()
                if (result != null) {
                    val separator = if (currentAiResponse.isBlank()) "" else "\n\n"
                    val newContent =
                            currentAiResponse + separator + "---\nQ: " + question + "\n\n" + result
                    val updated = updateNoteFromStrings(note, null, newContent)
                    repository.update(updated)
                    currentNote = updated
                    // Avoid jumping the viewport after publish to keep the reader in place.
                    updateUI(updated, scrollToQuestionHeader = false, preserveScroll = false)
                    triggerRelatedNotesLookup(updated, force = true)
                    showRemainingCreditsToast()
                    binding.etFollowUp.setText("")
                } else {
                    Toast.makeText(
                                    this@AiNoteDetailActivity,
                                    getString(R.string.ai_note_follow_up_failed),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }
            } finally {
                binding.btnFollowUp.isEnabled = true
                binding.btnFollowUp.text = getString(R.string.ai_note_follow_up_button)
                setLoading(false)
                // restoreScrollIfJumped(savedScrollY)
            }
        }
    }

    private fun rePublishSelection(note: AiNoteEntity) {
        val savedScrollY = currentScrollY()
        binding.btnRepublishSelection.isEnabled = false
        binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_progress)
        setLoading(true)
        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()
            val originalText = getOriginalText(note)
            if (useStreaming) {
                startStreaming(note, originalText, savedScrollY)
                binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
                return@launch
            }

            val result = repository.fetchAiExplanation(originalText)
            if (result != null) {
                val (serverText, content) = result
                val updatedNote = updateNoteFromStrings(note, serverText, content)
                repository.update(updatedNote)
                currentNote = updatedNote
                updateUI(updatedNote)
                triggerRelatedNotesLookup(updatedNote, force = true)
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_republish_success),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_republish_failed),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    private fun promptFollowUpPublish(selectedText: String) {
        val note = currentNote
        if (note == null) {
            Toast.makeText(this, "No note loaded", Toast.LENGTH_SHORT).show()
            return
        }

        // Directly send follow-up without dialog; mimic bottom input "Send"
        val question = selectedText.trim()
        if (question.isNotEmpty()) {
            sendFollowUp(note, question)
        } else {
            Toast.makeText(this, getString(R.string.ai_note_follow_up_empty), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    private fun startStreaming(
            note: AiNoteEntity,
            text: String,
            preserveScrollY: Int? = null,
            magicTag: MagicTag? = null
    ) {
        setLoading(true)
        val savedScrollY = preserveScrollY ?: currentScrollY()
        lifecycleScope.launch {
            val result =
                    repository.fetchAiExplanationStreaming(text, magicTag) { partial ->
                        renderStreamingMarkdown(partial)
                    }
            clearStreamingRenderer()
            if (result != null) {
                val (serverText, content) = result
                val updated = updateNoteFromStrings(note, serverText, content)
                repository.update(updated)
                currentNote = updated
                updateUI(updated)
                triggerRelatedNotesLookup(updated, force = true)
                showRemainingCreditsToast()
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_streaming_failed),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    private fun showRemainingCreditsToast() {
        lifecycleScope.launch {
            val credits = repository.fetchRemainingCredits()
            if (credits != null) {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_credits_left_toast, credits),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
    }

    private fun openMapSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, getString(R.string.action_selection_empty), Toast.LENGTH_SHORT)
                    .show()
            return
        }
        val encoded = Uri.encode(trimmed)
        val intent =
                Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")
                )
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.action_map_search_failed), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    private fun openWebSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, getString(R.string.action_selection_empty), Toast.LENGTH_SHORT)
                    .show()
            return
        }
        val encoded = Uri.encode(trimmed)
        val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, getString(R.string.action_web_search_failed), Toast.LENGTH_SHORT)
                    .show()
        }
    }

    // 設置滾動監聽
    private fun setupScrollListener() {
        binding.scrollView.viewTreeObserver.addOnScrollChangedListener {
            // 檢查滾動位置並控制按鈕顯示/隱藏
            checkScrollPosition()
        }
    }

    // 滾動到底功能
    private fun scrollToBottom() {

        // 使用多種方法確保滾動到底部
        binding.scrollView.post {
            val scrollView = binding.scrollView
            val childView = scrollView.getChildAt(0)

            if (childView != null) {
                val visibleHeight =
                        scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
                val maxScroll = (childView.height - visibleHeight).coerceAtLeast(0)
                // 方法1: 直接設置滾動位置

                // 先嘗試直接滾動
                scrollView.scrollTo(0, maxScroll)

                // 延遲後再次確保滾動到底部
                scrollView.postDelayed(
                        {
                            // 方法2: 使用 fullScroll 作為備用
                            scrollView.fullScroll(View.FOCUS_DOWN)

                            // 方法3: 再次直接設置滾動位置（最終保險）
                            val finalVisibleHeight =
                                    scrollView.height -
                                            scrollView.paddingTop -
                                            scrollView.paddingBottom
                            val finalMaxScroll =
                                    (childView.height - finalVisibleHeight).coerceAtLeast(0)
                            scrollView.scrollTo(0, finalMaxScroll)

                            // 滾動完成後隱藏按鈕
                            hideScrollButton()

                            scrollView.postInvalidateOnAnimation()
                        },
                        100
                )
            }
        }
    }

    // 檢查滾動位置並控制按鈕顯示/隱藏
    private fun checkScrollPosition() {
        binding.scrollView.post {
            val scrollView = binding.scrollView
            val childView = scrollView.getChildAt(0)

            if (childView != null) {
                // 檢查頁面內容是否超過螢幕高度（需要滾動）
                val contentHeight = childView.height
                val visibleHeight =
                        scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
                val needsScroll = contentHeight > visibleHeight

                if (!needsScroll) {
                    // 頁面內容不夠長，不需要滾動按鈕
                    hideScrollButton()
                    return@post
                }

                // 檢查是否已經滾動到底部
                val isAtBottom =
                        scrollView.scrollY + visibleHeight >=
                                contentHeight - scrollView.paddingBottom

                if (isAtBottom) {
                    // 在底部時隱藏按鈕
                    hideScrollButton()
                    if (userPausedAutoScroll) {
                        userPausedAutoScroll = false
                    }
                } else {
                    // 不在底部時顯示按鈕
                    showScrollButton()
                    scheduleScrollButtonAutoHide()
                    if (isLoading) {
                        userPausedAutoScroll = true
                    }
                }
                updateAutoScrollHint()
                updateScrollButtonAppearance()
            }
        }
    }

    // 顯示滾動按鈕
    private fun showScrollButton() {
        scrollToBottomButton?.let { button ->
            if (button.visibility != View.VISIBLE) {
                button.visibility = View.VISIBLE
                button.alpha = 0f
                button.animate().alpha(1f).setDuration(300).start()
            }
        }
    }

    // 隱藏滾動按鈕
    private fun hideScrollButton() {
        scrollToBottomButton?.let { button ->
            if (button.visibility == View.VISIBLE) {
                button.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { button.visibility = View.GONE }
                        .start()
            }
        }
        scrollButtonHideJob?.cancel()
        scrollButtonHideJob = null
    }

    private fun currentScrollY(): Int = binding.scrollView.scrollY

    // Keep the reader's position if the system jumps to the top or bottom after updates.
    private fun restoreScrollIfJumped(targetY: Int?) {
        if (targetY == null) return
        binding.scrollView.post {
            val scrollView = binding.scrollView
            val child = scrollView.getChildAt(0)
            val maxScroll =
                    ((child?.height ?: 0) - scrollView.height + scrollView.paddingBottom)
                            .coerceAtLeast(0)
            val clampedTarget = targetY.coerceIn(0, maxScroll)
            val current = scrollView.scrollY

            // Only fix if it weirdly jumped to top (common issue with text updates)
            // We do NOT fix "jumping to bottom" because the user might have scrolled there manually
            // to read.
            val jumpedToTop = clampedTarget > 0 && current < clampedTarget - 48 && current < 64

            if (jumpedToTop) {
                scrollView.scrollTo(0, clampedTarget)
                checkScrollPosition()
            }
        }
    }

    private fun renderStreamingMarkdown(
            markdown: String,
            force: Boolean = false,
            isFollowUp: Boolean = false
    ) {
        if (!force && markdown == lastRenderedMarkdown) return
        pendingStreamingMarkdown = markdown
        streamingRenderJob?.cancel()

        val priorScrollY = binding.scrollView.scrollY
        val wasAtBottom = isAtBottom()
        val nowMs = SystemClock.uptimeMillis()
        val minIntervalMs = 300L
        val minCharDelta = if (isFollowUp) 128 else 64

        val lengthDelta = kotlin.math.abs(markdown.length - lastRenderedLength)
        val intervalPassed = (nowMs - lastRenderAtMs) >= minIntervalMs

        if (!force && !intervalPassed && lengthDelta < minCharDelta) return

        streamingRenderJob =
                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        // Parsing is pure logic, usually safe on BG
                        val normalizedMarkdown = normalizeMarkdownForDisplay(markdown)
                        val node = markwon.parse(normalizedMarkdown)

                        // Rendering (creating Spans) might touch UI resources/plugins, safer on
                        // Main
                        withContext(Dispatchers.Main) {
                            val spanned = markwon.render(node)

                            if (pendingStreamingMarkdown == markdown) {
                                val currentAtBottom = isAtBottom()
                                binding.tvAiResponse.text = spanned
                                lastRenderedMarkdown = markdown
                                lastRenderedLength = markdown.length
                                lastRenderAtMs = SystemClock.uptimeMillis()

                                binding.scrollView.post {
                                    if (currentAtBottom && !userPausedAutoScroll) {
                                        scrollToBottomImmediate()
                                    } else if (!userPausedAutoScroll && !isFollowUp) {
                                        restoreScrollIfJumped(priorScrollY)
                                    }
                                    checkScrollPosition()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
    }

    private fun clearStreamingRenderer() {
        streamingRenderJob?.cancel()
        streamingRenderJob = null
        pendingStreamingMarkdown = null
    }

    private fun normalizeMarkdownForDisplay(markdown: String): String {
        if (!markdown.contains("**")) return markdown
        var normalized = markdown
        repeat(4) {
            val updated =
                    wrappedStrongWithBoundaryPunctuationRegex.replace(normalized) { match ->
                        val leading = match.groupValues[1]
                        val core = match.groupValues[2]
                        val trailing = match.groupValues[3]
                        "$leading**$core**$trailing"
                    }
            if (updated == normalized) return updated
            normalized = updated
        }
        return normalized
    }

    private fun scrollToBottomImmediate() {
        val scrollView = binding.scrollView
        val childView = scrollView.getChildAt(0) ?: return
        val visibleHeight = scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
        val maxScroll = (childView.height - visibleHeight).coerceAtLeast(0)
        scrollView.scrollTo(0, maxScroll)
    }

    private fun isLikelyMidTable(markdown: String): Boolean {
        val trimmed = markdown.trimEnd()
        if (trimmed.isEmpty()) return false
        val lastLine = trimmed.substringAfterLast('\n', trimmed).trim()
        val hasTablePipes = lastLine.count { it == '|' } >= 2
        val looksLikeHeaderSeparator = lastLine.contains("---")

        // If the last non-blank line looks like part of a table and there is no blank
        // line after it yet, wait for more content before re-rendering.
        return lastLine.isNotEmpty() && (hasTablePipes || looksLikeHeaderSeparator)
    }

    private fun isLikelyMidLatex(markdown: String): Boolean {
        if (markdown.isEmpty()) return false
        val blockDelimiterCount = Regex("(?<!\\\\)\\$\\$").findAll(markdown).count()
        val dollarCount = Regex("(?<!\\\\)\\$").findAll(markdown).count()
        val inlineDollarCount = (dollarCount - (blockDelimiterCount * 2)).coerceAtLeast(0)
        if (blockDelimiterCount % 2 != 0 || inlineDollarCount % 2 != 0) return true

        val openParenCount = Regex("\\\\\\(").findAll(markdown).count()
        val closeParenCount = Regex("\\\\\\)").findAll(markdown).count()
        if (openParenCount > closeParenCount) return true

        val openBracketCount = Regex("\\\\\\[").findAll(markdown).count()
        val closeBracketCount = Regex("\\\\\\]").findAll(markdown).count()
        return openBracketCount > closeBracketCount
    }

    private fun isAtBottom(): Boolean {
        val scrollView = binding.scrollView
        val child = scrollView.getChildAt(0) ?: return true
        val visibleHeight = scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
        val contentHeight = child.height
        return scrollView.scrollY + visibleHeight >= contentHeight - scrollView.paddingBottom - 8
    }

    private fun setLoading(active: Boolean) {
        isLoading = active
        binding.pbLoading.visibility = if (active) View.VISIBLE else View.GONE
        updateAutoScrollHint()
        updateScrollButtonAppearance()
    }

    private fun updateAutoScrollHint() {
        val shouldShow = isLoading && userPausedAutoScroll
        binding.tvAutoScrollHint.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun scheduleScrollButtonAutoHide() {
        scrollButtonHideJob?.cancel()
        scrollButtonHideJob =
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(2_000)
                    if (!isScrollButtonPressed) {
                        hideScrollButton()
                    }
                }
    }

    private fun updateScrollButtonAppearance() {
        val button = scrollToBottomButton ?: return
        val shouldBrighten =
                isScrollButtonPressed ||
                        isScrollButtonHovered ||
                        (isLoading && !userPausedAutoScroll)
        val baseColor =
                MaterialColors.getColor(
                        button,
                        com.google.android.material.R.attr.colorPrimary,
                        0xFF000000.toInt()
                )
        button.backgroundTintList = ColorStateList.valueOf(0x00000000)
        button.imageTintList = ColorStateList.valueOf(baseColor)
        val alpha = if (shouldBrighten) activeScrollIconAlpha else idleScrollIconAlpha
        button.imageAlpha = (alpha * 255).roundToInt()
    }

    private fun getOriginalText(note: AiNoteEntity): String {
        return note.originalText?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.originalTextFromMessages(note.messages).orEmpty()
    }

    private fun getAiResponse(note: AiNoteEntity): String {
        return note.aiResponse?.takeIf { it.isNotBlank() }
                ?: AiNoteSerialization.aiResponseFromMessages(note.messages).orEmpty()
    }

    private fun relatedLookupKey(note: AiNoteEntity): String {
        val response = getAiResponse(note)
        return "${note.id}:${note.updatedAt}:${response.hashCode()}"
    }

    private fun clearRelatedNotes() {
        relatedNotesJob?.cancel()
        relatedNotesJob = null
        lastRelatedLookupKey = null
        binding.tvRelatedNotesLabel.visibility = View.GONE
        binding.tvRelatedNotesHint.visibility = View.GONE
        binding.tvRelatedNotesStatus.visibility = View.GONE
        binding.tvRelatedNotesStatus.text = ""
        binding.llRelatedNotesContainer.visibility = View.GONE
        binding.llRelatedNotesContainer.removeAllViews()
    }

    private fun triggerRelatedNotesLookup(note: AiNoteEntity, force: Boolean = false) {
        val response = getAiResponse(note)
        if (response.isBlank()) {
            clearRelatedNotes()
            return
        }

        val key = relatedLookupKey(note)
        if (!force && key == lastRelatedLookupKey) return
        lastRelatedLookupKey = key

        binding.tvRelatedNotesLabel.visibility = View.VISIBLE
        binding.tvRelatedNotesHint.visibility = View.GONE
        binding.tvRelatedNotesStatus.visibility = View.VISIBLE
        binding.tvRelatedNotesLabel.setTextColor(Color.parseColor("#F6FAFF"))
        binding.tvRelatedNotesHint.setTextColor(Color.parseColor("#99ABC5"))
        binding.tvRelatedNotesStatus.setTextColor(Color.parseColor("#99ABC5"))
        binding.tvRelatedNotesStatus.text = getString(R.string.ai_note_related_loading)
        binding.llRelatedNotesContainer.visibility = View.GONE
        binding.llRelatedNotesContainer.removeAllViews()

        relatedNotesJob?.cancel()
        relatedNotesJob =
                lifecycleScope.launch {
                    val results = repository.searchRelatedNotesFromQdrant(note, limit = 5)
                    if (currentNote?.id != note.id) return@launch

                    binding.tvRelatedNotesLabel.visibility = View.VISIBLE
                    if (results.isEmpty()) {
                        binding.tvRelatedNotesHint.visibility = View.GONE
                        binding.tvRelatedNotesStatus.visibility = View.VISIBLE
                        binding.tvRelatedNotesStatus.text = getString(R.string.ai_note_related_empty)
                        binding.llRelatedNotesContainer.visibility = View.GONE
                        return@launch
                    }
                    binding.tvRelatedNotesHint.visibility = View.VISIBLE
                    binding.tvRelatedNotesStatus.visibility = View.GONE
                    renderRelatedNoteCards(results)
                }
    }

    private fun renderRelatedNoteCards(items: List<AiNoteRepository.SemanticRelatedNote>) {
        val container = binding.llRelatedNotesContainer
        container.removeAllViews()
        container.visibility = View.VISIBLE

        // Always render related-history cards in an elevated dark style for stronger contrast.
        val panelTop = Color.parseColor("#182233")
        val panelBottom = Color.parseColor("#0C111A")
        val cardBg = Color.parseColor("#1C2738")
        val cardBorder = Color.parseColor("#304562")
        val titleColor = Color.parseColor("#F6FAFF")
        val secondaryColor = Color.parseColor("#C9D4E5")
        val mutedColor = Color.parseColor("#99ABC5")
        val accentColor = Color.parseColor("#83D8FF")
        val actionColor = Color.parseColor("#B4E8FF")
        val pillColor = Color.parseColor("#294968")
        val snippetBg = Color.parseColor("#131D2B")

        binding.tvRelatedNotesLabel.setTextColor(titleColor)
        binding.tvRelatedNotesHint.setTextColor(mutedColor)
        binding.tvRelatedNotesStatus.setTextColor(mutedColor)
        container.setPadding(dp(10), dp(10), dp(10), dp(10))
        container.background =
                GradientDrawable(
                                GradientDrawable.Orientation.TOP_BOTTOM,
                                intArrayOf(panelTop, panelBottom)
                        )
                        .apply {
                            cornerRadius = dp(18).toFloat()
                            setStroke(dp(1), ColorUtils.setAlphaComponent(cardBorder, 170))
                        }

        items.forEachIndexed { index, item ->
            val title = item.bookTitle?.takeIf { it.isNotBlank() } ?: "Note ${index + 1}"
            val reason = item.reason ?: getString(R.string.ai_note_related_reason_default)
            val snippet =
                    (item.originalText ?: item.aiResponse)
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()
                            ?.take(130)
                            .orEmpty()
            val scorePercent =
                    ((item.score.coerceIn(0.0, 1.0) * 1000).roundToInt().toDouble() / 10.0)

            val card =
                    MaterialCardView(this).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            if (index > 0) topMargin = dp(12)
                                        }
                        radius = dp(18).toFloat()
                        strokeWidth = dp(1)
                        strokeColor = cardBorder
                        cardElevation = dp(0).toFloat()
                        setCardBackgroundColor(cardBg)
                        rippleColor =
                                ColorStateList.valueOf(
                                        ColorUtils.setAlphaComponent(accentColor, 70)
                                )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { openRelatedNote(item) }
                    }

            val content =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }

            val titleView =
                    TextView(this).apply {
                        text = title
                        setTextColor(titleColor)
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 17f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
            content.addView(titleView)

            val rankView =
                    TextView(this).apply {
                        text = "HISTORY MATCH  #${index + 1}"
                        setTextColor(mutedColor)
                        textSize = 11f
                        letterSpacing = 0.08f
                    }
            val rankParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply {
                                topMargin = dp(4)
                            }
            content.addView(rankView, rankParams)

            val metaView =
                    TextView(this).apply {
                        text = getString(R.string.ai_note_related_similarity, scorePercent)
                        setTextColor(accentColor)
                        textSize = 12f
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = pillBackground(pillColor)
                        setTypeface(typeface, Typeface.BOLD)
                    }
            val metaParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply {
                                topMargin = dp(8)
                            }
            content.addView(metaView, metaParams)

            val reasonView =
                    TextView(this).apply {
                        text = reason
                        setTextColor(secondaryColor)
                        textSize = 14f
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
            val reasonParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply {
                                topMargin = dp(8)
                            }
            content.addView(reasonView, reasonParams)

            if (snippet.isNotBlank()) {
                val snippetContainer =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            background =
                                    GradientDrawable().apply {
                                        shape = GradientDrawable.RECTANGLE
                                        cornerRadius = dp(12).toFloat()
                                        setColor(snippetBg)
                                        setStroke(
                                                dp(1),
                                                ColorUtils.setAlphaComponent(cardBorder, 140)
                                        )
                                    }
                        }
                val snippetView =
                        TextView(this).apply {
                            text = snippet
                            setTextColor(secondaryColor)
                            textSize = 13f
                            maxLines = 3
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setLineSpacing(dp(2).toFloat(), 1f)
                        }
                snippetContainer.addView(snippetView)
                val snippetParams =
                        LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                .apply {
                                    topMargin = dp(6)
                                }
                content.addView(snippetContainer, snippetParams)
            }

            val actionView =
                    TextView(this).apply {
                        text = getString(R.string.ai_note_related_open_action) + "  ›"
                        setTextColor(actionColor)
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 13f
                    }
            val actionParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply {
                                topMargin = dp(10)
                            }
            content.addView(actionView, actionParams)

            card.addView(content)
            container.addView(card)
        }

        val recommendedTags = recommendTagsForCurrentResponse(limit = RECOMMENDED_TAG_LIMIT)
        if (recommendedTags.isNotEmpty()) {
            val recCard =
                    MaterialCardView(this).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { topMargin = dp(14) }
                        radius = dp(16).toFloat()
                        strokeWidth = dp(1)
                        strokeColor = ColorUtils.setAlphaComponent(cardBorder, 180)
                        cardElevation = 0f
                        setCardBackgroundColor(Color.parseColor("#172232"))
                    }

            val recContent =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }

            val recTitle =
                    TextView(this).apply {
                        text = "Recommended Tags"
                        setTextColor(titleColor)
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 15f
                    }
            recContent.addView(recTitle)

            val recHint =
                    TextView(this).apply {
                        text = "根据当前 AI 回答，推荐 3 个可继续追问的标签"
                        setTextColor(mutedColor)
                        textSize = 12f
                    }
            val recHintParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { topMargin = dp(4) }
            recContent.addView(recHint, recHintParams)

            val chipGroup =
                    ChipGroup(this).apply {
                        isSingleLine = false
                        chipSpacingHorizontal = dp(8)
                        chipSpacingVertical = dp(8)
                    }

            recommendedTags.forEach { tag ->
                val chip =
                        Chip(this).apply {
                            text = tag.label
                            setTextColor(actionColor)
                            chipBackgroundColor =
                                    ColorStateList.valueOf(Color.parseColor("#223D5A"))
                            chipStrokeWidth = dp(1).toFloat()
                            chipStrokeColor =
                                    ColorStateList.valueOf(
                                            ColorUtils.setAlphaComponent(accentColor, 120)
                                    )
                            isClickable = true
                            isCheckable = false
                            setOnClickListener { handleMagicTagClick(tag) }
                        }
                chipGroup.addView(chip)
            }

            val chipsParams =
                    LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { topMargin = dp(10) }
            recContent.addView(chipGroup, chipsParams)

            recCard.addView(recContent)
            container.addView(recCard)
        }
    }

    private fun pillBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(color)
        }
    }

    private fun recommendTagsForCurrentResponse(limit: Int = 3): List<MagicTag> {
        val note = currentNote ?: return emptyList()
        val response = getAiResponse(note).trim()
        val tags = ReaderSettings.fromPrefs(settingsPrefs).magicTags.filter { it.label.isNotBlank() }
        if (tags.isEmpty()) return emptyList()
        if (response.isBlank()) return tags.take(limit)

        val responseNormalized = normalizeTagMatchText(response)
        val scored = tags.map { tag -> tag to scoreTagAgainstResponse(tag, responseNormalized) }
        val hasSignal = scored.any { it.second > TAG_SCORE_SIGNAL_THRESHOLD }
        if (!hasSignal) return tags.take(limit)
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun scoreTagAgainstResponse(tag: MagicTag, responseNormalized: String): Double {
        val labelText =
                normalizeTagMatchText(
                        tag.label
                                .replace("[", " ")
                                .replace("]", " ")
                )
        val descriptorText = normalizeTagMatchText("${tag.content} ${tag.description}")

        var score = 0.0
        if (labelText.isNotEmpty() && responseNormalized.contains(labelText)) {
            score += TAG_SCORE_LABEL_EXACT_HIT
        }

        val keywords =
                (labelText + " " + descriptorText)
                        .split(Regex("\\s+"))
                        .map { it.trim() }
                        .filter { it.length >= 2 }
                        .distinct()
                        .sortedByDescending { it.length }
                        .take(TAG_KEYWORD_MAX_COUNT)
        val keywordHits = keywords.count { kw -> responseNormalized.contains(kw) }
        score += keywordHits * TAG_SCORE_KEYWORD_HIT

        val overlapSource = (labelText + descriptorText).takeIf { it.isNotBlank() } ?: labelText
        if (overlapSource.length >= 2 && responseNormalized.length >= 2) {
            val responsePairs = responseNormalized.windowed(2, 1).toSet()
            val tagPairs = overlapSource.windowed(2, 1).toSet()
            if (responsePairs.isNotEmpty() && tagPairs.isNotEmpty()) {
                val union = responsePairs.union(tagPairs).size.coerceAtLeast(1)
                val intersect = responsePairs.intersect(tagPairs).size
                score +=
                        (intersect.toDouble() / union.toDouble()) *
                                TAG_SCORE_NGRAM_JACCARD_WEIGHT
            }
        }

        return score
    }

    private fun normalizeTagMatchText(raw: String): String {
        return raw.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }

    private fun openRelatedNote(item: AiNoteRepository.SemanticRelatedNote) {
        openRelatedNoteTarget(item.localId, item.remoteId?.trim().orEmpty(), item.noteId.trim())
    }

    private fun openRelatedNoteByLink(link: String) {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return
        val localId = uri.getQueryParameter("localId")?.toLongOrNull()
        val remoteId = uri.getQueryParameter("remoteId")?.trim().orEmpty()
        val noteId = uri.getQueryParameter("noteId")?.trim().orEmpty()
        openRelatedNoteTarget(localId, remoteId, noteId)
    }

    private fun openRelatedNoteTarget(localId: Long?, remoteId: String, noteId: String) {
        lifecycleScope.launch {
            if (localId == null && remoteId.isBlank() && noteId.isBlank()) return@launch
            val target =
                    when {
                        localId != null -> repository.getById(localId)
                        remoteId.isNotEmpty() -> repository.getByRemoteId(remoteId)
                        else -> {
                            val parsedLocal = noteId.toLongOrNull()
                            if (parsedLocal != null) {
                                repository.getById(parsedLocal)
                            } else {
                                repository.getByRemoteId(noteId)
                            }
                        }
                    }

            if (target == null) {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_not_found),
                                Toast.LENGTH_SHORT
                        )
                        .show()
                return@launch
            }
            if (target.id == currentNote?.id) return@launch
            AiNoteDetailActivity.open(
                    this@AiNoteDetailActivity,
                    target.id,
                    sourceNoteId = currentNote?.id,
                    linkDepth = (linkDepth + 1).coerceAtMost(MAX_BI_LINK_DEPTH)
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun updateNoteFromStrings(
            note: AiNoteEntity,
            original: String?,
            response: String?
    ): AiNoteEntity {
        val finalOriginal = original ?: getOriginalText(note)
        val finalResponse = response ?: getAiResponse(note)
        val messages =
                AiNoteSerialization.messagesFromOriginalAndResponse(finalOriginal, finalResponse)
        return note.copy(
                messages = messages,
                originalText = finalOriginal,
                aiResponse = finalResponse
        )
    }

    private fun setupMagicTags() {
        val settings = ReaderSettings.fromPrefs(settingsPrefs)
        val magicTags = settings.magicTags

        binding.cgMagicTags.removeAllViews()

        if (magicTags.isEmpty()) {
            binding.cgMagicTags.visibility = View.GONE
            return
        }

        binding.cgMagicTags.visibility = View.VISIBLE

        for (tag in magicTags) {
            val chip =
                    Chip(this).apply {
                        text = tag.label
                        setTextColor(magicTagTextColor)
                        chipBackgroundColor = ColorStateList.valueOf(magicTagBackgroundColor)
                        setOnClickListener { handleMagicTagClick(tag) }
                        setOnLongClickListener {
                            val info = tag.description.ifBlank { tag.content.ifBlank { tag.label } }
                            Toast.makeText(context, info, Toast.LENGTH_LONG).show()
                            true
                        }
                    }
            binding.cgMagicTags.addView(chip)
        }
    }

    private fun handleMagicTagClick(tag: MagicTag) {
        val note = currentNote ?: return
        val currentInput = binding.etFollowUp.text.toString().trim()

        if (currentInput.isNotEmpty()) {
            binding.etFollowUp.setText("")
            sendFollowUp(note, currentInput, tag)
        } else {
            val aiResponse = getAiResponse(note)
            if (aiResponse.isNotBlank()) {
                // If there's already a response, treat it as a follow-up to "continue"
                sendFollowUp(note, tag.label, tag)
            } else {
                // If first time (draft), then start the initial explanation
                val originalText = getOriginalText(note)
                rePublishWithPrompt(note, originalText, tag)
            }
        }
    }

    private fun rePublishWithPrompt(
            note: AiNoteEntity,
            promptText: String,
            magicTag: MagicTag? = null
    ) {
        val savedScrollY = currentScrollY()
        binding.btnRepublishSelection.isEnabled = false
        binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_progress)
        setLoading(true)

        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()

            if (useStreaming) {
                startStreaming(note, promptText, savedScrollY, magicTag)
                binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
                return@launch
            }

            val result = repository.fetchAiExplanation(promptText, magicTag)
            if (result != null) {
                val (serverText, content) = result
                val updatedNote = updateNoteFromStrings(note, serverText, content)
                repository.update(updatedNote)
                currentNote = updatedNote
                updateUI(updatedNote)
                triggerRelatedNotesLookup(updatedNote, force = true)
                showRemainingCreditsToast()
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_republish_success),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            } else {
                Toast.makeText(
                                this@AiNoteDetailActivity,
                                getString(R.string.ai_note_republish_failed),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Handle volume down button as back navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Act as back button - return to previous activity (reader page)
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
