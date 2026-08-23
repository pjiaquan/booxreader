package my.hinoki.booxreader.data.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.repo.createUserSyncRepository

/**
 * Centralized non-fatal error reporter.
 *
 * It stores handled errors locally via [CrashReportHandler] and then tries to upload them to
 * PocketBase crash_reports.
 */
object ErrorReporter {
    private const val TAG = "ErrorReporter"

    // Made internal and var for testing
    internal var uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var syncRepositoryFactory: (Context) -> UserSyncRepository = { createUserSyncRepository(it) }

    fun report(context: Context, source: String, message: String?, throwable: Throwable? = null) {
        val appContext = context.applicationContext
        val crashHandler = CrashReportHandler.getInstance() ?: CrashReportHandler.install(appContext)

        val report =
                runCatching {
                            crashHandler.reportHandledException(
                                    source = source,
                                    message = message,
                                    throwable = throwable
                            )
                        }
                        .onFailure { Log.e(TAG, "Failed to persist non-fatal report", it) }
                        .getOrNull()
                        ?: return

        uploadScope.launch {
            runCatching {
                        val syncRepo = syncRepositoryFactory(appContext)
                        if (syncRepo.pushCrashReport(report)) {
                            crashHandler.markReportAsUploaded(report.createdAt)
                        }
                    }
                    .onFailure { Log.e(TAG, "Failed to upload non-fatal report", it) }
        }
    }

    fun flushPending(context: Context) {
        val appContext = context.applicationContext
        val crashHandler = CrashReportHandler.getInstance() ?: CrashReportHandler.install(appContext)
        uploadScope.launch {
            runCatching {
                        val syncRepo = syncRepositoryFactory(appContext)
                        crashHandler.getPendingReports().forEach { report ->
                            if (syncRepo.pushCrashReport(report)) {
                                crashHandler.markReportAsUploaded(report.createdAt)
                            }
                        }
                    }
                    .onFailure { Log.e(TAG, "Failed to flush pending reports", it) }
        }
    }
}
