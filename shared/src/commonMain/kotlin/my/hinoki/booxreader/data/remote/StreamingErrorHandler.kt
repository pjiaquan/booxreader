package my.hinoki.booxreader.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class StreamingErrorInfo(
    val statusCode: Int,
    val rawMessage: String,
    val title: String,
    val reason: String,
    val resolution: String
)

/** 解析 AI 串流錯誤（純邏輯，KMP commonMain）。UI 對話框由 Android 端提供。 */
object StreamingErrorHandler {

    fun parseError(statusCode: Int, rawResponseBody: String?): StreamingErrorInfo {
        val bodyStr = rawResponseBody.orEmpty()
        var messageFromApi = ""

        runCatching {
            val json = Json.parseToJsonElement(bodyStr).jsonObject
            val errObj = json["error"] as? JsonObject
            messageFromApi =
                if (errObj != null) {
                    errObj.optString("message").ifBlank { errObj.optString("detail") }
                } else {
                    json.optString("message")
                }
        }

        return when {
            statusCode == 401 || statusCode == 403 -> StreamingErrorInfo(
                statusCode = statusCode,
                rawMessage = messageFromApi.ifBlank { "Unauthorized / Invalid API Key" },
                title = "🔑 API Key 認證失敗 ($statusCode)",
                reason = "API Key 無效、已過期或缺乏調用該 AI 模型的權限。",
                resolution = "解法：請前往「設定 -> AI 設定檔」，檢查並更新您的 API Key。"
            )
            statusCode == 404 -> StreamingErrorInfo(
                statusCode = statusCode,
                rawMessage = messageFromApi.ifBlank { "Model or Endpoint Not Found" },
                title = "🤖 模型或網址錯誤 (404)",
                reason = "找不到所選的模型名稱或 Base URL 網址不正確。",
                resolution = "解法：請在「AI 設定檔」點擊「取得最新模型」重新選擇模型，或檢查 Base URL 設定。"
            )
            statusCode == 429 -> StreamingErrorInfo(
                statusCode = statusCode,
                rawMessage = messageFromApi.ifBlank { "Rate limit / Quota exceeded" },
                title = "⏱️ 超過請求頻率或額度限制 (429)",
                reason = "您的 API 金鑰請求次數已達上限，或帳號額度已用盡。",
                resolution = "解法：請稍等幾分鐘後再試，或在「AI 設定檔」切換至其他模型 Profile。"
            )
            statusCode in 500..599 -> StreamingErrorInfo(
                statusCode = statusCode,
                rawMessage = messageFromApi.ifBlank { "Server Error" },
                title = "☁️ AI 伺服器內部錯誤 ($statusCode)",
                reason = "AI 服務商（如 Google/OpenAI/Groq）伺服器目前忙碌或維護中。",
                resolution = "解法：請點擊重試，或在「AI 設定檔」取消勾選「Streaming 串流」改用一般模式。"
            )
            else -> StreamingErrorInfo(
                statusCode = statusCode,
                rawMessage = messageFromApi.ifBlank { bodyStr.take(150) },
                title = "⚠️ 串流回應失敗 ${if (statusCode > 0) "($statusCode)" else ""}",
                reason = if (bodyStr.contains("timeout", ignoreCase = true) || statusCode == 0) {
                    "網路連線逾時，或伺服器回應未符合 SSE 串流格式。"
                } else {
                    "無法正確完成 AI 串流對話。"
                },
                resolution = "解法：\n1. 檢查網路連線狀態\n2. 前往「AI 設定檔」取消勾選「使用 Streaming 串流」改用標準對話模式。"
            )
        }
    }

    private fun JsonObject.optString(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
