package my.hinoki.booxreader.data.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Data class representing a crash report. */
@Serializable
data class CrashReport(
        @SerialName("app_version") val appVersion: String,
        @SerialName("version_code") val versionCode: Int,
        @SerialName("os_version") val osVersion: String,
        @SerialName("device_model") val deviceModel: String,
        @SerialName("device_manufacturer") val deviceManufacturer: String,
        @SerialName("stacktrace") val stacktrace: String,
        @SerialName("message") val message: String? = null,
        @SerialName("thread_name") val threadName: String,
        @SerialName("created_at") val createdAt: Long
)
