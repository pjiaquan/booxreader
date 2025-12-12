package my.hinoki.booxreader.data.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityAiProfileListBinding
import my.hinoki.booxreader.databinding.ItemAiProfileBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class AiProfileListActivity : AppCompatActivity() {

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

        val syncRepo = UserSyncRepository(applicationContext)
        repository = AiProfileRepository(applicationContext, syncRepo)

        setupUI()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            repository.sync()
        }
    }

    private fun setupUI() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        
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
                        "Sync completed: $count profiles updated", 
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@AiProfileListActivity, 
                        "Sync completed: All profiles are up to date", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@AiProfileListActivity, 
                    "Sync failed: ${e.message}", 
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
                        "Profile '${importedProfile.name}' imported and synced successfully", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@AiProfileListActivity, 
                    "Import Failed: ${e.message}", 
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            }
        }
    }

    private inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {
        private var list: List<AiProfileEntity> = emptyList()

        fun submitList(newList: List<AiProfileEntity>) {
            list = newList
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
            .setTitle("Apply Profile")
            .setMessage("Apply settings from '${profile.name}'?")
            .setPositiveButton("Apply") { _, _ ->
                lifecycleScope.launch {
                    repository.applyProfile(profile.id)
                    Toast.makeText(this@AiProfileListActivity, "Settings Applied", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOptionsDialog(profile: AiProfileEntity) {
        val options = arrayOf("Edit", "Duplicate", "Delete")
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
            name = "${profile.name} (Copy)",
            createdAt = now,
            updatedAt = now
        )
        repository.addProfile(copy)
        Toast.makeText(this, "Profile duplicated", Toast.LENGTH_SHORT).show()
    }
    
    companion object {
        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, AiProfileListActivity::class.java))
        }
    }
}
