package my.hinoki.booxreader.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import my.hinoki.booxreader.BuildConfig
import my.hinoki.booxreader.data.remote.createApiClient

class GitHubUpdateRepository(private val context: Context) {
    private val client: HttpClient = createApiClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val repoOwner = "pjiaquan"
    private val repoName = "booxreader"
    private val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    suspend fun fetchLatestRelease(): GitHubRelease? =
            withContext(Dispatchers.IO) {
                try {
                    val response =
                            client.get(apiUrl) {
                                header("Accept", "application/vnd.github.v3+json")
                            }
                    if (!response.status.isSuccess()) return@withContext null
                    val body = response.bodyAsText()
                    json.decodeFromString<GitHubRelease>(body)
                } catch (e: Exception) {
                    Log.e("GitHubUpdateRepo", "Error fetching latest release", e)
                    null
                }
            }

    fun isNewerVersion(
            remoteTagName: String,
            currentVersion: String = BuildConfig.VERSION_NAME
    ): Boolean {
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
                    val response = client.get(downloadUrl)
                    if (!response.status.isSuccess()) return@withContext null
                    val bytes = response.bodyAsBytes()

                    val downloadsDir =
                            context.getExternalFilesDir("updates") ?: return@withContext null
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()

                    val apkFile = File(downloadsDir, fileName)
                    apkFile.writeBytes(bytes)
                    apkFile
                } catch (e: Exception) {
                    Log.e("GitHubUpdateRepo", "Error downloading APK", e)
                    null
                }
            }

    fun createInstallIntent(file: File): Intent {
        val uri =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }
}
