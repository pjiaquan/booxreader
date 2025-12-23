// data/remote/ProgressPublisher.kt
package my.hinoki.booxreader.data.remote

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProgressPublisher(
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 同步呼叫，請在 IO thread 執行（我們會在 ReaderActivity 用 coroutine 包）
     */
    fun publishProgress(bookId: String, locatorJson: String) {
        val payload = ProgressPayload(
            bookId = bookId,
            locatorJson = locatorJson,
            updatedAt = System.currentTimeMillis()
        )

        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val baseUrl = baseUrlProvider()
        val url = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val request = Request.Builder()
            .url(url + HttpConfig.PATH_PROGRESS)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                } else {
                }
            }
        } catch (e: Exception) {
        }
    }
}

