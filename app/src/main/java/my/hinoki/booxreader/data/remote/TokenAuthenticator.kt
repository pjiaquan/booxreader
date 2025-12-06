package my.hinoki.booxreader.data.remote

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(private val tokenManager: TokenManager) : Authenticator {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    override fun authenticate(route: Route?, response: Response): Request? {
        synchronized(this) {
            val currentAccessToken = tokenManager.getAccessToken()
            val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

            if (currentAccessToken != null && currentAccessToken != requestToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentAccessToken")
                    .build()
            }

            val firebaseUser = auth.currentUser ?: return null
            val refreshedToken = try {
                Tasks.await(firebaseUser.getIdToken(true))?.token
            } catch (_: Exception) {
                null
            }

            return if (!refreshedToken.isNullOrBlank()) {
                tokenManager.saveAccessToken(refreshedToken)
                response.request.newBuilder()
                    .header("Authorization", "Bearer $refreshedToken")
                    .build()
            } else {
                null
            }
        }
    }
}
