package my.hinoki.booxreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val userId: String, // From backend user ID
    val email: String,
    val displayName: String?,
    val avatarUrl: String?
)
