package my.hinoki.booxreader.ui.bookmarks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.AnnotationEntity
import my.hinoki.booxreader.data.db.BookmarkEntity
import my.hinoki.booxreader.data.repo.AnnotationRepository
import my.hinoki.booxreader.data.repo.BookmarkRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import my.hinoki.booxreader.data.repo.createUserSyncRepository
import my.hinoki.booxreader.databinding.ActivityBookmarkListBinding
import my.hinoki.booxreader.reader.LocatorJsonHelper
import my.hinoki.booxreader.ui.common.BaseActivity

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
    private lateinit var repo: BookmarkRepository
    private lateinit var annotationRepo: AnnotationRepository
    private lateinit var syncRepo: UserSyncRepository
    private lateinit var bookId: String

    private var bookmarks: List<BookmarkEntity> = emptyList()
    private var annotations: List<AnnotationEntity> = emptyList()
    private var currentTabIndex: Int = 0 // 0: Bookmarks, 1: Annotations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBookmarkListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = applicationContext as my.hinoki.booxreader.BooxReaderApp
        syncRepo = createUserSyncRepository(app)
        repo = BookmarkRepository(app, syncRepo)
        annotationRepo = AnnotationRepository(syncRepo)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run {
            finish()
            return
        }

        setupTabs()

        binding.listBookmarks.setOnItemClickListener { _, _, position, _ ->
            val locatorJson = if (currentTabIndex == 0) {
                bookmarks.getOrNull(position)?.locatorJson
            } else {
                annotations.getOrNull(position)?.locatorJson
            } ?: return@setOnItemClickListener

            val result = Intent().apply {
                putExtra(EXTRA_LOCATOR_JSON, locatorJson)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        loadData()
    }

    private fun setupTabs() {
        val tabLayout = binding.tabLayout
        tabLayout.addTab(tabLayout.newTab().setText(R.string.reader_bookmark))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.annotation_list_title))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                renderList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadData() {
        lifecycleScope.launch {
            // Pull latest from remote (best effort)
            runCatching { syncRepo.pullBookmarks(bookId) }

            bookmarks = repo.getBookmarks(bookId)
            annotations = annotationRepo.getAnnotations(bookId)

            renderList()
        }
    }

    private fun renderList() {
        if (currentTabIndex == 0) {
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

            if (labels.isEmpty()) {
                binding.listBookmarks.visibility = android.view.View.GONE
                binding.tvEmptyState.text = getString(R.string.bookmark_empty_state)
                binding.tvEmptyState.visibility = android.view.View.VISIBLE
            } else {
                binding.listBookmarks.visibility = android.view.View.VISIBLE
                binding.tvEmptyState.visibility = android.view.View.GONE
            }
        } else {
            val labels = annotations.map {
                val time = android.text.format.DateFormat.format("MM-dd HH:mm", it.createdAt)
                val quote = it.selectedText.trim().replace("\n", " ").take(60)
                val note = it.note?.trim()?.take(60)

                val contentDesc = if (!note.isNullOrBlank()) {
                    "\"$quote\"\n💬 $note"
                } else {
                    "\"$quote\""
                }
                "$contentDesc ($time)"
            }

            binding.listBookmarks.adapter = ArrayAdapter(
                this@BookmarkListActivity,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                labels
            )

            if (labels.isEmpty()) {
                binding.listBookmarks.visibility = android.view.View.GONE
                binding.tvEmptyState.text = getString(R.string.annotation_list_empty)
                binding.tvEmptyState.visibility = android.view.View.VISIBLE
            } else {
                binding.listBookmarks.visibility = android.view.View.VISIBLE
                binding.tvEmptyState.visibility = android.view.View.GONE
            }
        }
    }
}
