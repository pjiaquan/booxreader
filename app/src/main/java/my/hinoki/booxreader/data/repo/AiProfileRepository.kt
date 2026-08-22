package my.hinoki.booxreader.data.repo
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.settings.ReaderSettings

class AiProfileRepository(private val context: Context, private val syncRepo: UserSyncRepository) {
    private val db = AppDatabase.get()
    private val dao = db.aiProfileDao()
    private val prefs =
            context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
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
                    val json = Json.parseToJsonElement(jsonString).jsonObject

                    val name =
                            json.optString("name")
                                    ?: context.getString(
                                            my.hinoki
                                                    .booxreader
                                                    .R
                                                    .string
                                                    .ai_profile_default_imported_name
                                    )
                    val modelName = json.optString("modelName") ?: "deepseek-chat"
                    val apiKey = json.optString("apiKey") ?: ""
                    val serverBaseUrl = json.optString("serverBaseUrl") ?: ""
                    val systemPrompt = json.optString("systemPrompt") ?: ""
                    val userPromptTemplate = json.optString("userPromptTemplate") ?: "%s"
                    val useStreaming = json["useStreaming"]?.jsonPrimitive?.booleanOrNull ?: false
                    val enableGoogleSearch =
                            json["enableGoogleSearch"]?.jsonPrimitive?.booleanOrNull ?: true
                    val extraParamsJson =
                            json["extraParamsJson"]?.let { value ->
                                when (value) {
                                    is JsonPrimitive -> value.contentOrNull ?: value.toString()
                                    else -> value.toString()
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
                                    assistantRole = json.optString("assistantRole") ?: "assistant",
                                    useStreaming = useStreaming,
                                    enableGoogleSearch = enableGoogleSearch,
                                    // Additional fields with defaults
                                    temperature =
                                            json["temperature"]?.jsonPrimitive?.doubleOrNull
                                                    ?: 0.7,
                                    maxTokens =
                                            json["maxTokens"]?.jsonPrimitive?.doubleOrNull?.toInt()
                                                    ?: 4096,
                                    topP = json["topP"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                                    frequencyPenalty =
                                            json["frequencyPenalty"]?.jsonPrimitive?.doubleOrNull
                                                    ?: 0.0,
                                    presencePenalty =
                                            json["presencePenalty"]?.jsonPrimitive?.doubleOrNull
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
                val profileRemoteId = profile.remoteId
                if (!profileRemoteId.isNullOrBlank()) {
                    val deletedRemote = syncRepo.deleteAiProfile(profileRemoteId)
                    if (!deletedRemote) {
                        return@withContext false
                    }
                }
                dao.delete(profile)
                val currentSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
                if (currentSettings.activeProfileId == profile.id) {
                    currentSettings
                            .copy(activeProfileId = -1L, updatedAt = System.currentTimeMillis())
                            .saveTo(SharedPreferencesStorage(prefs))
                }
                ensureSingleProfileAppliedIfNeeded()
                true
            }

    suspend fun applyProfile(profileId: Long) =
            withContext(Dispatchers.IO) {
                val profile = dao.getById(profileId) ?: return@withContext

                // Load current settings to preserve other values (font, tap, etc)
                val currentSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))

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

                newSettings.saveTo(SharedPreferencesStorage(prefs))

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
        val currentSettings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
        if (currentSettings.activeProfileId == onlyProfile.id) return
        applyProfile(onlyProfile.id)
    }
}

/** 安全讀取 JsonObject 欄位：非字串或缺失回傳 null（對應 Gson 的 `as? String`）。 */
private fun JsonObject.optString(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
