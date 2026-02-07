package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
class UserSyncRepositoryMagicTagTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: my.hinoki.booxreader.data.prefs.TokenManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pushSettings_includesMagicTagsInPayload() = runBlocking {
        var updateRequestBody: String? = null
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/api/collections/settings/records?") == true &&
                                        request.method == "GET"
                        ) {
                            return MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[{"id":"settings_record_1"}],"page":1,"perPage":30,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                        }
                        if (request.path == "/api/collections/settings/records/settings_record_1" &&
                                        request.method == "PATCH"
                        ) {
                            updateRequestBody = request.body.readUtf8()
                            return MockResponse().setResponseCode(200).setBody("{}")
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }

        val repo =
                UserSyncRepository(
                        context = context,
                        baseUrl = server.url("/").toString(),
                        tokenManager = tokenManager
                )
        setCachedUserId(repo, "user_1")

        val tags =
                listOf(
                        MagicTag(id = "t1", label = "Tag One", content = "Content One"),
                        MagicTag(id = "t2", label = "Tag Two", content = "Content Two")
                )

        repo.pushSettings(ReaderSettings(magicTags = tags))

        val body = updateRequestBody
        assertNotNull("Expected PATCH body to be sent", body)
        val json = JSONObject(body!!)
        assertTrue("Payload should include magicTags field", json.has("magicTags"))
        val magicTagsJson = json.getJSONArray("magicTags")
        assertEquals(2, magicTagsJson.length())
        assertEquals("t1", magicTagsJson.getJSONObject(0).getString("id"))
        assertEquals("t2", magicTagsJson.getJSONObject(1).getString("id"))
    }

    @Test
    fun pullSettingsIfNewer_keepsLocalMagicTagsWhenRemoteFieldMissing() = runBlocking {
        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val localTags = listOf(MagicTag(id = "local", label = "Local Tag", content = "Local Content"))
        ReaderSettings(magicTags = localTags, updatedAt = 1000L).saveTo(prefs)

        val remoteSettingsItem =
                JSONObject()
                        .put("id", "settings_record_2")
                        .put("updatedAt", 2000L)
                        .put("pageTapEnabled", true)
                        .put("pageSwipeEnabled", true)
                        .put("contrastMode", 0)
                        .put("convertToTraditionalChinese", true)
                        .put("serverBaseUrl", "https://example.com")
                        .put("exportToCustomUrl", false)
                        .put("exportCustomUrl", "")
                        .put("exportToLocalDownloads", false)
                        .put("apiKey", "")
                        .put("aiModelName", "deepseek-chat")
                        .put("aiSystemPrompt", "sys")
                        .put("aiUserPromptTemplate", "%s")
                        .put("temperature", 0.7)
                        .put("maxTokens", 4096)
                        .put("topP", 1.0)
                        .put("frequencyPenalty", 0.0)
                        .put("presencePenalty", 0.0)
                        .put("assistantRole", "assistant")
                        .put("enableGoogleSearch", true)
                        .put("useStreaming", false)
                        .put("pageAnimationEnabled", false)
                        .put("showPageIndicator", true)
                        .put("language", "system")
                        .put("activeProfileId", -1)

        val listBody =
                JSONObject()
                        .put("items", JSONArray().put(remoteSettingsItem))
                        .put("page", 1)
                        .put("perPage", 30)
                        .put("totalItems", 1)
                        .put("totalPages", 1)
                        .toString()

        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path?.startsWith("/api/collections/settings/records?") == true &&
                                        request.method == "GET"
                        ) {
                            return MockResponse().setResponseCode(200).setBody(listBody)
                        }
                        return MockResponse().setResponseCode(404)
                    }
                }

        val repo =
                UserSyncRepository(
                        context = context,
                        baseUrl = server.url("/").toString(),
                        tokenManager = tokenManager
                )
        setCachedUserId(repo, "user_1")

        val pulled = repo.pullSettingsIfNewer()
        assertNotNull("Expected remote settings to be pulled", pulled)
        assertEquals(localTags, pulled!!.magicTags)

        val persisted = ReaderSettings.fromPrefs(prefs)
        assertEquals(localTags, persisted.magicTags)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}

