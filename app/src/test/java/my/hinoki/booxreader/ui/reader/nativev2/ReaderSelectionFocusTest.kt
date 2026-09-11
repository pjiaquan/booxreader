package my.hinoki.booxreader.ui.reader.nativev2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ReaderSelectionFocus` 的單元測試（純 JVM）。
 *
 * `resolve` 決定「從別台裝置同步過來的選取焦點，應該落在目前資源的哪個位置」，
 * 它依序嘗試：精確 offset 快路徑（還要比對內容）→ 字串唯一命中 → 多個命中時以
 * before/after 上下文評分，同分再比與偏好 offset 的距離。這些分支只要有一處錯，
 * 使用者跨裝置同步後就會跳到錯的字，而且只有真的同步過才會發現。
 */
class ReaderSelectionFocusTest {

    private fun target(
            highlight: String,
            before: String? = null,
            after: String? = null,
            startOffset: Int? = null,
            endOffset: Int? = null
    ) = SelectionFocusTarget(
            highlight = highlight,
            before = before,
            after = after,
            startOffset = startOffset,
            endOffset = endOffset
    )

    private fun resolve(text: String, target: SelectionFocusTarget) =
            ReaderSelectionFocus.resolve(text, target)

    // --- 精確 offset 快路徑 ---

    @Test
    fun emptyTextHasNoRange() {
        assertNull(resolve("", target("x", startOffset = 0, endOffset = 1)))
    }

    @Test
    fun exactOffsetsAreUsedWhenTheContentMatches() {
        val text = "hello world"
        assertEquals(
                TextRange(0, 5),
                resolve(text, target("hello", startOffset = 0, endOffset = 5))
        )
    }

    @Test
    fun exactOffsetsAreRejectedWhenTheContentDiffers() {
        val text = "hello world"
        // offset 指向 "hello" 但 highlight 是 "world" -> 快路徑失效，改用搜尋
        assertEquals(
                TextRange(6, 11),
                resolve(text, target("world", startOffset = 0, endOffset = 5))
        )
    }

    @Test
    fun invalidOffsetsFallBackToSearch() {
        val text = "hello world"
        listOf(
                        target("world", startOffset = -1, endOffset = 5),
                        target("world", startOffset = 5, endOffset = 5),
                        target("world", startOffset = 8, endOffset = 3),
                        target("world", startOffset = 0, endOffset = 99),
                        target("world", startOffset = null, endOffset = 11),
                        target("world", startOffset = 6, endOffset = null)
                )
                .forEach { assertEquals("offsets=$it", TextRange(6, 11), resolve(text, it)) }
    }

    // --- 搜尋與唯一命中 ---

    @Test
    fun blankHighlightOrTooLongHighlightHasNoRange() {
        assertNull(resolve("hello", target("   ")))
        assertNull(resolve("hi", target("longer than the text")))
    }

    @Test
    fun aSingleOccurrenceIsReturned() {
        assertEquals(TextRange(6, 11), resolve("hello world", target("world")))
    }

    @Test
    fun noOccurrenceHasNoRange() {
        assertNull(resolve("hello world", target("missing")))
    }

    @Test
    fun occurrencesAtTheVeryEndAreFound() {
        assertEquals(TextRange(3, 6), resolve("abcXYZ", target("XYZ")))
    }

    @Test
    fun cjkHighlightsWork() {
        // 第(0)一(1)段(2)文(3)字(4)。(5)第(6)二(7)段(8)文(9)字(10)。(11)
        val text = "第一段文字。第二段文字。"
        assertEquals(TextRange(6, 11), resolve(text, target("第二段文字")))
    }

    // --- 多個命中：上下文評分 ---

    @Test
    fun exactSurroundingContextPicksTheRightOccurrence() {
        val text = "cat dog cat bird cat"
        // 三處 "cat"；只有第二處前後是 "dog " / " bird"
        assertEquals(
                TextRange(8, 11),
                resolve(text, target("cat", before = "dog ", after = " bird"))
        )
    }

    /**
     * 部分符合（只有前/後 12 個字元相同）得 1 分，勝過完全沒有上下文（0 分）。
     * 這裡用「後文」構造：第 1 個 cat 後面有 15 個字元、前 12 個與 after 相同；
     * 第 2 個 cat 後面只有 "ZZZ"。
     */
    @Test
    fun partialContextScoresOneAndBeatsNoContext() {
        val prefix12 = "abcdefghijkl"
        val after = prefix12 + "XYZ" // 15 字元 -> take(12) == prefix12
        val text = "cat" + prefix12 + "!!" + "cat" + "ZZZ"

        assertEquals(TextRange(0, 3), resolve(text, target("cat", after = after)))
    }

    @Test
    fun exactAfterContextBeatsAPartialOne() {
        val after = "abcdefghijklXYZ" // take(12) == "abcdefghijkl"
        // 第 1 個 cat 後面緊接著完整的 after -> 2 分
        // 第 2 個 cat 後面是 "abcdefghijkl!!" -> 只有前 12 字元相符 -> 1 分
        val text = "cat" + after + "cat" + "abcdefghijkl!!"

        assertEquals(TextRange(0, 3), resolve(text, target("cat", after = after)))
    }

    @Test
    fun distanceToThePreferredOffsetBreaksScoreTies() {
        val text = "ab ab ab"
        // 三處分數相同（沒有上下文），偏好 offset 靠近第三處
        assertEquals(
                TextRange(6, 8),
                resolve(text, target("ab", startOffset = 6, endOffset = 8))
        )
        assertEquals(
                TextRange(0, 2),
                resolve(text, target("ab", startOffset = 0, endOffset = 2))
        )
    }

    @Test
    fun withoutAPreferredOffsetTheFirstMaximalOccurrenceWins() {
        // 全部分數與決勝鍵都相同時，Kotlin 的 maxWith 會保留第一個
        assertEquals(TextRange(0, 2), resolve("ab ab ab", target("ab")))
    }

    @Test
    fun overlappingOccurrencesAreAllConsidered() {
        val text = "aaaa"
        // "aa" 在 0、1、2 三處都能命中（cursor 每次只前進 1）
        assertEquals(
                TextRange(1, 3),
                resolve(text, target("aa", before = "a"))
        )
    }

    // --- progression → 頁碼 ---

    @Test
    fun progressionMapsToTheExpectedPage() {
        assertEquals(0, ReaderSelectionFocus.progressionToPage(0.0, 10))
        assertEquals(5, ReaderSelectionFocus.progressionToPage(0.5, 10))
        assertEquals(9, ReaderSelectionFocus.progressionToPage(0.99, 10))
        assertEquals(9, ReaderSelectionFocus.progressionToPage(1.0, 10))
    }

    @Test
    fun progressionIsClampedIntoThePageRange() {
        assertEquals(9, ReaderSelectionFocus.progressionToPage(1.5, 10))
        assertEquals(0, ReaderSelectionFocus.progressionToPage(-0.5, 10))
        assertEquals(0, ReaderSelectionFocus.progressionToPage(0.0, 1))
        assertEquals(0, ReaderSelectionFocus.progressionToPage(1.0, 1))
    }

    /**
     * 這個 helper 取代了原本四處 `(progression * pageCount).toInt().coerceIn(0, pageCount - 1)`，
     * 其中一處是「跳到最後一頁」＝ progression 1.0。
     */
    @Test
    fun fullProgressionEqualsTheLastPageIndex() {
        listOf(1, 2, 10, 250).forEach { pageCount ->
            assertEquals(pageCount - 1, ReaderSelectionFocus.progressionToPage(1.0, pageCount))
        }
    }

    /**
     * 原本的呼叫點都在 `pageCount > 0` 的保護之下，抽出後若少了這個判斷，
     * `coerceIn(0, -1)` 會直接丟 IllegalArgumentException。
     */
    @Test
    fun degeneratePageCountsReturnZeroInsteadOfThrowing() {
        assertEquals(0, ReaderSelectionFocus.progressionToPage(0.5, 0))
        assertEquals(0, ReaderSelectionFocus.progressionToPage(0.5, -3))
        assertEquals(0, ReaderSelectionFocus.progressionToPage(Double.NaN, 10))
    }
}
