// data/remote/BookmarkPublisher.kt
package com.example.booxreader.data.remote

import android.util.Log
import com.example.booxreader.data.db.BookmarkEntity
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BookmarkPublisher(

    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson()

) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 同步發送，呼叫者自己確保在 IO thread 裡（我們會在 Repository 裡用 withContext(Dispatchers.IO) 包起來）
     */
    fun publishBookmark(entity: BookmarkEntity) {
        val payload = BookmarkPayload(
            bookId = entity.bookId,
            locatorJson = entity.locatorJson,
            createdAt = entity.createdAt
        )

        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(HttpConfig.BOOKMARK_ENDPOINT)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        "BookmarkPublisher",
                        "HTTP ${response.code} when publishing bookmark"
                    )
                } else {
                    Log.d("BookmarkPublisher", "Bookmark published successfully")
                }
            }
        } catch (e: Exception) {
            Log.e("BookmarkPublisher", "Failed to publish bookmark", e)
        }
    }
}
