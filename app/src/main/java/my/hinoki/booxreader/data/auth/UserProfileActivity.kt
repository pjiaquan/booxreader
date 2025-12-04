package my.hinoki.booxreader.data.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import my.hinoki.booxreader.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UserProfileActivity : AppCompatActivity() {

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        val tvName = findViewById<TextView>(R.id.tvDisplayName)
        val tvEmail = findViewById<TextView>(R.id.tvEmail)
        val btnLogout = findViewById<Button>(R.id.btnLogout)

        btnLogout.setOnClickListener {
            viewModel.logout()
            // Navigate back to login or main activity
            finish()
        }

        lifecycleScope.launch {
            viewModel.currentUser.collectLatest { user ->
                if (user == null) {
                    // No user logged in
                    tvName.text = "Guest"
                    tvEmail.text = "Please login"
                    btnLogout.text = "Login"
                    btnLogout.setOnClickListener {
                         startActivity(Intent(this@UserProfileActivity, LoginActivity::class.java))
                         finish()
                    }
                } else {
                    tvName.text = user.displayName ?: "User"
                    tvEmail.text = user.email
                    btnLogout.text = "Logout"
                    btnLogout.setOnClickListener {
                        viewModel.logout()
                    }
                }
            }
        }
    }
}

