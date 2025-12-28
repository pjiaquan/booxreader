package my.hinoki.booxreader.data.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.repo.AiProfileRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.ui.common.BaseActivity
import my.hinoki.booxreader.databinding.ActivityAiProfileEditBinding
import org.json.JSONObject

class AiProfileEditActivity : BaseActivity() {

    private lateinit var binding: ActivityAiProfileEditBinding
    private lateinit var repository: AiProfileRepository
    private var currentProfileId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val syncRepo = UserSyncRepository(applicationContext)
        repository = AiProfileRepository(applicationContext, syncRepo)

        currentProfileId = intent.getLongExtra("profile_id", -1)

        if (currentProfileId != -1L) {
            loadProfile(currentProfileId)
        }

        binding.btnSave.setOnClickListener {
            saveProfile()
        }
    }

    private fun loadProfile(id: Long) {
        lifecycleScope.launch {
            // We need a direct way to get profile by ID in repo, or access DAO directly for now via DB
            val db = my.hinoki.booxreader.data.db.AppDatabase.get(applicationContext)
            val profile = db.aiProfileDao().getById(id)
            
            profile?.let { p ->
                binding.etName.setText(p.name)
                binding.etModelName.setText(p.modelName)
                binding.etApiKey.setText(p.apiKey)
                binding.etBaseUrl.setText(p.serverBaseUrl)
                binding.etSystemPrompt.setText(p.systemPrompt)
                binding.etUserPromptTemplate.setText(p.userPromptTemplate)
                binding.cbStreaming.isChecked = p.useStreaming
                binding.cbEnableGoogleSearch.isChecked = p.enableGoogleSearch
                
                binding.etTemperature.setText(p.temperature.toString())
                binding.etMaxTokens.setText(p.maxTokens.toString())
                binding.etTopP.setText(p.topP.toString())
                binding.etFrequencyPenalty.setText(p.frequencyPenalty.toString())
                binding.etPresencePenalty.setText(p.presencePenalty.toString())
                binding.etAssistantRole.setText(p.assistantRole)
                binding.etExtraParams.setText(p.extraParamsJson.orEmpty())
            }
        }
    }

    private fun saveProfile() {
        val name = binding.etName.text.toString()
        val modelName = binding.etModelName.text.toString()
        val apiKey = binding.etApiKey.text.toString()
        val baseUrl = binding.etBaseUrl.text.toString()
        val systemPrompt = binding.etSystemPrompt.text.toString()
        var userPromptTemplate = binding.etUserPromptTemplate.text.toString()
        val useStreaming = binding.cbStreaming.isChecked
        val enableGoogleSearch = binding.cbEnableGoogleSearch.isChecked
        
        val temperature = binding.etTemperature.text.toString().toDoubleOrNull() ?: 0.7
        val maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 4096
        val topP = binding.etTopP.text.toString().toDoubleOrNull() ?: 1.0
        val frequencyPenalty = binding.etFrequencyPenalty.text.toString().toDoubleOrNull() ?: 0.0
        val presencePenalty = binding.etPresencePenalty.text.toString().toDoubleOrNull() ?: 0.0
        val assistantRole = binding.etAssistantRole.text.toString().takeIf { it.isNotBlank() } ?: "assistant"
        val extraParamsRaw = binding.etExtraParams.text.toString().trim()
        val extraParamsJson =
            if (extraParamsRaw.isNotBlank()) {
                val parsed = runCatching { JSONObject(extraParamsRaw) }.getOrNull()
                if (parsed == null) {
                    Toast.makeText(this, getString(R.string.ai_profile_invalid_extra_params), Toast.LENGTH_SHORT).show()
                    return
                }
                extraParamsRaw
            } else {
                null
            }

        if (name.isBlank() || modelName.isBlank()) {
            Toast.makeText(this, getString(R.string.ai_profile_required_fields), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (userPromptTemplate.isBlank()) userPromptTemplate = "%s"

        lifecycleScope.launch {
            if (currentProfileId != -1L) {
                // Update existing
                // Need to fetch original entity to preserve ID and remoteID
                val db = my.hinoki.booxreader.data.db.AppDatabase.get(applicationContext)
                val original = db.aiProfileDao().getById(currentProfileId) ?: return@launch
                
                val updated = original.copy(
                    name = name,
                    modelName = modelName,
                    apiKey = apiKey,
                    serverBaseUrl = baseUrl,
                    systemPrompt = systemPrompt,
                    userPromptTemplate = userPromptTemplate,
                    useStreaming = useStreaming,
                    enableGoogleSearch = enableGoogleSearch,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    topP = topP,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    assistantRole = assistantRole,
                    extraParamsJson = extraParamsJson
                )
                repository.updateProfile(updated)
            } else {
                // Create new
                val newProfile = AiProfileEntity(
                    name = name,
                    modelName = modelName,
                    apiKey = apiKey,
                    serverBaseUrl = baseUrl,
                    systemPrompt = systemPrompt,
                    userPromptTemplate = userPromptTemplate,
                    useStreaming = useStreaming,
                    enableGoogleSearch = enableGoogleSearch,
                    temperature = temperature,
                    maxTokens = maxTokens,
                    topP = topP,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    assistantRole = assistantRole,
                    extraParamsJson = extraParamsJson
                )
                repository.addProfile(newProfile)
            }
            finish()
        }
    }
}
