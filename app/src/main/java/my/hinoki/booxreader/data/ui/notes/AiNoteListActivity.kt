package my.hinoki.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityAiNoteListBinding
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
    private lateinit var syncRepo: UserSyncRepository
    private lateinit var repo: AiNoteRepository
    private var bookId: String? = null
    private var exportMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as my.hinoki.booxreader.BooxReaderApp
        syncRepo = UserSyncRepository(app)
        repo = AiNoteRepository(app, app.okHttpClient, syncRepo)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID)

        binding = ActivityAiNoteListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        binding.topAppBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        loadNotes()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ai_note_list, menu)
        exportMenuItem = menu.findItem(R.id.action_export_ai_notes)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_ai_notes -> {
                exportAllNotes()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setExportInProgress(inProgress: Boolean) {
        binding.progressBar.visibility = if (inProgress) View.VISIBLE else View.GONE
        binding.listAiNotes.isEnabled = !inProgress
        exportMenuItem?.isEnabled = !inProgress
        binding.topAppBar.subtitle = if (inProgress) "Exporting..." else ""
    }

    private fun loadNotes() {
        lifecycleScope.launch {
            // Pull cloud notes first (best effort)
            runCatching { syncRepo.pullNotes() }

            if (isFinishing || isDestroyed) return@launch
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

    private fun exportAllNotes() {
        lifecycleScope.launch {
            setExportInProgress(true)
            val result = repo.exportAllNotes()

            val message = when {
                result.isEmpty -> "No AI notes to export"
                result.success -> "Exported ${result.exportedCount} AI notes"
                else -> result.message ?: "Failed to export AI notes"
            }

            Toast.makeText(this@AiNoteListActivity, message, Toast.LENGTH_SHORT).show()
            setExportInProgress(false)
        }
    }
}
