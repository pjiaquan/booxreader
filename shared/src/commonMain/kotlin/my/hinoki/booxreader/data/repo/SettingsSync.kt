package my.hinoki.booxreader.data.repo

import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings

/**
 * 設定同步（自 `UserSyncRepository` 抽出的 settings 叢集，約 300 行）。
 *
 * 涵蓋 PocketBase settings collection 的推送 / 拉取，以及遠端 JSON → `ReaderSettings`
 * 的解析（`parseSettingsFromJson` / `parseMagicTags`）。
 *
 * 注意：`parseSettingsFromJson` 的 JSON 參數刻意命名為 `payload` 而非 `json`，
 * 以免遮蔽 host 的 `json` 成員（否則 prefix 轉換會出錯）。
 */

internal class SettingsSync(private val host: UserSyncRepository) {

    private fun parseMagicTags(raw: Any?, fallback: List<MagicTag>): List<MagicTag> {
        if (raw == null) return fallback

        return runCatching {
                        when (raw) {
                                is kotlinx.serialization.json.JsonPrimitive ->
                                        host.json.decodeFromString<List<MagicTag>>(raw.content)
                                else -> host.json.decodeFromString<List<MagicTag>>(raw.toString())
                        } ?: fallback
                }
                .getOrElse {
                        host.logger.w("UserSyncRepository", "parseMagicTags failed, using fallback", it)
                        fallback
                }
    }

