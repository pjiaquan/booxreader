package com.example.booxreader.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.booxreader.data.db.BookmarkEntity
import com.example.booxreader.data.repo.BookmarkRepository
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = BookmarkRepository(app)

    private val _bookmarks = MutableLiveData<List<BookmarkEntity>>(emptyList())
    val bookmarks: LiveData<List<BookmarkEntity>> = _bookmarks

    fun load(bookId: String) {
        viewModelScope.launch {
            _bookmarks.value = repo.getBookmarks(bookId)
        }
    }

    fun addBookmark(bookId: String, locator: Locator) {
        viewModelScope.launch {
            repo.add(bookId, locator)
            load(bookId)
        }
    }
}
