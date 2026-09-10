package my.hinoki.booxreader.data.repo

import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.db.ApiKey

/**
 * UserSyncRepository 的純函式 helper。
 *
 * 這些函式原本是 3000+ 行 God class 裡的 private method，但它們不依賴任何實例狀態
 * （`prefs` / `logger` / 網路 / `pocketBaseUrl`）。抽成 top-level `internal` 之後：
 * - `UserSyncRepository` 少掉約 120 行，只專注在同步流程本身
 * - 這些函式可以被單獨測試（見 `SyncPayloadHelpersTest`）
 *
 * 注意：`longValue` 同時取代了原本另一個內容完全相同、命名不同的 `parseEpochMillis`。
 */

private val syncJson = Json { ignoreUnknownKeys = true }

/** 從 JSON / Number / String 取 Long，無法解析時回 0。 */
internal fun longValue(value: Any?): Long {
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        is JsonPrimitive -> value.content.toLongOrNull() ?: 0L
        else -> 0L
    }
}

/** 從 PocketBase 回傳的清單中挑出 `updatedAt` 最新的一筆。 */
internal fun latestSettingsRecord(items: List<JsonObject>): JsonObject? =
        items.maxByOrNull { longValue(it["updatedAt"]) }

/** HTML escape（每日摘要 email 的內文）。 */
internal fun escapeHtmlForEmail(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 以名稱（去空白、轉小寫）作為 AI profile 的比對鍵。 */
internal fun profileNameKey(name: String): String = name.trim().lowercase()

/** 判斷 API key 是否可用（排除空白與預設 placeholder）。 */
internal fun hasUsableApiKey(apiKey: ApiKey): Boolean {
    val key = apiKey.value.trim()
    if (key.isBlank()) return false
    if (key.equals("<YOUR_GEMINI_API_KEY>", ignoreCase = true)) return false
    return !key.startsWith("<YOUR_", ignoreCase = true)
}

/** 正規化 storage path：去空白、去 `pocketbase://` 前綴。 */
internal fun normalizeStoragePath(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val normalized = path.trim().removePrefix("pocketbase://").trim()
    return normalized.takeIf { it.isNotBlank() }
}

/** 從 `pocketbase://<storagePath>` 形式的 URI 取出 storage path。 */
internal fun storagePathFromPseudoUri(uri: String?): String? {
    if (uri.isNullOrBlank()) return null
    if (!uri.startsWith("pocketbase://")) return null
    return normalizeStoragePath(uri.removePrefix("pocketbase://"))
}

/** URL-encode 路徑（保留 `/` 分隔）。 */
internal fun urlEncodePath(value: String): String =
        value.split('/').joinToString("/") { it.encodeURLParameter() }

internal fun urlEncodeQueryValue(value: String): String = value.encodeURLParameter()

/** 截斷過長的同步文字，並附上截斷標記。 */
internal fun truncateForRemoteText(raw: String, maxChars: Int): String {
    if (raw.length <= maxChars) return raw
    val marker = "\n\n[truncated for sync]"
    val keep = (maxChars - marker.length).coerceAtLeast(0)
    if (keep == 0) return raw.take(maxChars)
    return raw.take(keep) + marker
}

/** 從 messages JSON 中取出指定 role 的最後一則 content。 */
internal fun extractMessageContentByRole(messagesJson: String, role: String): String? {
    if (messagesJson.isBlank()) return null
    val messages =
            runCatching { syncJson.parseToJsonElement(messagesJson) as? JsonArray }.getOrNull()
                    ?: return null
    for (idx in messages.indices.reversed()) {
        val obj = messages[idx] as? JsonObject ?: continue
        val msgRole = (obj["role"]?.jsonPrimitive?.contentOrNull)?.trim()?.lowercase()
        if (msgRole != role) continue
        val content = (obj["content"]?.jsonPrimitive?.contentOrNull)?.trim()
        if (!content.isNullOrBlank()) {
            return content
        }
    }
    return null
}

/** 通用 JSON 值 -> JsonElement（取代 Gson 的 toJson）。 */
internal fun Any?.toJsonElement(): JsonElement =
        when (this) {
                null -> JsonNull
                is String -> JsonPrimitive(this)
                is Boolean -> JsonPrimitive(this)
                is Int -> JsonPrimitive(this)
                is Long -> JsonPrimitive(this)
                is Double -> JsonPrimitive(this)
                is Float -> JsonPrimitive(this)
                is JsonElement -> this
                is Map<*, *> ->
                        buildJsonObject {
                                forEach { (k, v) -> put(k.toString(), v.toJsonElement()) }
                        }
                is List<*> ->
                        buildJsonArray {
                                forEach { add(it.toJsonElement()) }
                        }
                else -> JsonPrimitive(toString())
        }

/** 通用 Map/List -> JSON 字串（取代 Gson toJson）。 */
internal fun mapToJsonString(data: Any?): String = data.toJsonElement().toString()
