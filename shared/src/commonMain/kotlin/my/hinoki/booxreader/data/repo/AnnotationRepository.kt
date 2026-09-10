package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AnnotationStyle
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.platform.currentEpochMillis

/**
 * 畫線 / 註記的本地存取 + 雲端同步。
 *
 * `syncRepo` 一直存在於建構子，但先前的實作完全沒有使用它，所以畫線只留在單一裝置。
 * 現在：
 * - 新增 / 更新後立即推送（與 AiNoteRepository 的行為一致）
 * - 刪除時先刪遠端再刪本機
 * - [sync] 先推本機未同步項目，再拉遠端
 * `syncRepo` 為 null（例如離線或未登入的來源）時，一切維持本地行為。
 */
class AnnotationRepository(
        private val syncRepo: UserSyncRepository? = null
) {
    private val db = AppDatabase.get()
    private val dao = db.annotationDao()

    suspend fun getAnnotations(bookId: String): List<AnnotationEntity> =
            withContext(Dispatchers.Default) {
                dao.getAll(bookId)
            }

    suspend fun getById(id: Long): AnnotationEntity? =
            withContext(Dispatchers.Default) {
                dao.getById(id)
            }

    suspend fun addAnnotation(
            bookId: String,
            locatorJson: String,
            selectedText: String,
            note: String? = null,
            style: AnnotationStyle = AnnotationStyle.UNDERLINE
    ): AnnotationEntity = withContext(Dispatchers.Default) {
        val now = currentEpochMillis()
        val entity =
                AnnotationEntity(
                        bookId = bookId,
                        locatorJson = locatorJson,
                        selectedText = selectedText,
                        note = note?.takeIf { it.isNotBlank() },
                        style = style.name,
                        createdAt = now,
                        updatedAt = now,
                        isSynced = false
                )
        val id = dao.insert(entity)
        pushIfPossible(entity.copy(id = id))
    }

    suspend fun updateAnnotation(entity: AnnotationEntity) =
            withContext(Dispatchers.Default) {
                val updated = entity.copy(updatedAt = currentEpochMillis(), isSynced = false)
                dao.update(updated)
                pushIfPossible(updated)
            }

    suspend fun updateNote(id: Long, note: String?) =
            withContext(Dispatchers.Default) {
                val existing = dao.getById(id) ?: return@withContext
                val updated =
                        existing.copy(
                                note = note?.takeIf { it.isNotBlank() },
                                updatedAt = currentEpochMillis(),
                                isSynced = false
                        )
                dao.update(updated)
                pushIfPossible(updated)
            }

    suspend fun updateStyle(id: Long, style: AnnotationStyle) =
            withContext(Dispatchers.Default) {
                val existing = dao.getById(id) ?: return@withContext
                val updated =
                        existing.copy(
                                style = style.name,
                                updatedAt = currentEpochMillis(),
                                isSynced = false
                        )
                dao.update(updated)
                pushIfPossible(updated)
            }

    suspend fun deleteAnnotation(id: Long) =
            withContext(Dispatchers.Default) {
                deleteRemoteIfPresent(dao.getById(id)?.remoteId)
                dao.deleteById(id)
            }

    suspend fun deleteAnnotation(entity: AnnotationEntity) =
            withContext(Dispatchers.Default) {
                deleteRemoteIfPresent(entity.remoteId)
                dao.delete(entity)
            }

    /**
     * 同步畫線：先推送本機尚未同步的項目，再拉取遠端。
     * 回傳這次寫入 / 更新的筆數；沒有設定 [syncRepo] 時回傳 0。
     */
    suspend fun sync(bookId: String? = null): Int =
            withContext(Dispatchers.Default) {
                val repo = syncRepo ?: return@withContext 0

                var pushed = 0
                dao.getPendingSync().forEach { pending ->
                    if (pushIfPossible(pending).isSynced) {
                        pushed++
                    }
                }

                pushed + repo.pullAnnotations(bookId)
            }

    /**
     * 推送單一畫線並在本機記錄 remoteId / isSynced。
     * 推送失敗時原樣回傳（isSynced 保持 false），下次 [sync] 會重試。
     */
    private suspend fun pushIfPossible(entity: AnnotationEntity): AnnotationEntity {
        val repo = syncRepo ?: return entity
        val synced = repo.pushAnnotation(entity) ?: return entity
        dao.update(synced)
        return synced
    }

    private suspend fun deleteRemoteIfPresent(remoteId: String?) {
        val repo = syncRepo ?: return
        if (!remoteId.isNullOrBlank()) {
            repo.deleteAnnotation(remoteId)
        }
    }
}
