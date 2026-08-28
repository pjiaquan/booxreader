package my.hinoki.booxreader.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.repo.createUserSyncRepository
import my.hinoki.booxreader.data.settings.MagicTag
import my.hinoki.booxreader.data.settings.ReaderSettings
import my.hinoki.booxreader.data.settings.SharedPreferencesStorage
import my.hinoki.booxreader.ui.common.BaseActivity
import my.hinoki.booxreader.ui.reader.ReaderActivity

/** Magic Tags manager (handoff screen 3f). Replaces the old dialog with a full list screen. */
class MagicTagListActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddTag: FloatingActionButton
    private lateinit var tvEmptyState: TextView
    private val adapter = MagicTagAdapter(
        onEdit = { showTagEditor(it) },
        onDelete = { deleteTag(it) }
    )

    private lateinit var prefs: SharedPreferences
    private var magicTags: List<MagicTag> = emptyList()
    private val userSyncRepository by lazy { createUserSyncRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_magic_tag_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.magic_tag_title)

        prefs = getSharedPreferences(ReaderActivity.PREFS_NAME, MODE_PRIVATE)

        recyclerView = findViewById(R.id.recyclerView)
        fabAddTag = findViewById(R.id.fabAddTag)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAddTag.setOnClickListener { showTagEditor(null) }

        loadTags()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadTags() {
        val settings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
        magicTags = settings.magicTags
        adapter.submitList(magicTags)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        tvEmptyState.visibility = if (magicTags.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun persist(newTags: List<MagicTag>) {
        magicTags = newTags
        adapter.submitList(newTags)
        updateEmptyState()

        val settings = ReaderSettings.fromStorage(SharedPreferencesStorage(prefs))
        settings.copy(magicTags = newTags, updatedAt = System.currentTimeMillis())
            .saveTo(SharedPreferencesStorage(prefs))
        pushSettingsToCloud()
        setResult(RESULT_OK)
    }

    private fun deleteTag(tag: MagicTag) {
        persist(magicTags.filterNot { it.id == tag.id })
        Toast.makeText(this, getString(R.string.magic_tag_delete) + ": " + tag.label, Toast.LENGTH_SHORT).show()
    }

    private fun showTagEditor(existing: MagicTag?) {
        val nameInput =
            EditText(this).apply {
                hint = getString(R.string.magic_tag_name_hint)
                setText(existing?.label.orEmpty())
                maxLines = 1
            }
        val contentInput =
            EditText(this).apply {
                hint = getString(R.string.magic_tag_desc_hint)
                setText(existing?.content.orEmpty())
                minLines = 2
            }
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 0)
                addView(nameInput)
                addView(
                    contentInput,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
                )
            }

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(if (existing == null) R.string.magic_tag_add_title else R.string.magic_tag_edit_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.magic_tag_invalid_empty_name, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val content = contentInput.text.toString().trim().ifBlank { name }
                val duplicate = magicTags.any { it.label == name && it.id != existing?.id }
                if (duplicate) {
                    Toast.makeText(this, R.string.magic_tag_invalid_duplicate, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (existing != null) {
                    persist(
                        magicTags.map {
                            if (it.id == existing.id) {
                                existing.copy(label = name, content = content, description = content)
                            } else {
                                it
                            }
                        }
                    )
                } else {
                    val newTag =
                        MagicTag(
                            id = "custom-${System.currentTimeMillis()}",
                            label = name,
                            content = content,
                            description = content
                        )
                    persist(magicTags + newTag)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun pushSettingsToCloud() {
        lifecycleScope.launch {
            userSyncRepository.pushSettings(ReaderSettings.fromStorage(SharedPreferencesStorage(prefs)))
        }
    }

    private class MagicTagAdapter(
        private val onEdit: (MagicTag) -> Unit,
        private val onDelete: (MagicTag) -> Unit
    ) : ListAdapter<MagicTag, MagicTagAdapter.VH>(Diff) {

        object Diff : DiffUtil.ItemCallback<MagicTag>() {
            override fun areItemsTheSame(old: MagicTag, new: MagicTag) = old.id == new.id
            override fun areContentsTheSame(old: MagicTag, new: MagicTag) = old == new
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val chip: TextView = view.findViewById(R.id.tvTagChip)
            val label: TextView = view.findViewById(R.id.tvTagLabel)
            val desc: TextView = view.findViewById(R.id.tvTagDesc)
            val edit: ImageButton = view.findViewById(R.id.btnTagEdit)
            val delete: ImageButton = view.findViewById(R.id.btnTagDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_magic_tag, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tag = getItem(position)
            holder.chip.text = tag.label.take(1).uppercase()
            holder.label.text = tag.label
            holder.desc.text = tag.description.ifBlank { tag.content }.ifBlank { tag.label }
            holder.edit.setOnClickListener { onEdit(tag) }
            holder.delete.setOnClickListener { onDelete(tag) }
        }
    }
}
