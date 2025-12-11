package my.hinoki.booxreader.data.repo

import my.hinoki.booxreader.data.db.AiProfileEntity
import org.junit.Assert.*
import org.junit.Test

class AiProfileSyncTest {

    @Test
    fun testAiProfileEntityHasAllRequiredFields() {
        // Test that AiProfileEntity has all the required fields
        val entity = AiProfileEntity(
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "test-api-key",
            serverBaseUrl = "https://api.example.com",
            systemPrompt = "You are a helpful assistant",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.8,
            maxTokens = 2048,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            assistantRole = "system"
        )

        // Verify all fields are present
        assertEquals("Test Profile", entity.name)
        assertEquals("gpt-4", entity.modelName)
        assertEquals("test-api-key", entity.apiKey)
        assertEquals("https://api.example.com", entity.serverBaseUrl)
        assertEquals("You are a helpful assistant", entity.systemPrompt)
        assertEquals("%s", entity.userPromptTemplate)
        assertEquals(true, entity.useStreaming)
        assertEquals(0.8, entity.temperature, 0.001)
        assertEquals(2048, entity.maxTokens)
        assertEquals(0.9, entity.topP, 0.001)
        assertEquals(0.1, entity.frequencyPenalty, 0.001)
        assertEquals(0.2, entity.presencePenalty, 0.001)
        assertEquals("system", entity.assistantRole)
    }

    @Test
    fun testRemoteAiProfileDataClassHasAllFields() {
        // Test that RemoteAiProfile has all the required fields
        val remoteProfile = RemoteAiProfile(
            remoteId = "test-id",
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "test-api-key",
            serverBaseUrl = "https://api.example.com",
            systemPrompt = "You are a helpful assistant",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.8,
            maxTokens = 2048,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            assistantRole = "system",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Verify all fields are present
        assertEquals("test-id", remoteProfile.remoteId)
        assertEquals("Test Profile", remoteProfile.name)
        assertEquals("gpt-4", remoteProfile.modelName)
        assertEquals("test-api-key", remoteProfile.apiKey)
        assertEquals("https://api.example.com", remoteProfile.serverBaseUrl)
        assertEquals("You are a helpful assistant", remoteProfile.systemPrompt)
        assertEquals("%s", remoteProfile.userPromptTemplate)
        assertEquals(true, remoteProfile.useStreaming)
        assertEquals(0.8, remoteProfile.temperature, 0.001)
        assertEquals(2048, remoteProfile.maxTokens)
        assertEquals(0.9, remoteProfile.topP, 0.001)
        assertEquals(0.1, remoteProfile.frequencyPenalty, 0.001)
        assertEquals(0.2, remoteProfile.presencePenalty, 0.001)
        assertEquals("system", remoteProfile.assistantRole)
        assertTrue(remoteProfile.createdAt > 0)
        assertTrue(remoteProfile.updatedAt > 0)
    }

    @Test
    fun testFieldMappingBetweenEntityAndRemote() {
        // Test that field names and types match between AiProfileEntity and RemoteAiProfile
        val entity = AiProfileEntity(
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "test-api-key",
            serverBaseUrl = "https://api.example.com",
            systemPrompt = "You are a helpful assistant",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.8,
            maxTokens = 2048,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            assistantRole = "system"
        )

        val remoteProfile = RemoteAiProfile(
            remoteId = "test-id",
            name = entity.name,
            modelName = entity.modelName,
            apiKey = entity.apiKey,
            serverBaseUrl = entity.serverBaseUrl,
            systemPrompt = entity.systemPrompt,
            userPromptTemplate = entity.userPromptTemplate,
            useStreaming = entity.useStreaming,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens,
            topP = entity.topP,
            frequencyPenalty = entity.frequencyPenalty,
            presencePenalty = entity.presencePenalty,
            assistantRole = entity.assistantRole,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )

        // Verify field mapping
        assertEquals(entity.name, remoteProfile.name)
        assertEquals(entity.modelName, remoteProfile.modelName)
        assertEquals(entity.apiKey, remoteProfile.apiKey)
        assertEquals(entity.serverBaseUrl, remoteProfile.serverBaseUrl)
        assertEquals(entity.systemPrompt, remoteProfile.systemPrompt)
        assertEquals(entity.userPromptTemplate, remoteProfile.userPromptTemplate)
        assertEquals(entity.useStreaming, remoteProfile.useStreaming)
        assertEquals(entity.temperature, remoteProfile.temperature, 0.001)
        assertEquals(entity.maxTokens, remoteProfile.maxTokens)
        assertEquals(entity.topP, remoteProfile.topP, 0.001)
        assertEquals(entity.frequencyPenalty, remoteProfile.frequencyPenalty, 0.001)
        assertEquals(entity.presencePenalty, remoteProfile.presencePenalty, 0.001)
        assertEquals(entity.assistantRole, remoteProfile.assistantRole)
    }

    @Test
    fun testDefaultValues() {
        // Test default values for optional fields
        val entity = AiProfileEntity(
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "",
            serverBaseUrl = "",
            systemPrompt = "",
            userPromptTemplate = "%s",
            useStreaming = false
        )

        // Verify default values
        assertEquals(0.7, entity.temperature, 0.001)
        assertEquals(4096, entity.maxTokens)
        assertEquals(1.0, entity.topP, 0.001)
        assertEquals(0.0, entity.frequencyPenalty, 0.001)
        assertEquals(0.0, entity.presencePenalty, 0.001)
        assertEquals("assistant", entity.assistantRole)
    }
}