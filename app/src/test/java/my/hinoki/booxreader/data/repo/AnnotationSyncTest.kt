package my.hinoki.booxreader.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AnnotationStyle
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.initBooxReaderDatabase
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/**
 * 畫線 / 註記同步測試。
 *
 * 這個功能原本完全不存在（`AnnotationEntity` 的 remoteId / isSynced 是死欄位），
 * 因此這裡驗證的是新行為：推送（POST/PATCH）、拉取（幂等）、刪除，以及
 * `AnnotationRepository` 是否真的用到它一直收著卻沒用的 `syncRepo`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AnnotationSyncTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var tokenManager: my.hinoki.booxreader.data.prefs.TokenManager
    private lateinit var repo: UserSyncRepository

    private val dao
        get() = AppDatabase.get().annotationDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        initBooxReaderDatabase(context)
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear().commit()
        runBlocking { dao.deleteAll() }

        tokenManager = Mockito.mock(my.hinoki.booxreader.data.prefs.TokenManager::class.java)
        Mockito.`when`(tokenManager.getAccessToken()).thenReturn("test-token")

        server = MockWebServer()
        server.start()

        repo = createUserSyncRepository(
                context = context,
                baseUrl = server.url("/").toString(),
                tokenManager = tokenManager
        )
        setCachedUserId(repo, "user_1")
    }

    @After
    fun tearDown() {
        AppDatabase.resetInstanceForTesting()
        server.shutdown()
    }

    @Test
    fun `pushAnnotation creates a remote record and marks it synced`() = runBlocking {
        var method: String? = null
        var path: String? = null
        var body: String? = null
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        method = request.method
                        path = request.path
                        body = request.body.readUtf8()
                        return MockResponse().setResponseCode(200).setBody("""{"id":"ann_remote_1"}""")
                    }
                }

        val id =
                dao.insert(
                        AnnotationEntity(
                                bookId = "book_1",
                                locatorJson = """{"href":"ch1"}""",
                                selectedText = "hello",
                                note = "my note",
                                style = AnnotationStyle.DASHED.name
                        )
                )

        val synced = repo.pushAnnotation(dao.getById(id)!!)

        assertNotNull(synced)
        assertEquals("ann_remote_1", synced!!.remoteId)
        assertTrue(synced.isSynced)
        assertEquals("POST", method)
        assertTrue("path=$path", path!!.startsWith("/api/collections/annotations/records"))
        assertTrue("body=$body", body!!.contains("selectedText"))
        assertTrue("body=$body", body!!.contains("DASHED"))
        assertTrue("body=$body", body!!.contains("my note"))
        assertTrue("body=$body", body!!.contains("user_1"))
    }

    @Test
    fun `pushAnnotation patches when a remoteId already exists`() = runBlocking {
        var method: String? = null
        var path: String? = null
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        method = request.method
                        path = request.path
                        return MockResponse().setResponseCode(200)
                                .setBody("""{"id":"ann_existing"}""")
                    }
                }

        val entity =
                AnnotationEntity(
                        remoteId = "ann_existing",
                        bookId = "book_1",
                        locatorJson = "{}",
                        selectedText = "text"
                )

        val synced = repo.pushAnnotation(entity)

        assertEquals("PATCH", method)
        assertEquals("/api/collections/annotations/records/ann_existing", path)
        assertEquals("ann_existing", synced!!.remoteId)
    }

    @Test
    fun `pullAnnotations inserts remote annotations locally`() = runBlocking {
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[
                                              {"id":"ann_remote_2","bookId":"book_1",
                                               "locatorJson":"{\"href\":\"ch2\"}",
                                               "selectedText":"quoted","note":"from device B",
                                               "style":"GRAY_FILL","createdAt":1000,"updatedAt":2000}
                                            ],"page":1,"perPage":100,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                }

        val merged = repo.pullAnnotations("book_1")

        assertEquals(1, merged)
        val stored = dao.getAll("book_1")
        assertEquals(1, stored.size)
        assertEquals("ann_remote_2", stored[0].remoteId)
        assertTrue(stored[0].isSynced)
        assertEquals("quoted", stored[0].selectedText)
        assertEquals("from device B", stored[0].note)
        assertEquals("GRAY_FILL", stored[0].style)
        assertEquals(2000L, stored[0].updatedAt)
    }

    @Test
    fun `pullAnnotations is idempotent for unchanged records`() = runBlocking {
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse()
                                    .setResponseCode(200)
                                    .setBody(
                                            """
                                            {"items":[
                                              {"id":"ann_remote_5","bookId":"book_1",
                                               "locatorJson":"{}","selectedText":"s",
                                               "createdAt":1000,"updatedAt":2000}
                                            ],"page":1,"perPage":100,"totalItems":1,"totalPages":1}
                                            """.trimIndent()
                                    )
                }

        assertEquals(1, repo.pullAnnotations("book_1"))
        assertEquals("second pull must not re-merge", 0, repo.pullAnnotations("book_1"))
        assertEquals(1, dao.getAll("book_1").size)
    }

    @Test
    fun `deleteAnnotation sends a DELETE request`() = runBlocking {
        var method: String? = null
        var path: String? = null
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        method = request.method
                        path = request.path
                        return MockResponse().setResponseCode(204)
                    }
                }

        assertTrue(repo.deleteAnnotation("ann_remote_3"))
        assertEquals("DELETE", method)
        assertEquals("/api/collections/annotations/records/ann_remote_3", path)
    }

    @Test
    fun `AnnotationRepository uses the syncRepo it was given`() = runBlocking {
        server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse().setResponseCode(200).setBody("""{"id":"ann_remote_9"}""")
                }

        val annotations = AnnotationRepository(repo)

        val added = annotations.addAnnotation(
                bookId = "book_9",
                locatorJson = "{}",
                selectedText = "highlighted"
        )

        assertEquals("ann_remote_9", added.remoteId)
        assertTrue(added.isSynced)
        // 本機也記錄了 remoteId，之後的更新才會走 PATCH
        assertEquals("ann_remote_9", dao.getById(added.id)?.remoteId)
    }

    @Test
    fun `AnnotationRepository stays local when no syncRepo is configured`() = runBlocking {
        val annotations = AnnotationRepository(syncRepo = null)

        val added = annotations.addAnnotation("book_local", "{}", "offline highlight")

        assertEquals(null, added.remoteId)
        assertEquals(false, added.isSynced)
        assertEquals(1, dao.getAll("book_local").size)
    }

    private fun setCachedUserId(repo: UserSyncRepository, userId: String) {
        val field = UserSyncRepository::class.java.getDeclaredField("cachedUserId")
        field.isAccessible = true
        field.set(repo, userId)
    }
}
