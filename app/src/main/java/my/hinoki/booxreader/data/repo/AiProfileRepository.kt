package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.settings.ReaderSettings

class AiProfileRepository(private val context: Context, private val syncRepo: UserSyncRepository) {
    private val db = AppDatabase.get(context)
    private val dao = db.aiProfileDao()
    private val prefs =
            context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val defaultGenerator = AiProfileDefaultGenerator()

    val allProfiles: LiveData<List<AiProfileEntity>> =
            dao.getAll()
                    .map { list ->
                        list.sortedWith(
                                compareByDescending<AiProfileEntity> { it.updatedAt }
                                        .thenByDescending { it.id }
                        )
                    }
                    .asLiveData()

    suspend fun importProfile(jsonString: String): AiProfileEntity =
            withContext(Dispatchers.IO) {
                try {
                    // Check if JSON is a valid profile structure
                    // We use a temporary data class or Map to validate basic fields
                    val profileMap = gson.fromJson(jsonString, Map::class.java)

                    val name =
                            profileMap["name"] as? String
                                    ?: context.getString(
                                            my.hinoki
                                                    .booxreader
                                                    .R
                                                    .string
                                                    .ai_profile_default_imported_name
                                    )
                    val modelName = profileMap["modelName"] as? String ?: "deepseek-chat"
                    val apiKey = profileMap["apiKey"] as? String ?: ""
                    val serverBaseUrl = profileMap["serverBaseUrl"] as? String ?: ""
                    val systemPrompt = profileMap["systemPrompt"] as? String ?: ""
                    val userPromptTemplate = profileMap["userPromptTemplate"] as? String ?: "%s"
                    val useStreaming = profileMap["useStreaming"] as? Boolean ?: false
                    val enableGoogleSearch = profileMap["enableGoogleSearch"] as? Boolean ?: true
                    val extraParamsJson =
                            profileMap["extraParamsJson"]?.let { value ->
                                when (value) {
                                    is String -> value
                                    else -> gson.toJson(value)
                                }
                            }

                    val entity =
                            AiProfileEntity(
                                    name = name,
                                    modelName = modelName,
                                    apiKey = apiKey,
                                    serverBaseUrl = serverBaseUrl,
                                    systemPrompt = systemPrompt,
                                    userPromptTemplate = userPromptTemplate,
                                    assistantRole = profileMap["assistantRole"] as? String
                                                    ?: "assistant",
                                    useStreaming = useStreaming,
                                    enableGoogleSearch = enableGoogleSearch,
                                    // Additional fields with defaults
                                    temperature = (profileMap["temperature"] as? Double) ?: 0.7,
                                    maxTokens = (profileMap["maxTokens"] as? Double)?.toInt()
                                                    ?: 4096,
                                    topP = (profileMap["topP"] as? Double) ?: 1.0,
                                    frequencyPenalty = (profileMap["frequencyPenalty"] as? Double)
                                                    ?: 0.0,
                                    presencePenalty = (profileMap["presencePenalty"] as? Double)
                                                    ?: 0.0,
                                    extraParamsJson = extraParamsJson
                            )

                    // Add the profile (this will also trigger an initial push)
                    return@withContext addProfile(entity)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw IllegalArgumentException("Invalid Profile JSON: ${e.message}")
                }
            }

    suspend fun addProfile(profile: AiProfileEntity): AiProfileEntity =
            withContext(Dispatchers.IO) {
                // Always mark new profiles as needing sync
                val unsyncedProfile = profile.copy(isSynced = false)
                val newId = dao.insert(unsyncedProfile)
                // Get the saved profile with generated ID
                val saved = dao.getById(newId)
                if (saved != null) {
                    val remoteId = syncRepo.pushProfile(saved)
                    if (!remoteId.isNullOrBlank()) {
                        val synced = saved.copy(remoteId = remoteId, isSynced = true)
                        dao.update(synced)
                        ensureSingleProfileAppliedIfNeeded()
                        return@withContext synced
                    }
                    ensureSingleProfileAppliedIfNeeded()
                    return@withContext saved
                }
                throw IllegalStateException("Failed to retrieve saved profile after insertion")
            }

