package my.hinoki.booxreader.data.platform

/**
 * 跨平台取得目前 epoch 毫秒。
 * Android: System.currentTimeMillis()；iOS: NSDate。
 */
expect fun currentEpochMillis(): Long

/**
 * 跨平台 IO dispatcher。
 * Android: Dispatchers.IO；iOS: Dispatchers.Default
 * （kotlinx-coroutines 在 Kotlin/Native 未公開 Dispatchers.IO）。
 */
expect val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
