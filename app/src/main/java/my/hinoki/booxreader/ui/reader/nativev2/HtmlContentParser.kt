package my.hinoki.booxreader.ui.reader.nativev2

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
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
    private val tagWithIdRegex =
            Regex(
                    "<([a-zA-Z][\\w:-]*)([^>]*?)\\bid\\s*=\\s*([\"'])([^\"']+)\\3([^>]*)>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
    private const val anchorTokenPrefix = "__boox_anchor_"
    private const val anchorTokenSuffix = "__"

    data class ParsedResult(val content: CharSequence, val anchorOffsets: Map<String, Int>)

    fun parseHtml(
            html: String,
            textColor: Int,
            imageGetter: Html.ImageGetter? = null
    ): CharSequence {
        return parseHtmlWithAnchors(html, textColor, imageGetter).content
    }

    fun parseHtmlWithAnchors(
            html: String,
            textColor: Int,
            imageGetter: Html.ImageGetter? = null
    ): ParsedResult {
        val cleaned =
                html.replace(scriptRegex, "")
                        .replace(styleRegex, "")
                        .replace(superSpanRegex, "<sup>$1</sup>")
        val (annotatedHtml, tokenToAnchorId) = injectAnchorTokens(cleaned)

        val effectiveImageGetter = imageGetter ?: Html.ImageGetter { null }
        val parsed =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    Html.fromHtml(
                            annotatedHtml,
                            Html.FROM_HTML_MODE_LEGACY,
                            effectiveImageGetter,
                            QuoteTagHandler(textColor)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    Html.fromHtml(annotatedHtml, effectiveImageGetter, QuoteTagHandler(textColor))
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
        val anchorOffsets = extractAnchorOffsets(builder, tokenToAnchorId)
        return ParsedResult(content = builder, anchorOffsets = anchorOffsets)
    }

    private fun injectAnchorTokens(cleanedHtml: String): Pair<String, Map<String, String>> {
        val tokenToAnchorId = LinkedHashMap<String, String>()
        var tokenIndex = 0
        val annotatedHtml =
                cleanedHtml.replace(tagWithIdRegex) { match ->
                    val anchorId = match.groupValues[4].trim()
                    if (anchorId.isEmpty()) {
                        return@replace match.value
                    }
                    val token = "$anchorTokenPrefix${tokenIndex++}$anchorTokenSuffix"
                    tokenToAnchorId[token] = Uri.decode(anchorId)
                    "${match.value}$token"
                }
        return annotatedHtml to tokenToAnchorId
    }

    private fun extractAnchorOffsets(
            builder: SpannableStringBuilder,
            tokenToAnchorId: Map<String, String>
    ): Map<String, Int> {
        if (tokenToAnchorId.isEmpty()) return emptyMap()
        val anchorOffsets = LinkedHashMap<String, Int>()
        for ((token, anchorId) in tokenToAnchorId) {
            var index = builder.indexOf(token)
            while (index >= 0) {
                anchorOffsets.putIfAbsent(anchorId, index)
                builder.delete(index, index + token.length)
                index = builder.indexOf(token, index)
            }
        }
        return anchorOffsets
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
        private val cardInsetStart = 4f * density
        private val cardInsetEnd = 4f * density
        private val cardCorner = 10f * density
        private val accentInsetStart = 6f * density
        private val accentWidth = 2f * density
        private val textGap = 10f * density
        private val cardVerticalInset = 1.25f * density
        private val firstLineTopPadding = 10f * density

        private val accentColor: Int
            get() = (textColor and 0x00FFFFFF) or 0xA6000000.toInt()

        private val backgroundColor: Int
            get() = (textColor and 0x00FFFFFF) or 0x12000000.toInt()

        override fun getLeadingMargin(first: Boolean): Int {
            return (cardInsetStart + accentInsetStart + accentWidth + textGap).toInt()
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
            paint.color = accentColor

            val spanStart = (text as? Spanned)?.getSpanStart(this) ?: start
            val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: end
            val isFirstLine = start <= spanStart
            val isLastLine = end >= spanEnd

            val topInset =
                    if (isFirstLine) {
                        top - firstLineTopPadding + cardVerticalInset
                    } else {
                        top.toFloat()
                    }
            val bottomInset = if (isLastLine) bottom - cardVerticalInset else bottom.toFloat()
            val accentStart =
                    if (dir >= 0) {
                        x + cardInsetStart + accentInsetStart
                    } else {
                        x - cardInsetStart - accentInsetStart - accentWidth
                    }
            val accentEnd = accentStart + accentWidth
            val accentRect = RectF(accentStart, topInset, accentEnd, bottomInset)
            drawSegmentShape(
                    canvas = canvas,
                    paint = paint,
                    rect = accentRect,
                    isFirstLine = isFirstLine,
                    isLastLine = isLastLine,
                    corner = accentWidth
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
            val style = paint.style
            val spanStart = (text as? Spanned)?.getSpanStart(this) ?: start
            val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: end
            val isFirstLine = start <= spanStart
            val isLastLine = end >= spanEnd

            val lineStart = start.coerceAtLeast(spanStart)
            val lineEnd = end.coerceAtMost(spanEnd)
            val measuredEnd =
                    if (lineEnd > lineStart && text[lineEnd - 1] == '\n') lineEnd - 1 else lineEnd
            val lineTextWidth =
                    if (measuredEnd > lineStart) paint.measureText(text, lineStart, measuredEnd)
                    else 0f
            val textStart = left + getLeadingMargin(isFirstLine)
            val panelRight =
                    (textStart + lineTextWidth + cardInsetEnd)
                            .coerceAtMost((right - cardInsetEnd).toFloat())
            val panelTop =
                    if (isFirstLine) {
                        top - firstLineTopPadding + cardVerticalInset
                    } else {
                        top.toFloat()
                    }
            val panelBottom = if (isLastLine) bottom - cardVerticalInset else bottom.toFloat()

            val cardRect =
                    RectF(
                            left + cardInsetStart,
                            panelTop,
                            panelRight,
                            panelBottom
                    )

            paint.style = Paint.Style.FILL
            paint.color = backgroundColor
            drawSegmentShape(
                    canvas = canvas,
                    paint = paint,
                    rect = cardRect,
                    isFirstLine = isFirstLine,
                    isLastLine = isLastLine,
                    corner = cardCorner
            )

            paint.style = style
            paint.color = color
        }

        private fun drawSegmentShape(
                canvas: android.graphics.Canvas,
                paint: Paint,
                rect: RectF,
                isFirstLine: Boolean,
                isLastLine: Boolean,
                corner: Float
        ) {
            if (rect.width() <= 0f || rect.height() <= 0f) return
            if (isFirstLine && isLastLine) {
                canvas.drawRoundRect(rect, corner, corner, paint)
                return
            }
            if (!isFirstLine && !isLastLine) {
                canvas.drawRect(rect, paint)
                return
            }

            val radii =
                    if (isFirstLine) {
                        floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f)
                    } else {
                        floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner)
                    }
            val path = Path()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }
}
