package com.example.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.booxreader.data.db.AiNoteEntity
import com.example.booxreader.data.repo.AiNoteRepository
import com.example.booxreader.databinding.ActivityAiNoteDetailBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

class AiNoteDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"

        fun open(context: Context, noteId: Long) {
            val intent = Intent(context, AiNoteDetailActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityAiNoteDetailBinding
    private val repository by lazy { AiNoteRepository(applicationContext) }
    private var currentNote: AiNoteEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set custom selection action mode for TextViews
        binding.tvOriginalText.customSelectionActionModeCallback = selectionActionModeCallback
        binding.tvAiResponse.customSelectionActionModeCallback = selectionActionModeCallback

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
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
    }

    private val selectionActionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            menu?.add(android.view.Menu.NONE, 999, 0, "發佈")
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
            if (item?.itemId == 999) {
                // Get selected text
                var min = 0
                var max = 0
                val tv = if (binding.tvOriginalText.hasSelection()) binding.tvOriginalText else binding.tvAiResponse
                
                if (tv.isFocused && tv.hasSelection()) {
                    min = tv.selectionStart
                    max = tv.selectionEnd
                    val selectedText = tv.text.subSequence(min, max).toString()
                    
                    mode?.finish()

                    if (selectedText.isNotBlank()) {
                         val trimmedText = selectedText.replace(Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$"), "")
                         createAndPublishNewNote(trimmedText)
                    }
                }
                return true
            }
            return false
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
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
            val newNoteId = repository.add(bookId, text, "")
            
            // 2. Fetch
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
            } else {
                Toast.makeText(this@AiNoteDetailActivity, "Note not found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updateUI(note: AiNoteEntity) {
        val markwon = Markwon.create(this)
        markwon.setMarkdown(binding.tvOriginalText, note.originalText)

        if (note.aiResponse.isBlank()) {
            binding.tvAiResponse.text = "(Draft / Failed) Click button to retry."
            binding.btnPublish.visibility = View.VISIBLE
            binding.btnPublish.isEnabled = true
            binding.btnPublish.text = "Publish / Retry"
        } else {
            markwon.setMarkdown(binding.tvAiResponse, note.aiResponse)
            binding.btnPublish.visibility = View.GONE
        }
    }

    private fun publishNote(note: AiNoteEntity) {
        binding.btnPublish.isEnabled = false
        binding.btnPublish.text = "Publishing..."

        lifecycleScope.launch {
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
        }
    }
}