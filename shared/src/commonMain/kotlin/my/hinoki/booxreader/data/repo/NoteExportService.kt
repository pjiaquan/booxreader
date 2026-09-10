package my.hinoki.booxreader.data.repo

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.core.utils.AiNoteSerialization
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.platform.ioDispatcher
import my.hinoki.booxreader.data.platform.platformFiles
import my.hinoki.booxreader.data.remote.HttpConfig
import my.hinoki.booxreader.data.remote.isValidHttpUrl

/**
 * 筆記匯出（自 `AiNoteRepository` 抽出的 export 叢集，約 190 行）。
 *
 * 涵蓋匯出端點測試與筆記匯出（單選 / 整本）。
 */

internal class NoteExportService(private val host: AiNoteRepository) {

    suspend fun exportSelectedNotes(noteIds: Collection<Long>): ExportResult =
            withContext(ioDispatcher) {
                try {
                    val notes = host.getByIds(noteIds)
                    exportNotesInternal(notes, "No selected AI notes to export")
                } catch (e: Exception) {
                    ExportResult(
                            success = false,
                            exportedCount = 0,
                            isEmpty = false,
                            message = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }

    suspend fun exportAllNotes(bookId: String): ExportResult =
            withContext(ioDispatcher) {
                try {
                    val notes = host.getByBook(bookId)
                    exportNotesInternal(notes, "No AI notes to export for this book")
                } catch (e: Exception) {
                    ExportResult(
                            success = false,
                            exportedCount = 0,
                            isEmpty = false,
                            message = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }

    private suspend fun exportNotesInternal(
            notes: List<AiNoteEntity>,
            emptyMessage: String
    ): ExportResult {
        if (notes.isEmpty()) {
            return ExportResult(
                    success = false,
                    exportedCount = 0,
                    isEmpty = true,
                    message = emptyMessage
            )
        }

        val settings = host.getSettings()

        val bookTitlesById: Map<String, String?> =
                notes.mapNotNull { it.bookId }.distinct().let { ids ->
                    if (ids.isEmpty()) emptyMap()
                    else ids.chunked(900).flatMap { chunk -> host.bookDao.getByIds(chunk) }.associateBy({ it.bookId }, { it.title })
                }

        val notesArray =
                jsonArr {
                    notes.forEach { note ->
                        val msgs = host.buildMessages(note)
                        val originalText =
                                note.originalText?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.originalTextFromMessages(
                                                        msgs.toString()
                                                )
                                                .orEmpty()
                        val aiResponse =
                                note.aiResponse?.takeIf { it.isNotBlank() }
                                        ?: AiNoteSerialization.aiResponseFromMessages(
                                                        msgs.toString()
                                                )
                                                .orEmpty()

                        add(
                                jsonObj {
                                    put("id", note.id)
                                    put("bookId", note.bookId?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put(
                                            "bookTitle",
                                            (note.bookTitle
                                                    ?: bookTitlesById[note.bookId])
                                                    ?.let { JsonPrimitive(it) }
                                                    ?: JsonNull
                                    )
                                    put("originalText", originalText)
                                    put("aiResponse", aiResponse)
                                    put("messages", msgs)
                                    put("locatorJson", note.locatorJson?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("createdAt", note.createdAt)
                                }
                        )
                    }
                }

        val payload = jsonObj { put("notes", notesArray) }
        val payloadString = payload.toString()

        val exportUrl =
                if (settings.exportToCustomUrl && settings.exportCustomUrl.isNotBlank()) {
                    settings.exportCustomUrl.trim()
                } else {
                    host.getBaseUrl() + HttpConfig.PATH_AI_NOTES_EXPORT
                }

        val statusMessages = mutableListOf<String>()
        var remoteSuccess = false
        var remoteAttempted = false

        if (exportUrl.isNotBlank()) {
            remoteAttempted = true
            val normalizedExportUrl =
                    when {
                        exportUrl.startsWith("http://", ignoreCase = true) ||
                                exportUrl.startsWith("https://", ignoreCase = true) -> exportUrl
                        else -> "https://$exportUrl"
                    }
            if (!normalizedExportUrl.isValidHttpUrl()) {
                statusMessages += "Invalid export URL: $exportUrl"
            } else {
                try {
                    val response =
                            host.ktorClient.post(normalizedExportUrl) {
                                    contentType(ContentType.Application.Json)
                                    setBody(payloadString)
                            }
                    if (response.status.isSuccess()) {
                        remoteSuccess = true
                        statusMessages += "Uploaded ${notes.size} notes to $normalizedExportUrl"
                    } else {
                        statusMessages += "Server export failed (${response.status.value})"
                    }
                } catch (e: Exception) {
                    statusMessages += "Server export error: ${e.message ?: "Unknown error"}"
                }
            }
        }

        var localSuccess = true
        var localPath: String? = null
        if (settings.exportToLocalDownloads) {
            val result = platformFiles().writeDownloadsFile("ai-notes.json", prettyJsonString(payload))
            if (result.localPath != null) {
                localPath = result.localPath
                statusMessages += result.message
            } else {
                statusMessages += result.message
                localSuccess = false
            }
        }

        val overallSuccess =
                (!remoteAttempted || remoteSuccess) &&
                        (!settings.exportToLocalDownloads || localSuccess)

        return ExportResult(
                success = overallSuccess,
                exportedCount = notes.size,
                isEmpty = false,
                message = statusMessages.joinToString(" | ").ifBlank { null },
                localPath = localPath
        )
    }

    suspend fun testExportEndpoint(targetUrl: String): String =
            withContext(ioDispatcher) {
                val safeUrl = targetUrl.trim()
                if (safeUrl.isEmpty()) {
                    return@withContext "URL is empty"
                }
                val normalizedUrl =
                        when {
                            safeUrl.startsWith("http://", ignoreCase = true) ||
                                    safeUrl.startsWith("https://", ignoreCase = true) -> safeUrl
                            else -> "https://$safeUrl"
                        }

                val payload =
                        jsonObj {
                            put("ping", "ai-notes-export-test")
                            put("timestamp", currentEpochMillis())
                        }
                if (!normalizedUrl.isValidHttpUrl()) return@withContext "Invalid URL"

                return@withContext try {
                    val response =
                            host.ktorClient.post(normalizedUrl) {
                                    contentType(ContentType.Application.Json)
                                    setBody(payload.toString())
                            }
                    if (response.status.isSuccess()) {
                        "Success (${response.status.value})"
                    } else {
                        "Failed (${response.status.value})"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message ?: "Unknown error"}"
                }
            }
}
