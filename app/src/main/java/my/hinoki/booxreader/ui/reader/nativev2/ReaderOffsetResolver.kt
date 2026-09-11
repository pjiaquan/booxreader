package my.hinoki.booxreader.ui.reader.nativev2

import android.text.Layout

/**
 * 選取游標定位需要的排版資訊（`android.text.Layout` 的最小介面）。
 *
 * 與 [LinkLayoutMetrics] 分開，是因為兩者需求不同（這裡要 width/height 與 offset 走訪，
 * 連結命中只要行範圍與水平位置）。分開可以讓各自的測試替身維持最小。
 * 理由同 [LinkLayoutMetrics]：Robolectric 的排版是退化的（不換行、horizontals 塌成常數），
 * 直接吃 `Layout` 會讓「跨行、邊緣吸附、慢速拖曳推進」這些規則完全測不到。
 */
internal interface OffsetLayoutMetrics {
    val width: Int
    val height: Int

    fun lineForVertical(y: Int): Int
    fun lineStart(line: Int): Int
    fun lineEnd(line: Int): Int
    fun lineForOffset(offset: Int): Int
    fun offsetForHorizontal(line: Int, x: Float): Int

    /** 下一個 / 上一個 offset；沒有下一個時回傳自己（與 `Layout` 行為一致）。 */
    fun offsetToRightOf(offset: Int): Int
    fun offsetToLeftOf(offset: Int): Int

    fun primaryHorizontal(offset: Int): Float
}

/** 把真正的 `Layout` 接上 [OffsetLayoutMetrics]。 */
internal fun Layout.asOffsetLayoutMetrics(): OffsetLayoutMetrics {
    val layout = this
    return object : OffsetLayoutMetrics {
        override val width: Int get() = layout.width
        override val height: Int get() = layout.height

        override fun lineForVertical(y: Int): Int = layout.getLineForVertical(y)

        override fun lineStart(line: Int): Int = layout.getLineStart(line)

        override fun lineEnd(line: Int): Int = layout.getLineEnd(line)

        override fun lineForOffset(offset: Int): Int = layout.getLineForOffset(offset)

        override fun offsetForHorizontal(line: Int, x: Float): Int =
                layout.getOffsetForHorizontal(line, x)

        override fun offsetToRightOf(offset: Int): Int = layout.getOffsetToRightOf(offset)

        override fun offsetToLeftOf(offset: Int): Int = layout.getOffsetToLeftOf(offset)

        override fun primaryHorizontal(offset: Int): Float = layout.getPrimaryHorizontal(offset)
    }
}

/**
 * 觸控座標 → 文字 offset（原本是 `NativeReaderView` 的私有方法）。
 *
 * 兩段邏輯：
 * 1. [resolve]：把座標換成行與水平位置，並在**靠邊**時吸附到行首/行尾
 *    （快速拖曳用寬容差 10% 寬或 100px；慢速拖曳縮到 18dp 或 4% 寬，讓使用者能精準停在字邊）。
 * 2. [refineForSlowDrag]：慢速拖曳時，「選取終點」不會直接跳到候選 offset，而是逐字推進，
 *    每跨一個字需要拖過該字寬的 [advanceFraction]（CJK 0.82、標點 0.78、其他 0.7）。
 *
 * 行為與原本實作**逐字相同**。
 */
internal object ReaderOffsetResolver {

    /** 非精準模式下的邊緣吸附上限（原本是 View 的欄位）。 */
    const val NORMAL_EDGE_SNAP_THRESHOLD_PX = 100f

