package my.hinoki.booxreader.ui.reader.nativev2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ReaderTextSelection` 的單元測試（純 JVM，不需要 Robolectric）。
 *
 * 這些邏輯原本埋在 1,300 行的 `NativeReaderView` 裡、沒有任何測試，
 * 而它決定了長按選取時「選到什麼」—— off-by-one 或 CJK 判斷錯誤都只會在使用者
 * 長按時才被發現。這裡把各種邊界情況固定下來。
 */
class ReaderTextSelectionTest {

    private fun bounds(text: String, offset: Int) = ReaderTextSelection.wordBoundariesAt(text, offset)

    // --- 英數字：擴展到整個詞 ---

    @Test
    fun asciiWordSelectsWholeWord() {
        val text = "hello world"
        assertEquals(0 to 5, bounds(text, 0))
        assertEquals(0 to 5, bounds(text, 2))
        assertEquals(0 to 5, bounds(text, 4))
        assertEquals(6 to 11, bounds(text, 6))
        assertEquals(6 to 11, bounds(text, 10))
    }

    @Test
    fun offsetOnWhitespaceSelectsSingleCharacter() {
        val text = "hello world"
        assertEquals(5 to 6, bounds(text, 5))
    }

    @Test
    fun offsetOnPunctuationSelectsSingleCharacter() {
        val text = "end. Next"
        assertEquals(3 to 4, bounds(text, 3))
    }

    @Test
    fun digitsUnderscoreAndApostropheCountAsWordCharacters() {
        assertEquals(0 to 8, bounds("foo_bar1 baz", 3))
        assertEquals(0 to 5, bounds("don't stop", 2))
        // 彎引號（’）也算詞字元
        assertEquals(0 to 5, bounds("don’t stop", 2))
    }

    // --- CJK：逐字選取 ---

    @Test
    fun cjkSelectsSingleCharacter() {
        val text = "中文測試"
        assertEquals(0 to 1, bounds(text, 0))
        assertEquals(1 to 2, bounds(text, 1))
        assertEquals(3 to 4, bounds(text, 3))
    }

    @Test
    fun mixedScriptSelectsPerCharacterWhenThePressedCharacterIsCjk() {
        val text = "abc中文def"
        // 按在 CJK 上 -> 只選該字
        assertEquals(3 to 4, bounds(text, 3))
        assertEquals(4 to 5, bounds(text, 4))
    }

    /**
     * 記錄一個**既有的**（可能非預期的）行為：`isWordChar` 用的是 `isLetterOrDigit()`，
     * 而 CJK 字元在 Java 裡算 letter，因此從拉丁字母往右擴展時會**穿過 CJK**，
     * 把整串拉丁+CJK 一起選起來（"abc中文def" 全部選取）。
     *
     * 這不是本次抽取造成的（抽取前後行為一致），先以測試固定現況；
     * 若要做成「CJK 與拉丁詞互不跨越」屬於行為變更，需要實際裝置驗證。
     */
    @Test
    fun latinWordExtensionCurrentlyCrossesAdjacentCjkRuns() {
        val text = "abc中文def"
        assertEquals(0 to 8, bounds(text, 1))
        assertEquals(0 to 8, bounds(text, 6))
    }

    @Test
    fun cjkPunctuationAndFullWidthSpaceAreTreatedAsCjk() {
        // U+3002 句號、U+3000 全形空白都落在 CJK Symbols and Punctuation 範圍
        assertEquals(0 to 1, bounds("。好", 0))
        assertEquals(0 to 1, bounds("\u3000abc", 0))
    }

    @Test
    fun kanaAndHangulAreTreatedAsCjk() {
        assertEquals(0 to 1, bounds("ひらがな", 0))
        assertEquals(0 to 1, bounds("カタカナ", 0))
        assertEquals(0 to 1, bounds("한글", 0))
    }

    // --- 邊界與夾取 ---

    @Test
    fun emptyTextReturnsAnEmptyRangeAtTheOffset() {
        // 空字串時把 offset 原樣回傳（不是硬性 0），呼叫端另外會檢查 first < second
        assertEquals(0 to 0, bounds("", 0))
        assertEquals(5 to 5, bounds("", 5))
    }

    @Test
    fun singleCharacterTextReturnsThatCharacter() {
        assertEquals(0 to 1, bounds("a", 0))
        assertEquals(0 to 1, bounds("中", 0))
    }

    @Test
    fun offsetOutsideTextIsClamped() {
        val text = "hello"
        assertEquals(0 to 5, bounds(text, -10))
        assertEquals(0 to 5, bounds(text, 99))
    }

    @Test
    fun wordTouchingBothEndsIsFullySelected() {
        assertEquals(0 to 3, bounds("abc", 1))
    }

    @Test
    fun rangeIsAlwaysWithinTextAndNonEmpty() {
        val samples = listOf("hello world", "中文測試", "abc中文def", "a", " ", "。", "don't_stop 123")
        samples.forEach { text ->
            for (offset in -1..text.length) {
                val (start, end) = bounds(text, offset)
                assertTrue("start<0 for $text@$offset", start >= 0)
                assertTrue("end>len for $text@$offset", end <= text.length)
                assertTrue("empty range for $text@$offset", start < end)
            }
        }
    }

    // --- 字元分類 ---

    @Test
    fun isWordCharAcceptsLettersDigitsApostropheUnderscore() {
        assertTrue(ReaderTextSelection.isWordChar('a'))
        assertTrue(ReaderTextSelection.isWordChar('Z'))
        assertTrue(ReaderTextSelection.isWordChar('7'))
        assertTrue(ReaderTextSelection.isWordChar('_'))
        assertTrue(ReaderTextSelection.isWordChar('\''))
        assertTrue(ReaderTextSelection.isWordChar('’'))
        // 非 ASCII 字母（法文、希臘文、俄文）也算
        assertTrue(ReaderTextSelection.isWordChar('é'))
        assertTrue(ReaderTextSelection.isWordChar('Ω'))
        assertTrue(ReaderTextSelection.isWordChar('д'))
    }

    @Test
    fun isWordCharRejectsWhitespaceAndPunctuation() {
        assertFalse(ReaderTextSelection.isWordChar(' '))
        assertFalse(ReaderTextSelection.isWordChar('\n'))
        assertFalse(ReaderTextSelection.isWordChar('.'))
        assertFalse(ReaderTextSelection.isWordChar('-'))
    }

    /**
     * CJK 字元**同時也是** word char（`isLetterOrDigit()` 對漢字為 true），
     * 這正是上面「拉丁詞會穿過 CJK」的原因。
     */
    @Test
    fun cjkCharactersAreAlsoWordCharacters() {
        assertTrue(ReaderTextSelection.isWordChar('中'))
        assertTrue(ReaderTextSelection.isWordChar('あ'))
        assertTrue(ReaderTextSelection.isWordChar('한'))
    }

    @Test
    fun isCjkCoversTheDocumentedRanges() {
        assertTrue("CJK ideograph", ReaderTextSelection.isCjk('中'))
        assertTrue("Extension A", ReaderTextSelection.isCjk('\u3400'))
        assertTrue("compatibility ideograph", ReaderTextSelection.isCjk('\uF900'))
        assertTrue("CJK punctuation", ReaderTextSelection.isCjk('\u3002'))
        assertTrue("hiragana", ReaderTextSelection.isCjk('あ'))
        assertTrue("katakana", ReaderTextSelection.isCjk('ア'))
        assertTrue("hangul", ReaderTextSelection.isCjk('한'))
        assertTrue("bopomofo", ReaderTextSelection.isCjk('\u3105'))
    }

    @Test
    fun isCjkIsFalseForNonCjkScripts() {
        assertFalse(ReaderTextSelection.isCjk('a'))
        assertFalse(ReaderTextSelection.isCjk('Ω'))
        assertFalse(ReaderTextSelection.isCjk('д'))
        assertFalse(ReaderTextSelection.isCjk('1'))
        assertFalse(ReaderTextSelection.isCjk(' '))
    }
}
