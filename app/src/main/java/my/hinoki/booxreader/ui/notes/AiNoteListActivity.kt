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
import android.view.inputmethod.EditorInfo
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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.widget.BaseAdapter
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.roundToInt

class AiNoteListActivity : BaseActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val EXPORTING_SUBTITLE = "Exporting..."
        private const val NOTE_TIME_FORMAT = "yyyy-MM-dd HH:mm"
        private const val PREVIEW_MAX_LENGTH = 100

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
    private var isExportInProgress: Boolean = false
    private var isSemanticSearchInProgress: Boolean = false
    private var pendingExportAllAfterPermission: Boolean = false
    private var pendingExportSelectedIds: Set<Long>? = null
    private var listTextColor: Int = Color.BLACK
    private var listSecondaryTextColor: Int = Color.DKGRAY
    private var listBackgroundColor: Int = Color.WHITE
    private var topBarContentColor: Int = Color.WHITE
    private var semanticClearDrawable: Drawable? = null
    private var notes: List<AiNoteEntity> = emptyList()
    private var allNotes: List<AiNoteEntity> = emptyList()
    private var semanticQueryInEffect: String? = null
    private val semanticMetaByNoteId = mutableMapOf<Long, AiNoteRepository.SemanticRelatedNote>()
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
        applyActionBarPadding(binding.llSemanticSearch)
        setupList()
        setupSemanticSearch()
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
        applyActionBarMenuColors(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        applyActionBarMenuColors(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleBackNavigation()
            }
            R.id.action_export_ai_notes -> {
                requestExportAllNotes()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setExportInProgress(inProgress: Boolean) {
        isExportInProgress = inProgress
        applyBusyState()
        supportActionBar?.subtitle = if (inProgress) EXPORTING_SUBTITLE else null
        applyActionBarContentColor(topBarContentColor)
        invalidateOptionsMenu()
    }

    private fun setSemanticSearchInProgress(inProgress: Boolean) {
        isSemanticSearchInProgress = inProgress
        binding.btnSemanticSearch.isEnabled = !inProgress
        binding.etSemanticSearch.isEnabled = !inProgress
        updateSemanticSearchClearIcon()
        applyBusyState()
    }

    private fun applyBusyState() {
        val busy = isExportInProgress || isSemanticSearchInProgress
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.listAiNotes.isEnabled = !busy
        exportMenuItem?.isEnabled = !busy
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

    private fun setupSemanticSearch() {
        binding.btnSemanticSearch.setOnClickListener { submitSemanticSearch() }
        binding.etSemanticSearch.setOnEditorActionListener { _, actionId, event ->
            val shouldSearch =
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                                    event.action == KeyEvent.ACTION_DOWN)
            if (shouldSearch) {
                submitSemanticSearch()
                true
            } else {
                false
            }
        }
        binding.etSemanticSearch.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) = Unit

                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {
                        updateSemanticSearchClearIcon()
                    }

                    override fun afterTextChanged(s: Editable?) = Unit
                }
        )
        binding.etSemanticSearch.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && isTouchOnSemanticClearIcon(event)) {
                clearSemanticSearch()
                true
            } else {
                false
            }
        }
        updateSemanticSearchClearIcon()
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
                    ContrastMode.DARK -> Color.parseColor("#F2F5FA")
                    ContrastMode.SEPIA -> Color.parseColor("#5B4636")
                    ContrastMode.HIGH_CONTRAST -> Color.WHITE
                }
        val secondaryTextAlpha =
                if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 215 else 170
        val hintTextAlpha =
                if (mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST) 190 else 140
        listSecondaryTextColor = ColorUtils.setAlphaComponent(listTextColor, secondaryTextAlpha)
        val topBarColor =
                when (mode) {
                    ContrastMode.DARK -> Color.parseColor("#0A0D12")
                    ContrastMode.HIGH_CONTRAST -> Color.BLACK
                    else -> ContextCompat.getColor(this, R.color.action_bar_surface)
                }
        topBarContentColor =
                when (mode) {
                    ContrastMode.DARK, ContrastMode.HIGH_CONTRAST -> Color.WHITE
                    else ->
                            if (ColorUtils.calculateLuminance(topBarColor) > 0.5) Color.BLACK
                            else Color.WHITE
                }

        binding.root.setBackgroundColor(listBackgroundColor)
        binding.llSemanticSearch.setBackgroundColor(listBackgroundColor)
        binding.listAiNotes.setBackgroundColor(listBackgroundColor)
        binding.progressBar.indeterminateTintList =
                ColorStateList.valueOf(listTextColor)
        binding.etSemanticSearch.setTextColor(listTextColor)
        binding.etSemanticSearch.setHintTextColor(
                ColorUtils.setAlphaComponent(listTextColor, hintTextAlpha)
        )
        binding.btnSemanticSearch.setTextColor(listTextColor)
        semanticClearDrawable = createSemanticClearDrawable(listTextColor)
        updateSemanticSearchClearIcon()
        binding.tvSemanticSearchStatus.setTextColor(listSecondaryTextColor)
        supportActionBar?.setBackgroundDrawable(ColorDrawable(topBarColor))
        applyActionBarContentColor(topBarContentColor)
        invalidateOptionsMenu()

        @Suppress("DEPRECATION")
        run {
            window.decorView.setBackgroundColor(listBackgroundColor)
            window.statusBarColor = topBarColor
            window.navigationBarColor = listBackgroundColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        val useLightStatusIcons =
                mode != ContrastMode.DARK &&
                        mode != ContrastMode.HIGH_CONTRAST &&
                        ColorUtils.calculateLuminance(topBarColor) > 0.5
        val useLightNavIcons = ColorUtils.calculateLuminance(listBackgroundColor) > 0.5
        insetsController.isAppearanceLightStatusBars = useLightStatusIcons
        insetsController.isAppearanceLightNavigationBars = useLightNavIcons

        applySelectionBarTheme()
        adapter.notifyDataSetChanged()
    }

    private fun createSemanticClearDrawable(color: Int): Drawable? {
        val icon =
                AppCompatResources.getDrawable(
                                this,
                                androidx.appcompat.R.drawable.abc_ic_clear_material
                        )
                        ?.mutate()
                        ?: AppCompatResources.getDrawable(
                                        this,
                                        android.R.drawable.ic_menu_close_clear_cancel
                                )
                                ?.mutate()
        if (icon != null) {
            DrawableCompat.setTint(icon, ColorUtils.setAlphaComponent(color, 220))
        }
        return icon
    }

    private fun updateSemanticSearchClearIcon() {
        val endIcon =
                if (binding.etSemanticSearch.isEnabled &&
                                binding.etSemanticSearch.text?.isNotEmpty() == true
                ) semanticClearDrawable
                else null
        val drawables = binding.etSemanticSearch.compoundDrawablesRelative
        binding.etSemanticSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(
                drawables[0],
                drawables[1],
                endIcon,
                drawables[3]
        )
        binding.etSemanticSearch.compoundDrawablePadding =
                resources.getDimensionPixelSize(R.dimen.spacing_sm)
    }

    private fun isTouchOnSemanticClearIcon(event: MotionEvent): Boolean {
        val drawables = binding.etSemanticSearch.compoundDrawablesRelative
        val endDrawable = drawables[2] ?: return false
        val iconWidth = endDrawable.bounds.width()
        if (iconWidth <= 0) return false
        val isRtl = binding.etSemanticSearch.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (isRtl) {
            event.x <= binding.etSemanticSearch.paddingStart + iconWidth
        } else {
            event.x >= binding.etSemanticSearch.width - binding.etSemanticSearch.paddingEnd - iconWidth
        }
    }

    private fun applyActionBarContentColor(contentColor: Int) {
        val actionTitle = supportActionBar?.title?.toString()?.takeIf { it.isNotBlank() } ?: title.toString()
        if (actionTitle.isNotBlank()) {
            supportActionBar?.title = colorizeText(actionTitle, contentColor)
        }
        val actionSubtitle = supportActionBar?.subtitle?.toString().orEmpty()
        if (actionSubtitle.isNotBlank()) {
            supportActionBar?.subtitle = colorizeText(actionSubtitle, contentColor)
        }
        val backDrawable =
                AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                        ?.mutate()
                        ?.let { drawable ->
                            DrawableCompat.setTint(drawable, contentColor)
                            drawable
                        }
        if (backDrawable != null) {
            supportActionBar?.setHomeAsUpIndicator(backDrawable)
        }
    }

    private fun applyActionBarMenuColors(menu: Menu) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            item.icon =
                    item.icon?.mutate()?.also { icon ->
                        DrawableCompat.setTint(icon, topBarContentColor)
                    }
            val title = item.title?.toString().orEmpty()
            if (title.isNotBlank()) {
                item.title = colorizeText(title, topBarContentColor)
            }
        }
    }

    private fun loadNotes() {
        lifecycleScope.launch {
            // Pull cloud notes first (best effort)
            runCatching { syncRepo.pullNotes() }

            if (isFinishing || isDestroyed) return@launch
            allNotes = if (bookId != null) {
                repo.getByBook(bookId!!)
            } else {
                repo.getAll()
            }
            notes = allNotes
            semanticMetaByNoteId.clear()
            semanticQueryInEffect = null
            binding.tvSemanticSearchStatus.visibility = View.GONE
            syncSelectionWithVisibleNotes()
            adapter.notifyDataSetChanged()
        }
    }

    private fun submitSemanticSearch() {
        val query = binding.etSemanticSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            clearSemanticSearch()
            return
        }
        semanticQueryInEffect = query
        lifecycleScope.launch {
            setSemanticSearchInProgress(true)
            binding.tvSemanticSearchStatus.visibility = View.VISIBLE
            binding.tvSemanticSearchStatus.text =
                    getString(R.string.ai_note_semantic_search_running)
            try {
                val semanticResults =
                        repo.searchNotesBySemanticQuery(
                                queryText = query,
                                limit = 20,
                                bookId = null
                        )
                var matchedNotes = mapSemanticResultsToLocalNotes(semanticResults)
                if (semanticResults.isNotEmpty() && matchedNotes.isEmpty()) {
                    // If remote semantic hits exist but local mapping is empty, refresh notes once.
                    runCatching { syncRepo.pullNotes() }
                    matchedNotes = mapSemanticResultsToLocalNotes(semanticResults)
                }

                notes = matchedNotes
                syncSelectionWithVisibleNotes()
                adapter.notifyDataSetChanged()

                showSemanticSearchResultStatus(query, notes.size)
            } catch (e: Exception) {
                semanticQueryInEffect = null
                semanticMetaByNoteId.clear()
                notes = allNotes
                syncSelectionWithVisibleNotes()
                adapter.notifyDataSetChanged()
                binding.tvSemanticSearchStatus.visibility = View.GONE
                Toast.makeText(
                                this@AiNoteListActivity,
                                getString(R.string.ai_note_semantic_search_failed),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            } finally {
                setSemanticSearchInProgress(false)
            }
        }
    }

    private fun clearSemanticSearch() {
        semanticQueryInEffect = null
        semanticMetaByNoteId.clear()
        binding.etSemanticSearch.setText("")
        notes = allNotes
        syncSelectionWithVisibleNotes()
        binding.tvSemanticSearchStatus.visibility = View.GONE
        adapter.notifyDataSetChanged()
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
        runExportWithProgress { repo.exportAllNotes(bookId ?: "") }
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
        runExportWithProgress { repo.exportSelectedNotes(noteIds) }
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
        return mainText.replace("\n", " ").take(PREVIEW_MAX_LENGTH)
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
            holder.tvTime.text = DateFormat.format(NOTE_TIME_FORMAT, note.createdAt).toString()
            holder.tvText.setTextColor(listTextColor)
            holder.tvTime.setTextColor(listSecondaryTextColor)
            val semantic = semanticMetaByNoteId[note.id]
            if (semantic != null) {
                val reason =
                        semantic.reason?.takeIf { it.isNotBlank() }
                                ?: getString(R.string.ai_note_related_reason_default)
                val scorePercent =
                        ((semantic.score.coerceIn(0.0, 1.0) * 1000).roundToInt().toDouble() / 10.0)
                holder.tvSemanticMeta.visibility = View.VISIBLE
                holder.tvSemanticMeta.text =
                        getString(R.string.ai_note_semantic_item_meta, scorePercent, reason)
                holder.tvSemanticMeta.setTextColor(listSecondaryTextColor)
            } else {
                holder.tvSemanticMeta.visibility = View.GONE
                holder.tvSemanticMeta.text = ""
            }
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

    private fun colorizeText(text: String, color: Int): SpannableString {
        return SpannableString(text).apply {
            setSpan(
                    ForegroundColorSpan(color),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun syncSelectionWithVisibleNotes() {
        val validIds = notes.asSequence().map { it.id }.toSet()
        selectedNoteIds.retainAll(validIds)
        if (selectedNoteIds.isEmpty()) {
            selectionActionMode?.finish()
        } else {
            updateSelectionTitle()
        }
    }

    private fun showSemanticSearchResultStatus(query: String, count: Int) {
        binding.tvSemanticSearchStatus.visibility = View.VISIBLE
        binding.tvSemanticSearchStatus.text =
                if (count == 0) {
                    getString(R.string.ai_note_semantic_search_status_none, query)
                } else {
                    getString(R.string.ai_note_semantic_search_status_results, query, count)
                }
    }

    private suspend fun mapSemanticResultsToLocalNotes(
            semanticResults: List<AiNoteRepository.SemanticRelatedNote>
    ): MutableList<AiNoteEntity> {
        val matchedNotes = mutableListOf<AiNoteEntity>()
        val seenIds = mutableSetOf<Long>()
        semanticMetaByNoteId.clear()
        for (result in semanticResults) {
            val local = resolveLocalNote(result) ?: continue
            if (bookId != null && local.bookId != bookId) continue
            if (!seenIds.add(local.id)) continue
            matchedNotes.add(local)
            semanticMetaByNoteId[local.id] = result
        }
        return matchedNotes
    }

    private suspend fun resolveLocalNote(
            semanticResult: AiNoteRepository.SemanticRelatedNote
    ): AiNoteEntity? {
        val byLocalId = semanticResult.localId?.let { localId -> repo.getById(localId) }
        val byRemoteId =
                byLocalId
                        ?: semanticResult.remoteId?.let { remoteId ->
                            repo.getByRemoteId(remoteId)
                        }
        return byRemoteId
                ?: semanticResult.noteId.toLongOrNull()?.let { id ->
                    repo.getById(id)
                }
    }

    private fun runExportWithProgress(exportOperation: suspend () -> ExportResult) {
        lifecycleScope.launch {
            setExportInProgress(true)
            try {
                val result =
                        runCatching { exportOperation() }
                                .getOrElse { e ->
                                    ExportResult(
                                            success = false,
                                            exportedCount = 0,
                                            isEmpty = false,
                                            message =
                                                    "Export failed: ${e.message ?: "Unknown error"}"
                                    )
                                }
                showExportResult(result)
            } finally {
                setExportInProgress(false)
            }
        }
    }

    private fun handleBackNavigation(): Boolean {
        if (selectionActionMode != null) {
            selectionActionMode?.finish()
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
        return true
    }

    // Handle volume down button as back navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleBackNavigation()
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
