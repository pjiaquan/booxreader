package my.hinoki.booxreader.data.auth

import android.app.Activity
import android.content.Intent
import android.widget.Toast

class GoogleSignInHelper(private val activity: Activity) {

    fun signIn(requestCode: Int) {
        Toast.makeText(activity, "Google Sign-In is not available in the FOSS version.", Toast.LENGTH_LONG).show()
    }

    fun handleActivityResult(data: Intent?, onResult: (idToken: String?, email: String?, name: String?) -> Unit) {
        // No-op
        onResult(null, null, null)
    }

    fun isSupported(): Boolean = false
}

