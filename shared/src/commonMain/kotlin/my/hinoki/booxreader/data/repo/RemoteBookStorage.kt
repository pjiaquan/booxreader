package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.flow.first
import my.hinoki.booxreader.data.db.BookEntity
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.patch
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import my.hinoki.booxreader.data.platform.currentEpochMillis
import my.hinoki.booxreader.data.platform.platformFiles

/**
 * `UserSyncRepository` 的「遠端書檔儲存」叢集。
 *
 * 這些函式原本是 3000+ 行 God class 內的 private method（約 370 行），內容涵蓋
 * PocketBase storage 的路徑解析、上傳、下載、可用性探測與本地快取檔名管理。
 *
 * 它們以 `internal` extension function 的形式存在，並使用 `UserSyncRepository` 的
 * `internal` 成員（`executeBackendRequest` / `getUserId` / `logger` 等）。這是把
 * God class 拆開、又不想改動公開 API 與呼叫點的折衷做法：
 * - `:app` 看到的 `UserSyncRepository` 公開方法完全不變
 * - 這些實作細節不會外洩到 `:app` 或 iOS framework（`internal`）
 */

internal enum class RemoteFileState {
    PRESENT,
    MISSING,
    UNKNOWN
}

internal val BOOK_FILE_FIELD_CANDIDATES =
        listOf("bookFile", "file", "epubFile", "epub", "asset", "book")

internal fun UserSyncRepository.localBooksCacheDir(): String {
    val base = platformFiles().appFilesDir() ?: return "synced_books"
    return "$base/synced_books"
}

internal fun UserSyncRepository.clearSyncedBookCache() {
    val cacheDirPath = localBooksCacheDir()
    if (!platformFiles().exists(cacheDirPath)) return
    runCatching { platformFiles().delete(cacheDirPath) }
            .onFailure {
                    logger.w(
                            "UserSyncRepository",
                            "Failed to clear synced_books",
                            it
                    )
            }
}

internal fun UserSyncRepository.localBookCacheFile(bookId: String): String {
    val safeBookId = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "${localBooksCacheDir()}/$safeBookId.epub"
}

internal fun UserSyncRepository.isUriReadable(uriStr: String): Boolean {
    if (uriStr.startsWith("pocketbase://", ignoreCase = true)) {
            return false
    }
    return try {
            platformFiles().isUriReadable(uriStr)
    } catch (_: Exception) {
            false
    }
}

internal suspend fun UserSyncRepository.fetchBookRecord(
    userId: String,
    bookId: String
): kotlinx.serialization.json.JsonObject? {
    val filter = "(user='$userId'%26%26bookId='$bookId')"
    val url =
            "$pocketBaseUrl/api/collections/books/records?filter=$filter&perPage=1"
                    val responseBody = executeBackendRequest(url)
    val response = json.decodeFromString<PocketBaseListResponse>(responseBody)
    return response.items.firstOrNull()
}

internal suspend fun UserSyncRepository.updateBookStoragePath(recordId: String, storagePath: String) {
    val payload =
            mapOf(
                    "storagePath" to storagePath,
                    "updatedAt" to currentEpochMillis()
            )
    val requestBody = mapToJsonString(payload)
    val url = "$pocketBaseUrl/api/collections/books/records/$recordId"
    executeBackendRequest(url) {
        method = HttpMethod.Patch
        contentType(ContentType.Application.Json)
        setBody(requestBody)
    }
}