    fun resolve(
            x: Float,
            y: Float,
            paddingLeft: Int,
            paddingTop: Int,
            content: CharSequence,
            metrics: OffsetLayoutMetrics,
            preciseEdgeSnapThresholdPx: Float,
            preciseMode: Boolean = false,
            anchorOffset: Int? = null
    ): Int {
        val relativeY = (y - paddingTop).coerceIn(0f, metrics.height.toFloat())
        val line = metrics.lineForVertical(relativeY.toInt())
        val relativeX = x - paddingLeft

        // 快速拖曳保留寬鬆的邊緣吸附；慢速拖曳縮小以取得更細的控制
        val snapThreshold =
                if (preciseMode) {
                    preciseEdgeSnapThresholdPx.coerceAtMost(metrics.width * 0.04f)
                } else {
                    (metrics.width * 0.1f).coerceAtMost(NORMAL_EDGE_SNAP_THRESHOLD_PX)
                }
        if (relativeX > metrics.width - snapThreshold) {
            return metrics.lineEnd(line)
        }
        if (relativeX < snapThreshold) {
            return metrics.lineStart(line)
        }

        val clampedX = relativeX.coerceIn(0f, metrics.width.toFloat())
        val candidate = metrics.offsetForHorizontal(line, clampedX)
        if (!preciseMode || anchorOffset == null) {
            return candidate
        }
        return refineForSlowDrag(
                content = content,
                metrics = metrics,
                line = line,
                relativeX = clampedX,
                anchorOffset = anchorOffset,
                candidateOffset = candidate
        )
    }

    fun refineForSlowDrag(
            content: CharSequence,
            metrics: OffsetLayoutMetrics,
            line: Int,
            relativeX: Float,
            anchorOffset: Int,
            candidateOffset: Int
    ): Int {
        val safeAnchor = anchorOffset.coerceIn(0, content.length)
        if (candidateOffset == safeAnchor) {
            return candidateOffset
        }
        if (lineForOffsetSafe(content, metrics, safeAnchor) != line) {
            return candidateOffset
        }

        val movingRight = candidateOffset > safeAnchor
        var resolved = safeAnchor
        var steps = 0
        while (steps++ < content.length + 1) {
            val nextOffset =
                    if (movingRight) metrics.offsetToRightOf(resolved)
                    else metrics.offsetToLeftOf(resolved)
            if (nextOffset == resolved) {
                break
            }
            if (lineForOffsetSafe(content, metrics, nextOffset) != line) {
                break
            }
            if (movingRight && nextOffset > candidateOffset) {
                break
            }
            if (!movingRight && nextOffset < candidateOffset) {
                break
            }

            val currentX = metrics.primaryHorizontal(resolved)
            val nextX = metrics.primaryHorizontal(nextOffset)
            val threshold = currentX + ((nextX - currentX) * advanceFraction(content, resolved, nextOffset))
            val crossed = if (movingRight) relativeX >= threshold else relativeX <= threshold
            if (!crossed) {
                break
            }

            resolved = nextOffset
            if (resolved == candidateOffset) {
                break
            }
        }

        return resolved
    }

    fun lineForOffsetSafe(content: CharSequence, metrics: OffsetLayoutMetrics, offset: Int): Int {
        if (content.isEmpty()) return 0
        return metrics.lineForOffset(offset.coerceIn(0, content.length))
    }

    /** 跨越這個字需要拖過的字寬比例：CJK 較快、標點次之、其他最慢。 */
    fun advanceFraction(content: CharSequence, fromOffset: Int, toOffset: Int): Float {
        val boundary = maxOf(fromOffset, toOffset).coerceIn(0, content.length)
        val leftChar = content.getOrNull(boundary - 1)
        val rightChar = content.getOrNull(boundary)
        return when {
            leftChar != null &&
                    rightChar != null &&
                    (ReaderTextSelection.isCjk(leftChar) || ReaderTextSelection.isCjk(rightChar)) ->
                    0.82f
            leftChar != null &&
                    rightChar != null &&
                    (!ReaderTextSelection.isWordChar(leftChar) ||
                            !ReaderTextSelection.isWordChar(rightChar)) ->
                    0.78f
            else -> 0.7f
        }
    }
}
