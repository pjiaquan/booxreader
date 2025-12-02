package com.example.booxreader.data.remote

data class BookmarkPayload(
    val bookId: String,
    val locatorJson: String,
    val createdAt: Long
)
