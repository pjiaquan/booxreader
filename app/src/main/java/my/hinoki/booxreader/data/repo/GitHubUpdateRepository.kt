package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubUpdateRepository(private val context: Context) {
    private val client =
            OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
    private val gson = Gson()

    private val repoOwner = "pjiaquan"
    private val repoName = "booxreader"
    private val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    suspend fun fetchLatestRelease(): GitHubRelease? =
            withContext(Dispatchers.IO) {
                try {
                    val request =
                            Request.Builder()
                                    .url(apiUrl)
                                    .header("Accept", "application/vnd.github.v3+json")
                                    .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body?.string() ?: return@withContext null
                        gson.fromJson(body, GitHubRelease::class.java)
                    }
                } catch (e: Exception) {
                    Log.e("GitHubUpdateRepo", "Error fetching latest release", e)
                    null
                }
            }

    fun isNewerVersion(remoteTagName: String): Boolean {
        val currentVersion = BuildConfig.VERSION_NAME // e.g., "1.1.162"
        val remoteVersion = remoteTagName.removePrefix("v").trim()

        // Simple version comparison logic
        return try {
            val currentParts = currentVersion.split(".").map { it.toInt() }
            val remoteParts = remoteVersion.split(".").map { it.toInt() }

            for (i in 0 until minOf(currentParts.size, remoteParts.size)) {
                if (remoteParts[i] > currentParts[i]) return true
                if (remoteParts[i] < currentParts[i]) return false
            }
            remoteParts.size > currentParts.size
        } catch (e: Exception) {
            // Fallback to string comparison if numeric fails
            remoteVersion != currentVersion
        }
    }

    suspend fun downloadApk(downloadUrl: String, fileName: String): File? =
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(downloadUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null

                        val downloadsDir =
                                context.getExternalFilesDir("updates") ?: return@withContext null
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()

                        val apkFile = File(downloadsDir, fileName)
                        apkFile.writeBytes(body.bytes())
                        apkFile
                    }
                } catch (e: Exception) {
                    Log.e("GitHubUpdateRepo", "Error downloading APK", e)
                    null
                }
            }

    fun installApk(file: File) {
        val uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }

        val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
        context.startActivity(intent)
    }
}
