package my.hinoki.booxreader.data.repo

import my.hinoki.booxreader.data.db.AiProfileEntity

/**
 * Factory for creating default AI profiles
 */
class AiProfileDefaultGenerator {

    /**
     * Creates a default Gemini AI profile with placeholder API key
     */
    fun createGeminiDefaultProfile(): AiProfileEntity {
        return AiProfileEntity(
            name = "Gemini",
            modelName = "gemini-3-flash-preview",
            apiKey = "<YOUR_GEMINI_API_KEY>",
            serverBaseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent",
            systemPrompt = "You are a helpful AI assistant.",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.7,
            maxTokens = 8192,
            topP = 1.0,
            frequencyPenalty = 0.0,
            presencePenalty = 0.0,
            assistantRole = "model",
            enableGoogleSearch = false,
            extraParamsJson = null,
            remoteId = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isSynced = false
        )
    }
}
