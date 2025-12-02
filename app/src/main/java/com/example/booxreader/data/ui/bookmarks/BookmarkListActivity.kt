package com.example.booxreader.ui.bookmarks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.booxreader.data.db.BookmarkEntity
import com.example.booxreader.data.repo.BookmarkRepository
import com.example.booxreader.databinding.ActivityBookmarkListBinding
import com.example.booxreader.reader.ReaderViewModel
import kotlinx.coroutines.launch

class BookmarkListActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_LOCATOR_JSON = "extra_locator_json"


        fun openForResult(activity: Activity, bookId: String, requestCode: Int) {
            val intent = Intent(activity, BookmarkListActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
            }
            activity.startActivityForResult(intent, requestCode)
        }
    }

    private lateinit var binding: ActivityBookmarkListBinding
//    private val viewModel: ReaderViewModel by viewModels()
    private lateinit var repo: BookmarkRepository
    private lateinit var bookId: String

    private var bookmarks: List<BookmarkEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBookmarkListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = BookmarkRepository(applicationContext)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            finish()
            return
        }

        loadBookmarks()

        binding.listBookmarks.setOnItemClickListener { _, _, position, _ ->
            val bookmark = bookmarks.getOrNull(position) ?: return@setOnItemClickListener
            val result = Intent().apply {
                putExtra(EXTRA_LOCATOR_JSON, bookmark.locatorJson)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            bookmarks = repo.getBookmarks(bookId)

            val labels = bookmarks.map {
                val time = android.text.format.DateFormat.format("MM-dd HH:mm", it.createdAt)
                "Bookmark @ $time"
            }

            binding.listBookmarks.adapter = ArrayAdapter(
                this@BookmarkListActivity,
                android.R.layout.simple_list_item_1,
                labels
            )
        }
    }
}
