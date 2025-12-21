package my.hinoki.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.ui.common.BaseActivity
import my.hinoki.booxreader.databinding.ActivityAiNoteDetailBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.google.android.material.floatingactionbutton.FloatingActionButton
import my.hinoki.booxreader.core.eink.EInkHelper

class AiNoteDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"

        private const val EXTRA_AUTO_STREAM_TEXT = "extra_auto_stream_text"

        fun open(context: Context, noteId: Long, autoStreamText: String? = null) {
            val intent = Intent(context, AiNoteDetailActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                if (autoStreamText != null) {
                    putExtra(EXTRA_AUTO_STREAM_TEXT, autoStreamText)
                }
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
    private val markwon by lazy {
        Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(JLatexMathPlugin.create(binding.tvAiResponse.textSize))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .build()
    }
    private var autoStreamText: String? = null
    private val selectionSanitizeRegex = Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$")
    private var isLoading = false
    private var scrollToBottomButton: FloatingActionButton? = null
    private var streamingRenderJob: Job? = null
    private var pendingStreamingMarkdown: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set custom selection action mode for TextViews
        binding.tvOriginalText.customSelectionActionModeCallback = selectionActionModeCallback
        binding.tvAiResponse.customSelectionActionModeCallback = selectionActionModeCallback


        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        autoStreamText = intent.getStringExtra(EXTRA_AUTO_STREAM_TEXT)
        if (noteId == -1L) {
            Toast.makeText(this, getString(R.string.ai_note_invalid_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadNote(noteId)

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
                Toast.makeText(this, getString(R.string.ai_note_follow_up_hint_input), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendFollowUp(note, question)
        }

        // 初始化快速滾動到底按鈕
        scrollToBottomButton = findViewById(R.id.btnScrollToBottom)
        scrollToBottomButton?.setOnClickListener {
            scrollToBottom()
        }

        // 設置滾動監聽來控制按鈕顯示/隱藏
        setupScrollListener()
    }

    private val selectionActionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            menu?.add(android.view.Menu.NONE, 999, 0, getString(R.string.action_publish))
            menu?.add(android.view.Menu.NONE, 1000, 1, getString(R.string.action_publish_follow_up))
            menu?.add(android.view.Menu.NONE, 1001, 2, getString(R.string.action_map_search))
            menu?.add(android.view.Menu.NONE, 1002, 3, getString(R.string.action_google_search))
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
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
                handleSelectionAction { selectedText ->
                    openMapSearch(selectedText)
                }
                return true
            }
            if (item?.itemId == 1002) {
                handleSelectionAction { selectedText ->
                    openWebSearch(selectedText)
                }
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
    }

    private fun handleSelectionAction(onSelected: (String) -> Unit) {
        val tv = if (binding.tvOriginalText.hasSelection()) binding.tvOriginalText else binding.tvAiResponse

        if (tv.isFocused && tv.hasSelection()) {
            val min = tv.selectionStart
            val max = tv.selectionEnd
            val selectedText = tv.text.subSequence(min, max).toString()

            if (selectedText.isNotBlank()) {
                val trimmedText = selectedText.replace(selectionSanitizeRegex, "")
                if (trimmedText.isNotBlank()) {
                    onSelected(trimmedText)
                } else {
                    Toast.makeText(this, getString(R.string.action_selection_empty), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_note_found), Toast.LENGTH_SHORT).show()
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, existingNote.id)
                return@launch
            }

            Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_publishing_selection), Toast.LENGTH_SHORT).show()

            // 1. Save Draft
            // Use current note's bookId if available
            val bookId = currentNote?.bookId
            val newNoteId = repository.add(
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
                // Open the NEW note
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, newNoteId)
            } else {
                 Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_saved_draft_network_error), Toast.LENGTH_SHORT).show()
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
                }
            } else {
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_not_found), Toast.LENGTH_SHORT).show()
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
        val previousScrollY = if (!scrollToQuestionHeader && preserveScroll) {
            binding.scrollView.scrollY
        } else {
            null
        }

        markwon.setMarkdown(binding.tvOriginalText, getOriginalText(note))
        
        val aiResponse = getAiResponse(note)

        if (aiResponse.isBlank()) {
            binding.tvAiResponse.text = getString(R.string.ai_note_draft_status)
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnPublish.isEnabled = true
            binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            binding.btnRepublishSelection.isEnabled = false
            setLoading(false)
        } else {
            markwon.setMarkdown(binding.tvAiResponse, aiResponse)
            binding.btnPublish.visibility = View.GONE
            binding.btnRepublishSelection.isEnabled = true
        }

        if (!note.locatorJson.isNullOrBlank() && !note.bookId.isNullOrBlank()) {
            binding.btnGoToPage.visibility = View.VISIBLE
            binding.btnGoToPage.setOnClickListener {
                my.hinoki.booxreader.data.ui.reader.ReaderActivity.open(
                    this@AiNoteDetailActivity,
                    note.bookId,
                    note.bookTitle,
                    note.locatorJson
                )
            }
        } else {
            binding.btnGoToPage.visibility = View.GONE
        }

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
            binding.scrollView.post {
                checkScrollPosition()
            }
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
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_published), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_publish_failed), Toast.LENGTH_SHORT).show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            }
            binding.btnRepublishSelection.isEnabled = true
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    private fun sendFollowUp(note: AiNoteEntity, question: String) {
        val savedScrollY = currentScrollY()
        binding.btnFollowUp.isEnabled = false
        binding.btnFollowUp.text = getString(R.string.ai_note_follow_up_publishing)
        setLoading(true)

        lifecycleScope.launch {
            try {
                val useStreaming = repository.isStreamingEnabled()
                val currentAiResponse = getAiResponse(note)
                val result = if (useStreaming) {
                    repository.continueConversationStreaming(note, question) { partial ->
                        val separator = if (currentAiResponse.isBlank()) "" else "\n\n"
                        val preview = currentAiResponse +
                                separator +
                                "---\nQ: " + question + "\n\n" + partial
                        renderStreamingMarkdown(preview)
                        // restoreScrollIfJumped(savedScrollY)
                    }
                } else {
                    repository.continueConversation(note, question)
                }
                clearStreamingRenderer()
                if (result != null) {
                    val separator = if (currentAiResponse.isBlank()) "" else "\n\n"
                    val newContent = currentAiResponse +
                            separator +
                            "---\nQ: " + question + "\n\n" + result
                    val updated = updateNoteFromStrings(note, null, newContent)
                    repository.update(updated)
                    currentNote = updated
                    // Avoid jumping the viewport after publish to keep the reader in place.
                    updateUI(updated, scrollToQuestionHeader = false)
                    binding.etFollowUp.setText("")
                    Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_follow_up_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_follow_up_failed), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_republish_success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_republish_failed), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.ai_note_follow_up_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStreaming(note: AiNoteEntity, text: String, preserveScrollY: Int? = null) {
        setLoading(true)
        val savedScrollY = preserveScrollY ?: currentScrollY()
        lifecycleScope.launch {
            val result = repository.fetchAiExplanationStreaming(text) { partial ->
                renderStreamingMarkdown(partial)
                restoreScrollIfJumped(savedScrollY)
            }
            clearStreamingRenderer()
            if (result != null) {
                val (serverText, content) = result
                val updated = updateNoteFromStrings(note, serverText, content)
                repository.update(updated)
                currentNote = updated
                updateUI(updated)
            } else {
                Toast.makeText(this@AiNoteDetailActivity, getString(R.string.ai_note_streaming_failed), Toast.LENGTH_SHORT).show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = getString(R.string.ai_note_publish_retry)
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = getString(R.string.ai_note_republish_button)
            setLoading(false)
            restoreScrollIfJumped(savedScrollY)
        }
    }

    private fun openMapSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, getString(R.string.action_selection_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = Uri.encode(trimmed)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded"))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.action_map_search_failed), Toast.LENGTH_SHORT).show() }
    }

    private fun openWebSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, getString(R.string.action_selection_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val encoded = Uri.encode(trimmed)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded"))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, getString(R.string.action_web_search_failed), Toast.LENGTH_SHORT).show() }
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
        android.util.Log.d("AiNoteDetailActivity", "執行滾動到底功能")

        // 使用多種方法確保滾動到底部
        binding.scrollView.post {
            val scrollView = binding.scrollView
            val childView = scrollView.getChildAt(0)

            if (childView != null) {
                // 方法1: 直接設置滾動位置
                val maxScroll = childView.height - scrollView.height + scrollView.paddingBottom
                android.util.Log.d("AiNoteDetailActivity", "最大滾動位置: $maxScroll, 當前位置: ${scrollView.scrollY}")

                // 先嘗試直接滾動
                scrollView.scrollTo(0, maxScroll)

                // 延遲後再次確保滾動到底部
                scrollView.postDelayed({
                    // 方法2: 使用 fullScroll 作為備用
                    scrollView.fullScroll(View.FOCUS_DOWN)

                    // 方法3: 再次直接設置滾動位置（最終保險）
                    val finalMaxScroll = childView.height - scrollView.height + scrollView.paddingBottom
                    scrollView.scrollTo(0, finalMaxScroll)

                    android.util.Log.d("AiNoteDetailActivity", "最終滾動位置: ${scrollView.scrollY}, 目標位置: $finalMaxScroll")

                    // 滾動完成後隱藏按鈕
                    hideScrollButton()

                    // 觸發文石刷新以確保顯示更新
                    if (EInkHelper.isBooxDevice()) {
                        EInkHelper.refreshFull(scrollView)
                    }
                }, 100)
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
                val visibleHeight = scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
                val needsScroll = contentHeight > visibleHeight

                android.util.Log.d("AiNoteDetailActivity", "內容高度: $contentHeight, 可見高度: $visibleHeight, 需要滾動: $needsScroll")

                if (!needsScroll) {
                    // 頁面內容不夠長，不需要滾動按鈕
                    hideScrollButton()
                    return@post
                }

                // 檢查是否已經滾動到底部
                val isAtBottom = scrollView.scrollY + visibleHeight >= contentHeight - scrollView.paddingBottom

                if (isAtBottom) {
                    // 在底部時隱藏按鈕
                    hideScrollButton()
                } else {
                    // 不在底部時顯示按鈕
                    showScrollButton()
                }
            }
        }
    }

    // 顯示滾動按鈕
    private fun showScrollButton() {
        scrollToBottomButton?.let { button ->
            if (button.visibility != View.VISIBLE) {
                button.visibility = View.VISIBLE
                button.alpha = 0f
                button.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
                android.util.Log.d("AiNoteDetailActivity", "顯示滾動到底按鈕")
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
                    .withEndAction {
                        button.visibility = View.GONE
                    }
                    .start()
                android.util.Log.d("AiNoteDetailActivity", "隱藏滾動到底按鈕")
            }
        }
    }

    private fun currentScrollY(): Int = binding.scrollView.scrollY

    // Keep the reader's position if the system jumps to the top or bottom after updates.
    private fun restoreScrollIfJumped(targetY: Int?) {
        if (targetY == null) return
        binding.scrollView.post {
            val scrollView = binding.scrollView
            val child = scrollView.getChildAt(0)
            val maxScroll = ((child?.height ?: 0) - scrollView.height + scrollView.paddingBottom)
                .coerceAtLeast(0)
            val clampedTarget = targetY.coerceIn(0, maxScroll)
            val current = scrollView.scrollY
            
            // Only fix if it weirdly jumped to top (common issue with text updates)
            // We do NOT fix "jumping to bottom" because the user might have scrolled there manually to read.
            val jumpedToTop = clampedTarget > 0 && current < clampedTarget - 48 && current < 64
            
            if (jumpedToTop) {
                scrollView.scrollTo(0, clampedTarget)
                checkScrollPosition()
            }
        }
    }

    private fun renderStreamingMarkdown(markdown: String, force: Boolean = false) {
        pendingStreamingMarkdown = markdown
        streamingRenderJob?.cancel()

        val priorScrollY = binding.scrollView.scrollY
        val wasAtBottom = isAtBottom()

        // Delay slightly when we appear to be in the middle of a table row so the parser
        // receives a complete block, which prevents malformed table rendering during SSE.
        val delayMs = when {
            force -> 0L
            isLikelyMidTable(markdown) -> 140L
            else -> 30L
        }

        streamingRenderJob = lifecycleScope.launch(Dispatchers.Main) {
            if (delayMs > 0) delay(delayMs)
            if (pendingStreamingMarkdown == markdown) {
                markwon.setMarkdown(binding.tvAiResponse, markdown)
                binding.scrollView.post {
                    if (wasAtBottom) {
                        scrollToBottom()
                    } else {
                        restoreScrollIfJumped(priorScrollY)
                    }
                }
            }
        }
    }

    private fun clearStreamingRenderer() {
        streamingRenderJob?.cancel()
        streamingRenderJob = null
        pendingStreamingMarkdown = null
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
    }

    private fun getOriginalText(note: AiNoteEntity): String {
        val msgs = try { JSONArray(note.messages) } catch(e: Exception) { JSONArray() }
        return msgs.optJSONObject(0)?.optString("content", "") ?: ""
    }

    private fun getAiResponse(note: AiNoteEntity): String {
        val msgs = try { JSONArray(note.messages) } catch(e: Exception) { JSONArray() }
        if (msgs.length() < 2) return ""
        val sb = StringBuilder()
        // First assistant response
        val first = msgs.optJSONObject(1)
        if (first?.optString("role") == "assistant") {
            sb.append(first.optString("content"))
        }
        
        // Subsequent turns
        for (i in 2 until msgs.length() step 2) {
             val user = msgs.optJSONObject(i)
             val assistant = msgs.optJSONObject(i+1)
             
             if (user != null && user.optString("role") == "user") {
                 sb.append("\n---\nQ: ").append(user.optString("content"))
             }
             if (assistant != null && assistant.optString("role") == "assistant") {
                 sb.append("\n\n").append(assistant.optString("content"))
             }
        }
        return sb.toString()
    }

    private fun updateNoteFromStrings(note: AiNoteEntity, original: String?, response: String?): AiNoteEntity {
        val finalOriginal = original ?: getOriginalText(note)
        val finalResponse = response ?: getAiResponse(note)
        
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "user").put("content", finalOriginal))
        
        if (finalResponse.isNotBlank()) {
            val marker = "\n---\nQ:"
            val segments = finalResponse.split(marker)
            val first = segments.firstOrNull()?.trim().orEmpty()
            if (first.isNotEmpty()) {
                messages.put(JSONObject().put("role", "assistant").put("content", first))
            }
            if (segments.size > 1) {
                for (i in 1 until segments.size) {
                    val seg = segments[i].trimStart()
                    if (seg.isEmpty()) continue
                    val lines = seg.lines()
                    val q = lines.firstOrNull()?.trim().orEmpty()
                    val a = lines.drop(1).joinToString("\n").trim()
                    messages.put(JSONObject().put("role", "user").put("content", q))
                    messages.put(JSONObject().put("role", "assistant").put("content", a))
                }
            }
        }
        return note.copy(messages = messages.toString())
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
