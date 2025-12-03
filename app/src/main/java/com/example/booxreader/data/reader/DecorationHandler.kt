package com.example.booxreader.data.reader

object DecorationHandler {
    fun extractNoteIdFromDecorationId(id: String): Long? {
        return when {
            id.startsWith("note_") -> id.removePrefix("note_").substringBefore("_").toLongOrNull()
            else -> id.toLongOrNull()
        }
    }
}
