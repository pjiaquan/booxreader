package my.hinoki.booxreader.data.repo

import android.content.Context
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.UserEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AuthRepository(
    private val context: Context,
    private val tokenManager: TokenManager
) {
    private val userDao = AppDatabase.get(context).userDao()

    // Mock Login
    suspend fun login(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        delay(1000) // Simulate network
        if (password == "password") { // Mock check
            val user = UserEntity(
                userId = "user_123",
                email = email,
                displayName = "Test User",
                avatarUrl = null
            )
            userDao.clearAllUsers()
            userDao.insertUser(user)
            tokenManager.saveAccessToken("mock_access_token")
            tokenManager.saveRefreshToken("mock_refresh_token")
            Result.success(user)
        } else {
            Result.failure(Exception("Invalid credentials"))
        }
    }

    // Mock Register
    suspend fun register(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        delay(1000)
        val user = UserEntity(
            userId = "user_${System.currentTimeMillis()}",
            email = email,
            displayName = email.substringBefore("@"),
            avatarUrl = null
        )
        userDao.clearAllUsers()
        userDao.insertUser(user)
        tokenManager.saveAccessToken("mock_access_token")
        tokenManager.saveRefreshToken("mock_refresh_token")
        Result.success(user)
    }

    // Mock Google Login (Simulate backend verification)
    suspend fun googleLogin(idToken: String, email: String?, name: String?): Result<UserEntity> = withContext(Dispatchers.IO) {
        delay(1000)
        // Verify idToken with backend...
        val user = UserEntity(
            userId = "google_user_${System.currentTimeMillis()}",
            email = email ?: "google@example.com", 
            displayName = name ?: "Google User",
            avatarUrl = null
        )
        userDao.clearAllUsers()
        userDao.insertUser(user)
        tokenManager.saveAccessToken("mock_google_access_token")
        tokenManager.saveRefreshToken("mock_refresh_token")
        Result.success(user)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        userDao.clearAllUsers()
        tokenManager.clearTokens()
    }

    fun getCurrentUser() = userDao.getUser()
}

