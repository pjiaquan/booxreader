package my.hinoki.booxreader.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.GitHubRelease
import my.hinoki.booxreader.data.repo.GitHubUpdateRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityMainBinding
import my.hinoki.booxreader.ui.auth.LoginActivity
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.ui.reader.ReaderActivity

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

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private var pendingManualSyncAfterPermission = false

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

        setupSwipeRefresh()

        binding.btnOpenEpub.setOnClickListener { pickEpub.launch(arrayOf("application/epub+zip")) }

        binding.btnProfile.setOnClickListener {
            startActivity(
                    Intent(this, my.hinoki.booxreader.ui.auth.UserProfileActivity::class.java)
            )
        }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        // Start observing books flow
        observeRecentBooks()

        // Check and request file permissions
        checkAndRequestFilePermissions()

        // setupManualSyncOnScroll() // Removed in favor of SwipeRefreshLayout

        checkForUpdates()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.purple_500)
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
                        } catch (e: Exception) {}
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
                // Silently fail
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
        } catch (_: Exception) {}
    }

    private fun openBookFromUri(uri: Uri) {
        try {
            ReaderActivity.open(this, uri)
        } catch (e: Exception) {
            // Silently fail
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
                // Silently fail
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
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
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
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        if (apkAsset == null) {
            // Fallback to browser if no APK asset found
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
            startActivity(intent)
            return
        }

        lifecycleScope.launch {
            val toast =
                    android.widget.Toast.makeText(
                            this@MainActivity,
                            R.string.update_downloading,
                            android.widget.Toast.LENGTH_LONG
                    )
            toast.show()

            val file = updateRepository.downloadApk(apkAsset.downloadUrl, apkAsset.name)
            if (file != null) {
                updateRepository.installApk(file)
            } else {
                android.widget.Toast.makeText(
                                this@MainActivity,
                                R.string.update_download_failed,
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
    }
}
