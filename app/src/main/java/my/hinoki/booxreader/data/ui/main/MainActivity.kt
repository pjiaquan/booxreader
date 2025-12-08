package my.hinoki.booxreader.data.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.auth.LoginActivity
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.repo.BookRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.ui.reader.ReaderActivity
import my.hinoki.booxreader.databinding.ActivityMainBinding
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val syncRepo by lazy { UserSyncRepository(applicationContext) }
    private val bookRepository by lazy { BookRepository(applicationContext, syncRepo) }
    private val recentAdapter by lazy { RecentBooksAdapter(emptyList()) { openBook(it) } }

    private val pickEpub =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                takePersistable(it)
                openBookFromUri(it)
            }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "檔案權限已授予", Toast.LENGTH_SHORT).show()
                // 權限授予後執行同步
                performFullSync()
            } else {
                val deniedCount = permissions.count { !it.value }
                Toast.makeText(this, "需要檔案權限才能下載和顯示書籍 ($deniedCount 個權限被拒絕)", Toast.LENGTH_LONG).show()
                showPermissionRationale()
            }
        }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if user is signed in
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            // User not signed in, redirect to login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenEpub.setOnClickListener {
            pickEpub.launch(arrayOf("application/epub+zip"))
        }

        binding.btnProfile.setOnClickListener {
            startActivity(Intent(this, my.hinoki.booxreader.data.auth.UserProfileActivity::class.java))
        }

        binding.recyclerRecent.layoutManager = LinearLayoutManager(this)
        binding.recyclerRecent.adapter = recentAdapter

        // Start observing books flow
        observeRecentBooks()

        // Check and request file permissions
        checkAndRequestFilePermissions()
    }

    override fun onResume() {
        super.onResume()
        // Check if user is still signed in
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Note: Books are now automatically observed via Flow
        // No need to manually call loadRecentBooks() here
    }

    private fun performFullSync() {
        lifecycleScope.launch {
            try {
                android.util.Log.d("MainActivity", "開始執行完整同步...")
                Toast.makeText(this@MainActivity, "開始同步資料...", Toast.LENGTH_SHORT).show()

                // Sync all user data
                val settingsResult = runCatching { syncRepo.pullSettingsIfNewer() }
                android.util.Log.d("MainActivity", "同步設定: ${settingsResult.isSuccess}")

                val booksResult = runCatching { syncRepo.pullBooks() }
                val booksUpdated = booksResult.getOrNull() ?: 0
                android.util.Log.d("MainActivity", "同步書籍: ${booksResult.isSuccess}, 更新數量: $booksUpdated")
                if (booksUpdated > 0) {
                    Toast.makeText(this@MainActivity, "已同步 $booksUpdated 本書籍", Toast.LENGTH_SHORT).show()
                }

                val notesResult = runCatching { syncRepo.pullNotes() }
                val notesUpdated = notesResult.getOrNull() ?: 0
                android.util.Log.d("MainActivity", "同步筆記: ${notesResult.isSuccess}, 更新數量: $notesUpdated")

                val progressResult = runCatching { syncRepo.pullAllProgress() }
                val progressUpdated = progressResult.getOrNull() ?: 0
                android.util.Log.d("MainActivity", "同步進度: ${progressResult.isSuccess}, 更新數量: $progressUpdated")

                val bookmarksResult = runCatching { syncRepo.pullBookmarks() }
                val bookmarksUpdated = bookmarksResult.getOrNull() ?: 0
                android.util.Log.d("MainActivity", "同步書籤: ${bookmarksResult.isSuccess}, 更新數量: $bookmarksUpdated")

                android.util.Log.d("MainActivity", "同步完成")
                Toast.makeText(this@MainActivity, "同步完成", Toast.LENGTH_SHORT).show()

                // 同步完成後重新載入最近閱讀
                loadRecentBooks()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "同步失敗", e)
                Toast.makeText(this@MainActivity, "同步失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasFilePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses READ_MEDIA_IMAGES for EPUB files
            // We only need read permission for accessing EPUB files
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12, no permission needed for app-specific files
            // But we still need READ_EXTERNAL_STORAGE for accessing EPUB files outside app dir
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10 and below
            // Need both read and write for EPUB file access
            val hasRead = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val hasWrite = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
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
        android.util.Log.d("MainActivity", "檢查檔案權限...")
        val hasPerms = hasFilePermissions()
        android.util.Log.d("MainActivity", "檔案權限狀態: $hasPerms")

        if (hasPerms) {
            // Already have permissions, perform sync
            android.util.Log.d("MainActivity", "已有檔案權限，開始同步")
            performFullSync()
            // Note: Books are now automatically observed via Flow
            // No need to manually call loadRecentBooks() here
        } else {
            // Request permissions
            android.util.Log.d("MainActivity", "缺少檔案權限，請求權限")
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
        val needsRationale = permissionsToRequest.any { permission ->
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
        val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "應用程式需要檔案權限才能：\n\n" +
            "• 讀取EPUB電子書檔案\n" +
            "• 下載雲端同步的書籍到本地裝置\n" +
            "• 顯示最近閱讀記錄\n" +
            "• 儲存下載的書籍檔案\n\n" +
            "這些權限僅用於書籍檔案管理，不會存取您的個人檔案。"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "應用程式需要檔案讀取權限才能：\n\n" +
            "• 存取EPUB電子書檔案\n" +
            "• 下載雲端書籍到本地儲存\n" +
            "• 顯示您的閱讀記錄\n\n" +
            "應用程式會將下載的書籍儲存在應用專用目錄中。"
        } else {
            "應用程式需要檔案讀寫權限才能：\n\n" +
            "• 讀取EPUB電子書檔案\n" +
            "• 下載並儲存書籍到本地裝置\n" +
            "• 管理您的書籍收藏\n" +
            "• 同步閱讀進度\n\n" +
            "這些權限對於書籍管理功能是必要的。"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要檔案權限")
            .setMessage(message)
            .setPositiveButton("授予權限") { _, _ ->
                requestFilePermissions()
            }
            .setNegativeButton("稍後再說") { _, _ ->
                Toast.makeText(this, "您可以在設定中手動授予權限", Toast.LENGTH_LONG).show()
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
                    recentAdapter.submit(recent)
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
        } catch (_: Exception) {
        }
    }

    private fun openBookFromUri(uri: Uri) {
        try {
            ReaderActivity.open(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, "無法開啟檔案: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
                    val localUri = syncRepo.ensureBookFileAvailable(entity.bookId, originalUri = entity.fileUri)
                    if (localUri != null) {
                        ReaderActivity.open(this@MainActivity, localUri)
                    } else {
                        Toast.makeText(this@MainActivity, "書籍檔案不存在", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "無法開啟檔案: ${e.message}", Toast.LENGTH_LONG).show()
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
}
