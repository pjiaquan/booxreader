package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ai_profiles")
data class AiProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Core Settings
    val name: String, // Profile name (e.g. "DeepSeek Chat")
    val modelName: String,
    val apiKey: String,
    val serverBaseUrl: String,
    val systemPrompt: String,
    val userPromptTemplate: String,
    val useStreaming: Boolean,

    // Generation Parameters
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val assistantRole: String = "assistant",
    
    // Sync Metadata
    val remoteId: String? = null, // Firebase Doc ID
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
