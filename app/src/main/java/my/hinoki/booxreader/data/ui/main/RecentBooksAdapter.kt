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

class RecentBooksAdapter(
    private var items: List<BookEntity>,
    private val onClick: (BookEntity) -> Unit,
    private val onDelete: (BookEntity) -> Unit,
    private val onMarkCompleted: (BookEntity) -> Unit
) : RecyclerView.Adapter<RecentBooksAdapter.RecentViewHolder>() {

    fun submit(list: List<BookEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_book, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, onClick, onDelete, onMarkCompleted)
    }

    override fun getItemCount(): Int = items.size

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvPath: TextView = itemView.findViewById(R.id.tvPath)
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
            android.util.Log.d("RecentBooksAdapter", "bind() 被調用 - 書籍: ${item.title}")
            tvTitle.text = item.title?.takeIf { it.isNotBlank() } ?: "未命名書籍"
            tvPath.text = item.fileUri
            tvTime.text = android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", item.lastOpenedAt)

            // Debug: 檢查原始數據
            android.util.Log.d("RecentBooksAdapter", "書籍: ${item.title}")
            android.util.Log.d("RecentBooksAdapter", "lastLocatorJson: ${item.lastLocatorJson}")

            val locator = LocatorJsonHelper.fromJson(item.lastLocatorJson)

            if (locator != null) {
                android.util.Log.d("RecentBooksAdapter", "Locator 解析成功 - 書籍: ${item.title}")
                android.util.Log.d("RecentBooksAdapter", "原始JSON: ${item.lastLocatorJson}")
                android.util.Log.d("RecentBooksAdapter", "Locator href: ${locator.href}")
                android.util.Log.d("RecentBooksAdapter", "totalProgression: ${locator.locations?.totalProgression}")
                android.util.Log.d("RecentBooksAdapter", "progression: ${locator.locations?.progression}")
                android.util.Log.d("RecentBooksAdapter", "position: ${locator.locations?.position}")
                android.util.Log.d("RecentBooksAdapter", "locations object: ${locator.locations}")

                // Use totalProgression (book-wide) if available, otherwise progression (chapter-wide)
                val totalProgression = locator.locations?.totalProgression
                val chapterProgression = locator.locations?.progression

                val progression = when {
                    // 優先使用 totalProgression (全書進度)
                    totalProgression != null && totalProgression > 0 && totalProgression <= 1.0 -> {
                        android.util.Log.d("RecentBooksAdapter", "使用 totalProgression: $totalProgression")
                        totalProgression
                    }
                    // 備用：使用章節進度，但更寬鬆的範圍檢查
                    chapterProgression != null && chapterProgression > 0 && chapterProgression <= 1.0 -> {
                        android.util.Log.d("RecentBooksAdapter", "使用 chapter progression: $chapterProgression")
                        chapterProgression
                    }
                    else -> {
                        android.util.Log.d("RecentBooksAdapter", "無有效進度信息，使用預設值 0.0")
                        0.0
                    }
                }

                val percentage = (progression * 100).toInt().coerceIn(0, 100)

                android.util.Log.d("RecentBooksAdapter", "最終進度: $progression, 百分比: $percentage%")
                tvProgress.text = "$percentage% complete"
                tvCompleted.visibility = if (percentage >= 99) View.VISIBLE else View.GONE
            } else {
                android.util.Log.d("RecentBooksAdapter", "Locator 解析失敗 - 書籍: ${item.title}, JSON: ${item.lastLocatorJson}")
                tvProgress.text = "0% complete"
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
}
