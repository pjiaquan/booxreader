package my.hinoki.booxreader.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.core.ErrorReporter
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.GitHubRelease
import my.hinoki.booxreader.data.repo.GitHubUpdateRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityMainBinding
import my.hinoki.booxreader.ui.auth.LoginActivity
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.ui.reader.ReaderActivity
import my.hinoki.booxreader.ui.reader.ReaderSettingsActivity

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val syncRepo by lazy { UserSyncRepository(applicationContext) }
    private val bookRepository by lazy { BookRepository(applicationContext, syncRepo) }
    private val updateRepository: GitHubUpdateRepository by lazy {
        GitHubUpdateRepository(applicationContext)
    }
    private val recentAdapter by lazy {
        RecentBooksAdapter(
                onClick = { openBook(it) },
                onDelete = { confirmDeleteBook(it) },
                onMarkCompleted = { markBookCompleted(it) }
        )
    }

    private val pickEpub =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    takePersistable(it)
                    openBookFromUri(it)
                }
            }

    private val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissions ->
                val allGranted = permissions.all { it.value }
                if (allGranted) {
                    if (pendingManualSyncAfterPermission) {
                        pendingManualSyncAfterPermission = false
                        startManualSync()
                    } else {
                        // 權限授予後執行同步
                        performFullSync()
                    }
                } else {
                    pendingManualSyncAfterPermission = false
                    showPermissionRationale()
                }
            }
    private val requestInstallUnknownAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val file = pendingUpdateApkFile ?: return@registerForActivityResult
                val targetTagName = pendingUpdateTagName ?: return@registerForActivityResult
                if (canInstallUnknownApps()) {
                    launchApkInstaller(file, targetTagName)
                } else {
                    android.widget.Toast.makeText(
                                    this,
                                    R.string.update_install_permission_required,
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                }
            }
    private val installApkLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val pendingVersion =
                        getPendingUpdateTargetVersion() ?: return@registerForActivityResult
                if (isCurrentVersionAtLeast(pendingVersion)) {
                    android.widget.Toast.makeText(
                                    this,
                                    getString(R.string.update_install_success, pendingVersion),
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                } else {
                    android.widget.Toast.makeText(
                                    this,
                                    R.string.update_install_failed_or_cancelled,
                                    android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                }
                clearPendingUpdateTargetVersion()
            }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val UPDATE_STATUS_PREFS = "update_status"
        private const val KEY_PENDING_UPDATE_TARGET_VERSION = "pending_update_target_version"
    }

    private var pendingManualSyncAfterPermission = false
    private var pendingUpdateApkFile: File? = null
    private var pendingUpdateTagName: String? = null
    private val updateStatusPrefs by lazy { getSharedPreferences(UPDATE_STATUS_PREFS, MODE_PRIVATE) }

    private fun startManualSync() {
        performFullSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is signed in
        if (!isSignedIn()) {
            // User not signed in, redirect to login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyTopInsets(binding.rootMain)
        applyMainPageSystemBars()
        setupSwipeRefresh()

        binding.btnOpenEpub.setOnClickListener { pickEpub.launch(arrayOf("application/epub+zip")) }

        binding.btnSettings.setOnClickListener { ReaderSettingsActivity.open(this, null) }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        // Start observing books flow
        observeRecentBooks()

        // Check and request file permissions
        checkAndRequestFilePermissions()

        // setupManualSyncOnScroll() // Removed in favor of SwipeRefreshLayout

        reportPendingUpdateResultIfNeeded()
        checkForUpdates()
    }

    private fun setupSwipeRefresh() {
        val primaryColor = ContextCompat.getColor(this, R.color.main_accent)
        val surfaceColor =
                resolveThemeColor(
                        com.google.android.material.R.attr.colorSurface,
                        ContextCompat.getColor(this, android.R.color.white)
                )
        binding.swipeRefreshLayout.setColorSchemeColors(primaryColor)
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(surfaceColor)
        binding.swipeRefreshLayout.setOnRefreshListener {
            lifecycleScope.launch {
                try {
                    executeFullSync()
                } finally {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun applyMainPageSystemBars() {
        val surfaceColor =
                resolveThemeColor(
                        com.google.android.material.R.attr.colorSurface,
                        Color.BLACK
                )
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = surfaceColor
            window.navigationBarColor = surfaceColor
        }
        val useLightIcons = ColorUtils.calculateLuminance(surfaceColor) > 0.5
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = useLightIcons
        insetsController.isAppearanceLightNavigationBars = useLightIcons
    }

    private fun resolveThemeColor(@AttrRes attrRes: Int, fallbackColor: Int): Int {
        val typedValue = TypedValue()
        if (!theme.resolveAttribute(attrRes, typedValue, true)) {
            return fallbackColor
        }
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private var progressSyncJob: Job? = null

    override fun onResume() {
        super.onResume()
        // Check if user is still signed in
        if (!isSignedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Note: Books are now automatically observed via Flow
        // No need to manually call loadRecentBooks() here
        startPeriodicProgressSync()
    }

    override fun onPause() {
        super.onPause()
        progressSyncJob?.cancel()
    }

    private fun startPeriodicProgressSync() {
        progressSyncJob?.cancel()
        progressSyncJob =
                lifecycleScope.launch {
                    while (true) {
                        // Wait 30 seconds
                        kotlinx.coroutines.delay(30_000)

                        try {
                            val updated = syncRepo.pullAllProgress()
                            if (updated > 0) {}
                        } catch (e: Exception) {
                            ErrorReporter.report(
                                    this@MainActivity,
                                    "MainActivity.startPeriodicProgressSync",
                                    "Periodic progress sync failed",
                                    e
                            )
                        }
                    }
                }
    }

    private fun isSignedIn(): Boolean {
        val app = application as BooxReaderApp
        return !app.tokenManager.getAccessToken().isNullOrBlank()
    }

    private fun performFullSync() {
        lifecycleScope.launch {
            binding.swipeRefreshLayout.isRefreshing = true
            try {
                executeFullSync()
            } finally {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private suspend fun executeFullSync() {
        withContext(Dispatchers.IO) {
            try {
                // Sync all user data
                // Sync Profiles FIRST so settings can link to them
                val profilesResult = runCatching { syncRepo.pullProfiles() }
                val profilesUpdated = profilesResult.getOrNull() ?: 0

                val settingsResult = runCatching { syncRepo.pullSettingsIfNewer() }

                // Push local books first so cloud gets local-only entries before pull/merge.
                runCatching { syncRepo.pushLocalBooks() }

                val booksResult = runCatching { syncRepo.pullBooks() }
                val booksUpdated = booksResult.getOrNull() ?: 0

                val notesResult = runCatching { syncRepo.pullNotes() }
                val notesUpdated = notesResult.getOrNull() ?: 0

                val progressResult = runCatching { syncRepo.pullAllProgress() }
                val progressUpdated = progressResult.getOrNull() ?: 0

                val bookmarksResult = runCatching { syncRepo.pullBookmarks() }
                val bookmarksUpdated = bookmarksResult.getOrNull() ?: 0

                // Force refresh recent books after sync to ensure UI updates
                // This ensures progress updates are reflected even if Flow doesn't emit
                // automatically
                withContext(Dispatchers.Main) {
                    val recentBooks = bookRepository.getRecentBooksSync(10)
                    recentAdapter.submitList(recentBooks)
                }
            } catch (e: Exception) {
                ErrorReporter.report(
                        this@MainActivity,
                        "MainActivity.executeFullSync",
                        "Full sync failed",
                        e
                )
            }
        }
    }

    private fun hasFilePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses READ_MEDIA_IMAGES for EPUB files
            // We only need read permission for accessing EPUB files
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12, no permission needed for app-specific files
            // But we still need READ_EXTERNAL_STORAGE for accessing EPUB files outside app dir
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10 and below
            // Need both read and write for EPUB file access
            val hasRead =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED

            val hasWrite =
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        // Android 9 and below need WRITE_EXTERNAL_STORAGE
                        ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    } else {
                        // Android 10 only needs READ
                        true
                    }

            hasRead && hasWrite
        }
    }

    private fun checkAndRequestFilePermissions() {
        val hasPerms = hasFilePermissions()

        if (hasPerms) {
            // Already have permissions, perform sync
            performFullSync()
            // Note: Books are now automatically observed via Flow
            // No need to manually call loadRecentBooks() here
        } else {
            // Request permissions
            requestFilePermissions()
        }
    }

    private fun requestFilePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android 10 and below
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                // Android 9 and below also need write permission
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Check if any permission needs rationale
        val needsRationale =
                permissionsToRequest.any { permission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
                }

        if (needsRationale) {
            // Show explanation
            showPermissionRationale()
        } else {
            // Request permissions
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun showPermissionRationale() {
        val message =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getString(R.string.permission_required_file_desc_13)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getString(R.string.permission_required_file_desc_11)
                } else {
                    getString(R.string.permission_required_file_desc_10)
                }

        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_required_file_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.welcome_grant_button)) { _, _ ->
                    requestFilePermissions()
                }
                .setNegativeButton(getString(R.string.welcome_skip_button)) { _, _ ->
                    // 即使沒有權限，仍然嘗試載入本地書籍
                    loadRecentBooks()
                }
                .show()
    }

    private fun observeRecentBooks() {
        lifecycleScope.launch {
            bookRepository.getRecent(10).collectLatest { recent ->
                if (recent.isEmpty()) {
                    binding.tvEmptyState.visibility = android.view.View.VISIBLE
                    binding.recyclerRecent.visibility = android.view.View.GONE
                } else {
                    binding.tvEmptyState.visibility = android.view.View.GONE
                    binding.recyclerRecent.visibility = android.view.View.VISIBLE
                    recentAdapter.submitList(recent)
                }
            }
        }
    }

    private fun loadRecentBooks() {
        // This method is kept for backward compatibility
        // The actual loading is now done through observeRecentBooks()
        // This can be called to trigger an immediate refresh if needed
        observeRecentBooks()
    }

    private fun takePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            ErrorReporter.report(
                    this,
                    "MainActivity.takePersistable",
                    "Failed to persist URI permission: $uri",
                    e
            )
        }
    }

    private fun openBookFromUri(uri: Uri) {
        try {
            ReaderActivity.open(this, uri)
        } catch (e: Exception) {
            ErrorReporter.report(
                    this,
                    "MainActivity.openBookFromUri",
                    "Failed to open selected book URI: $uri",
                    e
            )
        }
    }

    private fun markBookCompleted(entity: BookEntity) {
        val title =
                entity.title?.takeIf { it.isNotBlank() } ?: getString(R.string.book_title_untitled)
        lifecycleScope.launch { runCatching { bookRepository.markCompleted(entity.bookId) } }
    }

    private fun confirmDeleteBook(entity: BookEntity) {
        val title =
                entity.title?.takeIf { it.isNotBlank() } ?: getString(R.string.book_title_untitled)
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sync_delete_book_title))
                .setMessage(getString(R.string.sync_delete_book_message, title))
                .setPositiveButton(getString(R.string.sync_delete_action)) { _, _ ->
                    lifecycleScope.launch { bookRepository.deleteBook(entity.bookId) }
                }
                .setNegativeButton(getString(R.string.sync_cancel_action), null)
                .show()
    }

    private fun openBook(entity: BookEntity) {
        lifecycleScope.launch {
            try {
                val uri = Uri.parse(entity.fileUri)
                // Check if file exists using ContentResolver for content:// URIs
                if (isUriAccessible(uri)) {
                    ReaderActivity.open(this@MainActivity, uri)
                } else {
                    // Try to download from cloud
                    val syncRepo = UserSyncRepository(applicationContext)
                    val storagePath =
                            if (entity.fileUri.startsWith("pocketbase://")) {
                                entity.fileUri
                                        .removePrefix("pocketbase://")
                                        .takeIf { it.contains("/") }
                            } else {
                                null
                            }
                    val localUri =
                            syncRepo.ensureBookFileAvailable(
                                    entity.bookId,
                                    storagePath = storagePath,
                                    originalUri = entity.fileUri
                            )
                    if (localUri != null) {
                        ReaderActivity.open(this@MainActivity, localUri)
                    } else {
                        android.widget.Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.book_file_not_found),
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
            } catch (e: Exception) {
                ErrorReporter.report(
                        this@MainActivity,
                        "MainActivity.openBook",
                        "Failed to open recent book ${entity.bookId}",
                        e
                )
            }
        }
    }

    private fun runStorageSelfTest() {
        binding.cardSyncStatus.visibility = android.view.View.VISIBLE
        binding.tvSyncStatus.text = getString(R.string.sync_storage_test_start)
        binding.progressSync.visibility = android.view.View.VISIBLE
        binding.tvSyncProgress.visibility = android.view.View.VISIBLE
        binding.tvSyncDetails.visibility = android.view.View.VISIBLE
        binding.progressSync.progress = 0
        binding.tvSyncProgress.text = "0%"
        binding.tvSyncDetails.text = getString(R.string.sync_storage_test_start)

        lifecycleScope.launch {
            val result = syncRepo.runStorageSelfTest()
            if (result.ok) {
                binding.tvSyncStatus.text = getString(R.string.sync_storage_test_passed)
                binding.tvSyncDetails.text = result.message
                binding.tvSyncProgress.text = "100%"
                binding.progressSync.progress = 100
            } else {
                binding.tvSyncStatus.text = getString(R.string.sync_storage_test_failed)
                binding.tvSyncDetails.text = result.message
                binding.tvSyncProgress.text = "Error"
            }
        }
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        // Placeholder cloud URI, not a ContentProvider-backed URI.
        if (uri.scheme.equals("pocketbase", ignoreCase = true)) {
            return false
        }
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            ErrorReporter.report(
                    this,
                    "MainActivity.isUriAccessible",
                    "Failed to access URI: $uri",
                    e
            )
            false
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val release = updateRepository.fetchLatestRelease() ?: return@launch
            if (updateRepository.isNewerVersion(release.tagName)) {
                showUpdateDialog(release)
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_available_title))
                .setMessage(getString(R.string.update_new_version_available, release.tagName))
                .setPositiveButton(getString(R.string.update_action_download_and_install)) { _, _ ->
                    downloadAndInstallUpdate(release)
                }
                .setNegativeButton(getString(R.string.update_action_later), null)
                .show()
    }

    private fun downloadAndInstallUpdate(release: GitHubRelease) {
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAsset == null) {
            // Fallback to browser if no APK asset found
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
            startActivity(intent)
            return
        }

        lifecycleScope.launch {
            pendingUpdateTagName = release.tagName
            val toast =
                    android.widget.Toast.makeText(
                            this@MainActivity,
                            R.string.update_downloading,
                            android.widget.Toast.LENGTH_LONG
                    )
            toast.show()

            val file = updateRepository.downloadApk(apkAsset.downloadUrl, apkAsset.name)
            if (file != null) {
                pendingUpdateApkFile = file
                if (canInstallUnknownApps()) {
                    launchApkInstaller(file, release.tagName)
                } else {
                    val intent =
                            Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:$packageName")
                                    )
                    android.widget.Toast.makeText(
                                    this@MainActivity,
                                    R.string.update_install_permission_required,
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                    requestInstallUnknownAppsLauncher.launch(intent)
                }
            } else {
                pendingUpdateTagName = null
                android.widget.Toast.makeText(
                                this@MainActivity,
                                R.string.update_download_failed,
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
    }

    private fun canInstallUnknownApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun launchApkInstaller(file: File, tagName: String) {
        runCatching {
                    rememberPendingUpdateTargetVersion(tagName)
                    installApkLauncher.launch(updateRepository.createInstallIntent(file))
                }
                .onFailure {
                    clearPendingUpdateTargetVersion()
                    android.widget.Toast.makeText(
                                    this,
                                    R.string.update_install_launch_failed,
                                    android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                }
    }

    private fun reportPendingUpdateResultIfNeeded() {
        val pendingVersion = getPendingUpdateTargetVersion() ?: return
        if (isCurrentVersionAtLeast(pendingVersion)) {
            android.widget.Toast.makeText(
                            this,
                            getString(R.string.update_install_success, pendingVersion),
                            android.widget.Toast.LENGTH_LONG
                    )
                    .show()
        } else {
            android.widget.Toast.makeText(
                            this,
                            R.string.update_install_failed_or_cancelled,
                            android.widget.Toast.LENGTH_SHORT
                    )
                    .show()
        }
        clearPendingUpdateTargetVersion()
    }

    private fun rememberPendingUpdateTargetVersion(tagName: String) {
        val normalized = normalizeVersion(tagName)
        updateStatusPrefs.edit().putString(KEY_PENDING_UPDATE_TARGET_VERSION, normalized).apply()
    }

    private fun getPendingUpdateTargetVersion(): String? {
        return updateStatusPrefs.getString(KEY_PENDING_UPDATE_TARGET_VERSION, null)
    }

    private fun clearPendingUpdateTargetVersion() {
        updateStatusPrefs.edit().remove(KEY_PENDING_UPDATE_TARGET_VERSION).apply()
        pendingUpdateApkFile = null
        pendingUpdateTagName = null
    }

    private fun isCurrentVersionAtLeast(targetVersion: String): Boolean {
        return compareVersions(BuildConfig.VERSION_NAME, targetVersion) >= 0
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = parseVersionParts(left)
        val rightParts = parseVersionParts(right)

        if (leftParts != null && rightParts != null) {
            val maxParts = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until maxParts) {
                val leftPart = leftParts.getOrElse(index) { 0 }
                val rightPart = rightParts.getOrElse(index) { 0 }
                if (leftPart != rightPart) {
                    return leftPart.compareTo(rightPart)
                }
            }
            return 0
        }

        return normalizeVersion(left).compareTo(normalizeVersion(right))
    }

    private fun parseVersionParts(version: String): List<Int>? {
        return normalizeVersion(version).split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        }
    }

    private fun normalizeVersion(version: String): String {
        return version.removePrefix("v").trim()
    }
}
