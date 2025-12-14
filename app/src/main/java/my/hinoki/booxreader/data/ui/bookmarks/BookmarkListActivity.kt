package my.hinoki.booxreader.ui.bookmarks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.databinding.ActivityBookmarkListBinding
import kotlinx.coroutines.launch
import my.hinoki.booxreader.reader.LocatorJsonHelper
import org.readium.r2.shared.publication.Locator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.ui.common.BaseActivity

class BookmarkListActivity : BaseActivity() {

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
    private lateinit var syncRepo: UserSyncRepository
    private lateinit var bookId: String

    private var bookmarks: List<BookmarkEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBookmarkListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = applicationContext as my.hinoki.booxreader.BooxReaderApp
        syncRepo = UserSyncRepository(app)
        repo = BookmarkRepository(app, app.okHttpClient, syncRepo)

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
            // Pull latest from Firestore (best effort)
            runCatching { syncRepo.pullBookmarks(bookId) }

            bookmarks = repo.getBookmarks(bookId)

            val labels = bookmarks.map {
                val time = android.text.format.DateFormat.format("MM-dd HH:mm", it.createdAt)
                val locator = LocatorJsonHelper.fromJson(it.locatorJson)
                val chapter = locator?.title?.takeIf { t -> t.isNotBlank() } ?: "Bookmark"
                val percent = locator?.locations?.totalProgression
                    ?: locator?.locations?.progression
                val position = locator?.locations?.position
                val pageLabel = when {
                    position != null -> "Page $position"
                    percent != null -> "${(percent * 100).toInt()}%"
                    else -> ""
                }
                val highlight = locator?.text?.highlight?.take(80)?.trim().orEmpty()
                val subtitle = listOfNotNull(pageLabel.ifBlank { null }, highlight.ifBlank { null })
                    .joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    "$chapter • $subtitle ($time)"
                } else {
                    "$chapter ($time)"
                }
            }

            binding.listBookmarks.adapter = ArrayAdapter(
                this@BookmarkListActivity,
                android.R.layout.simple_list_item_1,
                labels
            )
        }
    }
}
