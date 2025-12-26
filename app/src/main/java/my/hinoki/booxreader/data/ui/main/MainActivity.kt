package my.hinoki.booxreader.data.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import my.hinoki.booxreader.R
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.data.auth.LoginActivity
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.ui.common.BaseActivity
import my.hinoki.booxreader.data.ui.reader.ReaderActivity
import my.hinoki.booxreader.databinding.ActivityMainBinding

class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private val syncRepo by lazy { UserSyncRepository(applicationContext) }
    private val bookRepository by lazy { BookRepository(applicationContext, syncRepo) }
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
                    Toast.makeText(
                                    this,
                                    getString(R.string.permission_granted_toast),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                    // 權限授予後執行同步
                    performFullSync()
                } else {
                    val deniedCount = permissions.count { !it.value }
                    Toast.makeText(
                                    this,
                                    getString(R.string.permission_denied_toast, deniedCount),
                                    Toast.LENGTH_LONG
                            )
                            .show()
                    showPermissionRationale()
                }
            }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
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

        binding.btnOpenEpub.setOnClickListener { pickEpub.launch(arrayOf("application/epub+zip")) }

        binding.btnProfile.setOnClickListener {
            startActivity(
                    Intent(this, my.hinoki.booxreader.data.auth.UserProfileActivity::class.java)
            )
        }

        binding.btnSync.setOnClickListener { startManualSync() }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        // Start observing books flow
        observeRecentBooks()

        // Check and request file permissions
        checkAndRequestFilePermissions()
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
                            if (updated > 0) {
                            }
                        } catch (e: Exception) {
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
            try {
                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sync_start_toast),
                                Toast.LENGTH_SHORT
                        )
                        .show()

                // Sync all user data
                // Sync Profiles FIRST so settings can link to them
                val profilesResult = runCatching { syncRepo.pullProfiles() }
                val profilesUpdated = profilesResult.getOrNull() ?: 0

                val settingsResult = runCatching { syncRepo.pullSettingsIfNewer() }

                val booksResult = runCatching { syncRepo.pullBooks() }
                val booksUpdated = booksResult.getOrNull() ?: 0
                if (booksUpdated > 0) {
                    Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.sync_books_toast),
                                    Toast.LENGTH_SHORT
                            )
                            .show()
                }

                val notesResult = runCatching { syncRepo.pullNotes() }
                val notesUpdated = notesResult.getOrNull() ?: 0

                val progressResult = runCatching { syncRepo.pullAllProgress() }
                val progressUpdated = progressResult.getOrNull() ?: 0

                val bookmarksResult = runCatching { syncRepo.pullBookmarks() }
                val bookmarksUpdated = bookmarksResult.getOrNull() ?: 0

                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sync_complete),
                                Toast.LENGTH_SHORT
                        )
                        .show()

                // Force refresh recent books after sync to ensure UI updates
                // This ensures progress updates are reflected even if Flow doesn't emit
                // automatically
                lifecycleScope.launch {
                    val recentBooks = bookRepository.getRecentBooksSync(10)
                    recentAdapter.submitList(recentBooks)
                }
            } catch (e: Exception) {
                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sync_failed) + ": ${e.message}",
                                Toast.LENGTH_SHORT
                        )
                        .show()
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
                    Toast.makeText(
                                    this,
                                    getString(R.string.permission_later_toast),
                                    Toast.LENGTH_LONG
                            )
                            .show()
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
            Toast.makeText(this, getString(R.string.open_book_error, e.message), Toast.LENGTH_LONG)
                    .show()
        }
    }

    private fun markBookCompleted(entity: BookEntity) {
        val title =
                entity.title?.takeIf { it.isNotBlank() } ?: getString(R.string.book_title_untitled)
        lifecycleScope.launch {
            runCatching { bookRepository.markCompleted(entity.bookId) }
                    .onSuccess {
                        Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.book_mark_completed, title),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                    .onFailure {
                        Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.book_mark_failed, it.message),
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
        }
    }

    private fun confirmDeleteBook(entity: BookEntity) {
        val title =
                entity.title?.takeIf { it.isNotBlank() } ?: getString(R.string.book_title_untitled)
        androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sync_delete_book_title))
                .setMessage(getString(R.string.sync_delete_book_message, title))
                .setPositiveButton(getString(R.string.sync_delete_action)) { _, _ ->
                    lifecycleScope.launch {
                        bookRepository.deleteBook(entity.bookId)
                        Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.sync_delete_success, title),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
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
                    val localUri =
                            syncRepo.ensureBookFileAvailable(
                                    entity.bookId,
                                    originalUri = entity.fileUri
                            )
                    if (localUri != null) {
                        ReaderActivity.open(this@MainActivity, localUri)
                    } else {
                        Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.book_file_not_found),
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.open_book_error, e.message),
                                Toast.LENGTH_LONG
                        )
                        .show()
            }
        }
    }

    private fun startManualSync() {
        // 顯示同步狀態卡片
        binding.cardSyncStatus.visibility = android.view.View.VISIBLE
        binding.tvSyncStatus.text = getString(R.string.sync_manual_start)
        binding.progressSync.visibility = android.view.View.VISIBLE
        binding.tvSyncProgress.visibility = android.view.View.VISIBLE
        binding.tvSyncDetails.visibility = android.view.View.VISIBLE

        // 重置進度條
        binding.progressSync.progress = 0
        binding.tvSyncProgress.text = "0%"
        binding.tvSyncDetails.text = getString(R.string.sync_manual_start)

        lifecycleScope.launch {
            try {

                val totalSteps =
                        9 // 下載 + 上傳 + 再次下載校驗 + Profiles + 設定 + Notes + Progress + Bookmarks + Done
                var currentStep = 0

                // 步驟1: 先下載雲端書籍（包含Storage檔案）
                currentStep++
                updateSyncProgress(
                        currentStep,
                        totalSteps,
                        getString(R.string.sync_downloading_books)
                )
                val pullBooksResult = runCatching { syncRepo.pullBooks() }
                val booksDownloadedInitial = pullBooksResult.getOrNull() ?: 0

                // 步驟2: 上傳本地書籍到雲端
                currentStep++
                updateSyncProgress(
                        currentStep,
                        totalSteps,
                        getString(R.string.sync_uploading_local_books)
                )
                val uploadResult = runCatching { uploadLocalBooks() }
                val booksUploaded = uploadResult.getOrNull() ?: 0

                // 步驟3: 再次下載，確保剛剛其它裝置上傳的書籍也同步到本機
                currentStep++
                updateSyncProgress(
                        currentStep,
                        totalSteps,
                        getString(R.string.sync_downloading_books_again)
                )
                val pullBooksResultAfterUpload = runCatching { syncRepo.pullBooks() }
                val booksDownloadedFinal = pullBooksResultAfterUpload.getOrNull() ?: 0

                // 步驟4: 同步AI Profiles (Critical to run before settings)
                currentStep++
                updateSyncProgress(
                        currentStep,
                        totalSteps,
                        "Syncing AI Profiles..."
                ) // TODO: Add string resource
                val profilesResult = runCatching { syncRepo.pullProfiles() }
                val profilesUpdated = profilesResult.getOrNull() ?: 0

                // 步驟5: 同步設定
                currentStep++
                updateSyncProgress(currentStep, totalSteps, getString(R.string.sync_settings))
                val settingsResult = runCatching { syncRepo.pullSettingsIfNewer() }

                // 步驟6: 同步AI筆記
                currentStep++
                updateSyncProgress(currentStep, totalSteps, getString(R.string.sync_ai_notes))
                val notesResult = runCatching { syncRepo.pullNotes() }
                val notesUpdated = notesResult.getOrNull() ?: 0

                // 步驟7: 同步閱讀進度
                currentStep++
                updateSyncProgress(currentStep, totalSteps, getString(R.string.sync_progress))
                val progressResult = runCatching { syncRepo.pullAllProgress() }
                val progressUpdated = progressResult.getOrNull() ?: 0

                // 步驟8: 同步書籤
                currentStep++
                updateSyncProgress(currentStep, totalSteps, getString(R.string.sync_bookmarks))
                val bookmarksResult = runCatching { syncRepo.pullBookmarks() }
                val bookmarksUpdated = bookmarksResult.getOrNull() ?: 0

                // 完成
                updateSyncProgress(totalSteps, totalSteps, getString(R.string.sync_complete))

                // 顯示結果摘要
                val summary = buildString {
                    append(getString(R.string.sync_complete) + "\n")
                    if (booksUploaded > 0)
                            append(
                                    getString(R.string.sync_result_uploaded_books, booksUploaded) +
                                            "\n"
                            )
                    val totalDownloaded = booksDownloadedInitial + booksDownloadedFinal
                    if (totalDownloaded > 0)
                            append(
                                    getString(
                                            R.string.sync_result_downloaded_books,
                                            totalDownloaded
                                    ) + "\n"
                            )
                    if (profilesUpdated > 0) append("Profiles updated: $profilesUpdated\n")
                    if (notesUpdated > 0)
                            append(
                                    getString(R.string.sync_result_updated_notes, notesUpdated) +
                                            "\n"
                            )
                    if (progressUpdated > 0)
                            append(
                                    getString(
                                            R.string.sync_result_updated_progress,
                                            progressUpdated
                                    ) + "\n"
                            )
                    if (bookmarksUpdated > 0)
                            append(
                                    getString(
                                            R.string.sync_result_updated_bookmarks,
                                            bookmarksUpdated
                                    ) + "\n"
                            )
                    if (booksUploaded == 0 &&
                                    totalDownloaded == 0 &&
                                    notesUpdated == 0 &&
                                    progressUpdated == 0 &&
                                    bookmarksUpdated == 0 &&
                                    profilesUpdated == 0
                    ) {
                        append(getString(R.string.sync_result_no_updates))
                    }
                }

                binding.tvSyncStatus.text = getString(R.string.sync_complete)
                binding.tvSyncDetails.text = summary
                binding.tvSyncProgress.text = "100%"
                binding.progressSync.progress = 100

                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sync_complete),
                                Toast.LENGTH_SHORT
                        )
                        .show()
            } catch (e: Exception) {
                binding.tvSyncStatus.text = getString(R.string.sync_failed)
                binding.tvSyncDetails.text = getString(R.string.sync_failed) + ": ${e.message}"
                binding.tvSyncProgress.text = "Error"
                Toast.makeText(
                                this@MainActivity,
                                getString(R.string.sync_failed) + ": ${e.message}",
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
    }

    private fun updateSyncProgress(current: Int, total: Int, message: String) {
        val progress = (current * 100 / total)
        binding.progressSync.progress = progress
        binding.tvSyncProgress.text = "$progress%"
        binding.tvSyncDetails.text = message
    }

    private suspend fun uploadLocalBooks(): Int =
            withContext(Dispatchers.IO) {

                val dao = AppDatabase.get(applicationContext).bookDao()
                val bookIds = dao.getAllBookIds()

                if (bookIds.isEmpty()) {
                    return@withContext 0
                }


                val localBooks = dao.getByIds(bookIds)
                var uploadedCount = 0

                localBooks.forEach { book ->
                    try {
                        syncRepo.pushBook(book = book, uploadFile = true)
                        uploadedCount++
                    } catch (e: Exception) {
                    }
                }

                return@withContext uploadedCount
            }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