internal suspend fun UserSyncRepository.tryUploadBookFile(
    recordId: String?,
    book: BookEntity
): String? {
    if (recordId.isNullOrBlank() || book.deleted) {
            return null
    }
    val userId = getUserId()

    val sourceUri = book.fileUri.takeIf { isUriReadable(it) } ?: return null

    try {
            val bytes =
                    platformFiles().readUriBytes(sourceUri)
                            ?: return null
            if (bytes.isEmpty()) {
                    return null
            }

            val displayName =
                    (platformFiles().contentName(sourceUri)
                            ?: "${book.bookId}.epub")
                            .substringAfterLast('/')
            val sanitizedBaseName =
                    displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val nonEmptyBaseName =
                    sanitizedBaseName.ifBlank { "${book.bookId}.epub" }
            val cleanName =
                    if (nonEmptyBaseName.lowercase().endsWith(".epub")) {
                            nonEmptyBaseName
                    } else {
                            "$nonEmptyBaseName.epub"
                    }
            val uploadUrl = "$pocketBaseUrl/api/collections/books/records/$recordId"

            for (field in BOOK_FILE_FIELD_CANDIDATES) {
                    val form =
                            formData {
                                    append("updatedAt", currentEpochMillis().toString())
                                    if (!userId.isNullOrBlank()) {
                                            append("user", userId)
                                    }
                                    append(
                                            field,
                                            bytes,
                                            Headers.build {
                                                    append(
                                                            HttpHeaders.ContentType,
                                                            "application/epub+zip"
                                                    )
                                                    append(
                                                            HttpHeaders.ContentDisposition,
                                                            "filename=\"$cleanName\""
                                                    )
                                            }
                                    )
                            }

                    val response =
                            ktorClient.patch(uploadUrl) {
                                    header(
                                            "Authorization",
                                            "Bearer ${tokenManager.getAccessToken().orEmpty()}"
                                    )
                                    setBody(MultiPartFormDataContent(form))
                            }
                    val body = response.bodyAsText()
                    if (!response.status.isSuccess()) {
                            logger.w(
                                    "UserSyncRepository",
                                    "tryUploadBookFile - field=$field failed code=${response.status.value}"
                            )
                    } else {
                                    val payload =
                                            runCatching {
                                                    json.parseToJsonElement(body).jsonObject
                                            }
                                                    .getOrNull()
                                    val uploadedFileName =
                                            extractUploadedFileName(
                                                    payload,
                                                    fieldName = field,
                                                    fallback = cleanName
                                            )
                                    if (!uploadedFileName.isNullOrBlank()) {
                                            return "$recordId/$uploadedFileName"
                                    }
                            }
            }
    } catch (e: Exception) {
            logger.e("UserSyncRepository", "tryUploadBookFile failed", e)
            reporter.report("UserSyncRepository.tryUploadBookFile",
                    "Failed to upload book file for ${book.bookId}",
                    e
            )
    }
    return null
}

internal fun UserSyncRepository.extractUploadedFileName(
    record: kotlinx.serialization.json.JsonObject?,
    fieldName: String,
    fallback: String? = null
): String? {
    if (record == null) {
            return fallback
    }

    val value = record[fieldName]
    when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                    val text = value.contentOrNull
                    if (!text.isNullOrBlank()) {
                            return text.substringAfterLast('/')
                    }
            }
            is kotlinx.serialization.json.JsonArray -> {
                    val firstFile =
                            value
                                    .firstOrNull {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)
                                                    ?.contentOrNull
                                                    ?.isNotBlank() == true
                                    }
                                    ?.let {
                                            (it as kotlinx.serialization.json.JsonPrimitive)
                                                    .contentOrNull
                                    }
                    if (!firstFile.isNullOrBlank()) {
                            return firstFile.substringAfterLast('/')
                    }
            }
            else -> { /* 其他型別（物件等）不處理 */ }
    }

    return fallback
}

internal fun UserSyncRepository.resolveStoragePathFromRecord(record: kotlinx.serialization.json.JsonObject?): String? {
    if (record == null) {
            return null
    }

    val direct = normalizeStoragePath(record["storagePath"]?.jsonPrimitive?.contentOrNull)
    if (!direct.isNullOrBlank()) {
            return direct
    }

    val recordId = record["id"]?.jsonPrimitive?.contentOrNull ?: return null
    for (field in BOOK_FILE_FIELD_CANDIDATES) {
            val fileName = extractUploadedFileName(record, field)
            if (!fileName.isNullOrBlank()) {
                    return "$recordId/$fileName"
            }
    }
    return null
}

internal fun UserSyncRepository.buildDownloadUrl(storagePath: String, recordId: String?): String? {
    val normalized = normalizeStoragePath(storagePath) ?: return null
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized
    }
    if (normalized.startsWith("/")) {
            return "$pocketBaseUrl$normalized"
    }

    val clean = normalized.removePrefix("books/")
    val parts = clean.split('/').filter { it.isNotBlank() }
    if (parts.size >= 2) {
            val rid = urlEncodePath(parts.first())
            val fileName = urlEncodePath(parts.drop(1).joinToString("/"))
            return "$pocketBaseUrl/api/files/books/$rid/$fileName"
    }

    if (parts.size == 1 && !recordId.isNullOrBlank()) {
            val rid = urlEncodePath(recordId)
            val fileName = urlEncodePath(parts.first())
            return "$pocketBaseUrl/api/files/books/$rid/$fileName"
    }

    return null
}

