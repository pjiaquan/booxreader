package my.hinoki.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityAiNoteDetailBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.launch

class AiNoteDetailActivity : AppCompatActivity() {

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
            .build()
    }
    private var autoStreamText: String? = null
    private val selectionSanitizeRegex = Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$")

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
            Toast.makeText(this, "Invalid Note ID", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "請輸入問題", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendFollowUp(note, question)
        }
    }

    private val selectionActionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            menu?.add(android.view.Menu.NONE, 999, 0, "發佈")
            menu?.add(android.view.Menu.NONE, 1000, 1, "發佈並追問")
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
                    Toast.makeText(this, "選取內容為空", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@AiNoteDetailActivity, "Note found!", Toast.LENGTH_SHORT).show()
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, existingNote.id)
                return@launch
            }

            Toast.makeText(this@AiNoteDetailActivity, "Publishing new selection...", Toast.LENGTH_SHORT).show()

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
                    val updatedNote = note.copy(
                        originalText = serverText,
                        aiResponse = content
                    )
                    repository.update(updatedNote)
                }
                // Open the NEW note
                AiNoteDetailActivity.open(this@AiNoteDetailActivity, newNoteId)
            } else {
                 Toast.makeText(this@AiNoteDetailActivity, "Saved as draft (Network Error)", Toast.LENGTH_SHORT).show()
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
                val shouldAutoStream = autoStreamText != null && note.aiResponse.isBlank()
                if (shouldAutoStream) {
                    autoStreamText?.let { text ->
                        binding.btnPublish.isEnabled = false
                        binding.btnPublish.text = "Streaming..."
                        startStreaming(note, text)
                    }
                }
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "Note not found", Toast.LENGTH_SHORT).show()
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

        markwon.setMarkdown(binding.tvOriginalText, note.originalText)

        if (note.aiResponse.isBlank()) {
            binding.tvAiResponse.text = "(Draft / Failed) Click button to retry."
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnPublish.isEnabled = true
            binding.btnPublish.text = "Publish / Retry"
            binding.btnRepublishSelection.isEnabled = false
        } else {
            markwon.setMarkdown(binding.tvAiResponse, note.aiResponse)
            binding.btnPublish.visibility = View.GONE
            binding.btnRepublishSelection.isEnabled = true
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
            }
        } else if (previousScrollY != null) {
            binding.scrollView.post {
                binding.scrollView.scrollTo(0, previousScrollY)
            }
        }
    }

    private fun publishNote(note: AiNoteEntity) {
        binding.btnPublish.isEnabled = false
        binding.btnPublish.text = "Publishing..."
        binding.btnRepublishSelection.isEnabled = false

        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()
            if (useStreaming) {
                startStreaming(note, note.originalText)
                binding.btnPublish.text = "Streaming..."
                return@launch
            }

            val result = repository.fetchAiExplanation(note.originalText)
            if (result != null) {
                val (serverText, content) = result
                val updatedNote = note.copy(
                    originalText = serverText,
                    aiResponse = content
                )
                repository.update(updatedNote)
                currentNote = updatedNote
                updateUI(updatedNote)
                Toast.makeText(this@AiNoteDetailActivity, "Published!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "Failed to publish", Toast.LENGTH_SHORT).show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = "Publish / Retry"
            }
            binding.btnRepublishSelection.isEnabled = true
        }
    }

    private fun sendFollowUp(note: AiNoteEntity, question: String) {
        binding.btnFollowUp.isEnabled = false
        binding.btnFollowUp.text = "發佈中..."

        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()
            val result = if (useStreaming) {
                repository.continueConversationStreaming(note, question) { partial ->
                    val separator = if (note.aiResponse.isBlank()) "" else "\n\n"
                    val preview = note.aiResponse +
                        separator +
                        "---\nQ: " + question + "\n\n" + partial
                    markwon.setMarkdown(binding.tvAiResponse, preview)
                }
            } else {
                repository.continueConversation(note, question)
            }
            if (result != null) {
                val separator = if (note.aiResponse.isBlank()) "" else "\n\n"
                val newContent = note.aiResponse +
                    separator +
                    "---\nQ: " + question + "\n\n" + result
                val updated = note.copy(aiResponse = newContent)
                repository.update(updated)
                currentNote = updated
                // Avoid jumping the viewport after publish to keep the reader in place.
                updateUI(updated, scrollToQuestionHeader = false)
                binding.etFollowUp.setText("")
                Toast.makeText(this@AiNoteDetailActivity, "已發佈", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "發佈失敗", Toast.LENGTH_SHORT).show()
            }
            binding.btnFollowUp.isEnabled = true
            binding.btnFollowUp.text = "發佈"
        }
    }

    private fun rePublishSelection(note: AiNoteEntity) {
        binding.btnRepublishSelection.isEnabled = false
        binding.btnRepublishSelection.text = "重新發佈中..."
        lifecycleScope.launch {
            val useStreaming = repository.isStreamingEnabled()
            if (useStreaming) {
                startStreaming(note, note.originalText)
                binding.btnRepublishSelection.text = "重新發佈選取內容"
                return@launch
            }

            val result = repository.fetchAiExplanation(note.originalText)
            if (result != null) {
                val (serverText, content) = result
                val updatedNote = note.copy(
                    originalText = serverText,
                    aiResponse = content
                )
                repository.update(updatedNote)
                currentNote = updatedNote
                updateUI(updatedNote)
                Toast.makeText(this@AiNoteDetailActivity, "已重新發佈", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "重新發佈失敗", Toast.LENGTH_SHORT).show()
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = "重新發佈選取內容"
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
            Toast.makeText(this, "請輸入內容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStreaming(note: AiNoteEntity, text: String) {
        lifecycleScope.launch {
            val result = repository.fetchAiExplanationStreaming(text) { partial ->
                markwon.setMarkdown(binding.tvAiResponse, partial)
            }
            if (result != null) {
                val (serverText, content) = result
                val updated = note.copy(
                    originalText = serverText,
                    aiResponse = content
                )
                repository.update(updated)
                currentNote = updated
                updateUI(updated)
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "Streaming failed", Toast.LENGTH_SHORT).show()
                binding.btnPublish.isEnabled = true
                binding.btnPublish.text = "Publish / Retry"
            }
            binding.btnRepublishSelection.isEnabled = true
            binding.btnRepublishSelection.text = "重新發佈選取內容"
        }
    }
}
