package my.hinoki.booxreader.data.repo

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.ApiKey
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.settings.ReaderSettings

/**
 * AI 設定檔同步（自 `UserSyncRepository` 抽出的 profile 叢集，約 350 行）。
 *
 * 涵蓋 profile 的推送 / 拉取 / 刪除、同名的去重與修復、遠端 JSON → `AiProfileEntity`
 * 的映射，以及把 profile 套用到本機 `ReaderSettings`。
 *
 * `apiKeyForUpload` 維持 `internal`：`SettingsSync` 需要透過 host 呼叫它。
 */

internal class ProfileSync(private val host: UserSyncRepository) {

    private fun shouldPreferProfile(candidate: AiProfileEntity, current: AiProfileEntity): Boolean {
        val candidateHasKey = hasUsableApiKey(candidate.apiKey)
        val currentHasKey = hasUsableApiKey(current.apiKey)
        if (candidateHasKey != currentHasKey) return candidateHasKey
        if (candidate.updatedAt != current.updatedAt) return candidate.updatedAt > current.updatedAt
        return candidate.id > current.id
    }

    private fun toRemoteProfile(item: kotlinx.serialization.json.JsonObject): AiProfileEntity? {
        val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val now = currentEpochMillis()
        return AiProfileEntity(
                remoteId = remoteId,
                name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                modelName = item["modelName"]?.jsonPrimitive?.contentOrNull ?: "",
                apiKey = ApiKey(item["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""),
                serverBaseUrl = item["serverBaseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                systemPrompt = item["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: "",
                userPromptTemplate = item["userPromptTemplate"]?.jsonPrimitive?.contentOrNull ?: "",
                useStreaming = item["useStreaming"]?.jsonPrimitive?.booleanOrNull ?: false,
                temperature = item["temperature"]?.jsonPrimitive?.doubleOrNull ?: 0.7,
                maxTokens = (item["maxTokens"]?.jsonPrimitive?.doubleOrNull)?.toInt() ?: 4096,
                topP = item["topP"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                frequencyPenalty = item["frequencyPenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                presencePenalty = item["presencePenalty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                assistantRole = item["assistantRole"]?.jsonPrimitive?.contentOrNull ?: "assistant",
                enableGoogleSearch = item["enableGoogleSearch"]?.jsonPrimitive?.booleanOrNull ?: true,
                extraParamsJson = item["extraParamsJson"]?.jsonPrimitive?.contentOrNull,
                createdAt = (item["createdAt"]?.jsonPrimitive?.doubleOrNull)?.toLong() ?: now,
                updatedAt = (item["updatedAt"]?.jsonPrimitive?.doubleOrNull)?.toLong() ?: now,
                isSynced = true
        )
    }

    private fun applyProfileToLocalSettings(profile: AiProfileEntity) {
        val currentSettings = ReaderSettings.fromStorage(host.prefs)
        currentSettings
                .copy(
                        aiModelName = profile.modelName,
                        apiKey = profile.apiKey.value,
                        serverBaseUrl = profile.serverBaseUrl,
                        aiSystemPrompt = profile.systemPrompt,
                        aiUserPromptTemplate = profile.userPromptTemplate,
                        assistantRole = profile.assistantRole,
                        enableGoogleSearch = profile.enableGoogleSearch,
                        useStreaming = profile.useStreaming,
                        temperature = profile.temperature,
                        maxTokens = profile.maxTokens,
                        topP = profile.topP,
                        frequencyPenalty = profile.frequencyPenalty,
                        presencePenalty = profile.presencePenalty,
                        activeProfileId = profile.id,
                        updatedAt = currentEpochMillis()
                )
                .saveTo(host.prefs)
    }

    private suspend fun cleanupDuplicateProfilesAndRepairActive(): Int {
        val allProfiles = host.db.aiProfileDao().getAllList()
        if (allProfiles.isEmpty()) return 0

        val grouped =
                allProfiles
                        .filter { it.name.isNotBlank() }
                        .groupBy { profileNameKey(it.name) }
                        .filterValues { it.size > 1 }
        var changedCount = 0
        var activeProfileId = ReaderSettings.fromStorage(host.prefs).activeProfileId
        val deletedIds = mutableSetOf<Long>()

        for ((_, group) in grouped) {
                val keep = group.reduce { best, next ->
                        if (shouldPreferProfile(next, best)) next else best
                }
                group.filter { it.id != keep.id }.forEach { duplicate ->
                        host.db.aiProfileDao().deleteById(duplicate.id)
                        deletedIds.add(duplicate.id)
                        changedCount++
                        if (activeProfileId == duplicate.id) {
                                activeProfileId = keep.id
                        }
                }
        }

        val activeProfile =
                if (activeProfileId > 0L) host.db.aiProfileDao().getById(activeProfileId) else null

        if (activeProfile != null && hasUsableApiKey(activeProfile.apiKey)) {
                return changedCount
        }

        val fallback =
                allProfiles
                        .filter { it.id !in deletedIds && hasUsableApiKey(it.apiKey) }
                        .maxByOrNull { it.updatedAt }
                        ?: return changedCount

        applyProfileToLocalSettings(fallback)
        return changedCount + 1
    }

    // --- Profile Sync ---

    suspend fun pushAiProfile(profile: AiProfileEntity): String? =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext null

                        val profileData =
                                mapOf(
                                        "user" to userId,
                                        "name" to profile.name,
                                        "modelName" to profile.modelName,
                                        "apiKey" to host.apiKeyForUpload(profile.apiKey.value),
                                        "serverBaseUrl" to profile.serverBaseUrl,
                                        "systemPrompt" to profile.systemPrompt,
                                        "userPromptTemplate" to profile.userPromptTemplate,
                                        "useStreaming" to profile.useStreaming,
                                        "temperature" to profile.temperature,
                                        "maxTokens" to profile.maxTokens,
                                        "topP" to profile.topP,
                                        "frequencyPenalty" to profile.frequencyPenalty,
                                        "presencePenalty" to profile.presencePenalty,
                                        "assistantRole" to profile.assistantRole,
                                        "enableGoogleSearch" to profile.enableGoogleSearch,
                                        "extraParamsJson" to
                                                (profile.extraParamsJson ?: ""),
                                        "updatedAt" to currentEpochMillis()
                                )

                        val requestBody =
                                mapToJsonString(profileData)
                                        

                        val syncedRemoteId =
                                if (!profile.remoteId.isNullOrBlank()) {
                                val updateUrl =
                                        "${host.pocketBaseUrl}/api/collections/ai_profiles/records/${profile.remoteId}"
                                host.executeBackendRequest(updateUrl) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                        profile.remoteId
                        } else {
                                val remoteItems =
                                        host.fetchAllItems(
                                                "ai_profiles",
                                                "(user='$userId')",
                                                sortParam = "-updatedAt",
                                                perPage = 100
                                        )
                                val sameNameRemote =
                                        remoteItems
                                                .mapNotNull { toRemoteProfile(it) }
                                                .filter {
                                                        profileNameKey(it.name) ==
                                                                profileNameKey(profile.name)
                                                }
                                                .reduceOrNull { best, next ->
                                                        if (shouldPreferProfile(next, best)) next
                                                        else best
                                                }

                                if (sameNameRemote != null) {
                                        val keepRemoteApiKey =
                                                !hasUsableApiKey(profile.apiKey) &&
                                                        hasUsableApiKey(sameNameRemote.apiKey)
                                        if (keepRemoteApiKey) {
                                                host.logger.d(
                                                        "UserSyncRepository",
                                                        "pushAiProfile - Skip overwrite for ${profile.name} because remote has usable API key"
                                                )
                                        } else {
                                                val updateUrl =
                                                        "${host.pocketBaseUrl}/api/collections/ai_profiles/records/${sameNameRemote.remoteId}"
                                                host.executeBackendRequest(updateUrl) {
                                                    method = HttpMethod.Patch
                                                    contentType(ContentType.Application.Json)
                                                    setBody(requestBody)
                                                }
                                        }
                                        sameNameRemote.remoteId
                                } else {
                                        val createUrl =
                                                "${host.pocketBaseUrl}/api/collections/ai_profiles/records"
                                        val createBody = host.executeBackendRequest(createUrl) {
                                            method = HttpMethod.Post
                                            contentType(ContentType.Application.Json)
                                            setBody(requestBody)
                                        }
                                        val created =
                                                host.json.parseToJsonElement(createBody).jsonObject
                                        created["id"]?.jsonPrimitive?.contentOrNull
                                }
                        } ?: return@withContext null

                        host.logger.d("UserSyncRepository", "pushAiProfile - Profile synced")
                        syncedRemoteId
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushAiProfile failed", e)
                        null
                }
        }

    suspend fun pushProfile(profile: AiProfileEntity): String? = pushAiProfile(profile)

    suspend fun pullAiProfiles(): Int =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext 0

                        val items =
                                host.fetchAllItems(
                                        "ai_profiles",
                                        "(user='$userId')",
                                        sortParam = "-updatedAt",
                                        perPage = 100
                                )
                        var syncedCount = 0

                        val selectedRemoteByName = LinkedHashMap<String, AiProfileEntity>()
                        for (item in items) {
                                val remoteProfile = toRemoteProfile(item) ?: continue
                                val nameKey = profileNameKey(remoteProfile.name)
                                if (nameKey.isBlank()) continue
                                val existing = selectedRemoteByName[nameKey]
                                if (existing == null ||
                                                shouldPreferProfile(
                                                        remoteProfile,
                                                        existing
                                                )
                                ) {
                                        selectedRemoteByName[nameKey] = remoteProfile
                                }
                        }

                        val localProfiles = host.db.aiProfileDao().getAllList()
                        val localByRemoteId = mutableMapOf<String, AiProfileEntity>()
                        localProfiles.forEach { localProfile ->
                                val remoteId = localProfile.remoteId
                                if (!remoteId.isNullOrBlank()) {
                                        localByRemoteId[remoteId] = localProfile
                                }
                        }
                        val localByName = mutableMapOf<String, AiProfileEntity>()
                        localProfiles.forEach { profile ->
                                val nameKey = profileNameKey(profile.name)
                                if (nameKey.isBlank()) return@forEach
                                val existing = localByName[nameKey]
                                if (existing == null || shouldPreferProfile(profile, existing)) {
                                        localByName[nameKey] = profile
                                }
                        }

                                                        val profilesToUpdate = mutableListOf<AiProfileEntity>()
                        val remoteProfilesToProcess = mutableListOf<Pair<String, AiProfileEntity>>()

                        for ((nameKey, remoteProfile) in selectedRemoteByName) {
                                val remoteId = remoteProfile.remoteId ?: continue
                                val byRemote = localByRemoteId[remoteId]
                                if (byRemote != null) {
                                        if (shouldPreferProfile(remoteProfile, byRemote)) {
                                                val merged = remoteProfile.copy(id = byRemote.id)
                                                profilesToUpdate.add(merged)
                                                localByName[nameKey] = merged
                                                localByRemoteId[remoteId] = merged
                                                syncedCount++
                                        }
                                        continue
                                }

                                val byName = localByName[nameKey]
                                if (byName != null) {
                                        if (shouldPreferProfile(remoteProfile, byName)) {
                                                val merged = remoteProfile.copy(id = byName.id)
                                                profilesToUpdate.add(merged)
                                                localByName[nameKey] = merged
                                                localByRemoteId[remoteId] = merged
                                                syncedCount++
                                        } else if (byName.remoteId.isNullOrBlank()) {
                                                val linked = byName.copy(remoteId = remoteId)
                                                profilesToUpdate.add(linked)
                                                localByName[nameKey] = linked
                                                localByRemoteId[remoteId] = linked
                                                syncedCount++
                                        }
                                        continue
                                }

                                remoteProfilesToProcess.add(Pair(nameKey, remoteProfile))
                        }

                        if (remoteProfilesToProcess.isNotEmpty() || profilesToUpdate.isNotEmpty()) {
                                host.db.withTransactionCompat {
                                        if (profilesToUpdate.isNotEmpty()) {
                                                host.db.aiProfileDao().updateBatch(profilesToUpdate)
                                        }
                                        if (remoteProfilesToProcess.isNotEmpty()) {
                                                val insertedIds = host.db.aiProfileDao().insertBatch(remoteProfilesToProcess.map { it.second })
                                                for (i in remoteProfilesToProcess.indices) {
                                                        val (nameKey, remoteProfile) = remoteProfilesToProcess[i]
                                                        val insertedId = insertedIds[i]
                                                        val inserted = remoteProfile.copy(id = insertedId)
                                                        localByName[nameKey] = inserted
                                                        localByRemoteId[inserted.remoteId!!] = inserted
                                                        syncedCount++
                                                }
                                        }
                                }
                        }

                        syncedCount += cleanupDuplicateProfilesAndRepairActive()

                        host.logger.d(
                                "UserSyncRepository",
                                "pullAiProfiles - Synced $syncedCount profiles"
                        )
                        syncedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullAiProfiles failed", e)
                        0
                }
        }

    suspend fun deleteAiProfile(remoteId: String): Boolean =
        withContext(host.io) {
                try {
                        val url =
                                "${host.pocketBaseUrl}/api/collections/ai_profiles/records/$remoteId"
                                                        host.executeBackendRequest(url) {
                            method = HttpMethod.Delete
                        }
                        host.logger.d("UserSyncRepository", "deleteAiProfile - Profile deleted")
                        true
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "deleteAiProfile failed", e)
                        false
                }
        }
}
