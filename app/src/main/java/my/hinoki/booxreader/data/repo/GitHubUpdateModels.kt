package my.hinoki.booxreader.data.repo

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<GitHubAsset>
)

data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long
)
