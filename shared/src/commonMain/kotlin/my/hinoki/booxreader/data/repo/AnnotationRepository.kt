package my.hinoki.booxreader.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.AnnotationStyle
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.platform.currentEpochMillis

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
        val entity = AnnotationEntity(
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
        entity.copy(id = id)
    }

    suspend fun updateAnnotation(entity: AnnotationEntity) =
        withContext(Dispatchers.Default) {
            val updated = entity.copy(updatedAt = currentEpochMillis(), isSynced = false)
            dao.update(updated)
        }

    suspend fun updateNote(id: Long, note: String?) =
        withContext(Dispatchers.Default) {
            val existing = dao.getById(id) ?: return@withContext
            val updated = existing.copy(
                note = note?.takeIf { it.isNotBlank() },
                updatedAt = currentEpochMillis(),
                isSynced = false
            )
            dao.update(updated)
        }

    suspend fun updateStyle(id: Long, style: AnnotationStyle) =
        withContext(Dispatchers.Default) {
            val existing = dao.getById(id) ?: return@withContext
            val updated = existing.copy(
                style = style.name,
                updatedAt = currentEpochMillis(),
                isSynced = false
            )
            dao.update(updated)
        }

    suspend fun deleteAnnotation(id: Long) =
        withContext(Dispatchers.Default) {
            dao.deleteById(id)
        }

    suspend fun deleteAnnotation(entity: AnnotationEntity) =
        withContext(Dispatchers.Default) {
            dao.delete(entity)
        }
}
