package my.hinoki.booxreader.ui.reader.nativev2

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * `ReaderOffsetResolver` 的單元測試。
 *
 * 這裡測的是「手指在哪裡 → 選到哪個字」的規則：邊緣吸附（快速 vs 慢速採用不同容差）、
 * 以及慢速拖曳時**必須拖過該字寬的一定比例才會推進**（CJK 0.82、標點 0.78、其他 0.7）。
 * 這些原本是 `NativeReaderView` 的私有方法，只有用手指在裝置上慢慢拖才會被驗證。
 *
 * 用假的排版模型（FakeLayout）精確控制每個 offset 的 x 座標；
 * 不用 Robolectric 的 `Layout`，因為它的排版是退化的。
 */
class ReaderOffsetResolverTest {

    /**
     * 單行或雙行的假排版：每個字元固定 [charWidth] 寬、每行固定 [lineHeight] 高，
     * 每行的水平座標從 0 重新起算（與真實 `Layout` 一致）。
     */
    private class FakeLayout(
            private val lineRanges: List<IntRange>,
            private val textLength: Int,
            private val charWidth: Float = 10f,
            override val width: Int = 400,
            private val lineHeight: Int = 40
    ) : OffsetLayoutMetrics {

        override val height: Int = lineRanges.size * lineHeight

        override fun lineForVertical(y: Int): Int {
            var found = 0
            lineRanges.indices.forEach { index -> if (y >= index * lineHeight) found = index }
            return found
        }

        override fun lineStart(line: Int): Int = lineRanges[line].first

        override fun lineEnd(line: Int): Int = lineRanges[line].last + 1

        override fun lineForOffset(offset: Int): Int {
            val clamped = offset.coerceIn(0, textLength)
            return lineRanges.indexOfFirst { clamped >= it.first && clamped <= it.last }
                    .takeIf { it >= 0 }
                    ?: (lineRanges.size - 1)
        }

        override fun offsetForHorizontal(line: Int, x: Float): Int =
                (lineStart(line) + (x / charWidth).roundToInt()).coerceIn(lineStart(line), lineEnd(line))

        override fun offsetToRightOf(offset: Int): Int = (offset + 1).coerceAtMost(textLength)

        override fun offsetToLeftOf(offset: Int): Int = (offset - 1).coerceAtLeast(0)

        override fun primaryHorizontal(offset: Int): Float {
            val clamped = offset.coerceIn(0, textLength)
            val line = lineForOffset(clamped)
            return (clamped - lineStart(line)) * charWidth
        }
    }

    private fun singleLine(text: String) = FakeLayout(listOf(text.indices), text.length)

    private fun resolve(
            x: Float,
            y: Float = 10f,
            content: String,
            metrics: OffsetLayoutMetrics = singleLine(content),
            precise: Boolean = false,
            anchor: Int? = null,
            paddingLeft: Int = 0,
            paddingTop: Int = 0
    ) = ReaderOffsetResolver.resolve(
            x = x,
            y = y,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            content = content,
            metrics = metrics,
            preciseEdgeSnapThresholdPx = 18f,
            preciseMode = precise,
            anchorOffset = anchor
    )

    // --- 邊緣吸附 ---

    @Test
    fun normalDragSnapsToLineEdgesWithinTenPercentOfWidth() {
        // width 400 -> 吸附門檻 = min(400*0.1, 100) = 40
        assertEquals(0, resolve(x = 39f, content = "abcdefghij"))
        assertEquals(10, resolve(x = 361f, content = "abcdefghij"))
    }

    @Test
    fun outsideTheSnapBandResolvesToAnOffset() {
        // FakeLayout 每字 10px，offsetForHorizontal 取最近整數：42 -> 4.2 -> 4
        assertEquals(4, resolve(x = 42f, content = "abcdefghij"))
    }

    @Test
    fun preciseDragUsesANarrowerSnapBand() {
        // precise：min(18dp * density(=1 於此測試), width*0.04=16) = 16
        // x=30 在一般模式會吸附到行首，精準模式不會
        assertEquals(0, resolve(x = 30f, content = "abcdefghij"))
        assertEquals(3, resolve(x = 30f, content = "abcdefghij", precise = true))
    }

    @Test
    fun paddingIsSubtractedBeforeResolving() {
        assertEquals(
                resolve(x = 100f, content = "abcdefghij"),
                resolve(x = 100f + 24, content = "abcdefghij", paddingLeft = 24)
        )
    }

    @Test
    fun yIsClampedIntoTheLayout() {
        val metrics = FakeLayout(listOf(0..9, 10..19), 20)
        // y 在 padding 之上 -> 夾到 0 -> 第 1 行
        val above = resolve(x = 50f, y = -50f, content = "0".repeat(20), metrics = metrics, paddingTop = 20)
        val inside = resolve(x = 50f, y = 20f, content = "0".repeat(20), metrics = metrics, paddingTop = 20)
        assertEquals(inside, above)
    }

    // --- 慢速拖曳的精修 ---

    @Test
    fun slowDragDoesNotAdvanceUntilTheFractionIsCrossed() {
        val text = "abcdefghij" // 每字 10px，字母 -> 比例 0.7
        // anchor=2 (x=20)，candidate=5 (x=50)；2->3 門檻 27、3->4 門檻 37、4->5 門檻 47
        assertEquals(2, resolve(x = 20f, content = text, precise = true, anchor = 2))
        assertEquals(3, resolve(x = 28f, content = text, precise = true, anchor = 2))
        assertEquals(4, resolve(x = 45f, content = text, precise = true, anchor = 2))
    }

