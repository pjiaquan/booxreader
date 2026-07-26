package my.hinoki.booxreader.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import my.hinoki.booxreader.data.repo.UserSyncRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ErrorReporterTest {

    private lateinit var context: Context
    private lateinit var mockSyncRepo: UserSyncRepository
    private lateinit var crashHandler: CrashReportHandler

    private val originalUploadScope = ErrorReporter.uploadScope
    private val originalSyncRepoFactory = ErrorReporter.syncRepositoryFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockSyncRepo = mock(UserSyncRepository::class.java)

        // Install CrashReportHandler and clear any pending reports
        crashHandler = CrashReportHandler.install(context)
        crashHandler.clearPendingReports()

        // Override dependencies in ErrorReporter to use test dispatchers and mocks
        ErrorReporter.uploadScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        ErrorReporter.syncRepositoryFactory = { mockSyncRepo }
    }

    @After
    fun tearDown() {
        // Restore original dependencies
        ErrorReporter.uploadScope = originalUploadScope
        ErrorReporter.syncRepositoryFactory = originalSyncRepoFactory
        crashHandler.clearPendingReports()
    }

    @Test
    fun `report uploads and clears report when successful`() = runTest {
        // Setup successful upload
        whenever(mockSyncRepo.pushCrashReport(any())).thenReturn(true)

        // Precondition
        assertTrue(crashHandler.getPendingReports().isEmpty())

        // Action
        ErrorReporter.report(context, "TestSource", "TestMessage", Exception("Test"))

        // Verification
        // Because the upload is successful, the report is saved and immediately marked as uploaded
        assertTrue(crashHandler.getPendingReports().isEmpty())
    }

    @Test
    fun `report persists report locally when upload fails`() = runTest {
        // Setup failed upload
        whenever(mockSyncRepo.pushCrashReport(any())).thenReturn(false)

        // Precondition
        assertTrue(crashHandler.getPendingReports().isEmpty())

        // Action
        ErrorReporter.report(context, "TestSource", "TestMessage", Exception("Test"))

        // Verification
        // Because the upload failed, the report remains pending
        val pending = crashHandler.getPendingReports()
        assertEquals(1, pending.size)
        assertEquals("[TestSource] TestMessage", pending[0].message)
    }

    @Test
    fun `flushPending uploads and clears all pending reports when successful`() = runTest {
        // Add pending reports
        crashHandler.reportHandledException("Source1", "Message1")
        crashHandler.reportHandledException("Source2", "Message2")

        assertEquals(2, crashHandler.getPendingReports().size)

        // Setup successful upload
        whenever(mockSyncRepo.pushCrashReport(any())).thenReturn(true)

        // Action
        ErrorReporter.flushPending(context)

        // Verification
        // All reports should be uploaded and cleared
        assertTrue(crashHandler.getPendingReports().isEmpty())
    }

    @Test
    fun `flushPending retains pending reports when upload fails`() = runTest {
        // Add pending reports
        crashHandler.reportHandledException("Source1", "Message1")
        crashHandler.reportHandledException("Source2", "Message2")

        assertEquals(2, crashHandler.getPendingReports().size)

        // Setup failed upload
        whenever(mockSyncRepo.pushCrashReport(any())).thenReturn(false)

        // Action
        ErrorReporter.flushPending(context)

        // Verification
        // Reports should remain pending
        assertEquals(2, crashHandler.getPendingReports().size)
    }
}
