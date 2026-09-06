package my.hinoki.booxreader.data.db

import androidx.room.*

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getAll(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AnnotationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(entities: List<AnnotationEntity>)

    @Update
    suspend fun update(entity: AnnotationEntity)

    @Delete
    suspend fun delete(entity: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM annotations WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): AnnotationEntity?

    @Query("SELECT * FROM annotations WHERE remoteId IN (:remoteIds)")
    suspend fun getByRemoteIds(remoteIds: List<String>): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE remoteId IS NULL")
    suspend fun getLocalOnly(): List<AnnotationEntity>

    @Query("UPDATE annotations SET bookId = :newBookId WHERE bookId = :oldBookId")
    suspend fun migrateBookId(oldBookId: String, newBookId: String)

    @Query("DELETE FROM annotations")
    suspend fun deleteAll()
}
