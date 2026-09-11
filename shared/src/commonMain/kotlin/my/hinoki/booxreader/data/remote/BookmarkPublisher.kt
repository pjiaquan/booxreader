// data/remote/BookmarkPublisher.kt
package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.core.Logger
import my.hinoki.booxreader.data.core.NoOpLogger

/**
 * 將書籤發布到伺服器。KMP 版本：OkHttp→Ktor、Gson→kotlinx.serialization。
 */
class BookmarkPublisher(
    private val baseUrlProvider: () -> String,
    private val client: HttpClient = HttpClient(),
    private val json: Json = Json,
    private val logger: Logger = NoOpLogger
) {

    /**
     * 發送書籤，呼叫者自己確保在 IO thread 裡。
     */
    suspend fun publishBookmark(entity: BookmarkEntity) {
        val payload = BookmarkPayload(
            bookId = entity.bookId,
            locatorJson = entity.locatorJson,
            createdAt = entity.createdAt
        )

        val body = json.encodeToString(payload)

        val baseUrl = baseUrlProvider()
        val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

        try {
            client.post(url + HttpConfig.PATH_BOOKMARK) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            // best-effort：不影響閱讀流程，但要留下痕跡，否則後端拒絕時完全無從得知
            logger.w("BookmarkPublisher", "publishBookmark failed", e)
        }
    }
}
