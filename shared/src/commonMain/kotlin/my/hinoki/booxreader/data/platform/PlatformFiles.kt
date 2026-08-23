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

    /** URI 是否可讀取（僅開啟檢查，不讀入記憶體）。 */
    fun isUriReadable(uri: String): Boolean

    /** URI 的顯示名稱（Android: DISPLAY_NAME；iOS: 最後路徑元件）。 */
    fun contentName(uri: String): String?

    /** 路徑是否存在。 */
    fun exists(path: String): Boolean

    /** 建立目錄（含父層）。 */
    fun mkdirs(path: String): Boolean

    /** 寫入 bytes 到路徑。 */
    fun writeFile(path: String, bytes: ByteArray): Boolean

    /** 讀取路徑的 bytes。 */
    fun readFile(path: String): ByteArray?

    /** 刪除檔案/目錄（遞迴）。 */
    fun delete(path: String): Boolean

    /** 重新命名/移動。 */
    fun rename(from: String, to: String): Boolean

    /** 檔案大小。 */
    fun fileLength(path: String): Long

    /** 讀取路徑檔案開頭最多 maxBytes 個 bytes（失敗或不足回傳實際讀到的 bytes）。 */
    fun readFilePrefix(path: String, maxBytes: Int): ByteArray?

    /** URI 的 MIME type（Android: contentResolver.getType；iOS: 依副檔名猜測，未知回傳 null）。 */
    fun contentType(uri: String): String?

    /**
     * 將內容寫入平台「公開下載」位置。
     * Android：MediaStore Downloads（API 29+）或 public Downloads/app 外部目錄（舊版，含權限檢查）。
     * iOS：Documents 目錄（best-effort，待 macOS 驗證）。
     * 成功時 localPath 非 null；失敗時 localPath 為 null 且 message 說明原因。
     */
    fun writeDownloadsFile(fileName: String, content: String): DownloadsWriteResult
}

/** 公開下載寫入結果。 */
data class DownloadsWriteResult(val localPath: String?, val message: String)
