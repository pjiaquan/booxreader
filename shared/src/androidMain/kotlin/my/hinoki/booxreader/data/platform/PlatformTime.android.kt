package my.hinoki.booxreader.data.platform

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
