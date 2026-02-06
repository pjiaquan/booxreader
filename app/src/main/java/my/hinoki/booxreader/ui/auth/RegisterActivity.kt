package my.hinoki.booxreader.ui.auth

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.ui.common.BaseActivity

class RegisterActivity : BaseActivity() {

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                btnRegister.performClick()
                true
            } else {
                false
            }
        }

        btnRegister.setOnClickListener {
            val email = etEmail.text.toString()
            val pass = etPassword.text.toString()
            if (email.isNotBlank() && pass.isNotBlank()) {
                viewModel.register(email, pass)
            }
        }

        lifecycleScope.launch {
            viewModel.authState.collect { state ->
                when (state) {
                    is AuthState.Loading -> {
                        btnRegister.isEnabled = false
                    }
                    is AuthState.Error -> {
                        btnRegister.isEnabled = true
                        Toast.makeText(this@RegisterActivity, state.message, Toast.LENGTH_SHORT)
                                .show()
                        val successMsg = getString(R.string.auth_registration_verification_sent)
                        if (state.message == successMsg ||
                                        state.message.contains("驗證", ignoreCase = true)
                        ) {
                            finish()
                        } else {
                            viewModel.resetState()
                        }
                    }
                    else -> {
                        btnRegister.isEnabled = true
                    }
                }
            }
        }
    }
}
