package my.hinoki.booxreader.data.auth

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class GoogleSignInHelper(private val activity: Activity) {

    fun signIn(requestCode: Int) {
        // NOTE: Replace "YOUR_SERVER_CLIENT_ID" with your actual web client ID from Google Console
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("YOUR_SERVER_CLIENT_ID") 
            .requestEmail()
            .build()
        val client = GoogleSignIn.getClient(activity, gso)
        activity.startActivityForResult(client.signInIntent, requestCode)
    }

    fun handleActivityResult(data: Intent?, onResult: (idToken: String?, email: String?, name: String?) -> Unit) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            onResult(account.idToken, account.email, account.displayName)
        } catch (e: ApiException) {
            Toast.makeText(activity, "Google Sign In Failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            onResult(null, null, null)
        }
    }
}

