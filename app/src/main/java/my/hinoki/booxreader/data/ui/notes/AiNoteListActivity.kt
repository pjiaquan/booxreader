package my.hinoki.booxreader.data.ui.notes

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.ExportResult
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.ui.common.BaseActivity
import my.hinoki.booxreader.databinding.ActivityAiNoteListBinding
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.TextView

class AiNoteListActivity : BaseActivity() {

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
    private var pendingExportAfterPermission: Boolean = false
    private var listTextColor: Int = Color.BLACK
    private var listSecondaryTextColor: Int = Color.DKGRAY
    private var listBackgroundColor: Int = Color.WHITE

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingExportAfterPermission) {
                exportAllNotes()
            } else if (!granted && pendingExportAfterPermission) {
                Toast.makeText(this, "Storage permission denied; local export skipped", Toast.LENGTH_SHORT).show()
            }
            pendingExportAfterPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as my.hinoki.booxreader.BooxReaderApp
        syncRepo = UserSyncRepository(app)
        repo = AiNoteRepository(app, app.okHttpClient, syncRepo)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID)

        binding = ActivityAiNoteListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyActionBarPadding(binding.listAiNotes)
        applyThemeFromSettings()

        loadNotes()
    }

    override fun onResume() {
        super.onResume()
        applyThemeFromSettings()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ai_note_list, menu)
        exportMenuItem = menu.findItem(R.id.action_export_ai_notes)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
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
        supportActionBar?.subtitle = if (inProgress) "Exporting..." else null
    }

    private fun applyThemeFromSettings() {
        val settings =
                ReaderSettings.fromPrefs(
                        getSharedPreferences(ReaderSettings.PREFS_NAME, MODE_PRIVATE)
                )
        val mode = ContrastMode.values().getOrNull(settings.contrastMode) ?: ContrastMode.NORMAL
        applyContrastMode(mode)
    }

    private fun applyContrastMode(mode: ContrastMode) {
        listBackgroundColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.parseColor("#FAF9F6")
                    ContrastMode.DARK -> Color.parseColor("#121212")
                    ContrastMode.SEPIA -> Color.parseColor("#F2E7D0")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                }
        listTextColor =
                when (mode) {
                    ContrastMode.NORMAL -> Color.BLACK
                    ContrastMode.DARK -> Color.LTGRAY
                    ContrastMode.SEPIA -> Color.parseColor("#5B4636")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }
        listSecondaryTextColor = ColorUtils.setAlphaComponent(listTextColor, 170)

        binding.root.setBackgroundColor(listBackgroundColor)
        binding.listAiNotes.setBackgroundColor(listBackgroundColor)
        binding.progressBar.indeterminateTintList =
                ColorStateList.valueOf(listTextColor)

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = listBackgroundColor
            window.navigationBarColor = listBackgroundColor
        }
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        val useLightIcons = mode == ContrastMode.NORMAL || mode == ContrastMode.SEPIA
        insetsController.isAppearanceLightStatusBars = useLightIcons
        insetsController.isAppearanceLightNavigationBars = useLightIcons

        (binding.listAiNotes.adapter as? android.widget.SimpleAdapter)?.notifyDataSetChanged()
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
                
                val messagesFallback = it.messages.trim()
                val msgs = try { org.json.JSONArray(it.messages) } catch(e: Exception) { org.json.JSONArray() }
                val rawOriginal = msgs.optJSONObject(0)?.optString("content", "")?.trim() ?: ""
                val rawResponse = if (msgs.length() > 1) msgs.optJSONObject(msgs.length()-1)?.optString("content", "")?.trim() ?: "" else ""
                
                // Prioritize original text, fallback to AI response
                val mainText = if (rawOriginal.isNotEmpty()) {
                    rawOriginal
                } else if (rawResponse.isNotEmpty()) {
                    rawResponse
                } else if (messagesFallback.isNotEmpty() && messagesFallback != "[]") {
                    messagesFallback
                } else {
                    "(No Content)"
                }

                val shortText = mainText.replace("\n", " ").take(100)
                
                mapOf("text" to shortText, "time" to time)
            }

            val adapter =
                android.widget.SimpleAdapter(
                    this@AiNoteListActivity,
                    dataList,
                    android.R.layout.simple_list_item_2,
                    arrayOf("text", "time"),
                    intArrayOf(android.R.id.text1, android.R.id.text2)
                ).apply {
                    setViewBinder { view, data, _ ->
                        if (view is TextView) {
                            when (view.id) {
                                android.R.id.text1 -> view.setTextColor(listTextColor)
                                android.R.id.text2 -> view.setTextColor(listSecondaryTextColor)
                            }
                        }
                        false
                    }
                }

            binding.listAiNotes.adapter = adapter

            binding.listAiNotes.setOnItemClickListener { _, _, position, _ ->
                val note = notes[position]
                AiNoteDetailActivity.open(this@AiNoteListActivity, note.id)
            }
        }
    }

    private fun exportAllNotes() {
        if (bookId.isNullOrEmpty()) {
            Toast.makeText(this, "No book selected for export", Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldRequestStoragePermission()) {
            pendingExportAfterPermission = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        lifecycleScope.launch {
            setExportInProgress(true)
            val result = try {
                repo.exportAllNotes(bookId!!)
            } catch (e: Exception) {
                ExportResult(
                    success = false,
                    exportedCount = 0,
                    isEmpty = false,
                    message = "Export failed: ${e.message ?: "Unknown error"}"
                )
            }

            val message = when {
                result.isEmpty -> "No AI notes to export"
                result.success -> {
                    val base = "Exported ${result.exportedCount} AI notes"
                    val pathHint = result.localPath?.let { " (Saved at $it)" } ?: ""
                    base + pathHint
                }
                else -> result.message ?: "Failed to export AI notes"
            }

            Toast.makeText(this@AiNoteListActivity, message, Toast.LENGTH_LONG).show()
            setExportInProgress(false)
        }
    }

    private fun shouldRequestStoragePermission(): Boolean {
        val settings = ReaderSettings.fromPrefs(getSharedPreferences(ReaderSettings.PREFS_NAME, MODE_PRIVATE))
        if (!settings.exportToLocalDownloads) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false // scoped storage; we save in app dir without permission
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return !granted
    }

    // Handle volume down button as back navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Act as back button - return to previous activity
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
