package my.hinoki.booxreader.ui.reader.nativev2

import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ReaderLinkHitTest` 的單元測試。
 *
 * 這些規則（行的選擇、span 被行裁剪、左右座標反向、14dp 容差）原本埋在
 * `NativeReaderView.findLinkByBounds` 裡，只有真的用手指點在裝置上才會被驗證。
 *
 * 測試用 [FakeMetrics] 精確控制排版。**不用** Robolectric 的 `Layout`，因為它的排版是
 * 退化的（不換行、horizontals 幾乎塌成常數），多行與裁剪根本測不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReaderLinkHitTestTest {

    /** 每行 40px 高；horizontal 由 lambda 決定，預設每字元 10px。 */
    private class FakeMetrics(
            private val lineRanges: List<IntRange>,
            private val horizontals: (Int) -> Float = { it * 10f }
    ) : LinkLayoutMetrics {

        private val lineTops: List<Int> = lineRanges.indices.map { it * 40 }

        override fun lineForVertical(y: Int): Int {
            var found = 0
            lineTops.forEachIndexed { index, top -> if (y >= top) found = index }
            return found
        }

        override fun lineStart(line: Int): Int = lineRanges[line].first

        override fun lineEnd(line: Int): Int = lineRanges[line].last + 1

        override fun primaryHorizontal(offset: Int): Float = horizontals(offset)
    }

    private fun singleLine(text: String = "0123456789") = FakeMetrics(listOf(text.indices))

    private fun links(vararg ranges: Pair<IntRange, String>): Spanned =
            SpannableString("0".repeat(64)).apply {
                ranges.forEach { (range, url) ->
                    setSpan(
                            URLSpan(url),
                            range.first,
                            range.last + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

    private fun hit(
            relX: Float,
            relY: Float = 10f,
            tolerancePx: Float = 14f,
            spanned: Spanned,
            metrics: LinkLayoutMetrics = singleLine()
    ) = ReaderLinkHitTest.findLinkAt(relX, relY, tolerancePx, spanned, metrics)

    // --- 命中與容差 ---

    @Test
    fun tapInsideTheLinkReturnsItsUrl() {
        // span [2,6) -> left=20, right=60
        assertEquals("https://a", hit(40f, spanned = links(2..5 to "https://a")))
    }

    @Test
    fun tapWithinToleranceOfEitherEdgeStillHits() {
        val spanned = links(2..5 to "https://a")
        // 左緣 20：容差內（20-14=6）
        assertEquals("https://a", hit(20f, spanned = spanned))
        assertEquals("https://a", hit(8f, spanned = spanned))
        // 右緣 60：容差內（60+14=74）
        assertEquals("https://a", hit(60f, spanned = spanned))
        assertEquals("https://a", hit(72f, spanned = spanned))
    }

    @Test
    fun tapBeyondToleranceMisses() {
        val spanned = links(2..5 to "https://a")
        assertNull(hit(4f, spanned = spanned)) // 20-14=6 之下
        assertNull(hit(80f, spanned = spanned)) // 60+14=74 之上
    }

    @Test
    fun toleranceScalesWithDensity() {
        val spanned = links(2..5 to "https://a")
        // density 2 -> 容差 28px，原本 miss 的 80 會命中（60+28=88）
        assertEquals("https://a", hit(80f, tolerancePx = 28f, spanned = spanned))
    }

    // --- 邊界與防護 ---

    @Test
    fun negativeRelativeCoordinatesInsidePaddingMiss() {
        val spanned = links(2..5 to "https://a")
        assertNull(hit(-1f, spanned = spanned))
        assertNull(hit(40f, relY = -1f, spanned = spanned))
    }

    @Test
    fun noLinkOnTheLineMisses() {
        assertNull(hit(40f, spanned = links()))
    }

    @Test
    fun lineWithoutAnySpanMisses() {
        // 連結在第 2 行，卻點在第 1 行
        val spanned = links(12..15 to "https://a")
        val metrics = FakeMetrics(listOf(0..9, 10..19))
        assertNull(hit(40f, relY = 10f, spanned = spanned, metrics = metrics))
    }

    @Test
    fun relYIsTruncatedToIntegerBeforeResolvingTheLine() {
        val spanned = links(12..15 to "https://a")
        val metrics = FakeMetrics(listOf(0..9, 10..19))
        // 第 2 行從 40px 開始：39.9 仍在第 1 行，40.0 進第 2 行
        assertNull(hit(120f, relY = 39.9f, spanned = spanned, metrics = metrics))
        assertEquals("https://a", hit(120f, relY = 40f, spanned = spanned, metrics = metrics))
    }

    // --- 多個連結與行的裁剪 ---

    @Test
    fun twoLinksOnTheSameLineResolveByPosition() {
        val spanned = links(2..5 to "https://a", 7..8 to "https://b")
        assertEquals("https://a", hit(30f, spanned = spanned))
        assertEquals("https://b", hit(75f, spanned = spanned))
    }

    @Test
    fun linkSpanningTwoLinesIsHitOnBothLines() {
        // span [8,14) 跨行：第 1 行 [0,10)、第 2 行 [10,20)
        val spanned = links(8..13 to "https://a")
        val metrics = FakeMetrics(listOf(0..9, 10..19))
        // 第 1 行被裁成 [8,10) -> 80..100
        assertEquals("https://a", hit(90f, relY = 10f, spanned = spanned, metrics = metrics))
        // 第 2 行被裁成 [10,14) -> 100..140
        assertEquals("https://a", hit(120f, relY = 50f, spanned = spanned, metrics = metrics))
    }

    @Test
    fun linkIsClippedToTheLineSoTheOtherLineDoesNotMatch() {
        val spanned = links(8..13 to "https://a")
        val metrics = FakeMetrics(listOf(0..9, 10..19))
        // 第 2 行只涵蓋 100..140，點在該行最左邊（容差外）不應命中
        assertNull(hit(20f, relY = 50f, spanned = spanned, metrics = metrics))
    }

    // --- 反向座標 ---

    @Test
    fun reversedHorizontalsAreNormalisedBeforeComparing() {
        // 遞減的 horizontal：span [2,6) -> left=80, right=40 -> 交換後 40..80
        val spanned = links(2..5 to "https://a")
        val metrics = FakeMetrics(listOf(0..9), horizontals = { 100f - it * 10f })
        assertEquals("https://a", hit(60f, spanned = spanned, metrics = metrics))
        assertNull(hit(10f, spanned = spanned, metrics = metrics))
    }

    @Test
    fun toleranceConstantMatchesTheDocumentedValue() {
        assertEquals(14f, ReaderLinkHitTest.HORIZONTAL_TOLERANCE_DP, 0.0001f)
    }
}
