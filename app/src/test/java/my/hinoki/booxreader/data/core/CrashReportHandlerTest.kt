package my.hinoki.booxreader.data.core

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashReportHandlerTest {

    private lateinit var context: Context
    private var originalDefaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        originalDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun teardown() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefaultHandler)
    }

    private fun createHandler(ctx: Context): CrashReportHandler {
        val constructor = CrashReportHandler::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(ctx)
    }

    @Test
    fun `uncaughtException happy path saves report and calls default handler`() {
        val mockDefaultHandler = Mockito.mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(mockDefaultHandler)

        val handler = createHandler(context)
        handler.clearPendingReports() // Ensure clean state

        val thread = Thread.currentThread()
        val exception = RuntimeException("Test Exception")

        handler.uncaughtException(thread, exception)

        val reports = handler.getPendingReports()
        assertEquals(1, reports.size)
        assertEquals("Test Exception", reports[0].message)
        assertEquals(thread.name, reports[0].threadName)

        Mockito.verify(mockDefaultHandler).uncaughtException(thread, exception)
    }

    @Test
    fun `uncaughtException error path swallows exception and calls default handler`() {
        val mockDefaultHandler = Mockito.mock(Thread.UncaughtExceptionHandler::class.java)
        Thread.setDefaultUncaughtExceptionHandler(mockDefaultHandler)

        val mockContext = Mockito.mock(Context::class.java)
        val mockPrefs = Mockito.mock(SharedPreferences::class.java)

        Mockito.`when`(mockContext.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
            .thenReturn(mockPrefs)

        // Throw exception when trying to read pending reports to trigger the catch block in uncaughtException
        Mockito.`when`(mockPrefs.getString(Mockito.anyString(), Mockito.any()))
            .thenThrow(RuntimeException("Simulated read error"))

        val handler = createHandler(mockContext)

        val thread = Thread.currentThread()
        val exception = RuntimeException("Test Exception")

        // This should not throw an exception itself
        handler.uncaughtException(thread, exception)

        // Verify default handler is still called even if saving failed
        Mockito.verify(mockDefaultHandler).uncaughtException(thread, exception)
    }
}
