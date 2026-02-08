package my.hinoki.booxreader.data.ui.reader.nativev2

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Html
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import my.hinoki.booxreader.data.util.ChineseConverter
import my.hinoki.booxreader.ui.reader.nativev2.HtmlContentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlContentParserTest {
    @Test
    fun parseHtml_preservesHeadingSpans() {
        val parsed = HtmlContentParser.parseHtml("<h1>Header</h1><p>Body</p>", Color.BLACK)
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        val sizeSpans = spanned.getSpans(0, spanned.length, RelativeSizeSpan::class.java)
        val styleSpans = spanned.getSpans(0, spanned.length, StyleSpan::class.java)
        assertTrue(sizeSpans.isNotEmpty() || styleSpans.isNotEmpty())
    }

    @Test
    fun parseHtml_promotesChapterTitleClassToHeadingSpans() {
        val parsed =
                HtmlContentParser.parseHtml(
                        "<p class='chapter-title'>Chapter 1</p><p>Body</p>",
                        Color.BLACK
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        val titleStart = spanned.toString().indexOf("Chapter 1")
        val titleEnd = titleStart + "Chapter 1".length
        assertTrue(titleStart >= 0)
        val titleSizeSpans = spanned.getSpans(titleStart, titleEnd, RelativeSizeSpan::class.java)
        val titleStyleSpans = spanned.getSpans(titleStart, titleEnd, StyleSpan::class.java)
        assertTrue(titleSizeSpans.isNotEmpty() || titleStyleSpans.isNotEmpty())
    }

    @Test
    fun parseHtml_convertsBoldClassSpanToBoldStyleSpan() {
        val parsed =
                HtmlContentParser.parseHtml(
                        "<p><span class='bold'>Bold text</span> normal</p>",
                        Color.BLACK
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        val start = spanned.toString().indexOf("Bold text")
        val end = start + "Bold text".length
        assertTrue(start >= 0)
        val styleSpans = spanned.getSpans(start, end, StyleSpan::class.java)
        assertTrue(styleSpans.isNotEmpty())
    }

    @Test
    fun parseHtml_appliesChapterHeadingHeuristicOnFirstLine() {
        val parsed =
                HtmlContentParser.parseHtml(
                        "<p><span class='bold'>第五章 军事</span></p><p>正文内容</p>",
                        Color.BLACK
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        val start = spanned.toString().indexOf("第五章 军事")
        val end = start + "第五章 军事".length
        assertTrue(start >= 0)
        val sizeSpans = spanned.getSpans(start, end, RelativeSizeSpan::class.java)
        assertTrue(sizeSpans.any { it.sizeChange > 1f })
    }

    @Test
    fun parseHtml_onlyConvertsSuperSpan() {
        val parsed =
                HtmlContentParser.parseHtml(
                        "<span class='super'>1</span><span>Normal</span>",
                        Color.BLACK
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        assertEquals("1Normal", spanned.toString())

        val superscriptSpans = spanned.getSpans(0, 1, SuperscriptSpan::class.java)
        assertTrue(superscriptSpans.isNotEmpty())

        val normalStart = spanned.toString().indexOf("Normal")
        val normalEnd = normalStart + "Normal".length
        val normalSuperscripts =
                spanned.getSpans(normalStart, normalEnd, SuperscriptSpan::class.java)
        assertTrue(normalSuperscripts.isEmpty())
    }

    @Test
    fun parseHtml_withImageGetter_preservesImageSpan() {
        val imageGetter =
                Html.ImageGetter {
                    ColorDrawable(Color.GRAY).apply {
                        setBounds(0, 0, 64, 36)
                    }
                }

        val parsed =
                HtmlContentParser.parseHtml(
                        "<p>Before <img src='cover.jpg'/> After</p>",
                        Color.BLACK,
                        imageGetter
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned
        val imageSpans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
        assertTrue("Expected parsed HTML to retain image spans", imageSpans.isNotEmpty())
    }

    @Test
    fun parseHtml_withImageGetter_keepsImageSpanAfterChineseConversion() {
        val imageGetter =
                Html.ImageGetter {
                    ColorDrawable(Color.GRAY).apply {
                        setBounds(0, 0, 64, 36)
                    }
                }

        val parsed =
                HtmlContentParser.parseHtml(
                        "<p>简体前 <img src='cover.jpg'/> 简体后</p>",
                        Color.BLACK,
                        imageGetter
                )
        val converted = ChineseConverter.toTraditional(parsed)
        assertTrue(converted is Spanned)
        val spanned = converted as Spanned
        val imageSpans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
        assertTrue(
                "Expected image spans to survive simplified->traditional conversion",
                imageSpans.isNotEmpty()
        )
    }

    @Test
    fun parseHtml_blockquote_usesModernQuoteSpanInsteadOfDefaultQuoteSpan() {
        val parsed =
                HtmlContentParser.parseHtml(
                        "<blockquote>Quoted content</blockquote>",
                        Color.BLACK
                )
        assertTrue(parsed is Spanned)
        val spanned = parsed as Spanned

        val defaultQuoteSpans = spanned.getSpans(0, spanned.length, QuoteSpan::class.java)
        assertTrue(
                "Expected default QuoteSpan to be replaced by modern quote span",
                defaultQuoteSpans.isEmpty()
        )

        val margins = spanned.getSpans(0, spanned.length, LeadingMarginSpan::class.java)
        assertTrue(
                "Expected at least one custom modern quote span",
                margins.any { it.javaClass.name.contains("ModernQuoteSpan") }
        )
    }

    @Test
    fun parseHtmlWithAnchors_extractsAnchorOffsetsWithoutLeakingMarkers() {
        val result =
                HtmlContentParser.parseHtmlWithAnchors(
                        "<h2 id='chapter-start'>Chapter</h2><p id='section-2'>Section</p>",
                        Color.BLACK
                )
        val content = result.content.toString()
        val chapterOffset = result.anchorOffsets["chapter-start"]
        val sectionOffset = result.anchorOffsets["section-2"]

        assertTrue(chapterOffset != null)
        assertTrue(sectionOffset != null)
        assertTrue(sectionOffset!! > chapterOffset!!)
        assertFalse(content.contains("boox_anchor"))
    }

    @Test
    fun parseHtmlWithAnchors_decodesPercentEncodedAnchorId() {
        val result =
                HtmlContentParser.parseHtmlWithAnchors(
                        "<p id='sec%201'>Anchor text</p>",
                        Color.BLACK
                )

        assertTrue(result.anchorOffsets.containsKey("sec 1"))
    }
}
