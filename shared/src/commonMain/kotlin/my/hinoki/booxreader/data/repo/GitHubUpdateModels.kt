package my.hinoki.booxreader.data.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("body") val body: String? = null,
        @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        @SerialName("size") val size: Long = 0
)
