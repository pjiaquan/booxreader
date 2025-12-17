package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.db.AiNoteEntity
import my.hinoki.booxreader.data.db.AiProfileEntity
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.reader.LocatorJsonHelper
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Syncs user-specific data (settings, reading progress, AI notes) to Firebase Firestore
 * so it can roam across devices. Falls back to no-ops when the user is not signed in.
 */
class UserSyncRepository(
    context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
 ) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private val db = AppDatabase.get(context)
    private val io = Dispatchers.IO
    private val storage = FirebaseStorage.getInstance()

    // --- Public API ---

    suspend fun pullSettingsIfNewer(): ReaderSettings? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val snapshot = runCatching {
            ref.collection(COL_SETTINGS).document(DOC_READER_SETTINGS).get().await()
        }.getOrElse { return@withContext null }

        if (!snapshot.exists()) return@withContext null
        
        val remote = try {
            snapshot.toObject(RemoteReaderSettings::class.java)
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to parse remote ReaderSettings", e)
            null
        } ?: return@withContext null
        
        // Map remote string ID to local long ID
        var localProfileId = -1L
        var activeProfile: AiProfileEntity? = null
        
        if (!remote.activeProfileId.isNullOrBlank()) {
             val profile = db.aiProfileDao().getByRemoteId(remote.activeProfileId)
             if (profile != null) {
                 localProfileId = profile.id
                 activeProfile = profile
             }
        }

        val local = ReaderSettings.fromPrefs(prefs)
        
        if (remote.updatedAt > local.updatedAt) {
            var mergedSettings = remote.toLocal(localProfileId)
            
            // If we switched to a profile (or the remote says we are on a profile),
            // we must apply that profile's values to the local settings immediately.
            // otherwise the app will have the new ID but old cached API key/model.
            if (activeProfile != null) {
                android.util.Log.i("UserSyncRepository", "Applying active profile settings: ${activeProfile.name}")
                mergedSettings = mergedSettings.copy(
                    apiKey = activeProfile.apiKey,
                    aiModelName = activeProfile.modelName,
                    serverBaseUrl = activeProfile.serverBaseUrl,
                    aiSystemPrompt = activeProfile.systemPrompt,
                    aiUserPromptTemplate = activeProfile.userPromptTemplate,
                    useStreaming = activeProfile.useStreaming,
                    enableGoogleSearch = activeProfile.enableGoogleSearch,
                    temperature = activeProfile.temperature,
                    maxTokens = activeProfile.maxTokens,
                    topP = activeProfile.topP,
                    frequencyPenalty = activeProfile.frequencyPenalty,
                    presencePenalty = activeProfile.presencePenalty,
                    assistantRole = activeProfile.assistantRole
                )
            }
            
            mergedSettings.saveTo(prefs)
            return@withContext mergedSettings
        } else {
            return@withContext null
        }
    }

    suspend fun pushSettings(settings: ReaderSettings = ReaderSettings.fromPrefs(prefs)) {
        val ref = userDoc() ?: return
        
        // Map local long ID to remote string ID
        var remoteProfileId: String? = null
        if (settings.activeProfileId > 0) {
            val profile = db.aiProfileDao().getById(settings.activeProfileId)
            remoteProfileId = profile?.remoteId
        }
        
        val payload = RemoteReaderSettings.fromLocal(settings, remoteProfileId)
            .copy(updatedAt = System.currentTimeMillis())
            
        withContext(io) {
            runCatching {
                ref.collection(COL_SETTINGS)
                    .document(DOC_READER_SETTINGS)
                    .set(payload, SetOptions.merge())
                    .await()
            }
        }
    }

    fun getCachedProgress(bookId: String): String? {
        return prefs.getString(progressKey(bookId), null)
    }

    fun cacheProgress(bookId: String, locatorJson: String, updatedAt: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(progressKey(bookId), locatorJson)
            .putLong(progressTimestampKey(bookId), updatedAt)
            .apply()
    }

    suspend fun pullProgress(bookId: String) = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        android.util.Log.d("ReaderDebug", "Pulling progress for bookId: '$bookId'")
        val snapshot = runCatching {
            ref.collection(COL_PROGRESS).document(bookId).get().await()
        }.getOrElse { 
             android.util.Log.e("ReaderDebug", "Pull progress failed", it)
             return@withContext null 
        }

        if (!snapshot.exists()) {
             android.util.Log.d("ReaderDebug", "Progress doc does not exist for $bookId")
             return@withContext null
        }
        val remote = snapshot.toObject(RemoteProgress::class.java) ?: return@withContext null
        
        android.util.Log.d("ReaderDebug", "Got remote progress: ${remote.locatorJson}")

        if (remote.locatorJson.isBlank()) return@withContext null

        val localTs = prefs.getLong(progressTimestampKey(bookId), 0)
        if (remote.updatedAt > localTs) {
            android.util.Log.d("ReaderDebug", "Remote is newer ($remote.updatedAt > $localTs), caching.")
            cacheProgress(bookId, remote.locatorJson, remote.updatedAt)
            runCatching {
                db.bookDao().updateProgress(bookId, remote.locatorJson, remote.updatedAt)
            }
        } else {
             android.util.Log.d("ReaderDebug", "Remote is not newer ($remote.updatedAt <= $localTs)")
        }
        remote.locatorJson
    }

    suspend fun pushProgress(
        bookId: String,
        locatorJson: String,
        bookTitle: String? = null
    ) = withContext(io) {
        val ref = userDoc() ?: return@withContext
        val now = System.currentTimeMillis()
        val payload = RemoteProgress(
            bookId = bookId,
            locatorJson = locatorJson,
            bookTitle = bookTitle,
            updatedAt = now
        )
        runCatching {
            ref.collection(COL_PROGRESS)
                .document(bookId)
                .set(payload, SetOptions.merge())
                .await()
        }
        cacheProgress(bookId, locatorJson, now)
    }

    suspend fun pushBook(book: BookEntity, uploadFile: Boolean = false) = withContext(io) {
        android.util.Log.d("UserSyncRepository", "開始上傳書籍到Firestore: ${book.title} (${book.bookId})")

        val ref = userDoc()
        if (ref == null) {
            android.util.Log.w("UserSyncRepository", "userDoc() 返回 null，無法上傳書籍")
            return@withContext
        }

        android.util.Log.d("UserSyncRepository", "用戶文檔: ${ref.path}")
        val storageRef = bookStorageRef(book.bookId)
        val remoteMeta = runCatching { storageRef?.metadata?.await() }.getOrNull()
        val shouldUploadFile = uploadFile || remoteMeta == null || remoteMeta.sizeBytes <= 0

        val uploadInfo = if (shouldUploadFile) {
            android.util.Log.d("UserSyncRepository", "嘗試上傳EPUB文件到Storage (shouldUpload=$shouldUploadFile)")
            uploadBookFileIfNeeded(book, remoteMeta)
        } else {
            android.util.Log.d("UserSyncRepository", "Storage 已有檔案，僅更新元數據: ${book.title}")
            remoteMeta?.let {
                UploadedBookInfo(
                    storageRef?.path ?: "",
                    it.sizeBytes,
                    it.getCustomMetadata("checksum")
                )
            }
        }

        val storagePath = uploadInfo?.storagePath
            ?: remoteMeta?.let { storageRef?.path }
            ?: storageRef?.path.takeIf { uploadFile }
        val storedSize = uploadInfo?.size ?: remoteMeta?.sizeBytes ?: 0L
        val storedChecksum = uploadInfo?.checksum ?: remoteMeta?.getCustomMetadata("checksum")
        val updatedAt = if (book.lastOpenedAt > 0) book.lastOpenedAt else System.currentTimeMillis()
        val payload = RemoteBook(
            bookId = book.bookId,
            title = book.title,
            fileUri = book.fileUri,
            lastLocatorJson = book.lastLocatorJson,
            lastOpenedAt = book.lastOpenedAt,
            updatedAt = updatedAt,
            storagePath = storagePath,
            fileSize = storedSize,
            checksumSha256 = storedChecksum
        )

        android.util.Log.d("UserSyncRepository", "上傳書籍數據: $payload")

        runCatching {
            ref.collection(COL_BOOKS)
                .document(book.bookId)
                .set(payload, SetOptions.merge())
                .await()
        }.onSuccess {
            android.util.Log.d("UserSyncRepository", "書籍上傳成功: ${book.title}")
        }.onFailure {
            android.util.Log.e("UserSyncRepository", "書籍上傳失敗: ${book.title}", it)
        }
    }

    suspend fun softDeleteBook(bookId: String): Boolean = withContext(io) {
        val ref = userDoc() ?: return@withContext true // Not signed in, safe to delete locally
        val updates = mapOf(
            "deleted" to true,
            "updatedAt" to System.currentTimeMillis()
        )
        return@withContext runCatching {
            // 1. Mark as deleted in Firestore (Tombstone)
            ref.collection(COL_BOOKS)
                .document(bookId)
                .set(updates, SetOptions.merge())
                .await()
            android.util.Log.d("UserSyncRepository", "書籍已標記為刪除 (Soft Delete): $bookId")

            // 2. Delete file from Storage (Best effort)
            val storageRef = bookStorageRef(bookId)
            if (storageRef != null) {
                runCatching {
                    storageRef.delete().await()
                    android.util.Log.d("UserSyncRepository", "書籍檔案已從Storage刪除: $bookId")
                }.onFailure {
                    android.util.Log.w("UserSyncRepository", "從Storage刪除檔案失敗 (可能已不存在): $bookId", it)
                }
            }
            true
        }.getOrElse {
            android.util.Log.e("UserSyncRepository", "標記書籍刪除失敗: $bookId", it)
            false
        }
    }

    suspend fun pullBooks(): Int = withContext(io) {
        val ref = userDoc()
        if (ref == null) {
            android.util.Log.w("UserSyncRepository", "userDoc() 返回 null，用戶可能未登入")
            return@withContext 0
        }

        android.util.Log.d("UserSyncRepository", "開始從Firebase拉取書籍數據，用戶文檔: ${ref.path}")
        val dao = db.bookDao()

        val snapshot = runCatching {
            ref.collection(COL_BOOKS).get().await()
        }.getOrElse {
            android.util.Log.e("UserSyncRepository", "獲取書籍數據失敗", it)
            return@withContext 0
        }

        android.util.Log.d("UserSyncRepository", "從Firebase獲取到 ${snapshot.documents.size} 本書籍")

        var updatedCount = 0
        var downloadedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteBook::class.java) ?: return@forEach
            if (remote.bookId.isBlank()) return@forEach

            android.util.Log.d("UserSyncRepository", "處理書籍: ${remote.title} (ID: ${remote.bookId})")

            if (remote.deleted) {
                // Cloud says deleted -> Delete locally
                android.util.Log.d("UserSyncRepository", "書籍已被標記為刪除 (Soft Delete)，移除本地紀錄: ${remote.title}")
                dao.deleteById(remote.bookId)
                updatedCount++
                return@forEach
            }

            android.util.Log.d("UserSyncRepository", "書籍數據: storagePath=${remote.storagePath}, checksum=${remote.checksumSha256}, fileUri=${remote.fileUri}")

            val existing = dao.getByIds(listOf(remote.bookId)).firstOrNull()
            val remoteLastOpened = if (remote.lastOpenedAt > 0) remote.lastOpenedAt else remote.updatedAt
            val remoteUpdatedAt = maxOf(remote.updatedAt, remoteLastOpened)

            // Try to download EPUB file if available in storage
            var fileUri = remote.fileUri
            var fileDownloaded = false

            // 總是嘗試下載書籍，即使沒有storagePath或checksum
            // 使用bookId來構建默認的storage路徑
            android.util.Log.d("UserSyncRepository", "嘗試下載書籍: ${remote.title} (${remote.bookId})")
            val localUri = ensureBookFileAvailable(remote.bookId, remote.storagePath)
            if (localUri != null) {
                fileUri = localUri.toString()
                fileDownloaded = true
                downloadedCount++
                android.util.Log.d("UserSyncRepository", "書籍下載成功: ${remote.title}")
            } else {
                android.util.Log.w("UserSyncRepository", "書籍下載失敗: ${remote.title}")
                // 如果下載失敗，但remote.fileUri是有效的，仍然使用它
                if (remote.fileUri.isNotBlank() && remote.fileUri.startsWith("content://")) {
                    android.util.Log.d("UserSyncRepository", "使用原始fileUri: ${remote.fileUri}")
                }
            }

            // DUPLICATE DETECTION & MIGRATION
            var migrationTarget: BookEntity? = null
            if (existing == null && !remote.checksumSha256.isNullOrBlank()) {
                // Aggressive Scan: Check ALL local books for checksum match
                // This handles cases where Titles differ (filename vs metadata) but content is identical.
                val allLocalBooks = dao.getAllBooks()
                
                for (candidate in allLocalBooks) {
                    // Skip if IDs somehow match (should be handled by 'existing' check, but safety first)
                    if (candidate.bookId == remote.bookId) continue
                    
                    try {
                        val uri = Uri.parse(candidate.fileUri)
                        val meta = readLocalFileMeta(uri)
                        if (meta != null && meta.checksum == remote.checksumSha256) {
                            migrationTarget = candidate
                            android.util.Log.i("UserSyncRepository", "發現重複書籍 (全庫掃描 Checksum匹配): ${candidate.title}. 準備遷移 ID: ${candidate.bookId} -> ${remote.bookId}")
                            break
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("UserSyncRepository", "檢查候選書籍失敗: ${candidate.title}", e)
                    }
                }
            }

            // SMART MERGE: Prevent overwriting valid local progress with "0%" remote stub
            var finalLocatorJson = remote.lastLocatorJson
            var finalLastOpenedAt = remoteLastOpened
            
            // Use existing OR migration target as the base for comparison
            val baseLocalBook = existing ?: migrationTarget

            if (baseLocalBook != null) {
                val remoteLoc = LocatorJsonHelper.fromJson(remote.lastLocatorJson)
                val localLoc = LocatorJsonHelper.fromJson(baseLocalBook.lastLocatorJson)
                
                val remoteProg = remoteLoc?.locations?.totalProgression ?: 0.0
                val localProg = localLoc?.locations?.totalProgression ?: 0.0
                
                if (localProg > 0.01 && remoteProg < 0.01) {
                    android.util.Log.w("UserSyncRepository", "防止進度回退: 保留本地進度 ${(localProg*100).toInt()}% (雲端為 ${(remoteProg*100).toInt()}%)")
                    finalLocatorJson = baseLocalBook.lastLocatorJson
                    finalLastOpenedAt = maxOf(baseLocalBook.lastOpenedAt, remoteLastOpened)
                }
            }

            val entity = BookEntity(
                bookId = remote.bookId,
                title = remote.title,
                fileUri = if (fileUri.isNotBlank()) fileUri else (migrationTarget?.fileUri ?: remote.bookId),
                lastLocatorJson = finalLocatorJson,
                lastOpenedAt = finalLastOpenedAt
            )

            if (existing != null) {
                // Update existing
                if (remoteUpdatedAt > existing.lastOpenedAt) {
                    dao.update(entity.copy(bookId = existing.bookId))
                    updatedCount++
                    android.util.Log.d("UserSyncRepository", "更新書籍資料: ${remote.title}")
                }
            } else if (migrationTarget != null) {
                // Perform Migration
                android.util.Log.i("UserSyncRepository", "執行書籍遷移: ${migrationTarget.title}")
                
                // 1. Update Foreign Keys
                db.aiNoteDao().migrateBookId(migrationTarget.bookId, remote.bookId)
                db.bookmarkDao().migrateBookId(migrationTarget.bookId, remote.bookId)
                
                // 2. Insert NEW entity (with Remote ID but Local File URI)
                dao.insert(entity)
                
                // 3. Delete OLD entity
                dao.deleteById(migrationTarget.bookId)
                
                updatedCount++
                android.util.Log.i("UserSyncRepository", "書籍遷移完成")
            } else {
                // Insert New
                dao.insert(entity)
                updatedCount++
                android.util.Log.d("UserSyncRepository", "新增書籍到資料庫: ${remote.title}")
            }
        }

        val storageDownloads = runCatching { syncStorageBooks() }.getOrElse {
            android.util.Log.e("UserSyncRepository", "掃描Storage書籍失敗", it)
            0
        }

        android.util.Log.d("UserSyncRepository", "同步書籍完成: 更新${updatedCount + storageDownloads}筆, 下載${downloadedCount + storageDownloads}個檔案 (Storage新增$storageDownloads)")
        updatedCount + storageDownloads
    }

    /**
     * 掃描 Firebase Storage 內的書籍，若本地沒有可讀檔案則下載並建立/更新資料庫紀錄
     */
    private suspend fun syncStorageBooks(): Int = withContext(io) {
        val user = auth.currentUser ?: return@withContext 0
        val dao = db.bookDao()

        val booksRoot = storage.reference.child("users/${user.uid}/books")
        val listResult = runCatching { booksRoot.listAll().await() }.getOrElse {
            android.util.Log.e("UserSyncRepository", "列出Storage書籍失敗", it)
            return@withContext 0
        }

        var downloaded = 0

        listResult.items.forEach { item ->
            val bookId = decodeBookIdFromStorageName(item.name) ?: return@forEach
            val storagePath = item.path
            val existing = dao.getByIds(listOf(bookId)).firstOrNull()

            if (hasReadableLocalCopy(existing)) {
                android.util.Log.d("UserSyncRepository", "本地已存在可讀書籍，跳過下載: $bookId")
                return@forEach
            }

            val localUri = ensureBookFileAvailable(bookId, storagePath, existing?.fileUri) ?: run {
                android.util.Log.w("UserSyncRepository", "無法從Storage下載書籍: $storagePath")
                return@forEach
            }

            val meta = runCatching { item.metadata.await() }.getOrNull()
            val title = meta?.getCustomMetadata("title")
                ?: existing?.title
                ?: "雲端書籍"
            val updatedAt = meta?.updatedTimeMillis?.takeIf { it > 0 }
                ?: meta?.creationTimeMillis?.takeIf { it > 0 }
                ?: existing?.lastOpenedAt
                ?: System.currentTimeMillis()

            val entity = BookEntity(
                bookId = bookId,
                title = title,
                fileUri = localUri.toString(),
                lastLocatorJson = existing?.lastLocatorJson,
                lastOpenedAt = updatedAt
            )

            if (existing == null) {
                dao.insert(entity)
                android.util.Log.d("UserSyncRepository", "從Storage新增書籍: $bookId, path=$storagePath")
            } else {
                dao.update(entity.copy(lastLocatorJson = existing.lastLocatorJson, lastOpenedAt = maxOf(existing.lastOpenedAt, updatedAt)))
                android.util.Log.d("UserSyncRepository", "從Storage更新書籍檔案: $bookId, path=$storagePath")
            }

            downloaded++
        }

        downloaded
    }

    private suspend fun uploadBookFileIfNeeded(
        book: BookEntity,
        existingMeta: StorageMetadata? = null
    ): UploadedBookInfo? = withContext(io) {
        val storageRef = bookStorageRef(book.bookId) ?: return@withContext null
        val uri = Uri.parse(book.fileUri)
        val localMeta = readLocalFileMeta(uri) ?: run {
            android.util.Log.w("UserSyncRepository", "讀取本地書籍檔案失敗，無法上傳: ${book.fileUri}")
            return@withContext null
        }

        val remoteMeta = existingMeta ?: runCatching { storageRef.metadata.await() }.getOrNull()
        val remoteChecksum = remoteMeta?.getCustomMetadata("checksum")
        if (remoteMeta != null) {
            // 雲端已有檔案就不再重複上傳，透過checksum或大小判斷
            if (remoteChecksum != null && remoteChecksum == localMeta.checksum) {
                android.util.Log.d("UserSyncRepository", "Storage 已有相同檔案，跳過上傳: ${book.title}")
                return@withContext UploadedBookInfo(storageRef.path, localMeta.size, remoteChecksum)
            }
            if (remoteMeta.sizeBytes > 0 && remoteMeta.sizeBytes == localMeta.size) {
                android.util.Log.d("UserSyncRepository", "Storage 已有同尺寸檔案（無/不同checksum），略過重複上傳: ${book.title}")
                return@withContext UploadedBookInfo(storageRef.path, remoteMeta.sizeBytes, remoteChecksum)
            }
            android.util.Log.d("UserSyncRepository", "Storage 已有檔案但校驗不符，重新上傳: ${book.title}")
        }

        val metadata = StorageMetadata.Builder()
            .setContentType("application/epub+zip")
            .setCustomMetadata("bookId", book.bookId)
            .setCustomMetadata("checksum", localMeta.checksum ?: "")
            .build()

        val resolver = appContext.contentResolver
        resolver.openInputStream(uri)?.use { stream ->
            runCatching { storageRef.putStream(stream, metadata).await() }
                .getOrElse { return@withContext null }
        } ?: return@withContext null

        UploadedBookInfo(storageRef.path, localMeta.size, localMeta.checksum)
    }

    private fun bookStorageRef(bookId: String) =
        auth.currentUser?.let { user ->
            if (bookId.isBlank()) return@let null
            val safeId = Base64.encodeToString(bookId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            storage.reference.child("users/${user.uid}/books/$safeId.epub")
        }

    private fun readLocalFileMeta(uri: Uri): LocalFileMeta? {
        val resolver = appContext.contentResolver
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
                size += read
            }
        } ?: return null
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return LocalFileMeta(size = size, checksum = checksum)
    }

    /**
     * Download EPUB file from Firebase Storage to local storage
     */
    suspend fun downloadBookFile(bookId: String, storagePath: String? = null): Uri? = withContext(io) {
        val user = auth.currentUser ?: return@withContext null
        val storageRef = if (!storagePath.isNullOrBlank()) {
            storage.reference.child(storagePath)
        } else {
            bookStorageRef(bookId)
        } ?: return@withContext null

        android.util.Log.d("UserSyncRepository", "開始下載書籍檔案: bookId=$bookId, storagePath=$storagePath")

        try {
            // Use app-specific files directory for permanent storage
            val filesDir = appContext.getExternalFilesDir("books") ?: appContext.filesDir
            val booksDir = File(filesDir, "downloaded")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            val safeId = android.util.Base64.encodeToString(bookId.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val fileName = "book_${safeId}_${System.currentTimeMillis()}.epub"
            val localFile = File(booksDir, fileName)

            android.util.Log.d("UserSyncRepository", "下載路徑: ${localFile.absolutePath}")

            // Download file
            storageRef.getFile(localFile).await()

            // Verify download
            if (localFile.exists() && localFile.length() > 0) {
                val fileSize = localFile.length()
                android.util.Log.d("UserSyncRepository", "書籍下載成功: ${localFile.name}, 大小: $fileSize bytes")

                // Create content URI using FileProvider for security
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Use FileProvider for Android 7+
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        localFile
                    )
                } else {
                    Uri.fromFile(localFile)
                }

                // Also store in cache for quick access
                val cacheDir = appContext.cacheDir
                val cacheFile = File(cacheDir, "book_${bookId}.epub")
                localFile.copyTo(cacheFile, overwrite = true)

                android.util.Log.d("UserSyncRepository", "書籍已儲存到快取: ${cacheFile.absolutePath}")
                return@withContext uri
            } else {
                android.util.Log.w("UserSyncRepository", "下載的檔案不存在或為空")
            }
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "下載書籍失敗", e)
            e.printStackTrace()
        }

        return@withContext null
    }

    /**
     * Check if book file exists locally, download if missing
     */
    suspend fun ensureBookFileAvailable(bookId: String, storagePath: String? = null, originalUri: String? = null): Uri? = withContext(io) {
        android.util.Log.d("UserSyncRepository", "確保書籍檔案可用: bookId=$bookId, storagePath=$storagePath, originalUri=$originalUri")

        // First, check if the original URI is still accessible
        if (!originalUri.isNullOrBlank()) {
            try {
                val uri = Uri.parse(originalUri)
                val resolver = appContext.contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    android.util.Log.d("UserSyncRepository", "原始URI仍然可訪問: $originalUri")
                    return@withContext uri
                }
            } catch (e: Exception) {
                android.util.Log.d("UserSyncRepository", "原始URI無法訪問: ${e.message}")
            }
        }

        // Check multiple possible locations for the book file
        val possibleLocations = mutableListOf<File>()

        // 1. Check cache directory (quick access)
        val cacheDir = appContext.cacheDir
        val cacheFile = File(cacheDir, "book_${bookId}.epub")
        possibleLocations.add(cacheFile)

        // 2. Check downloaded books directory (permanent storage)
        val filesDir = appContext.getExternalFilesDir("books") ?: appContext.filesDir
        val booksDir = File(filesDir, "downloaded")
        if (booksDir.exists()) {
            booksDir.listFiles()?.forEach { file ->
                if (file.name.contains(bookId) && file.name.endsWith(".epub")) {
                    possibleLocations.add(file)
                }
            }
        }

        // 3. Check for any file with bookId in name
        val externalFilesDir = appContext.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            externalFilesDir.walk().maxDepth(3).forEach { file ->
                if (file.isFile && file.name.contains(bookId) && file.name.endsWith(".epub")) {
                    possibleLocations.add(file)
                }
            }
        }

        // Check all possible locations
        for (file in possibleLocations) {
            if (file.exists() && file.length() > 0) {
                android.util.Log.d("UserSyncRepository", "找到書籍檔案: ${file.absolutePath}, 大小: ${file.length()} bytes")

                // Create URI using FileProvider for security
                return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    androidx.core.content.FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                } else {
                    Uri.fromFile(file)
                }
            }
        }

        android.util.Log.d("UserSyncRepository", "書籍檔案不存在於本地，開始從雲端下載...")

        // Download from storage
        val result = downloadBookFile(bookId, storagePath)
        if (result != null) {
            android.util.Log.d("UserSyncRepository", "書籍下載成功: ${result}")
        } else {
            android.util.Log.w("UserSyncRepository", "書籍下載失敗")
        }
        return@withContext result
    }

    suspend fun pushNote(note: AiNoteEntity): AiNoteEntity? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val now = System.currentTimeMillis()
        val remoteId = note.remoteId ?: ref.collection(COL_AI_NOTES).document().id
        val payload = RemoteAiNote(
            remoteId = remoteId,
            bookId = note.bookId,
            bookTitle = note.bookTitle,
            originalText = note.originalText,
            aiResponse = note.aiResponse,
            locatorJson = note.locatorJson,
            createdAt = note.createdAt,
            updatedAt = now
        )

        runCatching {
            ref.collection(COL_AI_NOTES)
                .document(remoteId)
                .set(payload, SetOptions.merge())
                .await()
        }

        val updated = note.copy(remoteId = remoteId, updatedAt = now)
        val dao = db.aiNoteDao()
        if (updated.id == 0L) {
            val newId = dao.insert(updated)
            updated.copy(id = newId)
        } else {
            dao.update(updated)
            updated
        }
    }

    suspend fun pullNotes(): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val dao = db.aiNoteDao()

        // First push any local-only notes so they get a remoteId
        runCatching {
            dao.getLocalOnly().forEach { local ->
                pushNote(local)
            }
        }

        val snapshot = runCatching {
            ref.collection(COL_AI_NOTES).get().await()
        }.getOrElse { return@withContext 0 }

        var updatedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteAiNote::class.java) ?: return@forEach
            if (remote.remoteId.isBlank()) return@forEach

            val existing = dao.getByRemoteId(remote.remoteId)
            val entity = AiNoteEntity(
                id = existing?.id ?: 0,
                remoteId = remote.remoteId,
                bookId = remote.bookId,
                bookTitle = remote.bookTitle,
                originalText = remote.originalText,
                aiResponse = remote.aiResponse,
                locatorJson = remote.locatorJson,
                createdAt = if (remote.createdAt > 0) remote.createdAt else (existing?.createdAt ?: System.currentTimeMillis()),
                updatedAt = remote.updatedAt
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
            } else if (remote.updatedAt > existing.updatedAt) {
                dao.update(entity)
                updatedCount++
            }
        }

        updatedCount
    }

    suspend fun pushBookmark(entity: BookmarkEntity): BookmarkEntity? = withContext(io) {
        val ref = userDoc() ?: return@withContext null
        val now = System.currentTimeMillis()
        val remoteId = entity.remoteId ?: ref.collection(COL_BOOKMARKS).document().id
        val payload = RemoteBookmark(
            remoteId = remoteId,
            bookId = entity.bookId,
            locatorJson = entity.locatorJson,
            createdAt = entity.createdAt,
            updatedAt = now
        )

        runCatching {
            ref.collection(COL_BOOKMARKS)
                .document(remoteId)
                .set(payload, SetOptions.merge())
                .await()
        }

        val updated = entity.copy(remoteId = remoteId, isSynced = true, updatedAt = now)
        val dao = db.bookmarkDao()
        if (updated.id == 0L) {
            val newId = dao.insert(updated)
            updated.copy(id = newId)
        } else {
            dao.update(updated)
            updated
        }
    }

    suspend fun pullBookmarks(bookId: String? = null): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        val dao = db.bookmarkDao()

        // Push local-only first to get remoteId
        runCatching {
            dao.getLocalOnly().forEach { local ->
                pushBookmark(local)
            }
        }

        val query = if (bookId != null) {
            ref.collection(COL_BOOKMARKS).whereEqualTo("bookId", bookId)
        } else {
            ref.collection(COL_BOOKMARKS)
        }

        val snapshot = runCatching { query.get().await() }.getOrElse { return@withContext 0 }
        var updatedCount = 0

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteBookmark::class.java) ?: return@forEach
            if (remote.remoteId.isBlank()) return@forEach

            val existing = dao.getByRemoteId(remote.remoteId)
            val entity = BookmarkEntity(
                id = existing?.id ?: 0,
                remoteId = remote.remoteId,
                bookId = remote.bookId,
                locatorJson = remote.locatorJson,
                createdAt = if (remote.createdAt > 0) remote.createdAt else (existing?.createdAt ?: System.currentTimeMillis()),
                isSynced = true,
                updatedAt = remote.updatedAt
            )

            if (existing == null) {
                dao.insert(entity)
                updatedCount++
            } else if (remote.updatedAt > existing.updatedAt) {
                dao.update(entity)
                updatedCount++
            }
        }

        updatedCount
    }

    suspend fun pushProfile(profile: AiProfileEntity): AiProfileEntity? = withContext(io) {
        val ref = userDoc() ?: run {
            android.util.Log.w("UserSyncRepository", "pushProfile: User not signed in")
            return@withContext null
        }
        
        val now = System.currentTimeMillis()
        val remoteId = profile.remoteId ?: ref.collection(COL_AI_PROFILES).document().id
        
        android.util.Log.d("UserSyncRepository", "Pushing profile '${profile.name}' (remoteId: $remoteId)")
        
        // Check for existing remote profile to handle conflicts
        val existingRemote = try {
            ref.collection(COL_AI_PROFILES).document(remoteId).get().await()
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to check existing remote profile: ${e.message}", e)
            null
        }
        
        // Encrypt the API key before uploading
        val sanitizedApiKey = SyncCrypto.encrypt(profile.apiKey)

        val payload = RemoteAiProfile(
            remoteId = remoteId,
            name = profile.name,
            modelName = profile.modelName,
            apiKey = sanitizedApiKey,
            serverBaseUrl = profile.serverBaseUrl,
            systemPrompt = profile.systemPrompt,
            userPromptTemplate = profile.userPromptTemplate,
            useStreaming = profile.useStreaming,
            enableGoogleSearch = profile.enableGoogleSearch,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            topP = profile.topP,
            frequencyPenalty = profile.frequencyPenalty,
            presencePenalty = profile.presencePenalty,
            assistantRole = profile.assistantRole,
            createdAt = profile.createdAt,
            updatedAt = now
        )

        try {
            ref.collection(COL_AI_PROFILES)
                .document(remoteId)
                .set(payload, SetOptions.merge())
                .await()
            android.util.Log.i("UserSyncRepository", "Successfully pushed profile '${profile.name}' to Firestore")
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to push profile '${profile.name}' to Firestore: ${e.message}", e)
            return@withContext null
        }

        val updated = profile.copy(remoteId = remoteId, isSynced = true, updatedAt = now)
        val dao = db.aiProfileDao()
        try {
            if (updated.id == 0L) {
                val newId = dao.insert(updated)
                android.util.Log.d("UserSyncRepository", "Inserted new profile with ID: $newId")
                updated.copy(id = newId)
            } else {
                dao.update(updated)
                android.util.Log.d("UserSyncRepository", "Updated existing profile with ID: ${updated.id}")
                updated
            }
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to update local database for profile '${profile.name}': ${e.message}", e)
            return@withContext null
        }
    }

    suspend fun pullProfiles(): Int = withContext(io) {
        val ref = userDoc() ?: run {
            android.util.Log.w("UserSyncRepository", "pullProfiles: User not signed in")
            return@withContext 0
        }
        val dao = db.aiProfileDao()

        android.util.Log.d("UserSyncRepository", "Starting pullProfiles operation")

        // Push local-only first
        try {
            val localProfiles = dao.getPendingSync()
            android.util.Log.d("UserSyncRepository", "Found ${localProfiles.size} local profiles pending sync to push first")
            localProfiles.forEach { local ->
                try {
                    pushProfile(local)
                    android.util.Log.i("UserSyncRepository", "Pushed pending profile: ${local.name}")
                } catch (e: Exception) {
                    android.util.Log.e("UserSyncRepository", "Failed to push pending profile '${local.name}': ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to push pending profiles: ${e.message}", e)
        }

        val snapshot = try {
            ref.collection(COL_AI_PROFILES).get().await()
        } catch (e: Exception) {
            android.util.Log.e("UserSyncRepository", "Failed to fetch remote profiles: ${e.message}", e)
            return@withContext 0
        }

        android.util.Log.d("UserSyncRepository", "Found ${snapshot.documents.size} remote profiles")
        var updatedCount = 0
        
        // 1. Collect all remote IDs
        val remoteIds = snapshot.documents.mapNotNull { it.id }.toSet()
        
        // 2. Find local profiles that have a remoteId but are missing from remoteIds (Deleted on Cloud)
        val allLocalProfiles = dao.getAllList()
        allLocalProfiles.forEach { local ->
            if (!local.remoteId.isNullOrBlank() && !remoteIds.contains(local.remoteId)) {
                android.util.Log.i("UserSyncRepository", "Deleting local profile '${local.name}' because it was removed from cloud.")
                dao.delete(local)
                updatedCount++
            }
        }

        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteAiProfile::class.java) ?: return@forEach
            if (remote.remoteId.isBlank()) return@forEach

            val existing = dao.getByRemoteId(remote.remoteId)
            
            // Enhanced conflict resolution strategy
            if (existing == null) {
                // New profile from cloud - add it
                android.util.Log.i("UserSyncRepository", "Adding new profile from cloud: ${remote.name}")
                val entity = AiProfileEntity(
                    id = 0, // Will be auto-generated
                    name = remote.name,
                    modelName = remote.modelName,
                    apiKey = SyncCrypto.decrypt(remote.apiKey),
                    serverBaseUrl = remote.serverBaseUrl,
                    systemPrompt = remote.systemPrompt,
                    userPromptTemplate = remote.userPromptTemplate,
                    useStreaming = remote.useStreaming,
                    temperature = remote.temperature,
                    maxTokens = remote.maxTokens,
                    topP = remote.topP,
                    frequencyPenalty = remote.frequencyPenalty,
                    presencePenalty = remote.presencePenalty,
                    assistantRole = remote.assistantRole,
                    enableGoogleSearch = remote.enableGoogleSearch,
                    remoteId = remote.remoteId,
                    createdAt = if (remote.createdAt > 0) remote.createdAt else System.currentTimeMillis(),
                    updatedAt = remote.updatedAt,
                    isSynced = true
                )
                try {
                    dao.insert(entity)
                    updatedCount++
                } catch (e: Exception) {
                    android.util.Log.e("UserSyncRepository", "Failed to insert new profile '${remote.name}': ${e.message}", e)
                }
            } else if (remote.updatedAt > existing.updatedAt) {
                // Remote is newer - update local (with conflict resolution)
                android.util.Log.i("UserSyncRepository", "Remote profile '${remote.name}' is newer (remote: ${remote.updatedAt}, local: ${existing.updatedAt})")
                // Strategy: Always prefer remote changes for most fields, but preserve local
                // sensitive data like API keys if they exist locally
                val entity = AiProfileEntity(
                    id = existing.id,
                    name = remote.name,
                    modelName = remote.modelName,
                    // Conflict resolution: prefer local API key if currently set, otherwise use remote (decrypted)
                    apiKey = if (existing.apiKey.isNotBlank()) existing.apiKey else SyncCrypto.decrypt(remote.apiKey),
                    serverBaseUrl = remote.serverBaseUrl,
                    systemPrompt = remote.systemPrompt,
                    userPromptTemplate = remote.userPromptTemplate,
                    useStreaming = remote.useStreaming,
                    temperature = remote.temperature,
                    maxTokens = remote.maxTokens,
                    topP = remote.topP,
                    frequencyPenalty = remote.frequencyPenalty,
                    presencePenalty = remote.presencePenalty,
                    assistantRole = remote.assistantRole,
                    enableGoogleSearch = remote.enableGoogleSearch,
                    remoteId = remote.remoteId,
                    createdAt = existing.createdAt, // Preserve original creation time
                    updatedAt = remote.updatedAt,
                    isSynced = true
                )
                try {
                    dao.update(entity)
                    updatedCount++
                } catch (e: Exception) {
                    android.util.Log.e("UserSyncRepository", "Failed to update existing profile '${remote.name}': ${e.message}", e)
                }
            } else if (existing.updatedAt > remote.updatedAt && !existing.isSynced) {
                // Local is newer and not synced - push to remote
                android.util.Log.i("UserSyncRepository", "Local profile '${existing.name}' is newer and not synced, pushing to remote")
                try {
                    pushProfile(existing)
                    updatedCount++
                } catch (e: Exception) {
                    android.util.Log.e("UserSyncRepository", "Failed to push newer local profile '${existing.name}': ${e.message}", e)
                }
            } else if (Math.abs(remote.updatedAt - existing.updatedAt) < 60000) {
                // Timestamps are very close (within 1 minute) - potential conflict
                android.util.Log.w("UserSyncRepository", "Potential conflict detected for profile '${existing.name}' - timestamps very close")
                // Strategy: Merge changes, preferring local changes for user-editable fields
                val mergedEntity = AiProfileEntity(
                    id = existing.id,
                    name = existing.name, // Prefer local name
                    modelName = existing.modelName, // Prefer local model
                    apiKey = if (existing.apiKey.isNotBlank()) existing.apiKey else SyncCrypto.decrypt(remote.apiKey), // Use local if available
                    serverBaseUrl = existing.serverBaseUrl, // Prefer local server
                    systemPrompt = existing.systemPrompt, // Prefer local prompts
                    userPromptTemplate = existing.userPromptTemplate,
                    useStreaming = existing.useStreaming,
                    temperature = existing.temperature,
                    maxTokens = existing.maxTokens,
                    topP = existing.topP,
                    frequencyPenalty = existing.frequencyPenalty,
                    presencePenalty = existing.presencePenalty,
                    assistantRole = existing.assistantRole,
                    enableGoogleSearch = existing.enableGoogleSearch,
                    remoteId = remote.remoteId,
                    createdAt = existing.createdAt,
                    updatedAt = System.currentTimeMillis(), // New timestamp for merged version
                    isSynced = false // Mark as not synced so it gets pushed back
                )
                try {
                    dao.update(mergedEntity)
                    // Push the merged version back to remote
                    pushProfile(mergedEntity)
                    updatedCount++
                } catch (e: Exception) {
                    android.util.Log.e("UserSyncRepository", "Failed to merge conflicting profile '${existing.name}': ${e.message}", e)
                }
            }
        }
        
        android.util.Log.i("UserSyncRepository", "pullProfiles completed: $updatedCount profiles updated")
        updatedCount
    }

    /**
     * Fetch all progress documents for the signed-in user and merge newer ones into local cache/DB.
     */
    suspend fun pullAllProgress(): Int = withContext(io) {
        val ref = userDoc() ?: return@withContext 0
        android.util.Log.d("UserSyncRepository", "開始同步閱讀進度 (pullAllProgress)")
        
        val snapshot = runCatching {
            ref.collection(COL_PROGRESS).get().await()
        }.getOrElse { 
            android.util.Log.e("UserSyncRepository", "獲取進度失敗", it)
            return@withContext 0 
        }

        android.util.Log.d("UserSyncRepository", "找到 ${snapshot.documents.size} 筆進度紀錄")

        var updated = 0
        snapshot.documents.forEach { doc ->
            val remote = doc.toObject(RemoteProgress::class.java) ?: return@forEach
            if (remote.bookId.isBlank() || remote.locatorJson.isBlank()) {
                android.util.Log.w("UserSyncRepository", "略過無效進度紀錄 (ID或JSON為空)")
                return@forEach
            }

            val localTs = prefs.getLong(progressTimestampKey(remote.bookId), 0)
            
            android.util.Log.d("UserSyncRepository", "檢查書籍[${remote.bookId.take(8)}...]: 雲端時間=${remote.updatedAt}, 本地時間=$localTs")
            android.util.Log.d("UserSyncRepository", "JSON預覽: ${remote.locatorJson.take(100)}...")

            // Force update if local is 0 (never synced) or remote is newer
            // Also lenient check: if remote > local - 1 minute (to handle clock drift slightly)
            if (remote.updatedAt > localTs) {
                android.util.Log.d("UserSyncRepository", "-> 執行更新")
                cacheProgress(remote.bookId, remote.locatorJson, remote.updatedAt)
                runCatching {
                    db.bookDao().updateProgress(remote.bookId, remote.locatorJson, remote.updatedAt)
                }.onSuccess {
                    android.util.Log.d("UserSyncRepository", "-> DB更新成功")
                }.onFailure {
                    android.util.Log.e("UserSyncRepository", "-> DB更新失敗", it)
                }
                updated++
            } else {
                android.util.Log.d("UserSyncRepository", "-> 跳過 (無需更新)")
            }
        }
        updated
    }

    // --- Helpers ---

    private fun decodeBookIdFromStorageName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val safeId = name.substringBeforeLast(".")
        return runCatching {
            val decoded = Base64.decode(safeId, Base64.URL_SAFE or Base64.NO_WRAP)
            String(decoded, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hasReadableLocalCopy(entity: BookEntity?): Boolean {
        entity ?: return false
        return runCatching {
            val uri = Uri.parse(entity.fileUri)
            appContext.contentResolver.openInputStream(uri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun userDoc() =
        auth.currentUser?.let { firestore.collection(COL_USERS).document(it.uid) }

    private fun progressKey(bookId: String) = "progress_$bookId"
    private fun progressTimestampKey(bookId: String) = "progress_ts_$bookId"

    companion object {
        private const val COL_USERS = "users"
        private const val COL_SETTINGS = "settings"
        private const val DOC_READER_SETTINGS = "reader"
        private const val COL_PROGRESS = "progress"
        private const val COL_BOOKS = "books"
        private const val COL_AI_NOTES = "ai_notes"
        private const val COL_BOOKMARKS = "bookmarks"
        private const val COL_AI_PROFILES = "ai_profiles"
    }
}

private data class LocalFileMeta(
    val size: Long,
    val checksum: String?
)

private data class UploadedBookInfo(
    val storagePath: String,
    val size: Long,
    val checksum: String?
)

data class RemoteProgress(
    val bookId: String = "",
    val locatorJson: String = "",
    val bookTitle: String? = null,
    val updatedAt: Long = 0L
)

data class RemoteBook(
    val bookId: String = "",
    val title: String? = null,
    val fileUri: String = "",
    val lastLocatorJson: String? = null,
    val lastOpenedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val storagePath: String? = null,
    val fileSize: Long = 0L,
    val checksumSha256: String? = null,
    val deleted: Boolean = false
)

data class RemoteAiNote(
    val remoteId: String = "",
    val bookId: String? = null,
    val bookTitle: String? = null,
    val originalText: String = "",
    val aiResponse: String = "",
    val locatorJson: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class RemoteBookmark(
    val remoteId: String = "",
    val bookId: String = "",
    val locatorJson: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class RemoteAiProfile(
    val remoteId: String = "",
    val name: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val serverBaseUrl: String = "",
    val systemPrompt: String = "",
    val userPromptTemplate: String = "",
    val useStreaming: Boolean = false,
    val enableGoogleSearch: Boolean = true,
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val assistantRole: String = "assistant",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

// Local copy to avoid adding new coroutine-play-services dependency
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

private object SyncCrypto {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/ECB/PKCS5Padding"
    // Use a fixed key for simplicity in this context. 
    private const val KEY_STR = "BooxReaderAiKeysSyncSecret2024!!" 

    fun encrypt(input: String): String {
        if (input.isBlank()) return ""
        return try {
            val key = SecretKeySpec(KEY_STR.toByteArray(), ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(input.toByteArray())
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("SyncCrypto", "Encryption failed", e)
            ""
        }
    }

    fun decrypt(input: String): String {
        if (input.isBlank()) return ""
        return try {
            val key = SecretKeySpec(KEY_STR.toByteArray(), ALGORITHM)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decoded = Base64.decode(input, Base64.NO_WRAP)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            android.util.Log.w("SyncCrypto", "Decryption failed, returning input/empty. Input: $input")
            ""
        }
    }
}

data class RemoteReaderSettings(
    val pageTapEnabled: Boolean = true,
    val pageSwipeEnabled: Boolean = true,
    val booxBatchRefresh: Boolean = true,
    val booxFastMode: Boolean = true,
    val contrastMode: Int = 0,
    val serverBaseUrl: String = "",
    val exportToCustomUrl: Boolean = false,
    val exportCustomUrl: String = "",
    val exportToLocalDownloads: Boolean = false,
    val apiKey: String = "",
    val aiModelName: String = "deepseek-chat",
    val aiSystemPrompt: String = "",
    val aiUserPromptTemplate: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val topP: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val assistantRole: String = "assistant",
    val enableGoogleSearch: Boolean = true,
    val useStreaming: Boolean = false,
    val pageAnimationEnabled: Boolean = false,
    val language: String = "system",
    val updatedAt: Long = 0L,
    val activeProfileId: String? = null // Remote UUID
) {
    fun toLocal(localProfileId: Long): ReaderSettings {
        return ReaderSettings(
            pageTapEnabled = pageTapEnabled,
            pageSwipeEnabled = pageSwipeEnabled,
            booxBatchRefresh = booxBatchRefresh,
            booxFastMode = booxFastMode,
            contrastMode = contrastMode,
            serverBaseUrl = serverBaseUrl,
            exportToCustomUrl = exportToCustomUrl,
            exportCustomUrl = exportCustomUrl,
            exportToLocalDownloads = exportToLocalDownloads,
            apiKey = apiKey,
            aiModelName = aiModelName,
            aiSystemPrompt = aiSystemPrompt,
            aiUserPromptTemplate = aiUserPromptTemplate,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            assistantRole = assistantRole,
            enableGoogleSearch = enableGoogleSearch,
            useStreaming = useStreaming,
            pageAnimationEnabled = pageAnimationEnabled,
            language = language,
            updatedAt = updatedAt,
            activeProfileId = localProfileId
        )
    }

    companion object {
        fun fromLocal(local: ReaderSettings, remoteProfileId: String?): RemoteReaderSettings {
            return RemoteReaderSettings(
                pageTapEnabled = local.pageTapEnabled,
                pageSwipeEnabled = local.pageSwipeEnabled,
                booxBatchRefresh = local.booxBatchRefresh,
                booxFastMode = local.booxFastMode,
                contrastMode = local.contrastMode,
                serverBaseUrl = local.serverBaseUrl,
                exportToCustomUrl = local.exportToCustomUrl,
                exportCustomUrl = local.exportCustomUrl,
                exportToLocalDownloads = local.exportToLocalDownloads,
                apiKey = local.apiKey,
                aiModelName = local.aiModelName,
                aiSystemPrompt = local.aiSystemPrompt,
                aiUserPromptTemplate = local.aiUserPromptTemplate,
                temperature = local.temperature,
                maxTokens = local.maxTokens,
                topP = local.topP,
                frequencyPenalty = local.frequencyPenalty,
                presencePenalty = local.presencePenalty,
                assistantRole = local.assistantRole,
                enableGoogleSearch = local.enableGoogleSearch,
                useStreaming = local.useStreaming,
                pageAnimationEnabled = local.pageAnimationEnabled,
                language = local.language,
                updatedAt = local.updatedAt,
                activeProfileId = remoteProfileId
            )
        }
    }
}