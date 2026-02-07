package my.hinoki.booxreader.ui.reader.nativev2

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.RectF
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

    fun parseHtml(
            html: String,
            textColor: Int,
            imageGetter: Html.ImageGetter? = null
    ): CharSequence {
        val cleaned =
                html.replace(scriptRegex, "")
                        .replace(styleRegex, "")
                        .replace(superSpanRegex, "<sup>$1</sup>")

        val effectiveImageGetter = imageGetter ?: Html.ImageGetter { null }
        val parsed =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    Html.fromHtml(
                            cleaned,
                            Html.FROM_HTML_MODE_LEGACY,
                            effectiveImageGetter,
                            QuoteTagHandler(textColor)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    Html.fromHtml(cleaned, effectiveImageGetter, QuoteTagHandler(textColor))
                }

        val builder = SpannableStringBuilder(parsed)
        replaceDefaultQuoteSpans(builder, textColor)
        // When images are disabled, Android inserts placeholder object chars.
        // Strip those to avoid visual noise in text-only mode.
        if (imageGetter == null) {
            removeChar(builder, '□')
            removeChar(builder, '\uFFFC')
            removeSubstring(builder, "OBJ")
            removeSubstring(builder, "\u672A\u77E5")
        }
        collapseWhitespace(builder)
        trimWhitespace(builder)
        return builder
    }

    private fun replaceDefaultQuoteSpans(builder: SpannableStringBuilder, textColor: Int) {
        val defaultQuoteSpans =
                builder.getSpans(0, builder.length, android.text.style.QuoteSpan::class.java)
        for (span in defaultQuoteSpans) {
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            val flags = builder.getSpanFlags(span)
            builder.removeSpan(span)
            if (start >= 0 && end > start && end <= builder.length) {
                builder.setSpan(ModernQuoteSpan(textColor), start, end, flags)
            }
        }
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
        private val cardInsetHorizontal = 10f * density
        private val cardCorner = 12f * density
        private val accentInsetStart = 8f * density
        private val accentWidth = 3f * density
        private val textGap = 12f * density
        private val cardVerticalInset = 2f * density

        private val accentColor: Int
            get() = (textColor and 0x00FFFFFF) or 0xCC000000.toInt()

        private val backgroundColor: Int
            get() = (textColor and 0x00FFFFFF) or 0x14000000.toInt()

        private val borderColor: Int
            get() = (textColor and 0x00FFFFFF) or 0x26000000.toInt()

        override fun getLeadingMargin(first: Boolean): Int {
            return (cardInsetHorizontal + accentInsetStart + accentWidth + textGap).toInt()
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
            val originalStrokeWidth = paint.strokeWidth

            paint.style = Paint.Style.FILL
            paint.color = accentColor

            val topInset = top + cardVerticalInset
            val bottomInset = bottom - cardVerticalInset
            val accentStart =
                    if (dir >= 0) {
                        x + cardInsetHorizontal + accentInsetStart
                    } else {
                        x - cardInsetHorizontal - accentInsetStart - accentWidth
                    }
            val accentEnd = accentStart + accentWidth
            val accentRect = RectF(accentStart, topInset, accentEnd, bottomInset)
            canvas.drawRoundRect(accentRect, accentWidth, accentWidth, paint)

            paint.style = style
            paint.color = color
            paint.strokeWidth = originalStrokeWidth
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
            val style = paint.style
            val originalStrokeWidth = paint.strokeWidth

            val cardRect =
                    RectF(
                            left + cardInsetHorizontal,
                            top + cardVerticalInset,
                            right - cardInsetHorizontal,
                            bottom - cardVerticalInset
                    )

            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * density
            paint.color = borderColor
            canvas.drawRoundRect(cardRect, cardCorner, cardCorner, paint)

            paint.style = style
            paint.strokeWidth = originalStrokeWidth
            paint.color = color
        }
    }
}
