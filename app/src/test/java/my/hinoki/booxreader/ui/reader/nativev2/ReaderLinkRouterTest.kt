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
        // mailto / tel 已改為允許（見 mailAndPhoneSchemesAreAllowedButScriptSchemesAreNot）
        listOf("javascript", "intent", "file", "content", "data", null)
                .forEach { scheme ->
                    assertFalse("scheme=$scheme should be blocked", ReaderLinkRouter.isAllowedExternalScheme(scheme))
                }
    }

    /**
     * `scheme:` 形式（沒有 `//`）**不是**內部連結。修正前它們全被當成內部連結，
     * 導致點 mailto:/tel: 沒反應。
     */
    @Test
    fun schemesWithoutDoubleSlashAreClassifiedAsExternal() {
        listOf("mailto:a@b.c", "tel:+886912345678", "javascript:alert(1)", "data:text/html,x")
                .forEach { url ->
                    assertTrue("$url should be External", ReaderLinkRouter.classify(url, null) is ReaderLinkTarget.External)
                }
    }

    @Test
    fun mailAndPhoneSchemesAreAllowedButScriptSchemesAreNot() {
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("mailto"))
        assertTrue(ReaderLinkRouter.isAllowedExternalScheme("tel"))
        assertFalse(ReaderLinkRouter.isAllowedExternalScheme("javascript"))
        assertFalse(ReaderLinkRouter.isAllowedExternalScheme("data"))
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
     * id 會經過 `Regex.escape`，因此 EPUB 常見帶 `.` 的 id 不會變成「任意字元」
     * 而命中錯的元素（修正前的行為是回傳 "WRONG"）。
     */
    @Test
    fun regexMetacharactersInTheIdAreEscaped() {
        val html = """<p id="secX1">WRONG</p><p id="sec.1">RIGHT</p>"""
        assertEquals("RIGHT", ReaderLinkRouter.extractElementById(html, "sec.1"))
    }

    @Test
    fun otherMetacharactersAreAlsoEscaped() {
        val html = """<p id="a+b">PLUS</p><p id="aab">WRONG</p>"""
        assertEquals("PLUS", ReaderLinkRouter.extractElementById(html, "a+b"))

        // id 內含 $ 與大括號：未 escape 時 "$" / "{1}" 會被 regex 當成錨點或量詞
        val dollarId = "a" + '$' + "{1}"
        val htmlWithDollar = """<p id="$dollarId">DOLLAR</p>"""
        assertEquals("DOLLAR", ReaderLinkRouter.extractElementById(htmlWithDollar, dollarId))
    }

    @Test
    fun plainIdsStillWorkAsBefore() {
        assertEquals("RIGHT", ReaderLinkRouter.extractElementById("""<p id="sec-1">RIGHT</p>""", "sec-1"))
    }

    // --- isInternalLink ---

    @Test
    fun relativePathsAndFragmentsAreInternal() {
        listOf("#sec", "ch2.xhtml", "text/ch2.xhtml#n", "../ch2.xhtml", "/OEBPS/ch2.xhtml", "./ch2.xhtml")
                .forEach { assertTrue("$it should be Internal", ReaderLinkRouter.isInternalLink(it)) }
    }

    @Test
    fun aColonInsideThePathDoesNotMakeItAScheme() {
        // scheme 必須出現在第一個 '/' 之前
        assertTrue(ReaderLinkRouter.isInternalLink("files/ch:2.xhtml"))
    }

    @Test
    fun anythingWithASchemeOrDoubleSlashIsNotInternal() {
        listOf("https://x/y", "mailto:a@b.c", "tel:+886", "javascript:void(0)", "file:///etc/passwd")
                .forEach { assertFalse("$it should not be Internal", ReaderLinkRouter.isInternalLink(it)) }
    }
}