    suspend fun updateProfile(profile: AiProfileEntity): AiProfileEntity =
            withContext(Dispatchers.IO) {
                // Mark as dirty before pushing so offline edits are retried later
                val updatedProfile =
                        profile.copy(updatedAt = System.currentTimeMillis(), isSynced = false)
                dao.update(updatedProfile)

                val remoteId = syncRepo.pushProfile(updatedProfile)
                val syncedProfile =
                        if (!remoteId.isNullOrBlank()) {
                            val synced = updatedProfile.copy(remoteId = remoteId, isSynced = true)
                            dao.update(synced)
                            synced
                        } else {
                            updatedProfile
                        }

                return@withContext syncedProfile.also { applyProfile(it.id) }
            }

    suspend fun deleteProfile(profile: AiProfileEntity): Boolean =
            withContext(Dispatchers.IO) {
                if (!profile.remoteId.isNullOrBlank()) {
                    val deletedRemote = syncRepo.deleteAiProfile(profile.remoteId)
                    if (!deletedRemote) {
                        return@withContext false
                    }
                }
                dao.delete(profile)
                val currentSettings = ReaderSettings.fromPrefs(prefs)
                if (currentSettings.activeProfileId == profile.id) {
                    currentSettings
                            .copy(activeProfileId = -1L, updatedAt = System.currentTimeMillis())
                            .saveTo(prefs)
                }
                ensureSingleProfileAppliedIfNeeded()
                true
            }

    suspend fun applyProfile(profileId: Long) =
            withContext(Dispatchers.IO) {
                val profile = dao.getById(profileId) ?: return@withContext

                // Load current settings to preserve other values (font, tap, etc)
                val currentSettings = ReaderSettings.fromPrefs(prefs)

                val newSettings =
                        currentSettings.copy(
                                aiModelName = profile.modelName,
                                apiKey = profile.apiKey,
                                serverBaseUrl = profile.serverBaseUrl,
                                aiSystemPrompt = profile.systemPrompt,
                                aiUserPromptTemplate = profile.userPromptTemplate,
                                assistantRole = profile.assistantRole,
                                enableGoogleSearch = profile.enableGoogleSearch,
                                useStreaming = profile.useStreaming,
                                temperature = profile.temperature,
                                maxTokens = profile.maxTokens,
                                topP = profile.topP,
                                frequencyPenalty = profile.frequencyPenalty,
                                presencePenalty = profile.presencePenalty,
                                updatedAt = System.currentTimeMillis(),
                                activeProfileId = profile.id
                        )

                newSettings.saveTo(prefs)

                // Also push new settings to Firebase
                syncRepo.pushSettings(newSettings)
            }

    suspend fun sync(): Int =
            withContext(Dispatchers.IO) {
                var totalSynced = 0

                try {
                    // First push any local changes to ensure they're backed up
                    val localProfiles = dao.getPendingSync()
                    localProfiles.forEach { localProfile ->
                        try {
                            val remoteId = syncRepo.pushProfile(localProfile)
                            if (!remoteId.isNullOrBlank()) {
                                dao.update(localProfile.copy(remoteId = remoteId, isSynced = true))
                                totalSynced++
                            }
                        } catch (e: Exception) {
                            // Log error but continue with other profiles
                            e.printStackTrace()
                        }
                    }

                    // Then pull latest changes from cloud
                    val pulledCount = syncRepo.pullProfiles()
                    totalSynced += pulledCount

                    // Also sync settings to get the latest 'activeProfileId'
                    try {
                        syncRepo.pullSettingsIfNewer()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    ensureSingleProfileAppliedIfNeeded()
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }

                return@withContext totalSynced
            }

    suspend fun ensureDefaultProfile(): Boolean =
            withContext(Dispatchers.IO) {
                val profiles = dao.getAllList()

                if (profiles.isEmpty()) {
                    // No profiles exist, create default
                    val defaultProfile = defaultGenerator.createGeminiDefaultProfile()
                    val saved = addProfile(defaultProfile)
                    applyProfile(saved.id)
                    return@withContext true
                }

                // Profiles already exist, do nothing
                return@withContext false
            }

    private suspend fun ensureSingleProfileAppliedIfNeeded() {
        val profiles = dao.getAllList()
        if (profiles.size != 1) return
        val onlyProfile = profiles.first()
        val currentSettings = ReaderSettings.fromPrefs(prefs)
        if (currentSettings.activeProfileId == onlyProfile.id) return
        applyProfile(onlyProfile.id)
    }
}
