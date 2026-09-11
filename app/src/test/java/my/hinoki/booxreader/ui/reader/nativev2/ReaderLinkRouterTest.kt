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
 * `ReaderLinkRouter` 的單元測試。
 *
 * 這裡測的是 `NativeNavigatorFragment.handleLinkClick` 的判斷部分，其中**外部 scheme 白名單
 * 是安全政策**：只有 http/https 可以交給系統開啟，`javascript:`、`intent:`、`file:`、
 * `content:` 等一律封鎖。另一個重點是 fragment-only 連結的組合與切分 —— 錯了就跳錯章節
 * 或打不開 footnote。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReaderLinkRouterTest {

    // --- 外部 scheme 政策 ---

    @Test
    fun onlyHttpAndHttpsAreAllowedExternally() {
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("http"))
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("https"))
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("HTTPS"))
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("HtTp"))
    }

    @Test
    fun dangerousSchemesAreBlocked() {
        listOf("javascript", "intent", "file", "content", "data", "mailto", "tel", null)
                .forEach { scheme ->
                    assertFalse("scheme=$scheme should be blocked", ReaderLinkRouter.isAllowedExternalScheme(scheme))
                }
    }

    /**
     * ⚠️ **已知問題**（記錄現況、未變更行為）：內外部的判定是「不含 `://` 就算內部」，
     * 因此 `javascript:`、`mailto:`、`tel:` 這類**沒有 `//` 的 scheme** 一律被歸類為內部連結，
     * 不會走外部（也就不會開郵件 / 電話 App）。
     *
     * 對 javascript: 而言這反而意外安全（內部路徑不會建 Intent，只會找不到資源）；
     * 但 mailto:/tel: 是使用者可見的功能缺失。
     */
    @Test
    fun schemesWithoutDoubleSlashAreClassifiedAsInternal() {
        listOf("javascript:alert(1)", "mailto:a@b.c", "tel:+886912345678")
                .forEach { url ->
                    assertTrue("$url should be Internal", ReaderLinkRouter.classify(url, null) is ReaderLinkTarget.Internal)
                }
    }

    @Test
    fun allowListStillRejectsThoseSchemesIfTheyEverReachTheExternalPath() {
        assertFalse(ReaderLinkRouter.isAllowedExternalScheme("javascript"))
        assertFalse(ReaderLinkRouter.isAllowedExternalScheme("mailto"))
        assertFalse(ReaderLinkRouter.isAllowedExternalScheme("tel"))
    }

    @Test
    fun fileAndContentUrisAreBlocked() {
        listOf("file:///etc/passwd", "content://media/external/1", "intent://scan/#Intent;end")
                .forEach { url ->
                    val target = ReaderLinkRouter.classify(url, null) as ReaderLinkTarget.External
                    assertFalse("$url should be blocked", ReaderLinkRouter.isAllowedExternalScheme(target.scheme))
                }
    }

    @Test
    fun httpLinksAreClassifiedAsExternalWithLowercasedScheme() {
        val target = ReaderLinkRouter.classify("HTTPS://EXAMPLE.com/x", null)
        assertEquals(ReaderLinkTarget.External("HTTPS://EXAMPLE.com/x", "https"), target)
    }

    // --- 內部連結的判定與組合 ---

    @Test
    fun fragmentOnlyHrefIsJoinedWithTheCurrentResource() {
        val target = ReaderLinkRouter.classify("#sec1", "OEBPS/ch1.xhtml")
        assertEquals(
                ReaderLinkTarget.Internal(
                        href = "OEBPS/ch1.xhtml#sec1",
                        resourceHref = "OEBPS/ch1.xhtml",
                        fragmentId = "sec1"
                ),
                target
        )
    }

    @Test
    fun hrefWithoutSchemeIsInternal() {
        assertEquals(
                ReaderLinkTarget.Internal("ch2.xhtml", "ch2.xhtml", null),
                ReaderLinkRouter.classify("ch2.xhtml", "OEBPS/ch1.xhtml")
        )
    }

    @Test
    fun relativeHrefWithFragmentIsSplitIntoResourceAndFragment() {
        assertEquals(
                ReaderLinkTarget.Internal("text/ch2.xhtml#note", "text/ch2.xhtml", "note"),
                ReaderLinkRouter.classify("text/ch2.xhtml#note", "OEBPS/ch1.xhtml")
        )
    }

    @Test
    fun queryStringsDoNotMakeAHrefExternal() {
        val target = ReaderLinkRouter.classify("ch2.xhtml?q=1", "OEBPS/ch1.xhtml")
        assertTrue(target is ReaderLinkTarget.Internal)
    }

    @Test
    fun onlyTheFirstFragmentIsUsed() {
        assertEquals(
                ReaderLinkTarget.Internal("ch2.xhtml#a#b", "ch2.xhtml", "a"),
                ReaderLinkRouter.classify("ch2.xhtml#a#b", "OEBPS/ch1.xhtml")
        )
    }

    /**
     * 記錄一個既有的邊界行為：fragment-only 連結在**沒有** current resource 時，
     * href 維持原樣，切出來的 resourceHref 是空字串（呼叫端之後會 fallback 成 go(href)）。
     */
    @Test
    fun fragmentOnlyHrefWithoutCurrentResourceKeepsAnEmptyResource() {
        assertEquals(
                ReaderLinkTarget.Internal("#sec", "", "sec"),
                ReaderLinkRouter.classify("#sec", null)
        )
    }

    /**
     * 帶 `#` 但 fragment 為空時，fragmentId 是空字串而不是 null
     * （因為判斷用的是「原字串是否含 #」）。
     */
    @Test
    fun trailingHashProducesAnEmptyFragmentRatherThanNull() {
        assertEquals("", (ReaderLinkRouter.classify("ch2.xhtml#", "ch1.xhtml") as ReaderLinkTarget.Internal).fragmentId)
    }

    // --- footnote 內容抽取 ---

    @Test
    fun extractsTheElementContentById() {
        val html = """<div><p id="fn1">First note</p><p id="fn2">Second</p></div>"""
        assertEquals("First note", ReaderLinkRouter.extractElementById(html, "fn1"))
        assertEquals("Second", ReaderLinkRouter.extractElementById(html, "fn2"))
    }

    @Test
    fun extractsEvenWhenOtherAttributesComeFirst() {
        val html = """<p class="note" data-x="1" id="fn1">Text</p>"""
        assertEquals("Text", ReaderLinkRouter.extractElementById(html, "fn1"))
    }

    @Test
    fun capturesMultilineContent() {
        val html = "<div id=\"fn1\">\n  plain text\n  more\n</div>"
        assertEquals("\n  plain text\n  more\n", ReaderLinkRouter.extractElementById(html, "fn1"))
    }

    /**
     * ⚠️ **已知問題**（記錄現況、未變更行為）：`(.*?)` 是 lazy，會在**第一個**結束標籤就停止，
     * 因此 footnote 內容只要含有巢狀標籤（`<b>`、`<i>`、`<a>`）就會被截斷。
     * 修法是改用非貪婪但以「對應的結束標籤」結尾，或改用 HTML parser。
     */
    @Test
    fun nestedMarkupTruncatesTheExtractedContent() {
        val html = "<div id=\"fn1\">\n  <b>bold</b>\n  plain\n</div>"
        assertEquals("\n  <b>bold", ReaderLinkRouter.extractElementById(html, "fn1"))
    }

    @Test
    fun returnsNullWhenTheIdIsAbsent() {
        assertNull(ReaderLinkRouter.extractElementById("<p id=\"other\">x</p>", "fn1"))
        assertNull(ReaderLinkRouter.extractElementById("", "fn1"))
    }

    /**
     * ⚠️ **已知問題**（本次只記錄、未變更行為）：id 直接內插進 regex，因此 EPUB 中常見
     * 帶 `.` 的 id 會變成「任意字元」而命中錯的元素。
     *
     * 這裡的 id `sec.1` 會先命中 `id="secX1"`，回傳錯誤的 footnote 內容。
     * 修法是一行：`Regex.escape(id)`（對沒有特殊字元的 id 行為完全相同）。
     * 因為屬於使用者可見的行為變更，另外提出而不是順手改。
     */
    @Test
    fun dotInFragmentIdCurrentlyMatchesAnyCharacter() {
        val html = """<p id="secX1">WRONG</p><p id="sec.1">RIGHT</p>"""
        assertEquals("WRONG", ReaderLinkRouter.extractElementById(html, "sec.1"))
    }

    /**
     * 對照組：不含 regex 特殊字元的 id 完全正常。
     */
    @Test
    fun plainIdsAreUnaffectedByThatIssue() {
        val html = """<p id="sec-1">RIGHT</p>"""
        assertEquals("RIGHT", ReaderLinkRouter.extractElementById(html, "sec-1"))
    }
}
