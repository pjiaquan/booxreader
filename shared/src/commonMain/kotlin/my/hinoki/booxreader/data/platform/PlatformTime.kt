package my.hinoki.booxreader.data.platform

/**
 * 跨平台取得目前 epoch 毫秒。
 * Android: System.currentTimeMillis()；iOS: NSDate。
 */
expect fun currentEpochMillis(): Long
