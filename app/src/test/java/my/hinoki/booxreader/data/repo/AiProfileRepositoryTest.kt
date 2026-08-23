package my.hinoki.booxreader.data.repo
import my.hinoki.booxreader.data.db.initBooxReaderDatabase
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.settings.ReaderSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AiProfileRepositoryTest {

    private lateinit var context: Context
    private lateinit var syncRepo: UserSyncRepository
    private lateinit var repository: AiProfileRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initBooxReaderDatabase(context)
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        syncRepo = Mockito.mock(UserSyncRepository::class.java)
        repository = createAiProfileRepository(context, syncRepo)
    }

    @After
    fun tearDown() {
        AppDatabase.resetInstanceForTesting()
    }

    @Test
    fun ensureDefaultProfile_createsDefaultWhenEmpty() = runBlocking {
        val db = AppDatabase.get()
        db.aiProfileDao().deleteAll()

        val created = repository.ensureDefaultProfile()
        assertTrue(created)

        val profiles = db.aiProfileDao().getAllList()
        assertEquals(1, profiles.size)
        assertEquals("Gemini", profiles.first().name)

        val settings = ReaderSettings.fromStorage(SharedPreferencesStorage(
            context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        ))
        assertEquals(profiles.first().id, settings.activeProfileId)
    }

    @Test
    fun ensureDefaultProfile_doesNotCreateWhenProfilesExist() = runBlocking {
        val db = AppDatabase.get()
        val dao = db.aiProfileDao()
        dao.deleteAll()

        val customProfile = AiProfileEntity(
            name = "Custom LLM",
            modelName = "custom-model",
            apiKey = "key-123",
            serverBaseUrl = "https://api.custom.com",
            systemPrompt = "sys",
            userPromptTemplate = "%s",
            useStreaming = false
        )
        dao.insert(customProfile)

        val created = repository.ensureDefaultProfile()
        assertFalse(created)

        val profiles = dao.getAllList()
        assertEquals(1, profiles.size)
        assertEquals("Custom LLM", profiles.first().name)
    }

    @Test
    fun applyProfile_updatesSettingsAndPushesToSyncRepo() = runBlocking {
        val db = AppDatabase.get()
        val dao = db.aiProfileDao()
        dao.deleteAll()

        val profile1 = AiProfileEntity(
            name = "Profile 1",
            modelName = "model-1",
            apiKey = "key-1",
            serverBaseUrl = "https://api.test1.com",
            systemPrompt = "prompt 1",
            userPromptTemplate = "%s",
            useStreaming = false
        )
        dao.insert(profile1)

        val profile2 = AiProfileEntity(
            name = "Claude 3.5 Sonnet",
            modelName = "claude-3-5-sonnet",
            apiKey = "sk-ant-123",
            serverBaseUrl = "https://api.anthropic.com",
            systemPrompt = "You are a helpful assistant.",
            userPromptTemplate = "%s",
            useStreaming = false,
            temperature = 0.3
        )
        val profile2Id = dao.insert(profile2)

        repository.applyProfile(profile2Id)

        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
        assertEquals(profile2Id, settings.activeProfileId)
        assertEquals("claude-3-5-sonnet", settings.aiModelName)
        assertEquals("sk-ant-123", settings.apiKey)
        assertEquals("You are a helpful assistant.", settings.aiSystemPrompt)
        assertEquals(0.3, settings.temperature, 0.001)

        Mockito.verify(syncRepo).pushSettings(org.mockito.kotlin.check {
            assertEquals(profile2Id, it.activeProfileId)
            assertEquals("claude-3-5-sonnet", it.aiModelName)
        })
    }
}
