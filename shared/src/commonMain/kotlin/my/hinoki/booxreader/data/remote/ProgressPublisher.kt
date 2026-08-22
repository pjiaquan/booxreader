// data/remote/ProgressPublisher.kt
package my.hinoki.booxreader.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 將閱讀進度發布到伺服器。KMP 版本：OkHttp→Ktor、Gson→kotlinx.serialization。
 */
class ProgressPublisher(
    private val baseUrlProvider: () -> String,
    private val client: HttpClient = HttpClient(),
    private val json: Json = Json
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
            // 與原本行為一致：best-effort，失敗時靜默忽略
        }
    }
}
