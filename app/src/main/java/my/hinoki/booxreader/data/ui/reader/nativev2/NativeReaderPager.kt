package my.hinoki.booxreader.data.ui.reader.nativev2

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Manages pagination for native text content. */
class NativeReaderPager(
        private val textPaint: TextPaint,
        private val viewWidth: Int,
        private val viewHeight: Int
) {
    private var fullLayout: StaticLayout? = null
    private val pages = mutableListOf<PageRange>()
    private var currentText: String = ""

    data class PageRange(val startOffset: Int, val endOffset: Int)

    fun paginate(text: String): List<PageRange> {
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

    fun getPageText(pageIndex: Int): String {
        val range = pages.getOrNull(pageIndex) ?: return ""
        return currentText.substring(range.startOffset, range.endOffset)
    }

    val pageCount: Int
        get() = pages.size
}