    /** Parse settings from PocketBase JSON response. */
    private fun parseSettingsFromJson(
        payload: kotlinx.serialization.json.JsonObject,
        fallbackMagicTags: List<MagicTag>
    ): ReaderSettings {
        return ReaderSettings(
                pageTapEnabled = payload["pageTapEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                pageSwipeEnabled = payload["pageSwipeEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                contrastMode = (payload["contrastMode"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 0,
                convertToTraditionalChinese =
                        payload["convertToTraditionalChinese"]?.jsonPrimitive?.booleanOrNull ?: true,
                serverBaseUrl = payload["serverBaseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                exportToCustomUrl = payload["exportToCustomUrl"]?.jsonPrimitive?.booleanOrNull ?: false,
                exportCustomUrl = payload["exportCustomUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                exportToLocalDownloads = payload["exportToLocalDownloads"]?.jsonPrimitive?.booleanOrNull
                                ?: false,
                apiKey = payload["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                aiModelName = payload["aiModelName"]?.jsonPrimitive?.contentOrNull ?: "deepseek-chat",
                aiSystemPrompt = payload["aiSystemPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                aiUserPromptTemplate = payload["aiUserPromptTemplate"]?.jsonPrimitive?.contentOrNull ?: "%s",
                temperature = payload["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
                maxTokens = (payload["maxTokens"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 4096,
                topP = payload["topP"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                frequencyPenalty = payload["frequencyPenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                presencePenalty = payload["presencePenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                assistantRole = payload["assistantRole"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                enableGoogleSearch = payload["enableGoogleSearch"]?.jsonPrimitive?.booleanOrNull ?: true,
                useStreaming = payload["useStreaming"]?.jsonPrimitive?.booleanOrNull ?: false,
                pageAnimationEnabled = payload["pageAnimationEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                showPageIndicator = payload["showPageIndicator"]?.jsonPrimitive?.booleanOrNull ?: true,
                autoCheckUpdates = host.prefs.getBoolean("auto_check_updates", true),
                dailySummaryEmailEnabled =
                        payload["dailySummaryEmailEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                dailySummaryEmailHour = (payload["dailySummaryEmailHour"]?.jsonPrimitive?.doubleOrNull)?.toInt()
                                ?: 21,
                dailySummaryEmailMinute =
                        (payload["dailySummaryEmailMinute"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 0,
                dailySummaryEmailTo = payload["dailySummaryEmailTo"]?.jsonPrimitive?.contentOrNull ?: "",
                language = payload["language"]?.jsonPrimitive?.contentOrNull ?: "system",
                activeProfileId = longValue(payload["activeProfileId"]).takeIf { it != 0L } ?: -1L,
                updatedAt = longValue(payload["updatedAt"]).takeIf { it > 0L }
                                ?: currentEpochMillis(),
                magicTags =
                        parseMagicTags(
                                raw = payload["magicTags"] ?: payload["magic_tags"],
                                fallback = fallbackMagicTags
                        )
        )
    }

    // --- Settings Sync ---

    /**
     * Pull settings from PocketBase if remote is newer than local. Returns the settings if
     * pulled, null if local is up to date or on error.
     */
    suspend fun pullSettingsIfNewer(): ReaderSettings? =
        withContext(host.io) {
                try {
                        val userId =
                                host.getUserId()
                                        ?: run {
                                                host.logger.w(
                                                        "UserSyncRepository",
                                                        "pullSettingsIfNewer - No user logged in"
                                                )
                                                return@withContext null
                                        }

                        val items =
                                host.fetchAllItems(
                                        "settings",
                                        "(user='$userId')",
                                        sortParam = "-updatedAt",
                                        perPage = 100
                                )
                        if (items.isEmpty()) {
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pullSettingsIfNewer - No remote settings found"
                                )
                                return@withContext null
                        }

                        val remoteSettings = latestSettingsRecord(items) ?: return@withContext null
                        val remoteUpdatedAt = longValue(remoteSettings["updatedAt"])
                        val localSettings = ReaderSettings.fromStorage(host.prefs)

                        if (remoteUpdatedAt > localSettings.updatedAt) {
                                // Remote is newer, update local
                                val parsed =
                                        parseSettingsFromJson(
                                                remoteSettings,
                                                fallbackMagicTags = emptyList()
                                        )
                                // 裝置專屬欄位不隨雲端覆蓋：
                                // - apiKey：未開啟同步時遠端是空的，保留本機金鑰
                                // - textSize：本來就只存在本機（不同裝置可有不同字級）
                                // - syncAiApiKeys：本機偏好，不從雲端讀
                                val updated =
                                        parsed.copy(
                                                apiKey =
                                                        parsed.apiKey.ifBlank {
                                                                localSettings.apiKey
                                                        },
                                                textSize = localSettings.textSize,
                                                syncAiApiKeys =
                                                        localSettings.syncAiApiKeys
                                        )
                                updated.saveTo(host.prefs)
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pullSettingsIfNewer - Settings pulled and saved"
                                )
                                updated
                        } else {
                                host.logger.d(
                                        "UserSyncRepository",
                                        "pullSettingsIfNewer - Local settings are up to date"
                                )
                                null
                        }
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullSettingsIfNewer failed", e)
                        null
                }
        }

    /** Push current settings to PocketBase. Creates a new record or updates existing one. */
    suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromStorage(host.prefs)) =
        withContext(host.io) {
                try {
                        val userId =
                                host.getUserId()
                                        ?: run {
                                                host.logger.w(
                                                        "UserSyncRepository",
                                                        "pushSettings - No user logged in"
                                                )
                                                return@withContext
                                        }

                        // First check if settings record exists
                        val checkUrl =
                                "${host.pocketBaseUrl}/api/collections/settings/records?filter=(user='$userId')"
                                                        val checkBody = host.executeBackendRequest(checkUrl)
                        val checkResponse =
                                host.json.decodeFromString<PocketBaseListResponse>(checkBody)

                        val existingSettingsRecord = latestSettingsRecord(checkResponse.items)
                        val magicTagsForUpload =
                                settings.magicTags.map { tag ->
                                        kotlinx.serialization.json.buildJsonObject {
                                                put("id", tag.id)
                                                put("label", tag.label)
                                                put("content", tag.content)
                                                put("description", tag.description)
                                                put("role", tag.role)
                                        }
                                }

                        val baseSettingsData =
                                mapOf(
                                        "user" to userId,
                                        "pageTapEnabled" to settings.pageTapEnabled,
                                        "pageSwipeEnabled" to settings.pageSwipeEnabled,
                                        "contrastMode" to settings.contrastMode,
                                        "convertToTraditionalChinese" to
                                                settings.convertToTraditionalChinese,
                                        "serverBaseUrl" to settings.serverBaseUrl,
                                        "exportToCustomUrl" to settings.exportToCustomUrl,
                                        "exportCustomUrl" to settings.exportCustomUrl,
                                        "exportToLocalDownloads" to
                                                settings.exportToLocalDownloads,
                                        "apiKey" to host.apiKeyForUpload(settings.apiKey),
                                        "aiModelName" to settings.aiModelName,
                                        "aiSystemPrompt" to settings.aiSystemPrompt,
                                        "aiUserPromptTemplate" to
                                                settings.aiUserPromptTemplate,
                                        "temperature" to settings.temperature,
                                        "maxTokens" to settings.maxTokens,
                                        "topP" to settings.topP,
                                        "frequencyPenalty" to settings.frequencyPenalty,
                                        "presencePenalty" to settings.presencePenalty,
                                        "assistantRole" to settings.assistantRole,
                                        "enableGoogleSearch" to settings.enableGoogleSearch,
                                        "useStreaming" to settings.useStreaming,
                                        "pageAnimationEnabled" to
                                                settings.pageAnimationEnabled,
                                        "showPageIndicator" to settings.showPageIndicator,
                                        "dailySummaryEmailEnabled" to
                                                settings.dailySummaryEmailEnabled,
                                        "dailySummaryEmailHour" to
                                                settings.dailySummaryEmailHour.coerceIn(0, 23),
                                        "dailySummaryEmailMinute" to
                                                settings.dailySummaryEmailMinute.coerceIn(0, 59),
                                        "dailySummaryEmailTo" to
                                                settings.dailySummaryEmailTo.trim(),
                                        "language" to settings.language,
                                        "activeProfileId" to settings.activeProfileId,
                                        "updatedAt" to currentEpochMillis()
                                )
                        val settingsDataWithMagicTags =
                                baseSettingsData + ("magicTags" to magicTagsForUpload)

                        fun toBody(data: Map<String, Any>): String =
                                buildJsonObject {
                                        data.forEach { (k, v) -> put(k, v.toJsonElement()) }
                                }.toString()

                        if (checkResponse.items.isNotEmpty()) {
                                // Update existing record
                                val recordId =
                                        existingSettingsRecord?.get("id")?.jsonPrimitive?.contentOrNull
                                                ?: return@withContext
                                val updateUrl =
                                        "${host.pocketBaseUrl}/api/collections/settings/records/$recordId"
                                try {
                                        host.executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(toBody(settingsDataWithMagicTags))
                                        }
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings updated with magicTags"
                                        )
                                } catch (e: Exception) {
                                        host.logger.w(
                                                "UserSyncRepository",
                                                "pushSettings - update with magicTags failed, retrying without magicTags",
                                                e
                                        )
                                        host.executeBackendRequest(updateUrl) {
                                            method = HttpMethod.Patch
                                            contentType(ContentType.Application.Json)
                                            setBody(toBody(baseSettingsData))
                                        }
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings updated without magicTags fallback"
                                        )
                                }
                        } else {
                                // Create new record
                                val createUrl =
                                        "${host.pocketBaseUrl}/api/collections/settings/records"
                                try {
                                        host.executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(toBody(settingsDataWithMagicTags))
                                        }
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings created with magicTags"
                                        )
                                } catch (e: Exception) {
                                        host.logger.w(
                                                "UserSyncRepository",
                                                "pushSettings - create with magicTags failed, retrying without magicTags",
                                                e
                                        )
                                        host.executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(toBody(baseSettingsData))
                                        }
                                        host.logger.d(
                                                "UserSyncRepository",
                                                "pushSettings - Settings created without magicTags fallback"
                                        )
                                }
                        }
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushSettings failed", e)
                }
        }
}
