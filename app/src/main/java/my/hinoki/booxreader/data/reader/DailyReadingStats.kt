package my.hinoki.booxreader.data.reader

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import my.hinoki.booxreader.data.settings.ReaderSettings

object DailyReadingStats {
    private const val KEY_PREFIX_DAILY_READING_MS = "daily_reading_ms_"
    private const val DATE_PATTERN = "yyyyMMdd"

    fun addSession(context: Context, startMillis: Long, endMillis: Long) {
        if (startMillis <= 0L || endMillis <= startMillis) return
        val duration = endMillis - startMillis
        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val key = keyFor(endMillis)
        val existing = prefs.getLong(key, 0L)
        prefs.edit().putLong(key, existing + duration).apply()
    }

    fun getTodayReadingMillis(context: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(keyFor(nowMillis), 0L)
    }

    fun formatDuration(totalMillis: Long): String {
        val totalMinutes = (totalMillis / 60000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0L) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }

    private fun keyFor(timeMillis: Long): String {
        val day = SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(Date(timeMillis))
        return "$KEY_PREFIX_DAILY_READING_MS$day"
    }
}
