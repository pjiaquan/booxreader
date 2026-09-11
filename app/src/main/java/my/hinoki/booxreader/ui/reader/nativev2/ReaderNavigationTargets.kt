package my.hinoki.booxreader.ui.reader.nativev2

import android.net.Uri
import java.net.URI

/** 導覽目標：reading order 中的索引，加上選用的 fragment。 */
internal data class NavigationTarget(val index: Int, val fragment: String?)

/**
 * EPUB 內部連結 / locator href → reading order 索引的解析。
 *
 * 這組函式原本是 `NativeNavigatorFragment` 的私有方法，但它們是**純字串與 URI 邏輯**
 * （不碰任何 View），而且是最容易出現微妙錯誤的地方：百分比編碼、fragment 與 query 的
 * 剝除、相對路徑 resolve、`./` 與 `\` 的正規化、以及「模糊比對必須唯一命中」的規則。
 * 抽出來之後可以完整測試（見 `ReaderNavigationTargetsTest`）。
 *
 * 行為與原本實作**逐字相同**。
 */
internal object ReaderNavigationTargets {

    /** 取出 `#` 之後的 fragment 並解碼；沒有則回 null。 */
    fun extractFragmentId(href: String): String? {
        val raw = href.substringAfter('#', "").trim()
        if (raw.isEmpty()) return null
        return Uri.decode(raw)
    }

    /**
     * 把 href 正規化成可互相比對的形式：
     * 去空白、剝掉 `#fragment` 與 `?query`、解百分比編碼、URI 正規化（處理 `..`）、
     * 絕對 URL 只留 path、`\` 轉 `/`、去掉開頭的 `./` 與 `/`。
     */
    fun normalizeHrefForMatch(href: String?): String {
        if (href.isNullOrBlank()) return ""
        var normalized = href.trim().substringBefore('#').substringBefore('?')
        if (normalized.isEmpty()) return ""
        normalized = Uri.decode(normalized)

        val asUri = runCatching { URI(normalized).normalize() }.getOrNull()
        normalized = asUri?.toString() ?: normalized
        if (normalized.contains("://")) {
            normalized = runCatching { URI(normalized).path ?: normalized }.getOrDefault(normalized)
        }

        normalized = normalized.replace('\\', '/')
        normalized = normalized.removePrefix("./")
        normalized = normalized.removePrefix("/")
        return normalized
    }

    /**
     * 產生候選 href：原始值本身，以及（相對路徑時）以目前資源為基準 resolve 出來的結果。
     */
    fun buildNavigationHrefCandidates(
            rawHref: String,
            currentResourceHref: String?
    ): Set<String> {
        val candidates = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            val normalized = normalizeHrefForMatch(value)
            if (normalized.isNotEmpty()) {
                candidates.add(normalized)
            }
        }

        addCandidate(rawHref)
        if (!rawHref.contains("://")) {
            val base = currentResourceHref?.substringBefore('#')
            if (!base.isNullOrBlank()) {
                val resolved = runCatching { URI(base).resolve(rawHref).toString() }.getOrNull()
                addCandidate(resolved)
            }
        }
        return candidates
    }

    /**
     * 解析 href 對應的 reading order 索引。
     *
     * 先做**精確**比對（正規化後完全相等）；若無，再做一次模糊比對（`endsWith` 互為後綴），
     * 但**只有唯一命中時**才採用 —— 避免同名檔名在不同目錄時誤跳。
     */
    fun resolve(
            rawHref: String,
            readingOrderHrefs: List<String>,
            currentResourceHref: String?
    ): NavigationTarget? {
        val trimmed = rawHref.trim()
        if (trimmed.isEmpty()) return null

        val fragment = extractFragmentId(trimmed)
        val rawResource = trimmed.substringBefore('#').trim()
        val resourceHref =
                if (rawResource.isNotEmpty()) {
                    rawResource
                } else {
                    currentResourceHref?.substringBefore('#') ?: return null
                }

        val candidates = buildNavigationHrefCandidates(resourceHref, currentResourceHref)
        if (candidates.isEmpty()) return null

        val normalizedReadingOrder = readingOrderHrefs.map { normalizeHrefForMatch(it) }

        for (candidate in candidates) {
            val index = normalizedReadingOrder.indexOf(candidate)
            if (index >= 0) {
                return NavigationTarget(index = index, fragment = fragment)
            }
        }

        for (candidate in candidates.sortedByDescending { it.length }) {
            if (candidate.length < 3) continue
            val matches =
                    normalizedReadingOrder.mapIndexedNotNull { index, href ->
                        if (href == candidate ||
                                        href.endsWith("/$candidate") ||
                                        candidate.endsWith("/$href")) {
                                    index
                                } else {
                                    null
                                }
                    }
            if (matches.size == 1) {
                return NavigationTarget(index = matches.first(), fragment = fragment)
            }
        }
        return null
    }

    /** 目標 href 與目前已載入的資源是否指向同一個檔案。 */
    fun targetsSameResource(
            targetHref: String,
            loadedResourceHref: String,
            readingOrderHrefs: List<String>,
            currentResourceHref: String?
    ): Boolean {
        val target = resolve(targetHref, readingOrderHrefs, currentResourceHref) ?: return false
        val loaded = normalizeHrefForMatch(loadedResourceHref)
        val targetResource = readingOrderHrefs.getOrNull(target.index)
        return loaded == normalizeHrefForMatch(targetResource)
    }
}
