package my.hinoki.booxreader.ui.reader.nativev2

import android.text.SpannableString
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeReaderPagerTest {

    private lateinit var textPaint: TextPaint

    @Before
    fun setup() {
        textPaint = TextPaint().apply {
            textSize = 20f
        }
    }

    @Test
    fun paginate_withInvalidDimensions_returnsEmptyList() {
        val pagerZeroWidth = NativeReaderPager(textPaint, 0, 100)
        val pagesZeroWidth = pagerZeroWidth.paginate("Some text")
        assertTrue(pagesZeroWidth.isEmpty())

        val pagerNegativeHeight = NativeReaderPager(textPaint, 100, -50)
        val pagesNegativeHeight = pagerNegativeHeight.paginate("Some text")
        assertTrue(pagesNegativeHeight.isEmpty())
    }

    @Test
    fun paginate_splitsTextIntoPages() {
        // A standard TextPaint with size 20f might fit about 5 chars per line if width is 100
        val text = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
        val pager = NativeReaderPager(textPaint, 100, 50)

        val pages = pager.paginate(text)

        assertTrue("Expected multiple pages", pages.size > 1)
        assertEquals(0, pages.first().startOffset)
        assertEquals(text.length, pages.last().endOffset)

        // Ensure contiguous pages
        for (i in 0 until pages.size - 1) {
            assertEquals(pages[i].endOffset, pages[i + 1].startOffset)
        }
    }

    @Test
    fun getPageText_returnsCorrectTextSlice() {
        val text = "Hello world\nThis is a test of pagination."
        val pager = NativeReaderPager(textPaint, 150, 100)
        pager.paginate(text)

        val firstPageRange = pager.getPageRange(0)!!
        val firstPageText = pager.getPageText(0)

        assertEquals(text.substring(firstPageRange.startOffset, firstPageRange.endOffset), firstPageText.toString())

        val outOfBoundsText = pager.getPageText(999)
        assertEquals("", outOfBoundsText)
    }

    @Test
    fun getPageText_preservesSpans() {
        val text = SpannableString("Hello bold world")
        val span = RelativeSizeSpan(1.5f)
        text.setSpan(span, 6, 10, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Make the view large enough to hold all text in one page
        val pager = NativeReaderPager(textPaint, 1000, 1000)
        pager.paginate(text)

        val pageText = pager.getPageText(0) as android.text.Spanned
        val spans = pageText.getSpans(0, pageText.length, RelativeSizeSpan::class.java)

        assertEquals(1, spans.size)
        assertEquals(1.5f, spans[0].sizeChange)
    }

    @Test
    fun getPageRange_returnsCorrectRange() {
        val text = "Short text"
        val pager = NativeReaderPager(textPaint, 100, 100)
        val pages = pager.paginate(text)

        assertEquals(pages[0], pager.getPageRange(0))
        assertNull(pager.getPageRange(-1))
        assertNull(pager.getPageRange(999))
    }

    @Test
    fun findPageForOffset_returnsCorrectPageIndex() {
        val text = "Page 1\nPage 2\nPage 3"
        // Adjust width and height to force multiple pages
        val pager = NativeReaderPager(textPaint, 50, 30)
        val pages = pager.paginate(text)

        assertTrue("Expected multiple pages", pages.size > 1)
        val secondPage = pages[1]
        val indexInSecondPage = secondPage.startOffset + 1

        val foundIndex = pager.findPageForOffset(indexInSecondPage)
        assertEquals(1, foundIndex)

        // Out of bounds offsets clamp
        assertEquals(pages.size - 1, pager.findPageForOffset(9999))
        assertEquals(0, pager.findPageForOffset(-5))
    }

    @Test
    fun findPageForOffset_emptyPages_returnsNull() {
        val pager = NativeReaderPager(textPaint, 100, 100)
        assertNull(pager.findPageForOffset(5))
    }

    @Test
    fun pageCount_returnsCorrectSize() {
        val text = "Line 1\nLine 2"
        val pager = NativeReaderPager(textPaint, 100, 100)
        pager.paginate(text)

        assertEquals(1, pager.pageCount)
    }
}
