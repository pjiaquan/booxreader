// data/remote/ProgressPublisher.kt
package com.example.booxreader.data.remote

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ProgressPublisher(

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

        val request = Request.Builder()
            .url(HttpConfig.PROGRESS_ENDPOINT)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        "ProgressPublisher",
                        "HTTP ${response.code} when publishing progress"
                    )
                } else {
                    Log.d("ProgressPublisher", "Progress published successfully")
                }
            }
        } catch (e: Exception) {
            Log.e("ProgressPublisher", "Failed to publish progress", e)
        }
    }
}
