package my.hinoki.booxreader.core.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileUtilsTest {

    @Test
    fun copyToCache_success() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://dummy/file")

        val contentData = "dummy content"
        val inputStream = ByteArrayInputStream(contentData.toByteArray())

        shadowOf(context.contentResolver).registerInputStream(uri, inputStream)

        val resultFile = FileUtils.copyToCache(context, uri)

        assertTrue(resultFile.exists())
        assertEquals(contentData, resultFile.readText())
    }

    @Test
    fun copyToCache_nullInputStream_throwsIllegalStateException() {
        val mockContext = mock(Context::class.java)
        val mockResolver = mock(ContentResolver::class.java)

        `when`(mockContext.contentResolver).thenReturn(mockResolver)
        `when`(mockContext.cacheDir).thenReturn(File(System.getProperty("java.io.tmpdir")))

        val uri = Uri.parse("content://dummy/nonexistent")
        `when`(mockResolver.openInputStream(uri)).thenReturn(null)

        val exception = assertThrows(IllegalStateException::class.java) {
            FileUtils.copyToCache(mockContext, uri)
        }

        assertEquals("Cannot open inputStream for content://dummy/nonexistent", exception.message)
    }
}
