package my.hinoki.booxreader.data.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.db.BookEntity
import my.hinoki.booxreader.reader.LocatorJsonHelper

class RecentBooksAdapter(
    private var items: List<BookEntity>,
    private val onClick: (BookEntity) -> Unit
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
        holder.bind(item, onClick)
    }

    override fun getItemCount(): Int = items.size

    class RecentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvPath: TextView = itemView.findViewById(R.id.tvPath)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvProgress: TextView = itemView.findViewById(R.id.tvProgress)

        fun bind(item: BookEntity, onClick: (BookEntity) -> Unit) {
            tvTitle.text = item.title?.takeIf { it.isNotBlank() } ?: "未命名書籍"
            tvPath.text = item.fileUri
            tvTime.text = android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", item.lastOpenedAt)

            val locator = LocatorJsonHelper.fromJson(item.lastLocatorJson)
            // Use totalProgression (book-wide) if available, otherwise progression (chapter-wide) 
            // Default to 0.0 if neither exists.
            val progression = locator?.locations?.totalProgression ?: locator?.locations?.progression ?: 0.0
            val percentage = (progression * 100).toInt().coerceIn(0, 100)
            tvProgress.text = "$percentage% complete"

            itemView.setOnClickListener { onClick(item) }
        }
    }
}

