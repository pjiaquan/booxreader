package my.hinoki.booxreader.data.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private val json = Json { ignoreUnknownKeys = true }

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

        prefs.edit().putString(KEY_PENDING_REPORTS, json.encodeToString(trimmed)).apply()
    }

    fun getPendingReports(): List<CrashReport> {
        val stored = prefs.getString(KEY_PENDING_REPORTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<CrashReport>>(stored) ?: emptyList()
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
        prefs.edit().putString(KEY_PENDING_REPORTS, json.encodeToString(existing)).apply()
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

