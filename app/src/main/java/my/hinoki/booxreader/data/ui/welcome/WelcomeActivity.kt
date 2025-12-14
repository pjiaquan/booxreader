package my.hinoki.booxreader.data.ui.welcome

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.data.ui.main.MainActivity
import my.hinoki.booxreader.databinding.ActivityWelcomeBinding

class WelcomeActivity : ComponentActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var prefs: SharedPreferences
    
    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_FIRST_RUN = "first_run"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                navigateToMain()
            } else {
                // Determine if we should show rationale or if "Don't ask again" was checked
                 val deniedCount = permissions.count { !it.value }
                 if (deniedCount > 0) {
                     // Check if we need to show manual setting instructions (if "Don't ask again" was likely formatted)
                      val shouldShowRationale = permissions.keys.any { permission ->
                        ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                    }
                    
                    if (!shouldShowRationale) {
                        // User likely checked "Don't ask again", prompt to open settings
                         androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Permission Required")
                            .setMessage("Permissions are needed to read books. Please grant them in Settings.")
                             .setPositiveButton("Settings") { _, _ ->
                                openAppSettings()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        Toast.makeText(this, "Permissions are required to access books.", Toast.LENGTH_SHORT).show()
                    }
                 }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Check first run status
        if (!isFirstRun()) {
            navigateToMain()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }
    
    private fun isFirstRun(): Boolean {
        return prefs.getBoolean(KEY_FIRST_RUN, true)
    }

    private fun setupListeners() {
        binding.btnGrant.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.btnSkip.setOnClickListener {
            // User chose to skip, we still mark first run as complete but maybe without permissions
            markFirstRunComplete()
            navigateToMain()
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasPermissions()) {
            navigateToMain()
        } else {
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
             val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
             val hasWrite = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                 ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
             } else {
                 true
             }
             hasRead && hasWrite
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun markFirstRunComplete() {
        prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
    }

    private fun navigateToMain() {
        markFirstRunComplete()
        val intent = Intent(this, MainActivity::class.java)
        // Clear back stack so user can't go back to Welcome
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }
}
