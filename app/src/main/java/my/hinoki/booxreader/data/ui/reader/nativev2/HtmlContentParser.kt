package my.hinoki.booxreader.data.ui.reader.nativev2

import android.content.res.Resources
import android.graphics.Paint
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan

internal object HtmlContentParser {
    private val scriptRegex =
            Regex("<script.*?>.*?</script>", RegexOption.DOT_MATCHES_ALL)
    private val styleRegex =
            Regex("<style.*?>.*?</style>", RegexOption.DOT_MATCHES_ALL)
    private val superSpanRegex =
            Regex(
                    "<span[^>]*class=[\"'][^\"']*\\bsuper\\b[^\"']*[\"'][^>]*>(.*?)</span>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

    fun parseHtml(html: String, textColor: Int): CharSequence {
        val cleaned =
                html.replace(scriptRegex, "")
                        .replace(styleRegex, "")
                        .replace(superSpanRegex, "<sup>$1</sup>")

        val imageGetter = Html.ImageGetter { null }
        val parsed =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    Html.fromHtml(
                            cleaned,
                            Html.FROM_HTML_MODE_LEGACY,
                            imageGetter,
                            QuoteTagHandler(textColor)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    Html.fromHtml(cleaned, imageGetter, QuoteTagHandler(textColor))
                }

        val builder = SpannableStringBuilder(parsed)
        removeChar(builder, '□')
        removeChar(builder, '\uFFFC')
        removeSubstring(builder, "OBJ")
        removeSubstring(builder, "\u672A\u77E5")
        collapseWhitespace(builder)
        trimWhitespace(builder)
        return builder
    }

    private fun removeChar(builder: SpannableStringBuilder, target: Char) {
        var index = 0
        while (index < builder.length) {
            if (builder[index] == target) {
                builder.delete(index, index + 1)
            } else {
                index++
            }
        }
    }

    private fun removeSubstring(builder: SpannableStringBuilder, target: String) {
        var index = builder.indexOf(target)
        while (index >= 0) {
            builder.delete(index, index + target.length)
            index = builder.indexOf(target, index)
        }
    }

    private fun collapseWhitespace(builder: SpannableStringBuilder) {
        var index = 0
        var runCount = 0
        while (index < builder.length) {
            if (builder[index].isWhitespace()) {
                runCount++
                if (runCount > 2) {
                    builder.delete(index, index + 1)
                    continue
                }
            } else {
                runCount = 0
            }
            index++
        }
    }

    private fun trimWhitespace(builder: SpannableStringBuilder) {
        while (builder.isNotEmpty() && builder.first().isWhitespace()) {
            builder.delete(0, 1)
        }
        while (builder.isNotEmpty() && builder.last().isWhitespace()) {
            builder.delete(builder.length - 1, builder.length)
        }
    }

    private class Mark

    private class QuoteTagHandler(private val textColor: Int) : Html.TagHandler {
        override fun handleTag(
                opening: Boolean,
                tag: String,
                output: Editable,
                xmlReader: org.xml.sax.XMLReader
        ) {
            if (!tag.equals("blockquote", ignoreCase = true)) return

            if (opening) {
                if (output.isNotEmpty() && output.last() != '\n') {
                    output.append("\n")
                }
                output.setSpan(Mark(), output.length, output.length, Spanned.SPAN_MARK_MARK)
            } else {
                val len = output.length
                val marks = output.getSpans(0, len, Mark::class.java)
                if (marks.isNotEmpty()) {
                    val start = output.getSpanStart(marks.last())
                    output.removeSpan(marks.last())
                    if (start != len) {
                        output.setSpan(
                                ModernQuoteSpan(textColor),
                                start,
                                len,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                if (output.isNotEmpty() && output.last() != '\n') {
                    output.append("\n")
                }
            }
        }
    }

    private class ModernQuoteSpan(private val textColor: Int) :
            LeadingMarginSpan, LineBackgroundSpan {

        private val density = Resources.getSystem().displayMetrics.density
        private val stripeWidth = (4 * density).toInt()
        private val gapWidth = (16 * density).toInt()

        private val stripeColor: Int
            get() = (textColor and 0x00FFFFFF) or 0xB3000000.toInt()

        private val backgroundColor: Int
            get() = (textColor and 0x00FFFFFF) or 0x0D000000.toInt()

        override fun getLeadingMargin(first: Boolean): Int {
            return stripeWidth + gapWidth
        }

        override fun drawLeadingMargin(
                canvas: android.graphics.Canvas,
                paint: Paint,
                x: Int,
                dir: Int,
                top: Int,
                baseline: Int,
                bottom: Int,
                text: CharSequence,
                start: Int,
                end: Int,
                first: Boolean,
                layout: Layout
        ) {
            val style = paint.style
            val color = paint.color

            paint.style = Paint.Style.FILL
            paint.color = stripeColor
            canvas.drawRect(
                    x.toFloat(),
                    top.toFloat(),
                    (x + dir * stripeWidth).toFloat(),
                    bottom.toFloat(),
                    paint
            )
            paint.style = style
            paint.color = color
        }

        override fun drawBackground(
                canvas: android.graphics.Canvas,
                paint: Paint,
                left: Int,
                right: Int,
                top: Int,
                baseline: Int,
                bottom: Int,
                text: CharSequence,
                start: Int,
                end: Int,
                lineNumber: Int
        ) {
            val color = paint.color
            paint.color = backgroundColor
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = color
        }
    }
}
