// data/remote/ProgressPublisher.kt
package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.hinoki.booxreader.data.core.Logger
import my.hinoki.booxreader.data.core.NoOpLogger
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 將閱讀進度發布到伺服器。KMP 版本：OkHttp→Ktor、Gson→kotlinx.serialization。
 */
class ProgressPublisher(
    private val baseUrlProvider: () -> String,
    private val client: HttpClient = HttpClient(),
    private val json: Json = Json,
    private val logger: Logger = NoOpLogger
) {

    /**
     * 同步呼叫，請在 IO thread 執行（我們會在 ReaderActivity 用 coroutine 包）
     */
    suspend fun publishProgress(bookId: String, locatorJson: String) {
        val payload = ProgressPayload(
            bookId = bookId,
            locatorJson = locatorJson,
            updatedAt = currentEpochMillis()
        )

        val body = json.encodeToString(payload)

        val baseUrl = baseUrlProvider()
        val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl

        try {
            client.post(url + HttpConfig.PATH_PROGRESS) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Exception) {
            // best-effort：不影響閱讀流程，但要留下痕跡，否則後端拒絕時完全無從得知
            logger.w("ProgressPublisher", "publishProgress failed", e)
        }
    }
}
