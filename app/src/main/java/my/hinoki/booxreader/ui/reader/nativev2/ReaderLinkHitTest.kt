package my.hinoki.booxreader.ui.reader.nativev2

import android.text.Layout
import android.text.Spanned
import android.text.style.URLSpan

/**
 * 命中測試需要的排版資訊（`android.text.Layout` 的最小介面）。
 *
 * 抽成介面的理由：`findLinkByBounds` 的規則（行的選擇、span 被行裁剪、左右座標反向、
 * 14dp 容差）全都是**邏輯**，但直接吃 `Layout` 就只能在 Robolectric 裡測 ——
 * 而 Robolectric 的排版是退化的（不換行、horizontals 幾乎塌成常數），
 * 多行與裁剪根本測不到。有了這層介面，測試可以用假實作精確控制幾何。
 */
internal interface LinkLayoutMetrics {
    fun lineForVertical(y: Int): Int
    fun lineStart(line: Int): Int
    fun lineEnd(line: Int): Int

    /** 該 offset 在該行的水平位置；同一行內回傳值單調遞增（呼叫端仍會處理反向）。 */
    fun primaryHorizontal(offset: Int): Float
}

/** 把真正的 `Layout` 接上 [LinkLayoutMetrics]。 */
internal fun Layout.asLinkLayoutMetrics(): LinkLayoutMetrics =
        object : LinkLayoutMetrics {
            override fun lineForVertical(y: Int): Int = getLineForVertical(y)

            override fun lineStart(line: Int): Int = getLineStart(line)

            override fun lineEnd(line: Int): Int = getLineEnd(line)

            override fun primaryHorizontal(offset: Int): Float = getPrimaryHorizontal(offset)
        }

/**
 * 連結點擊判定（原本是 `NativeReaderView` 的私有方法 `findLinkByBounds`）。
 *
 * 流程：先扣掉 padding 得到相對座標 → 找出所在行 → 取該行內的 `URLSpan` →
 * 把 span 範圍夾進該行 → 用水平範圍（含容差）判斷是否命中。
 *
 * 行為與原本實作**逐字相同**，包含：
 * - 相對座標為負（落在 padding 內）直接回 null
 * - `relY` 以小數截斷轉為行號
 * - span 跨越行時以該行範圍裁剪；裁剪後為空則跳過
 * - 左右座標相反時交換
 * - 命中容差 `xPad = 14dp * density`，**左右各放寬**
 */
internal object ReaderLinkHitTest {

    /** 手指不會精準點在字上，水平方向左右各放寬這麼多 dp。 */
    const val HORIZONTAL_TOLERANCE_DP = 14f

    fun findLinkAt(
            relX: Float,
            relY: Float,
            tolerancePx: Float,
            spanned: Spanned,
            metrics: LinkLayoutMetrics
    ): String? {
        if (relY < 0 || relX < 0) return null

        val line = metrics.lineForVertical(relY.toInt())
        val lineStart = metrics.lineStart(line)
        val lineEnd = metrics.lineEnd(line)
        val spans = spanned.getSpans(lineStart, lineEnd, URLSpan::class.java)
        if (spans.isEmpty()) return null

        for (span in spans) {
            val spanStart = maxOf(spanned.getSpanStart(span), lineStart)
            val spanEnd = minOf(spanned.getSpanEnd(span), lineEnd)
            if (spanStart >= spanEnd) continue

            var left = metrics.primaryHorizontal(spanStart)
            var right = metrics.primaryHorizontal(spanEnd)
            if (left > right) {
                val tmp = left
                left = right
                right = tmp
            }
            if (relX + tolerancePx >= left && relX - tolerancePx <= right) {
                return span.url
            }
        }
        return null
    }
}
