package my.hinoki.booxreader.data.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS 的 PlatformFiles 行為測試（由 :shared:iosSimulatorArm64Test 在 macOS 上執行）。
 * 驗證 iosMain 的 NSFileManager 實作：寫入/讀取/改名/刪除/快取目錄/內容名稱。
 */
class PlatformFilesIosTest {

    private val files = platformFiles()

    @Test
    fun fileWriteReadRenameDeleteRoundTrip() {
        val dir = files.appFilesDir() ?: return // 無法取得目錄時跳過
        val path = "$dir/roundtrip_test.txt"
        val renamed = "$dir/roundtrip_test_renamed.txt"

        files.delete(path)
        files.delete(renamed)

        assertTrue(files.writeFile(path, "hello-ios".encodeToByteArray()), "writeFile 應成功")
        assertTrue(files.exists(path), "寫入後應存在")
        assertEquals(9L, files.fileLength(path), "檔案長度應為 9 bytes")
        assertEquals("hello-ios", files.readFile(path)?.decodeToString(), "讀回內容應一致")

        assertTrue(files.rename(path, renamed), "rename 應成功")
        assertFalse(files.exists(path), "改名後原路徑不應存在")
        assertTrue(files.exists(renamed), "改名後新路徑應存在")
        assertEquals("hello-ios", files.readFile(renamed)?.decodeToString())

        assertTrue(files.delete(renamed), "delete 應成功")
        assertFalse(files.exists(renamed), "刪除後不應存在")
    }

    @Test
    fun writeCacheFileAndReadFilePrefix() {
        val path = files.writeCacheFile("cache_test.bin", byteArrayOf(1, 2, 3, 4, 5)) ?: return
        assertTrue(files.exists(path))
        assertEquals(5L, files.fileLength(path))

        val prefix = files.readFilePrefix(path, 3)
        assertEquals(byteArrayOf(1, 2, 3).toList(), prefix?.toList(), "readFilePrefix 應回傳前 3 bytes")
        assertTrue(files.delete(path))
    }

    @Test
    fun contentNameFromFileUri() {
        val dir = files.appFilesDir() ?: return
        assertEquals("abc.txt", files.contentName("file://$dir/abc.txt"))
        // 尾斜線（目錄本身）沒有檔名元件 → null
        assertNull(files.contentName("file://$dir/"))
    }

    @Test
    fun contentTypeByExtension() {
        assertEquals("application/json", files.contentType("file:///tmp/notes.json"))
        assertEquals("application/epub+zip", files.contentType("file:///tmp/book.epub"))
    }
}
