package my.hinoki.booxreader.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {
    @Query("SELECT * FROM ai_profiles ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<AiProfileEntity>>

    @Query("SELECT * FROM ai_profiles WHERE id = :id")
    suspend fun getById(id: Long): AiProfileEntity?
    
    @Query("SELECT * FROM ai_profiles WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): AiProfileEntity?
    
    @Query("SELECT * FROM ai_profiles WHERE remoteId IS NULL OR remoteId = ''")
    suspend fun getLocalOnly(): List<AiProfileEntity>
    
    @Query("SELECT * FROM ai_profiles WHERE isSynced = 0")
    suspend fun getPendingSync(): List<AiProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: AiProfileEntity): Long
    
    @Update
    suspend fun update(profile: AiProfileEntity)

    @Delete
    suspend fun delete(profile: AiProfileEntity)
    
    @Query("DELETE FROM ai_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
