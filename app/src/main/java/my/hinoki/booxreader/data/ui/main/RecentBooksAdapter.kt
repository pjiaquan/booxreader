package my.hinoki.booxreader.data.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.reader.LocatorJsonHelper

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter

class RecentBooksAdapter(
    private val onClick: (BookEntity) -> Unit,
    private val onDelete: (BookEntity) -> Unit,
    private val onMarkCompleted: (BookEntity) -> Unit
) : ListAdapter<BookEntity, RecentBooksAdapter.RecentViewHolder>(BookDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_book, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onClick, onDelete, onMarkCompleted)
    }

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)

        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)
        private val tvCompleted: TextView = itemView.findViewById(R.id.tvCompleted)
        private val btnMenu: ImageButton = itemView.findViewById(R.id.btnMenu)

        fun bind(
            item: BookEntity,
            onClick: (BookEntity) -> Unit,
            onDelete: (BookEntity) -> Unit,
            onMarkCompleted: (BookEntity) -> Unit
        ) {
            // 
            tvTitle.text = item.title?.takeIf { it.isNotBlank() } ?: itemView.context.getString(R.string.book_title_untitled)
            tvTime.text = android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", item.lastOpenedAt)

            // Debug: 檢查原始數據
            if (item.lastLocatorJson != null) {
            }

            val locator = LocatorJsonHelper.fromJson(item.lastLocatorJson)

            if (locator != null) {
                // Use totalProgression (book-wide) if available, otherwise progression (chapter-wide)
                val totalProgression = locator.locations?.totalProgression
                val chapterProgression = locator.locations?.progression
                
                // 

                val progression = when {
                    // 優先使用 totalProgression (全書進度)
                    totalProgression != null && totalProgression > 0 && totalProgression <= 1.0 -> {
                        totalProgression
                    }
                    // 備用：使用章節進度，但更寬鬆的範圍檢查
                    chapterProgression != null && chapterProgression > 0 && chapterProgression <= 1.0 -> {
                        chapterProgression
                    }
                    else -> {
                        0.0
                    }
                }

                val percentage = (progression * 100).toInt().coerceIn(0, 100)

                // 
                tvProgress.text = itemView.context.getString(R.string.book_progress_format, percentage)
                tvCompleted.visibility = if (percentage >= 99) View.VISIBLE else View.GONE
            } else {
                // 
                tvProgress.text = itemView.context.getString(R.string.book_progress_format, 0)
                tvCompleted.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(item) }
            btnMenu.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    inflate(R.menu.menu_recent_book)
                    setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            R.id.action_mark_complete -> {
                                onMarkCompleted(item)
                                true
                            }
                            R.id.action_delete -> {
                                onDelete(item)
                                true
                            }
                            else -> false
                        }
                    }
                }.show()
            }
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<BookEntity>() {
        override fun areItemsTheSame(oldItem: BookEntity, newItem: BookEntity): Boolean {
            return oldItem.bookId == newItem.bookId
        }

        override fun areContentsTheSame(oldItem: BookEntity, newItem: BookEntity): Boolean {
            return oldItem == newItem
        }
    }
}
