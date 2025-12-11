package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.settings.ReaderSettings

class AiProfileRepository(
    context: Context,
    private val syncRepo: UserSyncRepository
) {
    private val db = AppDatabase.get(context)
    private val dao = db.aiProfileDao()
    private val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    val allProfiles: LiveData<List<AiProfileEntity>> = dao.getAll().asLiveData()

    suspend fun importProfile(jsonString: String) = withContext(Dispatchers.IO) {
        try {
            // Check if JSON is a valid profile structure
            // We use a temporary data class or Map to validate basic fields
            val profileMap = gson.fromJson(jsonString, Map::class.java)
            
            val name = profileMap["name"] as? String ?: "Imported Profile"
            val modelName = profileMap["modelName"] as? String ?: "deepseek-chat"
            val apiKey = profileMap["apiKey"] as? String ?: ""
            val serverBaseUrl = profileMap["serverBaseUrl"] as? String ?: ""
            val systemPrompt = profileMap["systemPrompt"] as? String ?: ""
            val userPromptTemplate = profileMap["userPromptTemplate"] as? String ?: "%s"
            val useStreaming = profileMap["useStreaming"] as? Boolean ?: false

            val entity = AiProfileEntity(
                name = name,
                modelName = modelName,
                apiKey = apiKey,
                serverBaseUrl = serverBaseUrl,
                systemPrompt = systemPrompt,
                userPromptTemplate = userPromptTemplate,
                assistantRole = profileMap["assistantRole"] as? String ?: "assistant",
                useStreaming = useStreaming
            )
            
            addProfile(entity)
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalArgumentException("Invalid Profile JSON")
        }
    }

    suspend fun addProfile(profile: AiProfileEntity) = withContext(Dispatchers.IO) {
        val newId = dao.insert(profile)
        // Trigger sync immediately
        val saved = dao.getById(newId)
        if (saved != null) {
            syncRepo.pushProfile(saved)
        }
    }
    
    suspend fun updateProfile(profile: AiProfileEntity) = withContext(Dispatchers.IO) {
        dao.update(profile.copy(updatedAt = System.currentTimeMillis()))
        syncRepo.pushProfile(profile)
    }

    suspend fun deleteProfile(profile: AiProfileEntity) = withContext(Dispatchers.IO) {
        dao.delete(profile)
        // Note: We are not handling remote delete sync yet for simplicity, 
        // as per current sync architecture which is mostly append/update.
    }

    suspend fun applyProfile(profileId: Long) = withContext(Dispatchers.IO) {
        val profile = dao.getById(profileId) ?: return@withContext
        
        // Load current settings to preserve other values (font, tap, etc)
        val currentSettings = ReaderSettings.fromPrefs(prefs)
        
        val newSettings = currentSettings.copy(
            aiModelName = profile.modelName,
            apiKey = profile.apiKey,
            serverBaseUrl = profile.serverBaseUrl,
            aiSystemPrompt = profile.systemPrompt,
            aiUserPromptTemplate = profile.userPromptTemplate,
            assistantRole = profile.assistantRole,
            useStreaming = profile.useStreaming,
            updatedAt = System.currentTimeMillis()
        )
        
        newSettings.saveTo(prefs)
        
        // Also push new settings to Firebase
        syncRepo.pushSettings(newSettings)
    }
    
    suspend fun sync() = withContext(Dispatchers.IO) {
        syncRepo.pullProfiles()
    }
}
