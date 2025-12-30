package my.hinoki.booxreader.data.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import my.hinoki.booxreader.data.ui.common.BaseActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityAiProfileListBinding
import my.hinoki.booxreader.databinding.ItemAiProfileBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class AiProfileListActivity : BaseActivity() {

    private lateinit var binding: ActivityAiProfileListBinding
    private lateinit var repository: AiProfileRepository
    private val adapter = ProfileAdapter()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importProfileFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiProfileListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBarInset(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val syncRepo = UserSyncRepository(applicationContext)
        repository = AiProfileRepository(applicationContext, syncRepo)

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
        updateActiveProfileId()
        lifecycleScope.launch {
            repository.sync()
            updateActiveProfileId()
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
        
        binding.btnSync.setOnClickListener {
            syncProfiles()
        }
        
        binding.btnImport.setOnClickListener {
            openFilePicker()
        }
        
        binding.btnCreate.setOnClickListener {
            startActivity(Intent(this, AiProfileEditActivity::class.java))
        }
    }

    private fun observeData() {
        repository.allProfiles.observe(this) { profiles ->
            adapter.submitList(profiles)
        }
    }

    private fun syncProfiles() {
        lifecycleScope.launch {
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
            }
        }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
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
            val oldId = activeProfileId
            activeProfileId = id
            // Notify changes to refresh UI (could be optimized)
            notifyDataSetChanged()
        }

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
                    2 -> { // Delete
                        lifecycleScope.launch {
                            repository.deleteProfile(profile)
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
