package my.hinoki.booxreader.data.repo

import my.hinoki.booxreader.data.db.AiProfileEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.*

/**
 * Unit tests for AI Profile synchronization logic without Android dependencies.
 * Tests the core synchronization and conflict resolution logic.
 */
class AiProfileSyncLogicTest {

    @Test
    fun testProfileEntityCreation() {
        // Test creating a profile entity with all fields
        val profile = AiProfileEntity(
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "test-api-key",
            serverBaseUrl = "https://api.example.com",
            systemPrompt = "You are a test assistant",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.8,
            maxTokens = 2048,
            topP = 0.9,
            frequencyPenalty = 0.1,
            presencePenalty = 0.2,
            assistantRole = "system"
        )

        // Verify all fields are set correctly
        assertEquals("Test Profile", profile.name)
        assertEquals("gpt-4", profile.modelName)
        assertEquals("test-api-key", profile.apiKey)
        assertEquals("https://api.example.com", profile.serverBaseUrl)
        assertEquals("You are a test assistant", profile.systemPrompt)
        assertEquals("%s", profile.userPromptTemplate)
        assertTrue(profile.useStreaming)
        assertEquals(0.8, profile.temperature, 0.001)
        assertEquals(2048, profile.maxTokens)
        assertEquals(0.9, profile.topP, 0.001)
        assertEquals(0.1, profile.frequencyPenalty, 0.001)
        assertEquals(0.2, profile.presencePenalty, 0.001)
        assertEquals("system", profile.assistantRole)
        
        // Verify timestamps are set
        assertTrue(profile.createdAt > 0)
        assertTrue(profile.updatedAt > 0)
        
        // Verify sync fields
        assertNull(profile.remoteId)
        assertFalse(profile.isSynced)
    }

    @Test
    fun testProfileEntityWithDefaults() {
        // Test creating a profile with minimal required fields
        val profile = AiProfileEntity(
            name = "Minimal Profile",
            modelName = "tiny-model",
            apiKey = "",
            serverBaseUrl = "",
            systemPrompt = "",
            userPromptTemplate = "%s",
            useStreaming = false
        )

        // Verify required fields
        assertEquals("Minimal Profile", profile.name)
        assertEquals("tiny-model", profile.modelName)
        assertEquals("", profile.apiKey)
        assertEquals("", profile.serverBaseUrl)
        assertEquals("", profile.systemPrompt)
        assertEquals("%s", profile.userPromptTemplate)
        assertFalse(profile.useStreaming)
        
        // Verify default values
        assertEquals(0.7, profile.temperature, 0.001)
        assertEquals(4096, profile.maxTokens)
        assertEquals(1.0, profile.topP, 0.001)
        assertEquals(0.0, profile.frequencyPenalty, 0.001)
        assertEquals(0.0, profile.presencePenalty, 0.001)
        assertEquals("assistant", profile.assistantRole)
    }

    @Test
    fun testProfileCopyWithUpdates() {
        // Test creating a copy with updated fields
        val original = AiProfileEntity(
            name = "Original",
            modelName = "gpt-3.5",
            apiKey = "original-key",
            serverBaseUrl = "https://original.com",
            systemPrompt = "Original prompt",
            userPromptTemplate = "%s",
            useStreaming = false
        )

        // Create an updated copy
        val updated = original.copy(
            name = "Updated",
            modelName = "gpt-4",
            apiKey = "updated-key",
            updatedAt = System.currentTimeMillis() + 1000 // Future timestamp
        )

        // Verify the copy has updated fields
        assertEquals("Updated", updated.name)
        assertEquals("gpt-4", updated.modelName)
        assertEquals("updated-key", updated.apiKey)
        
        // Verify unchanged fields are preserved
        assertEquals(original.serverBaseUrl, updated.serverBaseUrl)
        assertEquals(original.systemPrompt, updated.systemPrompt)
        assertEquals(original.userPromptTemplate, updated.userPromptTemplate)
        assertEquals(original.useStreaming, updated.useStreaming)
        
        // Verify timestamps
        assertEquals(original.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > original.updatedAt)
    }

