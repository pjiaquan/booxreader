package my.hinoki.booxreader.data.platform

/**
 * 平台檔案存取抽象（KMP）。
 * Android：contentResolver + 檔案系統；iOS：NSFileManager（待 macOS 驗證）。
 */
expect fun platformFiles(): PlatformFiles

expect class PlatformFiles {
    /** 讀取 content:// 或 file:// URI 的 bytes（失敗回傳 null）。 */
    suspend fun readUriBytes(uri: String): ByteArray?

    /** 將 bytes 寫入 app 暫存目錄，回傳絕對路徑（失敗回傳 null）。 */
    fun writeCacheFile(fileName: String, bytes: ByteArray): String?

    /** app 內部檔案目錄絕對路徑（失敗回傳 null）。 */
    fun appFilesDir(): String?
}
