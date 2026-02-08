package my.hinoki.booxreader.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.repo.AuthRepository
import my.hinoki.booxreader.ui.common.BaseActivity

class UserProfileActivity : BaseActivity() {

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        val tvName = findViewById<TextView>(R.id.tvDisplayName)
        val tvEmail = findViewById<TextView>(R.id.tvEmail)
        val btnLogout = findViewById<Button>(R.id.btnLogout)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)

        tvVersion.text =
                getString(
                        R.string.profile_version_format,
                        my.hinoki.booxreader.BuildConfig.VERSION_NAME
                )

        btnLogout.setOnClickListener {
            btnLogout.isEnabled = false
            viewModel.logout().invokeOnCompletion {
                runOnUiThread {
                    val intent =
                            Intent(this@UserProfileActivity, LoginActivity::class.java).apply {
                                flags =
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                    startActivity(intent)
                    finish()
                }
            }
        }

        lifecycleScope.launch {
            val user =
                    AuthRepository(
                                    this@UserProfileActivity,
                                    (application as my.hinoki.booxreader.BooxReaderApp).tokenManager
                            )
                            .getCurrentUser()
            if (user != null) {
                tvName.text = user.displayName ?: "User"
                tvEmail.text = user.email ?: "No email"
            } else {
                tvName.text = "Guest"
                tvEmail.text = "Please login"
            }
        }
    }
}