internal fun UserSyncRepository.withFileToken(url: String, token: String): String {
    if (Regex("[?&]token=").containsMatchIn(url)) return url
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}token=${urlEncodeQueryValue(token)}"
}

internal suspend fun UserSyncRepository.getProtectedFileToken(): String? {
    return try {
            val tokenUrl = "$pocketBaseUrl/api/files/token"
            val requestBody = "{}"
            val responseBody =
                    executeBackendRequest(tokenUrl, reportError = false) {
                            method = HttpMethod.Post
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                    }
            val payload =
                    runCatching {
                                    json.parseToJsonElement(responseBody).jsonObject
                            }
                            .getOrNull()
            payload?.get("token")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
            null
    }
}

internal suspend fun UserSyncRepository.probeRemoteFileState(url: String): RemoteFileState {
    return try {
            var resolvedUrl = url
            if (resolvedUrl.startsWith(pocketBaseUrl)) {
                    val fileToken = getProtectedFileToken()
                    if (!fileToken.isNullOrBlank()) {
                            resolvedUrl = withFileToken(resolvedUrl, fileToken)
                    }
            }

            fun classify(code: Int): RemoteFileState =
                    when {
                            code in 200..299 || code == 304 || code == 416 ->
                                    RemoteFileState.PRESENT
                            code == 404 || code == 410 -> RemoteFileState.MISSING
                            else -> RemoteFileState.UNKNOWN
                    }

            val headCode =
                    ktorClient.head(resolvedUrl) {
                            authIfBackend(resolvedUrl)
                    }.status.value
            if (headCode == 405 || headCode == 501) {
                    val getCode =
                            ktorClient.get(resolvedUrl) {
                                    authIfBackend(resolvedUrl)
                                    header("Range", "bytes=0-0")
                            }.status.value
                    return classify(getCode)
            }
            classify(headCode)
    } catch (_: Exception) {
            RemoteFileState.UNKNOWN
    }
}

internal suspend fun UserSyncRepository.downloadRemoteFile(url: String, targetPath: String): Boolean {
    return try {
            platformFiles().mkdirs(targetPath.substringBeforeLast('/'))
            var resolvedUrl = url
            if (resolvedUrl.startsWith(pocketBaseUrl)) {
                    val fileToken = getProtectedFileToken()
                    if (!fileToken.isNullOrBlank()) {
                            resolvedUrl = withFileToken(resolvedUrl, fileToken)
                    }
            }
            val response = ktorClient.get(resolvedUrl) { authIfBackend(resolvedUrl) }
            if (!response.status.isSuccess()) {
                    logger.w(
                            "UserSyncRepository",
                            "downloadRemoteFile failed code=${response.status.value} url=$resolvedUrl"
                    )
                    return false
            }

            val bytes = response.bodyAsBytes()
            val tmpPath = "$targetPath.part"
            if (!platformFiles().writeFile(tmpPath, bytes)) {
                    return false
            }

            if (platformFiles().exists(targetPath)) {
                    platformFiles().delete(targetPath)
            }
            if (!platformFiles().rename(tmpPath, targetPath)) {
                    platformFiles().writeFile(targetPath, bytes)
                    platformFiles().delete(tmpPath)
            }
            platformFiles().exists(targetPath) &&
                    platformFiles().fileLength(targetPath) > 0L
    } catch (e: Exception) {
            logger.e("UserSyncRepository", "downloadRemoteFile failed for $url", e)
            reporter.report("UserSyncRepository.downloadRemoteFile",
                    "Failed to download remote file: $url",
                    e
            )
            false
    }
}

/**
 * Bug 5 fix: Validate that a file is a non-corrupt EPUB (ZIP) by checking the
 * ZIP magic bytes PK\x03\x04 at the start of the file.
 */
internal fun UserSyncRepository.isValidEpubFile(path: String): Boolean {
    if (!platformFiles().exists(path) || platformFiles().fileLength(path) < 4L) {
            return false
    }
    return try {
            val magic = platformFiles().readFilePrefix(path, 4) ?: return false
            magic.size == 4 &&
                    magic[0] == 0x50.toByte() && // 'P'
                    magic[1] == 0x4B.toByte() && // 'K'
                    magic[2] == 0x03.toByte() &&
                    magic[3] == 0x04.toByte()
    } catch (_: Exception) {
            false
    }
}
