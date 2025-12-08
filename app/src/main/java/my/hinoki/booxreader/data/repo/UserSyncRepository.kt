package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
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
        val ref = userDoc() ?: return@withContext
        val uploadInfo = if (uploadFile) uploadBookFileIfNeeded(book) else null
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

        runCatching {
            ref.collection(COL_BOOKS)
                .document(book.bookId)
                .set(payload, SetOptions.merge())
                .await()
        }
    }

    suspend fun pullBooks(): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val dao = db.bookDao()

        val snapshot = runCatching {
            ref.collection(COL_BOOKS).get().await()
        }.getOrElse { return@withContext 0 }

        var updatedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteBook::class.java) ?: return@forEach
            if (remote.bookId.isBlank()) return@forEach

            val existing = dao.getByIds(listOf(remote.bookId)).firstOrNull()
            val remoteLastOpened = if (remote.lastOpenedAt > 0) remote.lastOpenedAt else remote.updatedAt
            val remoteUpdatedAt = maxOf(remote.updatedAt, remoteLastOpened)

            val entity = BookEntity(
                bookId = remote.bookId,
                title = remote.title,
                fileUri = if (remote.fileUri.isNotBlank()) remote.fileUri else remote.bookId,
                lastLocatorJson = remote.lastLocatorJson,
                lastOpenedAt = remoteLastOpened
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
            } else if (remoteUpdatedAt > existing.lastOpenedAt) {
                dao.update(entity.copy(bookId = existing.bookId))
                updatedCount++
            }
        }

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
