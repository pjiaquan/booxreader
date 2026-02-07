package my.hinoki.booxreader.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import com.google.gson.JsonParser
import my.hinoki.booxreader.ui.common.BaseActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ContrastMode
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.databinding.ActivityAiProfileListBinding
import my.hinoki.booxreader.databinding.ItemAiProfileBinding
import android.content.res.ColorStateList
import android.graphics.Color
import java.io.BufferedReader
import java.io.InputStreamReader

class AiProfileListActivity : BaseActivity() {

    private lateinit var binding: ActivityAiProfileListBinding
    private lateinit var repository: AiProfileRepository
    private val adapter = ProfileAdapter()
    private val gson = Gson()
    private var listTextColor: Int = Color.BLACK
    private var listSecondaryTextColor: Int = Color.DKGRAY
    private var listBackgroundColor: Int = Color.WHITE
    private var tagBackgroundColor: Int = Color.parseColor("#E6E0D6")
    private var pendingExportJson: String? = null
    private var pendingExportName: String? = null
    private val selectedProfileIds = mutableSetOf<Long>()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importProfileFromFile(it) }
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingExportJson
            val name = pendingExportName
            pendingExportJson = null
            pendingExportName = null
            if (uri == null || json == null) {
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            outputStream.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }
                    Toast.makeText(
                        this@AiProfileListActivity,
                        getString(R.string.ai_profile_export_success, name ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@AiProfileListActivity,
                        getString(R.string.ai_profile_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                    e.printStackTrace()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiProfileListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyActionBarPadding(binding.selectionBar)

        val syncRepo = UserSyncRepository(applicationContext)
        repository = AiProfileRepository(applicationContext, syncRepo)

        setLoading(true)
        applyThemeFromSettings()
        setupUI()
        observeData()
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

    override fun onResume() {
        super.onResume()
        applyThemeFromSettings()
        updateActiveProfileId()
        lifecycleScope.launch {
            setLoading(true)
            try {
                repository.sync()
            } finally {
                updateActiveProfileId()
                setLoading(false)
            }
        }
    }

    private fun updateActiveProfileId() {
        val prefs = getSharedPreferences(my.hinoki.booxreader.data.settings.ReaderSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val settings = my.hinoki.booxreader.data.settings.ReaderSettings.fromPrefs(prefs)
        adapter.setActiveProfileId(settings.activeProfileId)
    }

    private fun setupUI() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // Disable item animator to prevent automatic reordering animations
        binding.recyclerView.itemAnimator = null

        binding.btnDeleteSelected.setOnClickListener {
            showBulkDeleteConfirmation()
        }
        
        binding.btnSync.setOnClickListener {
            syncProfiles()
        }
        
        binding.btnImport.setOnClickListener {
            openFilePicker()
        }
        
        binding.btnCreate.setOnClickListener {
            startActivity(Intent(this, AiProfileEditActivity::class.java))
        }

        updateSelectionUi()
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.btnSync.isEnabled = !loading
        binding.btnImport.isEnabled = !loading
        binding.btnCreate.isEnabled = !loading
        binding.cbSelectAll.isEnabled = !loading
        binding.btnDeleteSelected.isEnabled = !loading && selectedProfileIds.isNotEmpty()
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
        tagBackgroundColor = ColorUtils.setAlphaComponent(listTextColor, 48)

        binding.root.setBackgroundColor(listBackgroundColor)
        binding.recyclerView.setBackgroundColor(listBackgroundColor)
        binding.bottomBar.setBackgroundColor(listBackgroundColor)
        binding.loadingOverlay.setBackgroundColor(listBackgroundColor)
        binding.tvLoading.setTextColor(listTextColor)

        val tint = ColorStateList.valueOf(listTextColor)
        binding.btnSync.imageTintList = tint
        binding.btnImport.imageTintList = tint
        binding.btnCreate.imageTintList = tint
        binding.cbSelectAll.setTextColor(listTextColor)
        binding.btnDeleteSelected.setTextColor(listTextColor)

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = listBackgroundColor
            window.navigationBarColor = listBackgroundColor
        }
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        val useLightIcons = mode == ContrastMode.NORMAL || mode == ContrastMode.SEPIA
        insetsController.isAppearanceLightStatusBars = useLightIcons
        insetsController.isAppearanceLightNavigationBars = useLightIcons

        adapter.notifyDataSetChanged()
    }

    private fun observeData() {
        repository.allProfiles.observe(this) { profiles ->
            val currentIds = profiles.map { it.id }.toSet()
            selectedProfileIds.retainAll(currentIds)
            adapter.submitList(profiles)
            updateSelectionUi()
        }
    }

    private fun updateSelectionUi() {
        val totalCount = adapter.itemCount
        val selectedCount = selectedProfileIds.size
        val allSelected = totalCount > 0 && selectedCount == totalCount
        binding.cbSelectAll.setOnCheckedChangeListener(null)
        binding.cbSelectAll.isChecked = allSelected
        binding.cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            val profiles = adapter.currentList()
            if (profiles.isEmpty()) {
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                selectedProfileIds.clear()
                selectedProfileIds.addAll(profiles.map { it.id })
            } else if (selectedProfileIds.size == profiles.size) {
                selectedProfileIds.clear()
            }
            adapter.notifyDataSetChanged()
            updateSelectionUi()
        }

        binding.cbSelectAll.text = if (totalCount > 0) {
            getString(R.string.ai_profile_select_all_count, selectedCount, totalCount)
        } else {
            getString(R.string.ai_profile_select_all)
        }
        binding.btnDeleteSelected.text =
            if (selectedCount > 0) {
                getString(R.string.ai_profile_delete_selected_count, selectedCount)
            } else {
                getString(R.string.ai_profile_delete_selected)
            }
        binding.btnDeleteSelected.isEnabled =
            binding.loadingOverlay.visibility != View.VISIBLE && selectedCount > 0
    }

    private fun toggleProfileSelection(profileId: Long, selected: Boolean) {
        if (selected) {
            selectedProfileIds.add(profileId)
        } else {
            selectedProfileIds.remove(profileId)
        }
        updateSelectionUi()
    }

    private fun showBulkDeleteConfirmation() {
        val selectedCount = selectedProfileIds.size
        if (selectedCount == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_profile_bulk_delete_title)
            .setMessage(getString(R.string.ai_profile_bulk_delete_message, selectedCount))
            .setPositiveButton(R.string.ai_profile_option_delete) { _, _ ->
                deleteSelectedProfiles()
            }
            .setNegativeButton(R.string.ai_profile_apply_negative, null)
            .show()
    }

    private fun deleteSelectedProfiles() {
        val selectedIds = selectedProfileIds.toSet()
        if (selectedIds.isEmpty()) return
        lifecycleScope.launch {
            setLoading(true)
            try {
                val selectedProfiles = adapter.currentList().filter { it.id in selectedIds }
                var deletedCount = 0
                selectedProfiles.forEach { profile ->
                    if (repository.deleteProfile(profile)) {
                        deletedCount++
                    }
                }
                selectedProfileIds.clear()
                Toast.makeText(
                    this@AiProfileListActivity,
                    getString(R.string.ai_profile_bulk_delete_success, deletedCount),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                setLoading(false)
                updateSelectionUi()
            }
        }
    }

    private fun syncProfiles() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                val count = repository.sync()
                if (count > 0) {
                    Toast.makeText(
                        this@AiProfileListActivity, 
                        getString(R.string.ai_profile_sync_completed_count, count), 
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@AiProfileListActivity, 
                        getString(R.string.ai_profile_sync_completed_up_to_date), 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@AiProfileListActivity, 
                    getString(R.string.ai_profile_sync_failed, e.message ?: ""), 
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun exportProfile(profile: AiProfileEntity) {
        val json = buildExportJson(profile)
        val fileName = buildExportFileName(profile.name)
        pendingExportJson = json
        pendingExportName = fileName
        exportLauncher.launch(fileName)
    }

    private fun buildExportJson(profile: AiProfileEntity): String {
        val extra = profile.extraParamsJson?.takeIf { it.isNotBlank() }
        val extraElement = extra?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
        val payload = linkedMapOf(
            "name" to profile.name,
            "modelName" to profile.modelName,
            "apiKey" to profile.apiKey,
            "serverBaseUrl" to profile.serverBaseUrl,
            "systemPrompt" to profile.systemPrompt,
            "userPromptTemplate" to profile.userPromptTemplate,
            "useStreaming" to profile.useStreaming,
            "temperature" to profile.temperature,
            "maxTokens" to profile.maxTokens,
            "topP" to profile.topP,
            "frequencyPenalty" to profile.frequencyPenalty,
            "presencePenalty" to profile.presencePenalty,
            "assistantRole" to profile.assistantRole,
            "enableGoogleSearch" to profile.enableGoogleSearch,
            "extraParamsJson" to (extraElement ?: extra)
        )
        return gson.toJson(payload)
    }

    private fun buildExportFileName(name: String): String {
        val base = name.trim().ifBlank { "ai-profile" }
        val safe = base.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        val finalName = safe.ifBlank { "ai-profile" }
        return if (finalName.endsWith(".json", ignoreCase = true)) {
            finalName
        } else {
            "$finalName.json"
        }
    }

    private fun importProfileFromFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonString = reader.readText()
                    val importedProfile = repository.importProfile(jsonString)
                    Toast.makeText(
                        this@AiProfileListActivity, 
                        getString(R.string.ai_profile_import_success, importedProfile.name), 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@AiProfileListActivity, 
                    getString(R.string.ai_profile_import_failed, e.message ?: ""), 
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            }
        }
    }

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {
        private var list: List<AiProfileEntity> = emptyList()
        private var activeProfileId: Long = -1L

        fun submitList(newList: List<AiProfileEntity>) {
            list = newList
            notifyDataSetChanged()
        }

        fun setActiveProfileId(id: Long) {
            activeProfileId = id
            // Notify changes to refresh UI (could be optimized)
            notifyDataSetChanged()
        }

        fun currentList(): List<AiProfileEntity> = list

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAiProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(list[position])
        }

        override fun getItemCount() = list.size

        inner class ViewHolder(val binding: ItemAiProfileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(profile: AiProfileEntity) {
                binding.tvName.text = profile.name
                binding.tvModel.text = profile.modelName
                binding.tvName.setTextColor(listTextColor)
                binding.tvModel.setTextColor(listSecondaryTextColor)
                binding.tvCurrent.setTextColor(listTextColor)
                binding.tvCurrent.backgroundTintList =
                        ColorStateList.valueOf(tagBackgroundColor)
                binding.cbSelect.buttonTintList = ColorStateList.valueOf(listTextColor)
                binding.cbSelect.setOnCheckedChangeListener(null)
                binding.cbSelect.isChecked = selectedProfileIds.contains(profile.id)
                binding.cbSelect.contentDescription =
                    getString(R.string.ai_profile_select_profile, profile.name)
                binding.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    toggleProfileSelection(profile.id, isChecked)
                }
                
                if (profile.id == activeProfileId) {
                    binding.tvCurrent.visibility = android.view.View.VISIBLE
                    // Optional: Highlight background or something
                } else {
                    binding.tvCurrent.visibility = android.view.View.GONE
                }
                
                binding.root.setOnClickListener {
                    applyProfile(profile)
                }
                
                binding.root.setOnLongClickListener {
                    showOptionsDialog(profile)
                    true
                }
            }
        }
    }
    
    private fun applyProfile(profile: AiProfileEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_profile_apply_title)
            .setMessage(getString(R.string.ai_profile_apply_message, profile.name))
            .setPositiveButton(R.string.ai_profile_apply_positive) { _, _ ->
                lifecycleScope.launch {
                    repository.applyProfile(profile.id)
                    updateActiveProfileId()
                    Toast.makeText(this@AiProfileListActivity, getString(R.string.ai_profile_settings_applied), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.ai_profile_apply_negative, null)
            .show()
    }

    private fun showOptionsDialog(profile: AiProfileEntity) {
        val options = resources.getStringArray(R.array.ai_profile_options)
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Edit
                        val intent = Intent(this, AiProfileEditActivity::class.java)
                        intent.putExtra("profile_id", profile.id)
                        startActivity(intent)
                    }
                    1 -> { // Duplicate
                        lifecycleScope.launch {
                            duplicateProfile(profile)
                        }
                    }
                    2 -> { // Export
                        exportProfile(profile)
                    }
                    3 -> { // Delete
                        lifecycleScope.launch {
                            val deleted = repository.deleteProfile(profile)
                            if (!deleted) {
                                Toast.makeText(
                                    this@AiProfileListActivity,
                                    getString(R.string.ai_profile_delete_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private suspend fun duplicateProfile(profile: AiProfileEntity) {
        val now = System.currentTimeMillis()
        val copy = profile.copy(
            id = 0,
            remoteId = null,
            isSynced = false,
            name = getString(R.string.ai_profile_copy_name, profile.name),
            createdAt = now,
            updatedAt = now
        )
        repository.addProfile(copy)
        Toast.makeText(this, getString(R.string.ai_profile_duplicated), Toast.LENGTH_SHORT).show()
    }
    
    companion object {
        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, AiProfileListActivity::class.java))
        }
    }
}
