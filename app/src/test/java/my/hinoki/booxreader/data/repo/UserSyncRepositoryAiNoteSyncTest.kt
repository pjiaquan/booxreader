package my.hinoki.booxreader.data.repo
import my.hinoki.booxreader.data.db.initBooxReaderDatabase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
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
class UserSyncRepositoryAiNoteSyncTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: my.hinoki.booxreader.data.prefs.TokenManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initBooxReaderDatabase(context)
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {}
        AppDatabase.resetInstanceForTesting()
    }

    @Test
    fun `pullNotes batch inserts new notes and updates only newer remote notes`() = runBlocking {
        val db = AppDatabase.get()
        val dao = db.aiNoteDao()
        dao.deleteAll()

        // Seed 1 existing note with newer local updatedAt (local: 2000L vs remote: 1500L) -> should NOT update
        val localNewer = AiNoteEntity(
            remoteId = "note_remote_1",
            bookId = "book_1",
            messages = "[{\"role\":\"user\",\"content\":\"local newer\"}]",
            updatedAt = 2000L,
            createdAt = 1000L
        )
        val localId1 = dao.insert(localNewer)

        // Seed 1 existing note with older local updatedAt (local: 1000L vs remote: 3000L) -> should update
        val localOlder = AiNoteEntity(
            remoteId = "note_remote_2",
            bookId = "book_1",
            messages = "[{\"role\":\"user\",\"content\":\"local older\"}]",
            updatedAt = 1000L,
            createdAt = 1000L
        )
        val localId2 = dao.insert(localOlder)

        // Mock remote items: note_remote_1 (older), note_remote_2 (newer), note_remote_3 (new)
        val remoteNotes = JSONArray()
            .put(
                JSONObject()
                    .put("id", "note_remote_1")
                    .put("bookId", "book_1")
                    .put("messages", "[{\"role\":\"user\",\"content\":\"remote stale\"}]")
                    .put("updatedAt", 1500L)
                    .put("createdAt", 1000L)
            )
            .put(
                JSONObject()
                    .put("id", "note_remote_2")
                    .put("bookId", "book_1")
                    .put("messages", "[{\"role\":\"user\",\"content\":\"remote newer\"}]")
                    .put("updatedAt", 3000L)
                    .put("createdAt", 1000L)
            )
            .put(
                JSONObject()
                    .put("id", "note_remote_3")
                    .put("bookId", "book_1")
                    .put("messages", "[{\"role\":\"user\",\"content\":\"brand new\"}]")
                    .put("updatedAt", 4000L)
                    .put("createdAt", 4000L)
            )

        val listBody = JSONObject()
            .put("items", remoteNotes)
            .put("page", 1)
            .put("perPage", 100)
            .put("totalItems", 3)
            .put("totalPages", 1)
            .toString()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/api/collections/ai_notes/records?") == true && request.method == "GET") {
                    return MockResponse().setResponseCode(200).setBody(listBody)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repo = UserSyncRepository(
            context = context,
            baseUrl = server.url("/").toString(),
            tokenManager = tokenManager
        )
        setCachedUserId(repo, "user_test_1")

        val syncedCount = repo.pullNotes()
        assertEquals(2, syncedCount) // note_remote_2 updated + note_remote_3 inserted

        // Verify note_remote_1 was NOT overwritten
        val note1 = dao.getByRemoteId("note_remote_1")
        assertNotNull(note1)
        assertEquals(localId1, note1!!.id)
        assertEquals("[{\"role\":\"user\",\"content\":\"local newer\"}]", note1.messages)
        assertEquals(2000L, note1.updatedAt)

        // Verify note_remote_2 was updated with new remote message
        val note2 = dao.getByRemoteId("note_remote_2")
        assertNotNull(note2)
        assertEquals(localId2, note2!!.id)
        assertEquals("[{\"role\":\"user\",\"content\":\"remote newer\"}]", note2.messages)
        assertEquals(3000L, note2.updatedAt)

        // Verify note_remote_3 was inserted
        val note3 = dao.getByRemoteId("note_remote_3")
        assertNotNull(note3)
        assertEquals("note_remote_3", note3!!.remoteId)
        assertEquals("[{\"role\":\"user\",\"content\":\"brand new\"}]", note3.messages)
        assertEquals(4000L, note3.updatedAt)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}
