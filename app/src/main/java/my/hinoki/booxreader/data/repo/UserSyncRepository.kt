package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.db.BookmarkEntity
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Syncs user-specific data (settings, reading progress, AI notes) to Firebase Firestore
 * so it can roam across devices. Falls back to no-ops when the user is not signed in.
 */
class UserSyncRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
 ) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val db = AppDatabase.get(context)
    private val io = Dispatchers.IO
    private val storage = FirebaseStorage.getInstance()

    // --- Public API ---

    suspend fun pullSettingsIfNewer(): ReaderSettings? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val snapshot = runCatching {
            ref.collection(COL_SETTINGS).document(DOC_READER_SETTINGS).get().await()
        }.getOrElse { return@withContext null }

        if (!snapshot.exists()) return@withContext null
        val remote = snapshot.toObject(ReaderSettings::class.java) ?: return@withContext null
        val local = ReaderSettings.fromPrefs(prefs)
        return@withContext if (remote.updatedAt > local.updatedAt) {
            remote.saveTo(prefs)
            remote
        } else {
            null
        }
    }

    suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromPrefs(prefs)) {
        val ref = userDoc() ?: return
        val payload = settings.copy(updatedAt = System.currentTimeMillis())
        withContext(io) {
            runCatching {
                ref.collection(COL_SETTINGS)
                    .document(DOC_READER_SETTINGS)
                    .set(payload, SetOptions.merge())
                    .await()
            }
        }
    }

    fun getCachedProgress(bookId: String): String? {
        return prefs.getString(progressKey(bookId), null)
    }

    fun cacheProgress(bookId: String, locatorJson: String, updatedAt: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(progressKey(bookId), locatorJson)
            .putLong(progressTimestampKey(bookId), updatedAt)
            .apply()
    }

    suspend fun pullProgress(bookId: String) = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val snapshot = runCatching {
            ref.collection(COL_PROGRESS).document(bookId).get().await()
        }.getOrElse { return@withContext null }

        if (!snapshot.exists()) return@withContext null
        val remote = snapshot.toObject(RemoteProgress::class.java) ?: return@withContext null
        if (remote.locatorJson.isBlank()) return@withContext null

        val localTs = prefs.getLong(progressTimestampKey(bookId), 0)
        if (remote.updatedAt > localTs) {
            cacheProgress(bookId, remote.locatorJson, remote.updatedAt)
            runCatching {
                db.bookDao().updateProgress(bookId, remote.locatorJson, remote.updatedAt)
            }
        }
        remote.locatorJson
    }

    suspend fun pushProgress(
        bookId: String,
        locatorJson: String,
        bookTitle: String? = null
    ) = withContext(io) {
        val ref = userDoc() ?: return@withContext
        val now = System.currentTimeMillis()
        val payload = RemoteProgress(
            bookId = bookId,
            locatorJson = locatorJson,
            bookTitle = bookTitle,
            updatedAt = now
        )
        runCatching {
            ref.collection(COL_PROGRESS)
                .document(bookId)
                .set(payload, SetOptions.merge())
                .await()
        }
        cacheProgress(bookId, locatorJson, now)
    }

    suspend fun pushBook(book: BookEntity, uploadFile: Boolean = false) = withContext(io) {
        android.util.Log.d("UserSyncRepository", "開始上傳書籍到Firestore: ${book.title} (${book.bookId})")

        val ref = userDoc()
        if (ref == null) {
            android.util.Log.w("UserSyncRepository", "userDoc() 返回 null，無法上傳書籍")
            return@withContext
        }

        android.util.Log.d("UserSyncRepository", "用戶文檔: ${ref.path}")

        val uploadInfo = if (uploadFile) {
            android.util.Log.d("UserSyncRepository", "嘗試上傳EPUB文件到Storage")
            uploadBookFileIfNeeded(book)
        } else {
            android.util.Log.d("UserSyncRepository", "跳過文件上傳，只上傳元數據")
            null
        }

        val storagePath = uploadInfo?.storagePath ?: bookStorageRef(book.bookId)?.path
        val updatedAt = if (book.lastOpenedAt > 0) book.lastOpenedAt else System.currentTimeMillis()
        val payload = RemoteBook(
            bookId = book.bookId,
            title = book.title,
            fileUri = book.fileUri,
            lastLocatorJson = book.lastLocatorJson,
            lastOpenedAt = book.lastOpenedAt,
            updatedAt = updatedAt,
            storagePath = storagePath,
            fileSize = uploadInfo?.size ?: 0L,
            checksumSha256 = uploadInfo?.checksum
        )

        android.util.Log.d("UserSyncRepository", "上傳書籍數據: $payload")

        runCatching {
            ref.collection(COL_BOOKS)
                .document(book.bookId)
                .set(payload, SetOptions.merge())
                .await()
        }.onSuccess {
            android.util.Log.d("UserSyncRepository", "書籍上傳成功: ${book.title}")
        }.onFailure {
            android.util.Log.e("UserSyncRepository", "書籍上傳失敗: ${book.title}", it)
        }
    }

    suspend fun pullBooks(): Int = withContext(io) {
        val ref = userDoc()
        if (ref == null) {
            android.util.Log.w("UserSyncRepository", "userDoc() 返回 null，用戶可能未登入")
            return@withContext 0
        }

        android.util.Log.d("UserSyncRepository", "開始從Firebase拉取書籍數據，用戶文檔: ${ref.path}")
        val dao = db.bookDao()

        val snapshot = runCatching {
            ref.collection(COL_BOOKS).get().await()
        }.getOrElse {
            android.util.Log.e("UserSyncRepository", "獲取書籍數據失敗", it)
            return@withContext 0
        }

        android.util.Log.d("UserSyncRepository", "從Firebase獲取到 ${snapshot.documents.size} 本書籍")

        var updatedCount = 0
        var downloadedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteBook::class.java) ?: return@forEach
            if (remote.bookId.isBlank()) return@forEach

            android.util.Log.d("UserSyncRepository", "處理書籍: ${remote.title} (ID: ${remote.bookId})")
            android.util.Log.d("UserSyncRepository", "書籍數據: storagePath=${remote.storagePath}, checksum=${remote.checksumSha256}, fileUri=${remote.fileUri}")

            val existing = dao.getByIds(listOf(remote.bookId)).firstOrNull()
            val remoteLastOpened = if (remote.lastOpenedAt > 0) remote.lastOpenedAt else remote.updatedAt
            val remoteUpdatedAt = maxOf(remote.updatedAt, remoteLastOpened)

            // Try to download EPUB file if available in storage
            var fileUri = remote.fileUri
            var fileDownloaded = false

            // 總是嘗試下載書籍，即使沒有storagePath或checksum
            // 使用bookId來構建默認的storage路徑
            android.util.Log.d("UserSyncRepository", "嘗試下載書籍: ${remote.title} (${remote.bookId})")
            val localUri = ensureBookFileAvailable(remote.bookId, remote.storagePath)
            if (localUri != null) {
                fileUri = localUri.toString()
                fileDownloaded = true
                downloadedCount++
                android.util.Log.d("UserSyncRepository", "書籍下載成功: ${remote.title}")
            } else {
                android.util.Log.w("UserSyncRepository", "書籍下載失敗: ${remote.title}")
                // 如果下載失敗，但remote.fileUri是有效的，仍然使用它
                if (remote.fileUri.isNotBlank() && remote.fileUri.startsWith("content://")) {
                    android.util.Log.d("UserSyncRepository", "使用原始fileUri: ${remote.fileUri}")
                }
            }

            val entity = BookEntity(
                bookId = remote.bookId,
                title = remote.title,
                fileUri = if (fileUri.isNotBlank()) fileUri else remote.bookId,
                lastLocatorJson = remote.lastLocatorJson,
                lastOpenedAt = remoteLastOpened
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
                android.util.Log.d("UserSyncRepository", "新增書籍到資料庫: ${remote.title}")
            } else if (remoteUpdatedAt > existing.lastOpenedAt) {
                dao.update(entity.copy(bookId = existing.bookId))
                updatedCount++
                android.util.Log.d("UserSyncRepository", "更新書籍資料: ${remote.title}")
            }
        }

        android.util.Log.d("UserSyncRepository", "同步書籍完成: 更新${updatedCount}筆, 下載${downloadedCount}個檔案")
        updatedCount
    }

    private suspend fun uploadBookFileIfNeeded(book: BookEntity): UploadedBookInfo? = withContext(io) {
        val storageRef = bookStorageRef(book.bookId) ?: return@withContext null
        val uri = Uri.parse(book.fileUri)
        val localMeta = readLocalFileMeta(uri) ?: return@withContext null

        val remoteMeta = runCatching { storageRef.metadata.await() }.getOrNull()
        val remoteChecksum = remoteMeta?.getCustomMetadata("checksum")
        if (remoteMeta != null && remoteChecksum != null && remoteChecksum == localMeta.checksum) {
            return@withContext UploadedBookInfo(storageRef.path, localMeta.size, localMeta.checksum)
        }

        val metadata = StorageMetadata.Builder()
            .setContentType("application/epub+zip")
            .setCustomMetadata("bookId", book.bookId)
            .setCustomMetadata("checksum", localMeta.checksum ?: "")
            .build()

        val resolver = appContext.contentResolver
        resolver.openInputStream(uri)?.use { stream ->
            runCatching { storageRef.putStream(stream, metadata).await() }
                .getOrElse { return@withContext null }
        } ?: return@withContext null

        UploadedBookInfo(storageRef.path, localMeta.size, localMeta.checksum)
    }

    private fun bookStorageRef(bookId: String) =
        auth.currentUser?.let { user ->
            if (bookId.isBlank()) return@let null
            val safeId = Base64.encodeToString(bookId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            storage.reference.child("users/${user.uid}/books/$safeId.epub")
        }

    private fun readLocalFileMeta(uri: Uri): LocalFileMeta? {
        val resolver = appContext.contentResolver
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
                size += read
            }
        } ?: return null
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return LocalFileMeta(size = size, checksum = checksum)
    }

    /**
     * Download EPUB file from Firebase Storage to local storage
     */
    suspend fun downloadBookFile(bookId: String, storagePath: String? = null): Uri? = withContext(io) {
        val user = auth.currentUser ?: return@withContext null
        val storageRef = if (!storagePath.isNullOrBlank()) {
            storage.reference.child(storagePath)
        } else {
            bookStorageRef(bookId)
        } ?: return@withContext null

        android.util.Log.d("UserSyncRepository", "開始下載書籍檔案: bookId=$bookId, storagePath=$storagePath")

        try {
            // Use app-specific files directory for permanent storage
            val filesDir = appContext.getExternalFilesDir("books") ?: appContext.filesDir
            val booksDir = File(filesDir, "downloaded")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            val fileName = "book_${bookId}_${System.currentTimeMillis()}.epub"
            val localFile = File(booksDir, fileName)

            android.util.Log.d("UserSyncRepository", "下載路徑: ${localFile.absolutePath}")

            // Download file
            storageRef.getFile(localFile).await()

            // Verify download
            if (localFile.exists() && localFile.length() > 0) {
                val fileSize = localFile.length()
                android.util.Log.d("UserSyncRepository", "書籍下載成功: ${localFile.name}, 大小: $fileSize bytes")

                // Create content URI using FileProvider for security
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Use FileProvider for Android 7+
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        localFile
                    )
                } else {
                    Uri.fromFile(localFile)
                }

                // Also store in cache for quick access
                val cacheDir = appContext.cacheDir
                val cacheFile = File(cacheDir, "book_${bookId}.epub")
                localFile.copyTo(cacheFile, overwrite = true)

                android.util.Log.d("UserSyncRepository", "書籍已儲存到快取: ${cacheFile.absolutePath}")
                return@withContext uri
            } else {
                android.util.Log.w("UserSyncRepository", "下載的檔案不存在或為空")
            }
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "下載書籍失敗", e)
            e.printStackTrace()
        }

        return@withContext null
    }

    /**
     * Check if book file exists locally, download if missing
     */
    suspend fun ensureBookFileAvailable(bookId: String, storagePath: String? = null, originalUri: String? = null): Uri? = withContext(io) {
        android.util.Log.d("UserSyncRepository", "確保書籍檔案可用: bookId=$bookId, storagePath=$storagePath, originalUri=$originalUri")

        // First, check if the original URI is still accessible
        if (!originalUri.isNullOrBlank()) {
            try {
                val uri = Uri.parse(originalUri)
                val resolver = appContext.contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    android.util.Log.d("UserSyncRepository", "原始URI仍然可訪問: $originalUri")
                    return@withContext uri
                }
            } catch (e: Exception) {
                android.util.Log.d("UserSyncRepository", "原始URI無法訪問: ${e.message}")
            }
        }

        // Check multiple possible locations for the book file
        val possibleLocations = mutableListOf<File>()

        // 1. Check cache directory (quick access)
        val cacheDir = appContext.cacheDir
        val cacheFile = File(cacheDir, "book_${bookId}.epub")
        possibleLocations.add(cacheFile)

        // 2. Check downloaded books directory (permanent storage)
        val filesDir = appContext.getExternalFilesDir("books") ?: appContext.filesDir
        val booksDir = File(filesDir, "downloaded")
        if (booksDir.exists()) {
            booksDir.listFiles()?.forEach { file ->
                if (file.name.contains(bookId) && file.name.endsWith(".epub")) {
                    possibleLocations.add(file)
                }
            }
        }

        // 3. Check for any file with bookId in name
        val externalFilesDir = appContext.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            externalFilesDir.walk().maxDepth(3).forEach { file ->
                if (file.isFile && file.name.contains(bookId) && file.name.endsWith(".epub")) {
                    possibleLocations.add(file)
                }
            }
        }

        // Check all possible locations
        for (file in possibleLocations) {
            if (file.exists() && file.length() > 0) {
                android.util.Log.d("UserSyncRepository", "找到書籍檔案: ${file.absolutePath}, 大小: ${file.length()} bytes")

                // Create URI using FileProvider for security
                return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                } else {
                    Uri.fromFile(file)
                }
            }
        }

        android.util.Log.d("UserSyncRepository", "書籍檔案不存在於本地，開始從雲端下載...")

        // Download from storage
        val result = downloadBookFile(bookId, storagePath)
        if (result != null) {
            android.util.Log.d("UserSyncRepository", "書籍下載成功: $result")
        } else {
            android.util.Log.w("UserSyncRepository", "書籍下載失敗")
        }
        return@withContext result
    }

    suspend fun pushNote(note: AiNoteEntity): AiNoteEntity? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val now = System.currentTimeMillis()
        val remoteId = note.remoteId ?: ref.collection(COL_AI_NOTES).document().id
        val payload = RemoteAiNote(
            remoteId = remoteId,
            bookId = note.bookId,
            bookTitle = note.bookTitle,
            originalText = note.originalText,
            aiResponse = note.aiResponse,
            locatorJson = note.locatorJson,
            createdAt = note.createdAt,
            updatedAt = now
        )

        runCatching {
            ref.collection(COL_AI_NOTES)
                .document(remoteId)
                .set(payload, SetOptions.merge())
                .await()
        }

        val updated = note.copy(remoteId = remoteId, updatedAt = now)
        val dao = db.aiNoteDao()
        if (updated.id == 0L) {
            val newId = dao.insert(updated)
            updated.copy(id = newId)
        } else {
            dao.update(updated)
            updated
        }
    }

    suspend fun pullNotes(): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val dao = db.aiNoteDao()

        // First push any local-only notes so they get a remoteId
        runCatching {
            dao.getLocalOnly().forEach { local ->
                pushNote(local)
            }
        }

        val snapshot = runCatching {
            ref.collection(COL_AI_NOTES).get().await()
        }.getOrElse { return@withContext 0 }

        var updatedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteAiNote::class.java) ?: return@forEach
            if (remote.remoteId.isBlank()) return@forEach

            val existing = dao.getByRemoteId(remote.remoteId)
            val entity = AiNoteEntity(
                id = existing?.id ?: 0,
                remoteId = remote.remoteId,
                bookId = remote.bookId,
                bookTitle = remote.bookTitle,
                originalText = remote.originalText,
                aiResponse = remote.aiResponse,
                locatorJson = remote.locatorJson,
                createdAt = if (remote.createdAt > 0) remote.createdAt else (existing?.createdAt ?: System.currentTimeMillis()),
                updatedAt = remote.updatedAt
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
            } else if (remote.updatedAt > existing.updatedAt) {
                dao.update(entity)
                updatedCount++
            }
        }

        updatedCount
    }

    suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val now = System.currentTimeMillis()
        val remoteId = entity.remoteId ?: ref.collection(COL_BOOKMARKS).document().id
        val payload = RemoteBookmark(
            remoteId = remoteId,
            bookId = entity.bookId,
            locatorJson = entity.locatorJson,
            createdAt = entity.createdAt,
            updatedAt = now
        )

        runCatching {
            ref.collection(COL_BOOKMARKS)
                .document(remoteId)
                .set(payload, SetOptions.merge())
                .await()
        }

        val updated = entity.copy(remoteId = remoteId, isSynced = true, updatedAt = now)
        val dao = db.bookmarkDao()
        if (updated.id == 0L) {
            val newId = dao.insert(updated)
            updated.copy(id = newId)
        } else {
            dao.update(updated)
            updated
        }
    }

    suspend fun pullBookmarks(bookId: String? = null): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val dao = db.bookmarkDao()

        // Push local-only first to get remoteId
        runCatching {
            dao.getLocalOnly().forEach { local ->
                pushBookmark(local)
            }
        }

        val query = if (bookId != null) {
            ref.collection(COL_BOOKMARKS).whereEqualTo("bookId", bookId)
        } else {
            ref.collection(COL_BOOKMARKS)
        }

        val snapshot = runCatching { query.get().await() }.getOrElse { return@withContext 0 }
        var updatedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteBookmark::class.java) ?: return@forEach
            if (remote.remoteId.isBlank()) return@forEach

            val existing = dao.getByRemoteId(remote.remoteId)
            val entity = BookmarkEntity(
                id = existing?.id ?: 0,
                remoteId = remote.remoteId,
                bookId = remote.bookId,
                locatorJson = remote.locatorJson,
                createdAt = if (remote.createdAt > 0) remote.createdAt else (existing?.createdAt ?: System.currentTimeMillis()),
                isSynced = true,
                updatedAt = remote.updatedAt
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
            } else if (remote.updatedAt > existing.updatedAt) {
                dao.update(entity)
                updatedCount++
            }
        }

        updatedCount
    }

    /**
     * Fetch all progress documents for the signed-in user and merge newer ones into local cache/DB.
     */
    suspend fun pullAllProgress(): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val snapshot = runCatching {
            ref.collection(COL_PROGRESS).get().await()
        }.getOrElse { return@withContext 0 }

        var updated = 0
        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteProgress::class.java) ?: return@forEach
            if (remote.bookId.isBlank() || remote.locatorJson.isBlank()) return@forEach

            val localTs = prefs.getLong(progressTimestampKey(remote.bookId), 0)
            if (remote.updatedAt > localTs) {
                cacheProgress(remote.bookId, remote.locatorJson, remote.updatedAt)
                runCatching {
                    db.bookDao().updateProgress(remote.bookId, remote.locatorJson, remote.updatedAt)
                }
                updated++
            }
        }
        updated
    }

    // --- Helpers ---

    private fun userDoc() =
        auth.currentUser?.let { firestore.collection(COL_USERS).document(it.uid) }

    private fun progressKey(bookId: String) = "progress_$bookId"
    private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

    companion object {
        private const val COL_USERS = "users"
        private const val COL_SETTINGS = "settings"
        private const val DOC_READER_SETTINGS = "reader"
        private const val COL_PROGRESS = "progress"
        private const val COL_BOOKS = "books"
        private const val COL_AI_NOTES = "ai_notes"
        private const val COL_BOOKMARKS = "bookmarks"
    }
}

private data class LocalFileMeta(
    val size: Long,
    val checksum: String?
)

private data class UploadedBookInfo(
    val storagePath: String,
    val size: Long,
    val checksum: String?
)

data class RemoteProgress(
    val bookId: String = "",
    val locatorJson: String = "",
    val bookTitle: String? = null,
    val updatedAt: Long = 0L
)

data class RemoteBook(
    val bookId: String = "",
    val title: String? = null,
    val fileUri: String = "",
    val lastLocatorJson: String? = null,
    val lastOpenedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val storagePath: String? = null,
    val fileSize: Long = 0L,
    val checksumSha256: String? = null
)

data class RemoteAiNote(
    val remoteId: String = "",
    val bookId: String? = null,
    val bookTitle: String? = null,
    val originalText: String = "",
    val aiResponse: String = "",
    val locatorJson: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class RemoteBookmark(
    val remoteId: String = "",
    val bookId: String = "",
    val locatorJson: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

// Local copy to avoid adding new coroutine-play-services dependency
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