    @Test
    fun slowDragReachesTheCandidateWhenDraggedAllTheWay() {
        assertEquals(5, resolve(x = 50f, content = "abcdefghij", precise = true, anchor = 2))
    }

    @Test
    fun slowDragLeftwardsMirrorsTheSameRule() {
        val text = "abcdefghij"
        // anchor=7 (x=70)，candidate=3 (x=30)；往回每步門檻為 63、53、43、33
        assertEquals(4, resolve(x = 40f, content = text, precise = true, anchor = 7))
        assertEquals(3, resolve(x = 30f, content = text, precise = true, anchor = 7))
    }

    /**
     * 注意：精準模式的吸附帶是 min(18dp, width*0.04)，在這個假排版是 16px。
     * 任何要驗證「精修」的測試，x 必須落在吸附帶之外（>= 16），否則會先被吸附到行首。
     */
    @Test
    fun cjkCrossesLaterThanLatinBecauseItsFractionIsHigher() {
        // anchor=2 (x=20)：字母 2->3 門檻 = 20+10*0.7 = 27；CJK 門檻 = 20+10*0.82 = 28.2
        val cjk = "一二三四五"
        val latin = "abcdefghij"
        assertEquals(3, resolve(x = 27f, content = latin, precise = true, anchor = 2))
        assertEquals(2, resolve(x = 27f, content = cjk, precise = true, anchor = 2))
        // 再多拖一點，CJK 也會推進
        assertEquals(3, resolve(x = 29f, content = cjk, precise = true, anchor = 2))
    }

    @Test
    fun punctuationUsesTheMiddleFraction() {
        // "ab-cdef"：2->3 的門檻用到 boundary 2（'b' 與 '-'）-> 0.78
        // anchor=1：1->2 門檻 = 10+7.8 = 17.8；2->3 門檻 = 20+7.8 = 27.8
        val text = "ab-cdef"
        assertEquals(2, resolve(x = 27f, content = text, precise = true, anchor = 1))
        assertEquals(3, resolve(x = 28f, content = text, precise = true, anchor = 1))
    }

    // --- 精修不會跨越的條件 ---

    @Test
    fun refinementIsSkippedWithoutAnAnchor() {
        assertEquals(5, resolve(x = 50f, content = "abcdefghij", precise = true, anchor = null))
    }

    @Test
    fun refinementIsSkippedWhenTheAnchorIsOnAnotherLine() {
        val text = "a".repeat(20)
        val metrics = FakeLayout(listOf(0..9, 10..19), 20)
        // anchor 在第 1 行、候選在第 2 行 -> 直接採納候選（不做逐字精修）
        val expected = metrics.offsetForHorizontal(1, 30f)
        val result =
                resolve(x = 30f, y = 50f, content = text, metrics = metrics, precise = true, anchor = 3)
        assertEquals(expected, result)
    }

    @Test
    fun refinementReturnsImmediatelyWhenCandidateEqualsAnchor() {
        assertEquals(4, resolve(x = 40f, content = "abcdefghij", precise = true, anchor = 4))
    }

    // --- advanceFraction ---

    @Test
    fun advanceFractionReflectsTheCharacterClass() {
        assertEquals(0.7f, ReaderOffsetResolver.advanceFraction("abcdef", 1, 2), 0.0001f)
        assertEquals(0.82f, ReaderOffsetResolver.advanceFraction("一二三四", 1, 2), 0.0001f)
        // 混排：任一邊是 CJK 就採用 CJK 比例
        assertEquals(0.82f, ReaderOffsetResolver.advanceFraction("a一cd", 0, 1), 0.0001f)
        // 標點（非詞字元）在任一側
        assertEquals(0.78f, ReaderOffsetResolver.advanceFraction("ab-cd", 1, 2), 0.0001f)
        assertEquals(0.78f, ReaderOffsetResolver.advanceFraction("ab-cd", 2, 3), 0.0001f)
    }

    @Test
    fun advanceFractionClampsBoundariesToTheText() {
        // 邊界被夾在 [0, length]。邊界 0 時 leftChar 為 null，兩個特例分支都要求兩邊都非 null，
        // 因此只有右邊取得到字元時仍走 else（0.7）。
        assertEquals(0.7f, ReaderOffsetResolver.advanceFraction(" abc", 0, 0), 0.0001f)
        assertEquals(0.7f, ReaderOffsetResolver.advanceFraction("", 0, 0), 0.0001f)
        // 邊界在字串中間時，非詞字元（空白）那一側就會觸發 0.78
        assertEquals(0.78f, ReaderOffsetResolver.advanceFraction("a bc", 1, 2), 0.0001f)
    }

    // --- lineForOffsetSafe ---

    @Test
    fun lineForOffsetIsZeroForEmptyContent() {
        assertEquals(0, ReaderOffsetResolver.lineForOffsetSafe("", singleLine("x"), 5))
    }

    @Test
    fun lineForOffsetClampsOutOfRangeOffsets() {
        val text = "a".repeat(20)
        val metrics = FakeLayout(listOf(0..9, 10..19), 20)
        assertEquals(1, ReaderOffsetResolver.lineForOffsetSafe(text, metrics, 999))
        assertEquals(0, ReaderOffsetResolver.lineForOffsetSafe(text, metrics, -5))
    }

    @Test
    fun edgeSnapConstantMatchesTheDocumentedValue() {
        assertEquals(100f, ReaderOffsetResolver.NORMAL_EDGE_SNAP_THRESHOLD_PX, 0.0001f)
    }
}
