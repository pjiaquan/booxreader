package my.hinoki.booxreader.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.data.db.AppDatabase
import my.hinoki.booxreader.data.reader.DailyReadingStats
import my.hinoki.booxreader.data.repo.AiNoteDailySummaryBuilder
import my.hinoki.booxreader.data.repo.AiNoteRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.settings.ReaderSettings

class DailySummaryEmailWorker(
        appContext: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs =
                applicationContext.getSharedPreferences(
                        ReaderSettings.PREFS_NAME,
                        Context.MODE_PRIVATE
                )
        val settings = ReaderSettings.fromPrefs(prefs)
        if (!settings.dailySummaryEmailEnabled) {
            return Result.success()
        }

        val syncRepo = UserSyncRepository(applicationContext)
        val app = applicationContext as? BooxReaderApp ?: return Result.failure()

        runCatching { syncRepo.pullNotes() }

        val repo = AiNoteRepository(applicationContext, app.okHttpClient, syncRepo)
        val allNotes = runCatching { repo.getAll() }.getOrDefault(emptyList())
        val readingMillis = DailyReadingStats.getTodayReadingMillis(applicationContext)
        val summary = AiNoteDailySummaryBuilder.build(applicationContext, allNotes, todayReadingMillis = readingMillis)
        if (summary.noteCount == 0) {
            return Result.success()
        }

        val userEmail =
                runCatching {
                            AppDatabase.get(applicationContext)
                                    .userDao()
                                    .getUser()
                                    .first()
                                    ?.email
                                    ?.trim()
                                    .orEmpty()
                        }
                        .getOrDefault("")
        val recipient = settings.dailySummaryEmailTo.trim().ifBlank { userEmail }
        if (recipient.isBlank()) {
            return Result.failure()
        }

        val sendResult =
                runCatching {
                            syncRepo.sendDailySummaryEmail(
                                    recipient,
                                    summary.subject,
                                    summary.body
                            )
                        }
                        .getOrNull()

        return when {
            sendResult?.ok == true -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
