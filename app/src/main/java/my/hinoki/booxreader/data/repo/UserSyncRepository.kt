package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.db.BookmarkEntity
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
    private val prefs: SharedPreferences =
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val db = AppDatabase.get(context)
    private val io = Dispatchers.IO

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
        private const val COL_AI_NOTES = "ai_notes"
        private const val COL_BOOKMARKS = "bookmarks"
    }
}

data class RemoteProgress(
    val bookId: String = "",
    val locatorJson: String = "",
    val bookTitle: String? = null,
    val updatedAt: Long = 0L
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
