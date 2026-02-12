package my.hinoki.booxreader.ui.auth

import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.repo.AuthRepository
import my.hinoki.booxreader.ui.common.BaseActivity
import java.net.URL

class UserProfileActivity : BaseActivity() {

    private val viewModel: AuthViewModel by viewModels()
    private val authRepository: AuthRepository by lazy {
        AuthRepository(this, (application as BooxReaderApp).tokenManager)
    }

    private lateinit var ivAvatar: ImageView
    private lateinit var btnChangeAvatar: Button
    private lateinit var etUsername: EditText
    private lateinit var tvEmail: TextView
    private lateinit var btnSaveProfile: Button
    private lateinit var etCurrentPassword: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnChangePassword: Button
    private lateinit var btnLogout: Button
    private lateinit var profileProgressBar: ProgressBar
    private lateinit var tvVersion: TextView

    private var selectedAvatarUri: Uri? = null

    private val pickAvatar =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    return@registerForActivityResult
                }
                selectedAvatarUri = uri
                ivAvatar.setImageURI(uri)
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applyActionBarContrast()

        ivAvatar = findViewById(R.id.ivAvatar)
        btnChangeAvatar = findViewById(R.id.btnChangeAvatar)
        etUsername = findViewById(R.id.etUsername)
        tvEmail = findViewById(R.id.tvEmail)
        btnSaveProfile = findViewById(R.id.btnSaveProfile)
        etCurrentPassword = findViewById(R.id.etCurrentPassword)
        etNewPassword = findViewById(R.id.etNewPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnLogout = findViewById(R.id.btnLogout)
        profileProgressBar = findViewById(R.id.profileProgressBar)
        tvVersion = findViewById(R.id.tvVersion)

        tvVersion.text =
                getString(
                        R.string.profile_version_format,
                        my.hinoki.booxreader.BuildConfig.VERSION_NAME
                )

        val launchPicker: () -> Unit = { pickAvatar.launch("image/*") }
        ivAvatar.setOnClickListener { launchPicker() }
        btnChangeAvatar.setOnClickListener { launchPicker() }

        btnSaveProfile.setOnClickListener { onSaveProfileClicked() }
        btnChangePassword.setOnClickListener { onChangePasswordClicked() }

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

        loadCurrentUser()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun applyActionBarContrast() {
        val barColor = ContextCompat.getColor(this, R.color.action_bar_surface)
        val contentColor = if (ColorUtils.calculateLuminance(barColor) > 0.5) Color.BLACK else Color.WHITE

        supportActionBar?.title =
                android.text.SpannableString(getString(R.string.reader_settings_user_profile)).apply {
                    setSpan(android.text.style.ForegroundColorSpan(contentColor), 0, length, 0)
                }

        val backDrawable =
                AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                        ?.mutate()
                        ?.let { drawable ->
                            DrawableCompat.setTint(drawable, contentColor)
                            drawable
                        }
        if (backDrawable != null) {
            supportActionBar?.setHomeAsUpIndicator(backDrawable)
        }

        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = barColor
        }
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars =
                ColorUtils.calculateLuminance(barColor) > 0.5
    }

    private fun loadCurrentUser() {
        lifecycleScope.launch {
            val user = authRepository.getCurrentUser()
            if (user != null) {
                etUsername.setText(user.displayName ?: "")
                tvEmail.text = user.email
                loadAvatar(user.avatarUrl)
            } else {
                etUsername.setText(getString(R.string.profile_guest_name))
                tvEmail.text = getString(R.string.profile_login_required)
                ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                setEditingEnabled(false)
            }
        }
    }

    private fun onSaveProfileClicked() {
        val username = etUsername.text?.toString()?.trim().orEmpty()
        if (username.isBlank()) {
            Toast.makeText(this, R.string.profile_username_required, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = authRepository.updateProfile(username, selectedAvatarUri)
            setBusy(false)
            result.onSuccess { user ->
                selectedAvatarUri = null
                etUsername.setText(user.displayName ?: username)
                tvEmail.text = user.email
                loadAvatar(user.avatarUrl)
                Toast.makeText(this@UserProfileActivity, R.string.profile_saved, Toast.LENGTH_SHORT)
                        .show()
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                val text =
                        if (message == null) {
                            getString(R.string.profile_save_failed)
                        } else {
                            getString(R.string.profile_save_failed) + ": " + message
                        }
                Toast.makeText(this@UserProfileActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onChangePasswordClicked() {
        val currentPassword = etCurrentPassword.text?.toString().orEmpty()
        val newPassword = etNewPassword.text?.toString().orEmpty()
        val confirmPassword = etConfirmPassword.text?.toString().orEmpty()

        if (
                currentPassword.isBlank() || newPassword.isBlank() || confirmPassword.isBlank()
        ) {
            Toast.makeText(this, R.string.profile_password_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword != confirmPassword) {
            Toast.makeText(this, R.string.profile_password_mismatch, Toast.LENGTH_SHORT).show()
            return
        }
        if (newPassword.length < 8) {
            Toast.makeText(this, R.string.profile_password_too_short, Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = authRepository.changePassword(currentPassword, newPassword)
            setBusy(false)
            result.onSuccess {
                etCurrentPassword.text?.clear()
                etNewPassword.text?.clear()
                etConfirmPassword.text?.clear()
                Toast.makeText(
                                this@UserProfileActivity,
                                R.string.profile_password_updated,
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }.onFailure { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                val text =
                        if (message == null) {
                            getString(R.string.profile_password_change_failed)
                        } else {
                            getString(R.string.profile_password_change_failed) + ": " + message
                        }
                Toast.makeText(this@UserProfileActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadAvatar(avatarUrl: String?) {
        if (avatarUrl.isNullOrBlank()) {
            ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
            return
        }
        if (avatarUrl.startsWith("content://") || avatarUrl.startsWith("file://")) {
            ivAvatar.setImageURI(Uri.parse(avatarUrl))
            return
        }
        if (avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://")) {
            lifecycleScope.launch {
                val bitmap =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                        URL(avatarUrl).openStream().use { stream ->
                                            BitmapFactory.decodeStream(stream)
                                        }
                                    }
                                    .getOrNull()
                        }
                if (bitmap != null) {
                    ivAvatar.setImageBitmap(bitmap)
                } else {
                    ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
                }
            }
            return
        }
        ivAvatar.setImageResource(R.mipmap.ic_launcher_round)
    }

    private fun setBusy(busy: Boolean) {
        profileProgressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        btnSaveProfile.isEnabled = !busy
        btnChangePassword.isEnabled = !busy
        btnChangeAvatar.isEnabled = !busy
        ivAvatar.isEnabled = !busy
        etUsername.isEnabled = !busy
        etCurrentPassword.isEnabled = !busy
        etNewPassword.isEnabled = !busy
        etConfirmPassword.isEnabled = !busy
        btnLogout.isEnabled = !busy
    }

    private fun setEditingEnabled(enabled: Boolean) {
        btnSaveProfile.isEnabled = enabled
        btnChangePassword.isEnabled = enabled
        btnChangeAvatar.isEnabled = enabled
        ivAvatar.isEnabled = enabled
        etUsername.isEnabled = enabled
        etCurrentPassword.isEnabled = enabled
        etNewPassword.isEnabled = enabled
        etConfirmPassword.isEnabled = enabled
    }
}
