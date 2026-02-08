package my.hinoki.booxreader.ui.notes

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.DeleteResult
import my.hinoki.booxreader.data.repo.ExportResult
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.databinding.ActivityAiNoteListBinding
import my.hinoki.booxreader.databinding.ItemAiNoteSelectableBinding
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.BaseAdapter
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

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
    private lateinit var adapter: NoteListAdapter
    private var bookId: String? = null
    private var exportMenuItem: MenuItem? = null
    private var pendingExportAllAfterPermission: Boolean = false
    private var pendingExportSelectedIds: Set<Long>? = null
    private var listTextColor: Int = Color.BLACK
    private var listSecondaryTextColor: Int = Color.DKGRAY
    private var listBackgroundColor: Int = Color.WHITE
    private var baseListPaddingTop: Int = 0
    private var notes: List<AiNoteEntity> = emptyList()
    private val selectedNoteIds = linkedSetOf<Long>()
    private var selectionActionMode: ActionMode? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val selectedIds = pendingExportSelectedIds
            val exportAll = pendingExportAllAfterPermission
            pendingExportSelectedIds = null
            pendingExportAllAfterPermission = false

            if (granted) {
                if (selectedIds != null) {
                    exportSelectedNotes(selectedIds)
                } else if (exportAll) {
                    exportAllNotes()
                }
            } else if (selectedIds != null || exportAll) {
                Toast.makeText(this, "Storage permission denied; local export skipped", Toast.LENGTH_SHORT).show()
            }
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
        baseListPaddingTop = binding.listAiNotes.paddingTop
        adjustListTopInsetForActionBar()
        setupList()
        applyThemeFromSettings()

        loadNotes()
    }

    override fun onResume() {
        super.onResume()
        adjustListTopInsetForActionBar()
        applyThemeFromSettings()
    }

    private fun adjustListTopInsetForActionBar() {
        binding.listAiNotes.post {
            val actionBarView =
                    findViewById<View>(androidx.appcompat.R.id.action_bar_container)
                            ?: findViewById(androidx.appcompat.R.id.action_bar)
                            ?: return@post
            val actionBarLocation = IntArray(2)
            val listLocation = IntArray(2)
            actionBarView.getLocationInWindow(actionBarLocation)
            binding.listAiNotes.getLocationInWindow(listLocation)

            val actionBarBottom = actionBarLocation[1] + actionBarView.height
            val overlap = (actionBarBottom - listLocation[1]).coerceAtLeast(0)
            binding.listAiNotes.setPadding(
                    binding.listAiNotes.paddingLeft,
                    baseListPaddingTop + overlap,
                    binding.listAiNotes.paddingRight,
                    binding.listAiNotes.paddingBottom
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ai_note_list, menu)
        exportMenuItem = menu.findItem(R.id.action_export_ai_notes)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (selectionActionMode != null) {
                    selectionActionMode?.finish()
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
                true
            }
            R.id.action_export_ai_notes -> {
                requestExportAllNotes()
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

    private fun setupList() {
        adapter = NoteListAdapter()
        binding.listAiNotes.adapter = adapter
        binding.listAiNotes.setOnItemClickListener { _, _, position, _ ->
            val note = notes.getOrNull(position) ?: return@setOnItemClickListener
            if (selectionActionMode != null) {
                toggleSelection(note.id)
            } else {
                AiNoteDetailActivity.open(this@AiNoteListActivity, note.id)
            }
        }
        binding.listAiNotes.setOnItemLongClickListener { _, _, position, _ ->
            val note = notes.getOrNull(position) ?: return@setOnItemLongClickListener true
            if (selectionActionMode == null) {
                selectionActionMode = startSupportActionMode(selectionActionModeCallback)
            }
            toggleSelection(note.id)
            true
        }
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

        applySelectionBarTheme()
        adapter.notifyDataSetChanged()
    }

    private fun loadNotes() {
        lifecycleScope.launch {
            // Pull cloud notes first (best effort)
            runCatching { syncRepo.pullNotes() }

            if (isFinishing || isDestroyed) return@launch
            notes = if (bookId != null) {
                repo.getByBook(bookId!!)
            } else {
                repo.getAll()
            }
            val validIds = notes.map { it.id }.toSet()
            selectedNoteIds.retainAll(validIds)
            if (selectedNoteIds.isEmpty()) {
                selectionActionMode?.finish()
            } else {
                updateSelectionTitle()
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun requestExportAllNotes() {
        if (bookId.isNullOrEmpty()) {
            Toast.makeText(this, "No book selected for export", Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldRequestStoragePermission()) {
            pendingExportAllAfterPermission = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exportAllNotes()
    }

    private fun exportAllNotes() {
        lifecycleScope.launch {
            setExportInProgress(true)
            val result = try {
                repo.exportAllNotes(bookId ?: "")
            } catch (e: Exception) {
                ExportResult(
                    success = false,
                    exportedCount = 0,
                    isEmpty = false,
                    message = "Export failed: ${e.message ?: "Unknown error"}"
                )
            }
            showExportResult(result)
            setExportInProgress(false)
        }
    }

    private fun requestExportSelectedNotes(noteIds: Set<Long>) {
        if (noteIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.ai_note_export_selected_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (shouldRequestStoragePermission()) {
            pendingExportSelectedIds = noteIds
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        exportSelectedNotes(noteIds)
    }

    private fun exportSelectedNotes(noteIds: Set<Long>) {
        lifecycleScope.launch {
            setExportInProgress(true)
            val result = try {
                repo.exportSelectedNotes(noteIds)
            } catch (e: Exception) {
                ExportResult(
                        success = false,
                        exportedCount = 0,
                        isEmpty = false,
                        message = "Export failed: ${e.message ?: "Unknown error"}"
                )
            }
            showExportResult(result)
            setExportInProgress(false)
        }
    }

    private fun showExportResult(result: ExportResult) {
        val message = when {
            result.isEmpty -> result.message ?: "No AI notes to export"
            result.success -> {
                val base = "Exported ${result.exportedCount} AI notes"
                val pathHint = result.localPath?.let { " (Saved at $it)" } ?: ""
                base + pathHint
            }
            else -> result.message ?: "Failed to export AI notes"
        }
        Toast.makeText(this@AiNoteListActivity, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmDeleteSelected() {
        val count = selectedNoteIds.size
        if (count == 0) return
        AlertDialog.Builder(this)
                .setTitle(R.string.ai_note_delete_selected_title)
                .setMessage(getString(R.string.ai_note_delete_selected_message, count))
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    deleteSelectedNotes(selectedNoteIds.toSet())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun deleteSelectedNotes(noteIds: Set<Long>) {
        if (noteIds.isEmpty()) return
        lifecycleScope.launch {
            val result =
                    runCatching { repo.deleteSelectedNotes(noteIds) }
                            .getOrDefault(DeleteResult(deletedCount = 0, failedCount = noteIds.size))
            val message =
                    if (result.failedCount == 0) {
                        getString(R.string.ai_note_delete_selected_success, result.deletedCount)
                    } else {
                        getString(
                                R.string.ai_note_delete_selected_partial,
                                result.deletedCount,
                                result.failedCount
                        )
                    }
            Toast.makeText(this@AiNoteListActivity, message, Toast.LENGTH_LONG).show()
            selectedNoteIds.clear()
            selectionActionMode?.finish()
            loadNotes()
        }
    }

    private fun toggleSelection(noteId: Long) {
        if (selectedNoteIds.contains(noteId)) {
            selectedNoteIds.remove(noteId)
        } else {
            selectedNoteIds.add(noteId)
        }
        if (selectedNoteIds.isEmpty()) {
            selectionActionMode?.finish()
        } else {
            updateSelectionTitle()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionTitle() {
        selectionActionMode?.title = getString(R.string.ai_note_selected_count, selectedNoteIds.size)
    }

    private val selectionActionModeCallback =
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    mode.menuInflater.inflate(R.menu.menu_ai_note_selection, menu)
                    updateSelectionTitle()
                    exportMenuItem?.isVisible = false
                    applySelectionBarTheme()
                    tintActionModeMenuText(mode, listTextColor)
                    adapter.notifyDataSetChanged()
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    return when (item.itemId) {
                        R.id.action_select_all_notes -> {
                            selectedNoteIds.clear()
                            selectedNoteIds.addAll(notes.map { it.id })
                            updateSelectionTitle()
                            adapter.notifyDataSetChanged()
                            true
                        }

                        R.id.action_export_selected_notes -> {
                            requestExportSelectedNotes(selectedNoteIds.toSet())
                            true
                        }

                        R.id.action_delete_selected_notes -> {
                            confirmDeleteSelected()
                            true
                        }

                        else -> false
                    }
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    selectedNoteIds.clear()
                    selectionActionMode = null
                    exportMenuItem?.isVisible = true
                    adapter.notifyDataSetChanged()
                }
            }

    private fun applySelectionBarTheme() {
        if (selectionActionMode == null) return
        val contextBar =
                findViewById<View>(androidx.appcompat.R.id.action_context_bar)
                        ?: findViewById(androidx.appcompat.R.id.action_mode_bar)
                        ?: return
        contextBar.setBackgroundColor(listBackgroundColor)
        tintViewTree(contextBar, listTextColor)
        // Ensure menu/title colors are applied after ActionMode children are attached.
        contextBar.post { tintViewTree(contextBar, listTextColor) }
        selectionActionMode?.let { tintActionModeMenuText(it, listTextColor) }
    }

    private fun tintActionModeMenuText(mode: ActionMode, tintColor: Int) {
        for (index in 0 until mode.menu.size()) {
            val item = mode.menu.getItem(index)
            val title = item.title ?: continue
            val styled = SpannableString(title)
            styled.setSpan(
                    ForegroundColorSpan(tintColor),
                    0,
                    styled.length,
                    Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
            item.title = styled
        }
    }

    private fun tintViewTree(view: View, tintColor: Int) {
        when (view) {
            is android.widget.TextView -> view.setTextColor(tintColor)
            is ImageView -> view.imageTintList = ColorStateList.valueOf(tintColor)
            is ImageButton -> view.imageTintList = ColorStateList.valueOf(tintColor)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                tintViewTree(view.getChildAt(i), tintColor)
            }
        }
    }

    private fun previewText(note: AiNoteEntity): String {
        val messagesFallback = note.messages.trim()
        val msgs =
                try {
                    org.json.JSONArray(note.messages)
                } catch (_: Exception) {
                    org.json.JSONArray()
                }
        val rawOriginal = msgs.optJSONObject(0)?.optString("content", "")?.trim() ?: ""
        val rawResponse =
                if (msgs.length() > 1) {
                    msgs.optJSONObject(msgs.length() - 1)?.optString("content", "")?.trim() ?: ""
                } else {
                    ""
                }
        val mainText =
                when {
                    rawOriginal.isNotEmpty() -> rawOriginal
                    rawResponse.isNotEmpty() -> rawResponse
                    messagesFallback.isNotEmpty() && messagesFallback != "[]" -> messagesFallback
                    else -> "(No Content)"
                }
        return mainText.replace("\n", " ").take(100)
    }

    private inner class NoteListAdapter : BaseAdapter() {
        override fun getCount(): Int = notes.size

        override fun getItem(position: Int): Any = notes[position]

        override fun getItemId(position: Int): Long = notes[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ItemAiNoteSelectableBinding
            val rootView: View
            if (convertView == null) {
                holder =
                        ItemAiNoteSelectableBinding.inflate(
                                LayoutInflater.from(parent.context),
                                parent,
                                false
                        )
                rootView = holder.root
                rootView.tag = holder
            } else {
                rootView = convertView
                holder = convertView.tag as ItemAiNoteSelectableBinding
            }

            val note = notes[position]
            holder.tvText.text = previewText(note)
            holder.tvTime.text = DateFormat.format("yyyy-MM-dd HH:mm", note.createdAt).toString()
            holder.tvText.setTextColor(listTextColor)
            holder.tvTime.setTextColor(listSecondaryTextColor)
            holder.cbSelect.buttonTintList = ColorStateList.valueOf(listTextColor)
            holder.cbSelect.visibility = if (selectionActionMode != null) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = selectedNoteIds.contains(note.id)
            holder.cbSelect.isClickable = false
            holder.cbSelect.isFocusable = false
            return rootView
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
                if (selectionActionMode != null) {
                    selectionActionMode?.finish()
                } else {
                    onBackPressedDispatcher.onBackPressed()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
