package my.hinoki.booxreader.data.remote

import android.util.Log
import my.hinoki.booxreader.data.prefs.TokenManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Token authenticator for PocketBase. NOTE: PocketBase tokens are long-lived and don't use the same
 * refresh mechanism as Supabase. This is a stub implementation for now - token refresh may need to
 * be implemented differently.
 */
class TokenAuthenticator(private val tokenManager: TokenManager) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Skip auth for requests explicitly tagged
        if (response.request.tag(String::class.java) == "SKIP_AUTH") {
            return null
        }

        // PocketBase tokens are generally long-lived
        // If we get a 401, there's not much we can do except re-login
        // For now, just log and return null
        Log.w("TokenAuthenticator", "Authentication failed - user may need to re-login")

        return null
    }
}
