package my.hinoki.booxreader.data.remote

data class BookmarkPayload(
    val bookId: String,
    val locatorJson: String,
    val createdAt: Long
)

