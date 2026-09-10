package my.hinoki.booxreader.data.repo

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.withTransactionCompat
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * AI 筆記同步（自 `UserSyncRepository` 抽出的 note 叢集，約 290 行）。
 *
 * 涵蓋筆記的推送 / 拉取 / 刪除、重複筆記清理，以及同步前把 messages JSON
 * 正規化 / 截斷的 helper。
 */

internal class AiNoteSync(private val host: UserSyncRepository) {

    // --- Note Sync ---

    suspend fun pushAiNote(note: AiNoteEntity): String? =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext null
                        val originalTextForSync = resolveOriginalTextForSync(note)
                        val aiResponseResolved = resolveAiResponseForSync(note)
                        val aiResponseForSync =
                                truncateForRemoteText(
                                        aiResponseResolved,
                                        AI_NOTE_TEXT_FIELD_MAX_CHARS
                                )
                        val messagesForSync =
                                normalizeAiNoteMessagesForSync(
                                        note = note,
                                        originalText = originalTextForSync,
                                        aiResponse = aiResponseResolved,
                                        maxChars = AI_NOTE_TEXT_FIELD_MAX_CHARS
                                )
                        if (aiResponseResolved.length > aiResponseForSync.length ||
                                        note.messages.length > messagesForSync.length
                        ) {
                                host.logger.w(
                                        "UserSyncRepository",
                                        "pushAiNote - Truncated ai note payload for PocketBase text limits (id=${note.id}, remoteId=${note.remoteId})"
                                )
                        }

                        val noteData =
                                mapOf(
                                        "user" to userId,
                                        "bookId" to (note.bookId ?: ""),
                                        "bookTitle" to (note.bookTitle ?: ""),
                                        "messages" to messagesForSync,
                                        "originalText" to originalTextForSync,
                                        "aiResponse" to aiResponseForSync,
                                        "status" to
                                                if (aiResponseResolved.isBlank()) {
                                                        "generating"
                                                } else {
                                                        "done"
                                                },
                                        "locatorJson" to (note.locatorJson ?: ""),
                                        "createdAt" to note.createdAt,
                                        "updatedAt" to currentEpochMillis()
                                )

                        val requestBody =
                                mapToJsonString(noteData)
                                        

