package my.hinoki.booxreader.data.repo

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import my.hinoki.booxreader.data.remote.createApiClient

/**
 * GitHub Release 版本檢查（純 HTTP 邏輯，KMP commonMain）。
 * 下載與安裝（FileProvider/Intent）留在 Android 層。
 */
class GitHubUpdateChecker(
    private val client: HttpClient = createApiClient(),
    private val repoOwner: String = "pjiaquan",
    private val repoName: String = "booxreader",
    baseUrl: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiUrl =
            baseUrl ?: "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    suspend fun fetchLatestRelease(): GitHubRelease? {
        return try {
            val response =
                    client.get(apiUrl) {
                            header("Accept", "application/vnd.github.v3+json")
                    }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsText()
            json.decodeFromString<GitHubRelease>(body)
        } catch (e: Exception) {
            null
        }
    }

    fun isNewerVersion(remoteTagName: String, currentVersion: String): Boolean {
        val remoteVersion = remoteTagName.removePrefix("v").trim()
        val localVersion  = currentVersion.trim()

        // Parse each dotted segment as an integer for a proper numeric comparison.
        return try {
            val currentParts = localVersion.split(".").map { it.toInt() }
            val remoteParts  = remoteVersion.split(".").map { it.toInt() }

            for (i in 0 until minOf(currentParts.size, remoteParts.size)) {
                if (remoteParts[i] > currentParts[i]) return true
                if (remoteParts[i] < currentParts[i]) return false
            }
            remoteParts.size > currentParts.size
        } catch (e: Exception) {
            // Cannot parse version numbers — conservatively assume no update needed
            // to avoid a false-positive notification loop.
            false
        }
    }
}
