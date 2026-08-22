package my.hinoki.booxreader.data.remote

data class ProgressPayload(
    val bookId: String,
    val locatorJson: String,
    val updatedAt: Long
    // 之後可以加 deviceModel, appVersion 等欄位
)