                        val syncedRemoteId =
                                if (!note.remoteId.isNullOrBlank()) {
                                val updateUrl =
                                        "${host.pocketBaseUrl}/api/collections/ai_notes/records/${note.remoteId}"
                                host.executeBackendRequest(updateUrl) {
                                    method = HttpMethod.Patch
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                        note.remoteId
                        } else {
                                val createUrl =
                                        "${host.pocketBaseUrl}/api/collections/ai_notes/records"
                                val createBody = host.executeBackendRequest(createUrl) {
                                    method = HttpMethod.Post
                                    contentType(ContentType.Application.Json)
                                    setBody(requestBody)
                                }
                                val created =
                                        host.json.parseToJsonElement(createBody).jsonObject
                                created["id"]?.jsonPrimitive?.contentOrNull
                        } ?: return@withContext null

                        host.logger.d("UserSyncRepository", "pushAiNote - Note synced")
                        syncedRemoteId
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pushAiNote failed", e)
                        null
                }
        }

    suspend fun pullNotes(): Int =
        withContext(host.io) {
                try {
                        val userId = host.getUserId() ?: return@withContext 0

                        val items =
                                host.fetchAllItems(
                                        "ai_notes",
                                        "(user='$userId')",
                                        sortParam = "-updatedAt",
                                        perPage = 100
                                )
                        var syncedCount = 0

                        val allRemoteIds = items.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.distinct()
                        val cachedNotes = mutableMapOf<String, AiNoteEntity>()
                        allRemoteIds.chunked(900).forEach { chunk ->
                                cachedNotes.putAll(host.db.aiNoteDao().getByRemoteIds(chunk).associateBy { it.remoteId!! })
                        }

                        val notesToInsert = mutableListOf<AiNoteEntity>()
                        val notesToUpdate = mutableListOf<AiNoteEntity>()

                        for (item in items) {
                                val remoteId = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                val note =
                                        AiNoteEntity(
                                                remoteId = remoteId,
                                                bookId = item["bookId"]?.jsonPrimitive?.contentOrNull,
                                                bookTitle = item["bookTitle"]?.jsonPrimitive?.contentOrNull,
                                                messages = item["messages"]?.jsonPrimitive?.contentOrNull
                                                                ?: "",
                                                originalText =
                                                        item["originalText"]?.jsonPrimitive?.contentOrNull,
                                                aiResponse = item["aiResponse"]?.jsonPrimitive?.contentOrNull,
                                                locatorJson =
                                                        item["locatorJson"]?.jsonPrimitive?.contentOrNull,
                                                createdAt =
                                                        (item["createdAt"]?.jsonPrimitive?.doubleOrNull)
                                                                ?.toLong()
                                                                ?: currentEpochMillis(),
                                                updatedAt =
                                                        (item["updatedAt"]?.jsonPrimitive?.doubleOrNull)
                                                                ?.toLong()
                                                                ?: currentEpochMillis()
                                        )

                                val existing = cachedNotes[remoteId]
                                if (existing == null) {
                                        notesToInsert.add(note)
                                        syncedCount++
                                } else if (note.updatedAt > existing.updatedAt) {
                                        val updatedNote = note.copy(id = existing.id)
                                        notesToUpdate.add(updatedNote)
                                        cachedNotes[remoteId] = updatedNote
                                        syncedCount++
                                }
                        }

                        if (notesToInsert.isNotEmpty() || notesToUpdate.isNotEmpty()) {
                                host.db.withTransactionCompat {
                                        if (notesToInsert.isNotEmpty()) {
                                                val insertedIds = host.db.aiNoteDao().insertBatch(notesToInsert)
                                                for (i in notesToInsert.indices) {
                                                        val note = notesToInsert[i]
                                                        val insertedId = insertedIds[i]
                                                        cachedNotes[note.remoteId!!] = note.copy(id = insertedId)
                                                }
                                        }
                                        if (notesToUpdate.isNotEmpty()) {
                                                host.db.aiNoteDao().updateBatch(notesToUpdate)
                                        }
                                }
                        }

                        cleanupDuplicateNotes()

                        host.logger.d("UserSyncRepository", "pullNotes - Synced $syncedCount notes")
                        syncedCount
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "pullNotes failed", e)
                        0
                }
        }

    suspend fun deleteAiNote(remoteId: String): Boolean =
        withContext(host.io) {
                try {
                        val url =
                                "${host.pocketBaseUrl}/api/collections/ai_notes/records/$remoteId"
                                                        host.executeBackendRequest(url) {
                            method = HttpMethod.Delete
                        }
                        host.logger.d("UserSyncRepository", "deleteAiNote - Note deleted")
                        true
                } catch (e: Exception) {
                        host.logger.e("UserSyncRepository", "deleteAiNote failed", e)
                        false
                }
        }

    private suspend fun cleanupDuplicateNotes() {
        val notes = host.db.aiNoteDao().getAll()
        val seen = HashSet<String>(notes.size)
        val duplicateIds = ArrayList<Long>()
        for (note in notes) {
                val key =
                        listOf(
                                        note.remoteId.orEmpty(),
                                        note.bookId.orEmpty(),
                                        note.originalText.orEmpty(),
                                        note.aiResponse.orEmpty(),
                                        note.messages,
                                        note.locatorJson.orEmpty()
                                )
                                .joinToString("\u0001")
                if (!seen.add(key)) {
                        duplicateIds.add(note.id)
                }
        }
        if (duplicateIds.isEmpty()) return
        duplicateIds.chunked(900).forEach { chunk ->
                host.db.aiNoteDao().deleteByIds(chunk)
        }
        host.logger.d(
                "UserSyncRepository",
                "cleanupDuplicateNotes - Removed ${duplicateIds.size} duplicate notes"
        )
    }

    private fun normalizeAiNoteMessagesForSync(
        note: AiNoteEntity,
        originalText: String,
        aiResponse: String,
        maxChars: Int
    ): String {
        val raw = note.messages
        if (raw.length <= maxChars) return raw

        val compact = mutableListOf<Map<String, String>>()
        val original = originalText.trim()
        val response = aiResponse.trim()

        if (original.isNotBlank()) {
                compact +=
                        mapOf(
                                "role" to "user",
                                "content" to
                                        truncateForRemoteText(
                                                original,
                                                AI_NOTE_COMPACT_MESSAGE_CHARS
                                        )
                        )
        }
        if (response.isNotBlank()) {
                compact +=
                        mapOf(
                                "role" to "assistant",
                                "content" to
                                        truncateForRemoteText(
                                                response,
                                                AI_NOTE_COMPACT_MESSAGE_CHARS
                                        )
                        )
        }
        if (compact.isEmpty()) {
                compact +=
                        mapOf(
                                "role" to "assistant",
                                "content" to
                                        truncateForRemoteText(
                                                raw.trim().ifBlank { "No content" },
                                                AI_NOTE_COMPACT_MESSAGE_CHARS
                                        )
                        )
        }
        val compactJson = mapToJsonString(compact)
        if (compactJson.length <= maxChars) return compactJson
        return mapToJsonString(
                listOf(
                        mapOf(
                                "role" to "assistant",
                                "content" to
                                        truncateForRemoteText(
                                                "Conversation truncated for sync size limit.",
                                                AI_NOTE_COMPACT_MESSAGE_CHARS
                                        )
                        )
                )
        )
    }

    private fun resolveOriginalTextForSync(note: AiNoteEntity): String {
        val direct = note.originalText?.trim().orEmpty()
        if (direct.isNotBlank()) return direct
        return extractMessageContentByRole(note.messages, role = "user").orEmpty()
    }

    private fun resolveAiResponseForSync(note: AiNoteEntity): String {
        val direct = note.aiResponse?.trim().orEmpty()
        if (direct.isNotBlank()) return direct
        return extractMessageContentByRole(note.messages, role = "assistant").orEmpty()
    }

    private companion object {
        const val AI_NOTE_TEXT_FIELD_MAX_CHARS = 5000
        const val AI_NOTE_COMPACT_MESSAGE_CHARS = 1200
    }
}
