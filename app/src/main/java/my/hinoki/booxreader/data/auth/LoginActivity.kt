package my.hinoki.booxreader.data.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import my.hinoki.booxreader.R
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private val viewModel: AuthViewModel by viewModels()
    private val RC_SIGN_IN = 9001
    
    private val googleHelper by lazy { GoogleSignInHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGoogle = findViewById<Button>(R.id.btnGoogleSignIn)
        val btnResend = findViewById<Button>(R.id.btnResendVerification)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)

        if (!googleHelper.isSupported()) {
            btnGoogle.visibility = android.view.View.GONE
        }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString()
            val pass = etPassword.text.toString()
            if (email.isNotBlank() && pass.isNotBlank()) {
                viewModel.login(email, pass)
            }
        }

        btnGoogle.setOnClickListener {
            googleHelper.signIn(RC_SIGN_IN)
        }

        btnResend.setOnClickListener {
            val email = etEmail.text.toString()
            val pass = etPassword.text.toString()
            if (email.isNotBlank() && pass.isNotBlank()) {
                viewModel.resendVerification(email, pass)
            } else {
                Toast.makeText(this, getString(R.string.login_resend_prompt), Toast.LENGTH_SHORT).show()
            }
        }
        
        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        lifecycleScope.launch {
            viewModel.authState.collect { state ->
                when (state) {
                    is AuthState.Loading -> {
                        btnLogin.isEnabled = false
                        btnResend.visibility = View.GONE
                }
                is AuthState.Success -> {
                    Toast.makeText(this@LoginActivity, getString(R.string.login_success), Toast.LENGTH_SHORT).show()
                    btnResend.visibility = View.GONE
                    finish()
                }
                    is AuthState.Error -> {
                        btnLogin.isEnabled = true
                        Toast.makeText(this@LoginActivity, state.message, Toast.LENGTH_SHORT).show()
                        if (state.message.contains("not verified", ignoreCase = true) ||
                            state.message.contains("驗證", ignoreCase = true)) {
                            btnResend.visibility = View.VISIBLE
                        } else {
                            btnResend.visibility = View.GONE
                        }
                        viewModel.resetState()
                    }
                    else -> {
                        btnLogin.isEnabled = true
                        btnResend.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            googleHelper.handleActivityResult(data) { idToken, email, name ->
                if (idToken != null) {
                    viewModel.googleLogin(idToken, email, name)
                }
            }
        }
    }
}
