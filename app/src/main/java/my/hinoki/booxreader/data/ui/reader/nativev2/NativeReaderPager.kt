package my.hinoki.booxreader.data.ui.reader.nativev2

import android.text.Layout
import android.text.StaticLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils

/** Manages pagination for native text content. */
class NativeReaderPager(
        private val textPaint: TextPaint,
        private val viewWidth: Int,
        private val viewHeight: Int
) {
    private var fullLayout: StaticLayout? = null
    private val pages = mutableListOf<PageRange>()
    private var currentText: CharSequence = ""

    data class PageRange(val startOffset: Int, val endOffset: Int)

    fun paginate(text: CharSequence): List<PageRange> {
        this.currentText = text
        pages.clear()

        if (viewWidth <= 0 || viewHeight <= 0) return emptyList()

        val layout =
                StaticLayout.Builder.obtain(text, 0, text.length, textPaint, viewWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1.4f)
                        .setIncludePad(false)
                        .build()

        fullLayout = layout

        var currentPageStartLine = 0
        var totalLines = layout.lineCount

        while (currentPageStartLine < totalLines) {
            val startY = layout.getLineTop(currentPageStartLine)
            val maxY = startY + viewHeight

            var currentPageEndLine = currentPageStartLine
            while (currentPageEndLine < totalLines - 1 &&
                    layout.getLineBottom(currentPageEndLine + 1) <= maxY) {
                currentPageEndLine++
            }

            val startOffset = layout.getLineStart(currentPageStartLine)
            val endOffset = layout.getLineEnd(currentPageEndLine)
            pages.add(PageRange(startOffset, endOffset))

            currentPageStartLine = currentPageEndLine + 1
        }

        return pages
    }

    fun getPageText(pageIndex: Int): CharSequence {
        val range = pages.getOrNull(pageIndex) ?: return ""
        val slice = currentText.subSequence(range.startOffset, range.endOffset)
        if (currentText is Spanned) {
            val spanned = currentText as Spanned
            val out = SpannableStringBuilder(slice)
            TextUtils.copySpansFrom(
                    spanned,
                    range.startOffset,
                    range.endOffset,
                    Any::class.java,
                    out,
                    0
            )
            return out
        }
        return slice
    }

    fun getPageRange(pageIndex: Int): PageRange? {
        return pages.getOrNull(pageIndex)
    }

    val pageCount: Int
        get() = pages.size
}