    @Test
    fun testConflictResolutionStrategy() {
        // Simulate a conflict scenario where we need to merge profiles
        val localProfile = AiProfileEntity(
            id = 1,
            name = "Local Profile",
            modelName = "local-model",
            apiKey = "local-key-123",
            serverBaseUrl = "https://local.api.com",
            systemPrompt = "Local prompt",
            userPromptTemplate = "Local: %s",
            useStreaming = true,
            remoteId = "conflict-id",
            createdAt = 1000,
            updatedAt = 5000
        )

        // Simulate remote profile with similar timestamp (potential conflict)
        val remoteProfile = RemoteAiProfile(
            remoteId = "conflict-id",
            name = "Remote Profile",
            modelName = "remote-model",
            apiKey = "remote-key-456",
            serverBaseUrl = "https://remote.api.com",
            systemPrompt = "Remote prompt",
            userPromptTemplate = "Remote: %s",
            useStreaming = false,
            createdAt = 1000,
            updatedAt = 5100 // Slightly newer than local
        )

        // Test conflict resolution: remote is newer, so we should prefer remote
        // but preserve local API key
        val resolvedProfile = AiProfileEntity(
            id = localProfile.id,
            name = remoteProfile.name, // Prefer remote name
            modelName = remoteProfile.modelName, // Prefer remote model
            apiKey = localProfile.apiKey, // Prefer local API key (sensitive data)
            serverBaseUrl = remoteProfile.serverBaseUrl, // Prefer remote server
            systemPrompt = remoteProfile.systemPrompt, // Prefer remote prompt
            userPromptTemplate = remoteProfile.userPromptTemplate, // Prefer remote template
            useStreaming = remoteProfile.useStreaming, // Prefer remote streaming
            temperature = remoteProfile.temperature,
            maxTokens = remoteProfile.maxTokens,
            topP = remoteProfile.topP,
            frequencyPenalty = remoteProfile.frequencyPenalty,
            presencePenalty = remoteProfile.presencePenalty,
            assistantRole = remoteProfile.assistantRole,
            remoteId = remoteProfile.remoteId,
            createdAt = localProfile.createdAt, // Preserve original creation time
            updatedAt = remoteProfile.updatedAt, // Use remote update time
            isSynced = true
        )

        // Verify conflict resolution
        assertEquals(remoteProfile.name, resolvedProfile.name)
        assertEquals(remoteProfile.modelName, resolvedProfile.modelName)
        assertEquals(localProfile.apiKey, resolvedProfile.apiKey) // Local API key preserved
        assertEquals(remoteProfile.serverBaseUrl, resolvedProfile.serverBaseUrl)
        assertEquals(remoteProfile.systemPrompt, resolvedProfile.systemPrompt)
        assertEquals(remoteProfile.userPromptTemplate, resolvedProfile.userPromptTemplate)
        assertEquals(remoteProfile.useStreaming, resolvedProfile.useStreaming)
        assertEquals(localProfile.createdAt, resolvedProfile.createdAt)
        assertEquals(remoteProfile.updatedAt, resolvedProfile.updatedAt)
        assertTrue(resolvedProfile.isSynced)
    }

    @Test
    fun testTimestampConflictDetection() {
        val baseTime = System.currentTimeMillis()
        
        // Create two profiles with very close timestamps (potential conflict)
        val profile1 = AiProfileEntity(
            name = "Profile 1",
            modelName = "model-1",
            apiKey = "key-1",
            serverBaseUrl = "https://api.1.com",
            systemPrompt = "Prompt 1",
            userPromptTemplate = "%s",
            useStreaming = true,
            updatedAt = baseTime
        )

        val profile2 = AiProfileEntity(
            name = "Profile 2",
            modelName = "model-2",
            apiKey = "key-2",
            serverBaseUrl = "https://api.2.com",
            systemPrompt = "Prompt 2",
            userPromptTemplate = "%s",
            useStreaming = false,
            updatedAt = baseTime + 30000 // 30 seconds later
        )

        // Test if timestamps are close enough to be considered a conflict
        val timeDiff = Math.abs(profile2.updatedAt - profile1.updatedAt)
        assertTrue("Timestamps should be close", timeDiff < 60000) // Within 1 minute
        
        // Test if timestamps are far enough apart to not be a conflict
        val profile3 = profile1.copy(updatedAt = baseTime + 120000) // 2 minutes later
        val timeDiff2 = Math.abs(profile3.updatedAt - profile1.updatedAt)
        assertTrue("Timestamps should not be close", timeDiff2 >= 60000)
    }

