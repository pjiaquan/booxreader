package my.hinoki.booxreader.data.auth

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException

class GoogleSignInHelper(private val activity: Activity) {

    private val webClientId: String? by lazy {
        val resId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resId != 0) activity.getString(resId) else null
    }

    private val googleSignInClient by lazy {
        webClientId?.let {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(it)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(activity, options)
        }
    }

    fun signIn(requestCode: Int) {
        val client = googleSignInClient
        if (client == null) {
            Toast.makeText(activity, "Google Sign-In is not configured.", Toast.LENGTH_LONG).show()
            return
        }
        activity.startActivityForResult(client.signInIntent, requestCode)
    }

    fun handleActivityResult(data: Intent?, onResult: (idToken: String?, email: String?, name: String?) -> Unit) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            onResult(account.idToken, account.email, account.displayName)
        } catch (e: ApiException) {
            Toast.makeText(activity, "Google Sign-In failed: ${e.statusCode}", Toast.LENGTH_LONG).show()
            onResult(null, null, null)
        }
    }

    fun isSupported(): Boolean {
        val hasClientId = !webClientId.isNullOrBlank()
        val playServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS
        return hasClientId && playServicesAvailable
    }
}
