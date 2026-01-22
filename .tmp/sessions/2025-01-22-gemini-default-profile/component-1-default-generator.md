# Component: Default Profile Generator

## Purpose
Factory class for creating default AI profiles with proper configuration.

## Interface

```kotlin
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
            modelName = "gemini-1.5-flash",
            apiKey = "<YOUR_GEMINI_API_KEY>",
            serverBaseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent",
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
```

## Tasks

1. ✅ Create file `app/src/main/java/my/hinoki/booxreader/data/repo/AiProfileDefaultGenerator.kt`
2. ✅ Implement the `AiProfileDefaultGenerator` class
3. ✅ Implement `createGeminiDefaultProfile()` method with proper Gemini configuration
4. ✅ Verify file compiles

## Validation Criteria

- [x] File created at correct path
- [x] Class and method signatures match interface
- [x] Returns a valid `AiProfileEntity`
- [x] All required fields are properly configured
- [x] Code compiles without errors

## Status: COMPLETED ✅

## Notes

- Uses `gemini-3-flash-preview` model which is fast and cost-effective
- API key is a placeholder that guides users to get their own key
- Profile is marked as unsynced (`isSynced = false`) to trigger cloud sync
- Assistant role is "model" for Gemini API compatibility