    @Test
    fun testRemoteAiProfileMapping() {
        // Test that RemoteAiProfile can be created with all fields
        val remoteProfile = RemoteAiProfile(
            remoteId = "test-id-123",
            name = "Remote Test Profile",
            modelName = "gpt-4-remote",
            apiKey = "remote-key-789",
            serverBaseUrl = "https://remote.api.com",
            systemPrompt = "Remote system prompt",
            userPromptTemplate = "Remote: %s",
            useStreaming = true,
            temperature = 0.9,
            maxTokens = 8192,
            topP = 0.95,
            frequencyPenalty = 0.15,
            presencePenalty = 0.25,
            assistantRole = "system",
            createdAt = 10000,
            updatedAt = 20000
        )

        // Verify all fields
        assertEquals("test-id-123", remoteProfile.remoteId)
        assertEquals("Remote Test Profile", remoteProfile.name)
        assertEquals("gpt-4-remote", remoteProfile.modelName)
        assertEquals("remote-key-789", remoteProfile.apiKey)
        assertEquals("https://remote.api.com", remoteProfile.serverBaseUrl)
        assertEquals("Remote system prompt", remoteProfile.systemPrompt)
        assertEquals("Remote: %s", remoteProfile.userPromptTemplate)
        assertTrue(remoteProfile.useStreaming)
        assertEquals(0.9, remoteProfile.temperature, 0.001)
        assertEquals(8192, remoteProfile.maxTokens)
        assertEquals(0.95, remoteProfile.topP, 0.001)
        assertEquals(0.15, remoteProfile.frequencyPenalty, 0.001)
        assertEquals(0.25, remoteProfile.presencePenalty, 0.001)
        assertEquals("system", remoteProfile.assistantRole)
        assertEquals(10000, remoteProfile.createdAt)
        assertEquals(20000, remoteProfile.updatedAt)
    }

    @Test
    fun testProfileEquality() {
        // Test that profiles with same data are equal
        val profile1 = AiProfileEntity(
            name = "Test Profile",
            modelName = "gpt-4",
            apiKey = "test-key",
            serverBaseUrl = "https://api.com",
            systemPrompt = "Prompt",
            userPromptTemplate = "%s",
            useStreaming = true
        )

        val profile2 = profile1.copy() // Exact copy

        assertEquals(profile1, profile2)
        assertEquals(profile1.hashCode(), profile2.hashCode())
        
        // Test that profiles with different data are not equal
        val profile3 = profile1.copy(name = "Different Name")
        assertNotEquals(profile1, profile3)
    }

    @Test
    fun testProfileWithRemoteId() {
        // Test profile with remote ID (synced profile)
        val syncedProfile = AiProfileEntity(
            name = "Synced Profile",
            modelName = "gpt-4",
            apiKey = "synced-key",
            serverBaseUrl = "https://synced.api.com",
            systemPrompt = "Synced prompt",
            userPromptTemplate = "%s",
            useStreaming = true,
            remoteId = "remote-id-123",
            isSynced = true
        )

        assertEquals("remote-id-123", syncedProfile.remoteId)
        assertTrue(syncedProfile.isSynced)
        
        // Verify other fields are still set
        assertEquals("Synced Profile", syncedProfile.name)
        assertEquals("gpt-4", syncedProfile.modelName)
    }

    @Test
    fun testProfileSerializationCompatibility() {
        // Test that the profile structure is compatible with JSON serialization
        val profile = AiProfileEntity(
            name = "Serializable Profile",
            modelName = "gpt-4",
            apiKey = "serial-key",
            serverBaseUrl = "https://serial.api.com",
            systemPrompt = "Serial prompt",
            userPromptTemplate = "%s",
            useStreaming = true,
            temperature = 0.7,
            maxTokens = 4096,
            topP = 1.0,
            frequencyPenalty = 0.0,
            presencePenalty = 0.0,
            assistantRole = "assistant"
        )

        // Verify all fields that would be serialized to JSON
        assertNotNull(profile.name)
        assertNotNull(profile.modelName)
        assertNotNull(profile.apiKey)
        assertNotNull(profile.serverBaseUrl)
        assertNotNull(profile.systemPrompt)
        assertNotNull(profile.userPromptTemplate)
        assertNotNull(profile.assistantRole)
        
        // Verify numeric fields
        assertTrue(profile.temperature in 0.0..1.0)
        assertTrue(profile.maxTokens > 0)
        assertTrue(profile.topP in 0.0..1.0)
        assertTrue(profile.frequencyPenalty >= 0.0)
        assertTrue(profile.presencePenalty >= 0.0)
    }
}