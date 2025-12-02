package com.example.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.booxreader.data.repo.AiNoteRepository
import com.example.booxreader.databinding.ActivityAiNoteListBinding
import kotlinx.coroutines.launch

class AiNoteListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"

        fun open(context: Context, bookId: String?) {
            val intent = Intent(context, AiNoteListActivity::class.java).apply {
                if (bookId != null) {
                    putExtra(EXTRA_BOOK_ID, bookId)
                }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityAiNoteListBinding
    private lateinit var repo: AiNoteRepository
    private var bookId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAiNoteListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = AiNoteRepository(applicationContext)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID)

        loadNotes()
    }

    private fun loadNotes() {
        lifecycleScope.launch {
            val notes = if (bookId != null) {
                repo.getByBook(bookId!!)
            } else {
                repo.getAll()
            }

            // Map the data for SimpleAdapter
            val dataList = notes.map {
                val time = DateFormat.format("yyyy-MM-dd HH:mm", it.createdAt).toString()
                
                val rawOriginal = it.originalText.trim()
                val rawResponse = it.aiResponse.trim()
                
                // Prioritize original text, fallback to AI response
                val mainText = if (rawOriginal.isNotEmpty()) {
                    rawOriginal
                } else if (rawResponse.isNotEmpty()) {
                    rawResponse
                } else {
                    "(No Content)"
                }

                val shortText = mainText.replace("\n", " ").take(100)
                
                mapOf("text" to shortText, "time" to time)
            }

            binding.listAiNotes.adapter = android.widget.SimpleAdapter(
                this@AiNoteListActivity,
                dataList,
                android.R.layout.simple_list_item_2,
                arrayOf("text", "time"),
                intArrayOf(android.R.id.text1, android.R.id.text2)
            )

            binding.listAiNotes.setOnItemClickListener { _, _, position, _ ->
                val note = notes[position]
                AiNoteDetailActivity.open(this@AiNoteListActivity, note.id)
            }
        }
    }
}