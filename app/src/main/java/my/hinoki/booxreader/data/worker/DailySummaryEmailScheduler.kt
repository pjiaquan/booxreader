package my.hinoki.booxreader.data.worker

import android.content.Context
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import my.hinoki.booxreader.data.settings.ReaderSettings

object DailySummaryEmailScheduler {
    private const val UNIQUE_WORK_NAME = "daily_ai_note_email_summary"

    fun schedule(context: Context, settings: ReaderSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.dailySummaryEmailEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val now = System.currentTimeMillis()
        val nextRun = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, settings.dailySummaryEmailHour.coerceIn(0, 23))
            set(Calendar.MINUTE, settings.dailySummaryEmailMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis

        val initialDelayMillis = (nextRun - now).coerceAtLeast(0L)
        val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val request =
                PeriodicWorkRequestBuilder<DailySummaryEmailWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                        .setConstraints(constraints)
                        .build()

        workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        )
    }
}
