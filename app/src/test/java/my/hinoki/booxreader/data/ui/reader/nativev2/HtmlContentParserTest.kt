package my.hinoki.booxreader.data.ui.reader.nativev2

import android.graphics.Color
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import my.hinoki.booxreader.ui.reader.nativev2.HtmlContentParser
import org.junit.Assert.assertEquals
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
}
