package my.hinoki.booxreader.ui.reader

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.settings.MagicTag

class MagicTagManagerDialog(
    private val context: Context,
    private val initialTags: List<MagicTag>,
    private val onSave: (List<MagicTag>) -> Unit
) {

    private data class MagicTagRow(
        val id: String?,
        val titleInput: EditText,
        val contentInput: EditText,
        val container: View
    )

    fun show() {
        val rows = mutableListOf<MagicTagRow>()
        val contentLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 16)
            }

        val btnAdd =
            Button(context).apply { text = context.getString(R.string.action_manage_magic_tags) + " +" }
        contentLayout.addView(btnAdd)

        val scrollView = android.widget.ScrollView(context).apply { addView(contentLayout) }

        fun addRow(tag: MagicTag?) {
            val rowContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 16, 0, 16)
                }

            val titleInput =
                EditText(context).apply {
                    hint = "Tag Name"
                    setText(tag?.label.orEmpty())
                }
            val contentInput =
                EditText(context).apply {
                    hint = "Tag Content"
                    setText(tag?.content?.ifBlank { tag.label }.orEmpty())
                    minLines = 2
                }
            val btnDelete = Button(context).apply { text = "Delete" }

            rowContainer.addView(titleInput)
            rowContainer.addView(contentInput)
            rowContainer.addView(btnDelete)

            val row = MagicTagRow(tag?.id, titleInput, contentInput, rowContainer)
            rows.add(row)
            contentLayout.addView(rowContainer)

            btnDelete.setOnClickListener {
                rows.remove(row)
                contentLayout.removeView(rowContainer)
            }
        }

        initialTags.forEach { addRow(it) }

        btnAdd.setOnClickListener { addRow(null) }

        val dialog =
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.action_manage_magic_tags))
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                ?.setOnClickListener {
                    Log.d("MagicTags", "Save clicked; rows=${rows.size}")
                    val seen = mutableSetOf<String>()
                    val updatedTags =
                        rows.mapIndexedNotNull { index, row ->
                            val title = row.titleInput.text.toString().trim()
                            val content =
                                row.contentInput.text.toString().trim().ifBlank {
                                    title
                                }
                            if (title.isBlank()) {
                                if (content.isNotBlank()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string
                                                .magic_tag_invalid_empty_name
                                        ),
                                        Toast.LENGTH_SHORT
                                    )
                                        .show()
                                    return@setOnClickListener
                                }
                                return@mapIndexedNotNull null
                            }
                            if (!seen.add(title)) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.magic_tag_invalid_duplicate
                                    ),
                                    Toast.LENGTH_SHORT
                                )
                                    .show()
                                return@setOnClickListener
                            }
                            val id = row.id ?: "custom-${System.currentTimeMillis()}-$index"
                            MagicTag(
                                id = id,
                                label = title,
                                content = content,
                                description = content
                            )
                        }

                    onSave(updatedTags)

                    Toast.makeText(
                        context,
                        context.getString(R.string.action_manage_magic_tags) + " OK",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    dialog.dismiss()
                }
        }

        dialog.show()
    }
}