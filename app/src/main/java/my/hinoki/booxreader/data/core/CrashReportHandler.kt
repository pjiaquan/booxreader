package my.hinoki.booxreader.data.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import my.hinoki.booxreader.BuildConfig

/**
 * Crash handler that captures uncaught exceptions and stores them locally for later upload to
 * PocketBase when the app restarts.
 */
class CrashReportHandler private constructor(private val context: Context) :
        Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()

    private val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = Gson()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildReport(thread.name, throwable.message, throwable)
            saveCrashReport(report)
            Log.e(TAG, "Crash saved for later upload", throwable)
        } catch (e: Exception) {
            // Don't let crash handler crash
            Log.e(TAG, "Failed to save crash report", e)
        }

        // Chain to default handler (usually crashes the app)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * Record a handled error (non-fatal) so it can be uploaded to crash_reports.
     * Returns the created report instance.
     */
    fun reportHandledException(source: String, message: String?, throwable: Throwable? = null): CrashReport {
        val summary =
                buildString {
                    append("[")
                    append(source)
                    append("] ")
                    append(message ?: "Non-fatal error")
                }
        val report = buildReport(Thread.currentThread().name, summary, throwable)
        saveCrashReport(report)
        return report
    }

    private fun buildReport(
            threadName: String,
            message: String?,
            throwable: Throwable?
    ): CrashReport {
        val stack =
                if (throwable != null) {
                    Log.getStackTraceString(throwable)
                } else {
                    (message ?: "No stack trace").take(4000)
                }
        return CrashReport(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                osVersion = Build.VERSION.SDK_INT.toString(),
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                stacktrace = stack,
                message = message,
                threadName = threadName,
                createdAt = System.currentTimeMillis()
        )
    }

    private fun saveCrashReport(report: CrashReport) {
        val existing = getPendingReports().toMutableList()
        existing.add(report)

        // Keep max 10 crash reports to avoid storage bloat
        val trimmed = existing.takeLast(MAX_REPORTS)

        prefs.edit().putString(KEY_PENDING_REPORTS, gson.toJson(trimmed)).apply()
    }

    fun getPendingReports(): List<CrashReport> {
        val json = prefs.getString(KEY_PENDING_REPORTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CrashReport>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending reports", e)
            emptyList()
        }
    }

    fun clearPendingReports() {
        prefs.edit().remove(KEY_PENDING_REPORTS).apply()
    }

    fun markReportAsUploaded(createdAt: Long) {
        val existing = getPendingReports().toMutableList()
        existing.removeAll { it.createdAt == createdAt }
        prefs.edit().putString(KEY_PENDING_REPORTS, gson.toJson(existing)).apply()
    }

    companion object {
        private const val TAG = "CrashReportHandler"
        private const val PREFS_NAME = "crash_reports_prefs"
        private const val KEY_PENDING_REPORTS = "pending_crash_reports"
        private const val MAX_REPORTS = 10

        @Volatile private var instance: CrashReportHandler? = null

        fun install(context: Context): CrashReportHandler {
            return instance
                    ?: synchronized(this) {
                        instance
                                ?: CrashReportHandler(context.applicationContext).also {
                                    instance = it
                                    Thread.setDefaultUncaughtExceptionHandler(it)
                                }
                    }
        }

        fun getInstance(): CrashReportHandler? = instance
    }
}

/** Data class representing a crash report. */
data class CrashReport(
        @SerializedName("app_version") val appVersion: String,
        @SerializedName("version_code") val versionCode: Int,
        @SerializedName("os_version") val osVersion: String,
        @SerializedName("device_model") val deviceModel: String,
        @SerializedName("device_manufacturer") val deviceManufacturer: String,
        @SerializedName("stacktrace") val stacktrace: String,
        @SerializedName("message") val message: String?,
        @SerializedName("thread_name") val threadName: String,
        @SerializedName("created_at") val createdAt: Long
)
