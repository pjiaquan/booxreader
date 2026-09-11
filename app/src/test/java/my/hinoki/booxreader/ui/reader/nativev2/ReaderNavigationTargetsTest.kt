package my.hinoki.booxreader.ui.reader.nativev2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ReaderNavigationTargets` 的單元測試。
 *
 * 這些規則原本埋在 `NativeNavigatorFragment` 裡（1,500 行的 Fragment），決定「點 EPUB 內部
 * 連結會跳到哪一章」。百分比編碼、fragment/query 剝除、相對路徑 resolve、以及
 * 「模糊比對必須唯一命中」只要有一處錯，使用者就會跳到錯的章節 —— 而且只有真的點下去才會發現。
 *
 * 需要 Robolectric，因為 `Uri.decode` 來自 android.net.Uri。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReaderNavigationTargetsTest {

    private val readingOrder =
            listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/notes/footnotes.xhtml")

    private fun resolve(href: String, current: String? = "OEBPS/ch1.xhtml") =
            ReaderNavigationTargets.resolve(href, readingOrder, current)

    // --- normalizeHrefForMatch ---

    @Test
    fun normalizeReturnsEmptyForBlankInput() {
        assertEquals("", ReaderNavigationTargets.normalizeHrefForMatch(null))
        assertEquals("", ReaderNavigationTargets.normalizeHrefForMatch(""))
        assertEquals("", ReaderNavigationTargets.normalizeHrefForMatch("   "))
        assertEquals("", ReaderNavigationTargets.normalizeHrefForMatch("#onlyfragment"))
    }

    @Test
    fun normalizeStripsFragmentAndQuery() {
        assertEquals("ch1.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("ch1.xhtml#sec2"))
        assertEquals("ch1.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("ch1.xhtml?v=2"))
        assertEquals("ch1.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("ch1.xhtml?q=1#sec"))
    }

    @Test
    fun normalizeDecodesPercentEscapes() {
        assertEquals("my file.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("my%20file.xhtml"))
    }

    @Test
    fun normalizeResolvesDotSegments() {
        assertEquals("OEBPS/b.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("OEBPS/a/../b.xhtml"))
    }

    @Test
    fun normalizeKeepsOnlyThePathForAbsoluteUrls() {
        assertEquals(
                "books/a.xhtml",
                ReaderNavigationTargets.normalizeHrefForMatch("https://example.com/books/a.xhtml")
        )
    }

    @Test
    fun normalizeConvertsBackslashesAndStripsLeadingMarkers() {
        assertEquals("a/b.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("a\\b.xhtml"))
        assertEquals("a.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("./a.xhtml"))
        assertEquals("a.xhtml", ReaderNavigationTargets.normalizeHrefForMatch("/a.xhtml"))
        assertEquals("  a.xhtml  ".trim(), ReaderNavigationTargets.normalizeHrefForMatch("  a.xhtml  "))
    }

    // --- extractFragmentId ---

    @Test
    fun extractFragmentDecodesAndTrims() {
        assertEquals("sec1", ReaderNavigationTargets.extractFragmentId("ch1.xhtml#sec1"))
        assertEquals("sec 1", ReaderNavigationTargets.extractFragmentId("#sec%201"))
        assertEquals("sec", ReaderNavigationTargets.extractFragmentId("ch1.xhtml#  sec  "))
    }

    @Test
    fun extractFragmentReturnsNullWhenAbsentOrEmpty() {
        assertNull(ReaderNavigationTargets.extractFragmentId("ch1.xhtml"))
        assertNull(ReaderNavigationTargets.extractFragmentId("ch1.xhtml#"))
        assertNull(ReaderNavigationTargets.extractFragmentId("ch1.xhtml#   "))
    }

    // --- buildNavigationHrefCandidates ---

    @Test
    fun candidatesIncludeBothRawAndResolvedFormsForRelativeHrefs() {
        val candidates =
                ReaderNavigationTargets.buildNavigationHrefCandidates(
                        rawHref = "ch2.xhtml",
                        currentResourceHref = "OEBPS/ch1.xhtml"
                )
        assertTrue(candidates.contains("ch2.xhtml"))
        assertTrue(candidates.contains("OEBPS/ch2.xhtml"))
        assertEquals(2, candidates.size)
    }

    @Test
    fun candidatesSkipResolutionForAbsoluteHrefs() {
        val candidates =
                ReaderNavigationTargets.buildNavigationHrefCandidates(
                        rawHref = "https://example.com/books/a.xhtml",
                        currentResourceHref = "OEBPS/ch1.xhtml"
                )
        // 絕對 URL 不會再被 resolve 一次（只留 path 一種形式）
        assertEquals(setOf("books/a.xhtml"), candidates)
    }

    @Test
    fun candidatesAreEmptyForBlankHref() {
        assertTrue(ReaderNavigationTargets.buildNavigationHrefCandidates("   ", "OEBPS/ch1.xhtml").isEmpty())
    }

    // --- resolve ---

    @Test
    fun resolveFindsExactMatchAndCarriesTheFragment() {
        val target = resolve("OEBPS/ch2.xhtml#note3")
        assertEquals(NavigationTarget(index = 1, fragment = "note3"), target)
    }

    @Test
    fun resolveAcceptsRelativeHrefAgainstTheCurrentResource() {
        val target = resolve("ch2.xhtml")
        assertEquals(1, target?.index)
        assertNull(target?.fragment)
    }

    @Test
    fun resolveHandlesFragmentOnlyHrefsByUsingTheCurrentResource() {
        val target = resolve("#note9")
        assertEquals(0, target?.index)
        assertEquals("note9", target?.fragment)
    }

    @Test
    fun resolveMatchesByUniqueSuffixWhenExactMatchFails() {
        // 只有 "footnotes.xhtml" 這一段是唯一的後綴
        val target = resolve("notes/footnotes.xhtml")
        assertEquals(2, target?.index)
    }

    /**
     * 「相對於 current resource」解析出來的候選會**先**走精確比對，所以當 current 就在
     * 同一目錄時會直接命中，不會落到模糊比對。要驗證模糊比對的防護，current 必須在別的目錄。
     */
    @Test
    fun resolveIsExactWhenTheCurrentResourceIsInTheSameDirectory() {
        val order = listOf("a/ch1.xhtml", "b/ch1.xhtml")
        assertEquals(0, ReaderNavigationTargets.resolve("ch1.xhtml", order, "a/ch1.xhtml")?.index)
    }

    @Test
    fun resolveRefusesAmbiguousSuffixMatches() {
        val order = listOf("a/ch1.xhtml", "b/ch1.xhtml")
        // current 在別的目錄 -> 兩個候選都無法精確命中；模糊比對同時命中兩個 -> 不得亂猜
        assertNull(ReaderNavigationTargets.resolve("ch1.xhtml", order, "c/other.xhtml"))
    }

    @Test
    fun resolveRefusesTooShortSuffixCandidates() {
        // 短於 3 個字元的候選不參與模糊比對
        assertNull(ReaderNavigationTargets.resolve("a", readingOrder, "OEBPS/ch1.xhtml"))
    }

    @Test
    fun resolveReturnsNullForEmptyHrefOrEmptyReadingOrder() {
        assertNull(resolve("   "))
        assertNull(ReaderNavigationTargets.resolve("OEBPS/ch1.xhtml", emptyList(), "OEBPS/ch1.xhtml"))
    }

    @Test
    fun resolveDecodesPercentEncodedHrefsBeforeMatching() {
        val order = listOf("OEBPS/my file.xhtml")
        assertEquals(
                0,
                ReaderNavigationTargets.resolve("my%20file.xhtml", order, "OEBPS/my%20file.xhtml")?.index
        )
    }

    // --- targetsSameResource ---

    @Test
    fun targetsSameResourceComparesResolvedResources() {
        assertTrue(
                ReaderNavigationTargets.targetsSameResource(
                        targetHref = "OEBPS/ch1.xhtml#sec1",
                        loadedResourceHref = "OEBPS/ch1.xhtml",
                        readingOrderHrefs = readingOrder,
                        currentResourceHref = "OEBPS/ch1.xhtml"
                )
        )
        assertFalse(
                ReaderNavigationTargets.targetsSameResource(
                        targetHref = "OEBPS/ch2.xhtml",
                        loadedResourceHref = "OEBPS/ch1.xhtml",
                        readingOrderHrefs = readingOrder,
                        currentResourceHref = "OEBPS/ch1.xhtml"
                )
        )
    }

    @Test
    fun targetsSameResourceIsFalseWhenTheTargetCannotBeResolved() {
        assertFalse(
                ReaderNavigationTargets.targetsSameResource(
                        targetHref = "does-not-exist.xhtml",
                        loadedResourceHref = "OEBPS/ch1.xhtml",
                        readingOrderHrefs = readingOrder,
                        currentResourceHref = "OEBPS/ch1.xhtml"
                )
        )
    }
}
