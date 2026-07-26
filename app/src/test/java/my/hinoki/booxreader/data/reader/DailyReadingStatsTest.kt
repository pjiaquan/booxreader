package my.hinoki.booxreader.data.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import my.hinoki.booxreader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class DailyReadingStatsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared preferences before each test to ensure a clean state
        context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun getKey(timeMillis: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(timeMillis))
        return "daily_reading_ms_$day"
    }

    private fun getStoredDuration(timeMillis: Long): Long {
        val prefs = context.getSharedPreferences(ReaderSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(getKey(timeMillis), 0L)
    }

    @Test
    fun addSession_validSession_storesCorrectDuration() {
        val startMillis = 1000L
        val endMillis = 5000L // 4000ms duration

        DailyReadingStats.addSession(context, startMillis, endMillis)

        val storedDuration = getStoredDuration(endMillis)
        assertEquals(4000L, storedDuration)
    }

    @Test
    fun addSession_multipleSessionsSameDay_accumulatesDuration() {
        val start1 = 1000L
        val end1 = 5000L // 4000ms

        val start2 = 6000L
        val end2 = 16000L // 10000ms

        DailyReadingStats.addSession(context, start1, end1)
        DailyReadingStats.addSession(context, start2, end2)

        val storedDuration = getStoredDuration(end2)
        assertEquals(14000L, storedDuration)
    }

    @Test
    fun addSession_sessionsDifferentDays_storesSeparately() {
        // Day 1
        val day1Start = 1672531200000L // 2023-01-01 00:00:00 UTC
        val day1End = 1672534800000L // 2023-01-01 01:00:00 UTC (3600000ms)

        // Day 2 (add 24 hours)
        val day2Start = day1Start + 24 * 60 * 60 * 1000
        val day2End = day2Start + 1800000L // 30 mins later (1800000ms)

        DailyReadingStats.addSession(context, day1Start, day1End)
        DailyReadingStats.addSession(context, day2Start, day2End)

        assertEquals(3600000L, getStoredDuration(day1End))
        assertEquals(1800000L, getStoredDuration(day2End))
    }

    @Test
    fun addSession_startMillisZeroOrNegative_doesNothing() {
        val startMillis = 0L
        val endMillis = 5000L

        DailyReadingStats.addSession(context, startMillis, endMillis)

        assertEquals(0L, getStoredDuration(endMillis))

        val startMillisNeg = -1000L
        DailyReadingStats.addSession(context, startMillisNeg, endMillis)

        assertEquals(0L, getStoredDuration(endMillis))
    }

    @Test
    fun addSession_endMillisLessThanOrEqualStartMillis_doesNothing() {
        val startMillis = 5000L
        val endMillis = 5000L

        DailyReadingStats.addSession(context, startMillis, endMillis)
        assertEquals(0L, getStoredDuration(endMillis))

        val endMillisLess = 4000L
        DailyReadingStats.addSession(context, startMillis, endMillisLess)
        assertEquals(0L, getStoredDuration(endMillisLess))
    }
}
