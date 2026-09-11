package my.hinoki.booxreader.ui.reader.nativev2

import kotlin.math.abs

/** 文字範圍（`end` 為 exclusive）。 */
internal data class TextRange(val start: Int, val end: Int)

/** 從 locator 解析出來的選取焦點：要高亮的文字，以及它前後的上下文。 */
internal data class SelectionFocusTarget(
        val highlight: String,
        val before: String?,
        val after: String?,
        val startOffset: Int?,
        val endOffset: Int?
)

/**
 * 選取焦點的解析與頁碼換算（原本是 `NativeNavigatorFragment` 的私有成員）。
 *
 * `resolve` 是決定「跨裝置同步過來的選取焦點應該落在目前資源的哪一個位置」，
 * 三段邏輯都不容易用眼睛驗證：
 * 1. 精確 offset 快路徑（而且還要比對內容是否真的相符）
 * 2. 字串唯一命中
 * 3. 多個命中時的 before/after 上下文評分，同分再以「與偏好 offset 的距離」決勝
 *
 * `progressionToPage` 則收斂了原本散在 `resolveCurrentPage` 中**四處**相同的頁碼換算
 * （含 `pageCount = 0` 這個會讓 `coerceIn` 丟例外的邊界）。
 *
 * 行為與原本實作**逐字相同**。
 */
internal object ReaderSelectionFocus {

    /** 評分時比對前後文的最大長度。 */
    private const val CONTEXT_TAIL_CHARS = 12

    fun resolve(resourceText: CharSequence, target: SelectionFocusTarget): TextRange? {
        val length = resourceText.length
        if (length <= 0) return null

        val start = target.startOffset
        val end = target.endOffset
        if (start != null && end != null && start >= 0 && end > start && end <= length) {
            val candidate = resourceText.subSequence(start, end).toString()
            if (candidate == target.highlight) {
                return TextRange(start = start, end = end)
            }
        }

        val highlight = target.highlight
        if (highlight.isBlank() || highlight.length > length) return null

        val matches = mutableListOf<TextRange>()
        var cursor = 0
        while (cursor <= length - highlight.length) {
            val index = resourceText.toString().indexOf(highlight, startIndex = cursor)
            if (index < 0) break
            matches += TextRange(index, index + highlight.length)
            // +1：允許重疊命中（例如 "aaaa" 找 "aa" 會得到 0、1、2）
            cursor = index + 1
        }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.first()

        val preferredStart = target.startOffset
        val before = target.before
        val after = target.after

        return matches.maxWithOrNull(
                compareBy<TextRange> {
                            var score = 0
                            if (!before.isNullOrBlank()) {
                                val beforeSlice =
                                        resourceText
                                                .subSequence(
                                                        maxOf(0, it.start - before.length),
                                                        it.start
                                                )
                                                .toString()
                                if (beforeSlice == before) {
                                    score += 2
                                } else if (beforeSlice.endsWith(before.takeLast(CONTEXT_TAIL_CHARS))) {
                                    score += 1
                                }
                            }
                            if (!after.isNullOrBlank()) {
                                val afterSlice =
                                        resourceText
                                                .subSequence(
                                                        it.end,
                                                        minOf(length, it.end + after.length)
                                                )
                                                .toString()
                                if (afterSlice == after) {
                                    score += 2
                                } else if (afterSlice.startsWith(after.take(CONTEXT_TAIL_CHARS))) {
                                    score += 1
                                }
                            }
                            score
                        }
                        .thenBy {
                            if (preferredStart == null) {
                                Int.MAX_VALUE
                            } else {
                                abs(it.start - preferredStart)
                            }
                        }
        )
    }

    /**
     * 閱讀進度比例（0.0–1.0）→ 頁碼，夾在 `[0, pageCount - 1]`。
     *
     * `pageCount <= 0` 或 `progression` 為 NaN 時回 0：原本的呼叫點都在
     * `pageCount > 0` 的保護之下，抽出後必須自己處理，否則 `coerceIn(0, -1)` 會丟例外。
     */
    fun progressionToPage(progression: Double, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        if (progression.isNaN()) return 0
        return (progression * pageCount).toInt().coerceIn(0, pageCount - 1)
    }
}
