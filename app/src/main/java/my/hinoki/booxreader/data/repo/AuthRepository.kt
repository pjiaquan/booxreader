package my.hinoki.booxreader.data.repo

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.UserEntity
import my.hinoki.booxreader.data.prefs.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val context: Context,
    private val tokenManager: TokenManager
) {
    private val userDao = AppDatabase.get(context).userDao()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    suspend fun login(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: error(context.getString(my.hinoki.booxreader.R.string.error_user_not_found))
            if (!firebaseUser.isEmailVerified) {
                firebaseUser.sendEmailVerification().await()
                auth.signOut()
                userDao.clearAllUsers()
                tokenManager.clearTokens()
                error(context.getString(my.hinoki.booxreader.R.string.error_email_not_verified))
            }
            cacheUser(firebaseUser)
        }
    }

    suspend fun register(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: error(context.getString(my.hinoki.booxreader.R.string.error_user_not_found))
            firebaseUser.sendEmailVerification().await()
            auth.signOut()
            userDao.clearAllUsers()
            tokenManager.clearTokens()
            // Return lightweight entity for UI messaging; not cached until verification + login.
            UserEntity(
                userId = firebaseUser.uid,
                email = firebaseUser.email ?: email,
                displayName = firebaseUser.displayName ?: email.substringBefore("@"),
                avatarUrl = firebaseUser.photoUrl?.toString()
            )
        }
    }

    suspend fun googleLogin(idToken: String, email: String?, name: String?): Result<UserEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: error(context.getString(my.hinoki.booxreader.R.string.error_user_not_found))
            cacheUser(firebaseUser, fallbackEmail = email, fallbackName = name)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        auth.signOut()
        userDao.clearAllUsers()
        tokenManager.clearTokens()
    }

    suspend fun resendVerification(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: error(context.getString(my.hinoki.booxreader.R.string.error_user_not_found))
            firebaseUser.sendEmailVerification().await()
            auth.signOut()
            userDao.clearAllUsers()
            tokenManager.clearTokens()
        }
    }

    fun getCurrentUser() = userDao.getUser()

    private suspend fun cacheUser(
        user: FirebaseUser,
        fallbackEmail: String? = null,
        fallbackName: String? = null
    ): UserEntity {
        val email = user.email ?: fallbackEmail ?: error("Email not available")
        val displayName = user.displayName ?: fallbackName ?: email.substringBefore("@")
        val idToken = user.getIdToken(true).await().token ?: error("Unable to fetch ID token")

        val entity = UserEntity(
            userId = user.uid,
            email = email,
            displayName = displayName,
            avatarUrl = user.photoUrl?.toString()
        )

        userDao.clearAllUsers()
        userDao.insertUser(entity)

        tokenManager.clearTokens()
        tokenManager.saveAccessToken(idToken)

        return entity
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
