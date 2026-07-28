package my.hinoki.booxreader.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.CheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.ui.common.BaseActivity

class LoginActivity : BaseActivity() {

    private val viewModel: AuthViewModel by viewModels()
    private val RC_SIGN_IN = 9001

    private val googleHelper by lazy { GoogleSignInHelper(this) }
    private val tokenManager by lazy { (application as BooxReaderApp).tokenManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val cbRememberMe = findViewById<CheckBox>(R.id.cbRememberMe)
        val tvForgotEmail = findViewById<TextView>(R.id.tvForgotEmail)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGoogle = findViewById<Button>(R.id.btnGoogleSignIn)
        val btnResend = findViewById<Button>(R.id.btnResendVerification)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val btnGuestMode = findViewById<Button>(R.id.btnGuestMode)
        val loginScroll = findViewById<ScrollView>(R.id.loginScroll)

        if (tokenManager.isRememberMeEnabled()) {
            val savedEmail = tokenManager.getSavedEmail()
            if (!savedEmail.isNullOrBlank()) {
                etEmail.setText(savedEmail)
                cbRememberMe.isChecked = true
            }
        }

        val baseBottomPadding = loginScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(loginScroll) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.updatePadding(bottom = baseBottomPadding + maxOf(imeBottom, systemBottom))
            insets
        }
        ViewCompat.requestApplyInsets(loginScroll)

        if (!googleHelper.isSupported()) {
            btnGoogle.visibility = android.view.View.GONE
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                btnLogin.performClick()
                true
            } else {
                false
            }
        }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString()
            if (email.isNotBlank() && pass.isNotBlank()) {
                tokenManager.saveRememberMe(cbRememberMe.isChecked, email)
                viewModel.login(email, pass)
            }
        }

        tvForgotEmail.setOnClickListener {
            showForgotEmailDialog(etEmail.text.toString().trim())
        }

        btnGoogle.setOnClickListener { googleHelper.signIn(RC_SIGN_IN) }

        btnResend.setOnClickListener {
            val email = etEmail.text.toString()
            val pass = etPassword.text.toString()
            if (email.isNotBlank() && pass.isNotBlank()) {
                viewModel.resendVerification(email, pass)
            } else {
                Toast.makeText(this, getString(R.string.login_resend_prompt), Toast.LENGTH_SHORT)
                        .show()
            }
        }

        tvRegister.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }

        btnGuestMode.setOnClickListener {
            tokenManager.saveGuestMode(true)
            Toast.makeText(this, getString(R.string.guest_mode_notice), Toast.LENGTH_SHORT).show()
            val intent = Intent(this, my.hinoki.booxreader.ui.main.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        lifecycleScope.launch {
            viewModel.authState.collect { state ->
                when (state) {
                    is AuthState.Loading -> {
                        btnLogin.isEnabled = false
                        btnGoogle.isEnabled = false
                        progressBar.visibility = View.VISIBLE
                        btnResend.visibility = View.GONE
                        tvRegister.visibility = View.GONE
                        btnGuestMode.visibility = View.GONE
                    }
                    is AuthState.Success -> {
                        tokenManager.saveGuestMode(false)
                        Toast.makeText(
                                        this@LoginActivity,
                                        getString(R.string.login_success),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        btnResend.visibility = View.GONE
                        progressBar.visibility = View.GONE

                        // Navigate to MainActivity
                        val intent =
                                Intent(
                                        this@LoginActivity,
                                        my.hinoki.booxreader.ui.main.MainActivity::class.java
                                )
                        intent.flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    is AuthState.Error -> {
                        btnLogin.isEnabled = true
                        btnGoogle.isEnabled = true
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@LoginActivity, state.message, Toast.LENGTH_SHORT).show()

                        btnResend.visibility =
                                if (isVerificationError(state.message)) View.VISIBLE else View.GONE
                        tvRegister.visibility = View.VISIBLE
                        btnGuestMode.visibility = View.VISIBLE
                        viewModel.resetState()
                    }
                    else -> {
                        btnLogin.isEnabled = true
                        btnGoogle.isEnabled = true
                        progressBar.visibility = View.GONE
                        btnResend.visibility = View.GONE
                        tvRegister.visibility = View.VISIBLE
                        btnGuestMode.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun isVerificationError(message: String): Boolean {
        return message.contains("not verified", ignoreCase = true) ||
                message.contains("驗證", ignoreCase = true)
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

    private fun showForgotEmailDialog(currentEmailInput: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.forgot_email_dialog_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            if (currentEmailInput.isNotBlank()) {
                setText(currentEmailInput)
            }
        }

        val container = android.widget.FrameLayout(this).apply {
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, margin / 2)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forgot_email_dialog_title))
            .setMessage(getString(R.string.forgot_email_dialog_message))
            .setView(container)
            .setPositiveButton(getString(R.string.forgot_email_dialog_send)) { dialog, _ ->
                val inputEmail = input.text.toString().trim()
                if (inputEmail.isNotBlank()) {
                    viewModel.requestPasswordReset(inputEmail)
                } else {
                    Toast.makeText(this, getString(R.string.forgot_email_prompt), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.forgot_email_dialog_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
