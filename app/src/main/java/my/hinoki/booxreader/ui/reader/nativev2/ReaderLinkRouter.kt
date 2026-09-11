package my.hinoki.booxreader.ui.reader.nativev2

import android.net.Uri

/**
 * 連結點擊的分類結果：呼叫端只負責「執行」，判斷都在 [ReaderLinkRouter] 裡。
 */
internal sealed interface ReaderLinkTarget {

    /** 出版物內部連結（含 footnote）。 */
    data class Internal(
            /** 已把 fragment-only 的 href 接到目前資源上的完整 href。 */
            val href: String,
            /** href 中 `#` 之前的部分（可能為空字串，與原本行為一致）。 */
            val resourceHref: String,
            /** `#` 之後的第一段；只有在原字串含 `#` 時才非 null。 */
            val fragmentId: String?
    ) : ReaderLinkTarget

    /** 外部連結。 */
    data class External(val url: String, val scheme: String?) : ReaderLinkTarget
}

/**
 * 連結路由（原本是 `NativeNavigatorFragment.handleLinkClick` 的判斷部分）。
 *
 * 拆出來的理由：
 * - **外部 scheme 白名單是安全政策**：只有 http/https 允許開啟，其他（`intent:`、`javascript:`、
 *   `file:`、`content:` …）一律封鎖。這種規則不該只靠「沒人改壞」來保證。
 * - fragment-only 連結要接上目前資源、再切出 `#` 後的第一段，錯了就跳錯地方或打不開 footnote。
 * - footnote 內容是從 HTML 用 regex 取 `id` 對應的元素，屬於容易有邊界問題的解析。
 *
 * 執行面（建 Intent、顯示 popup）留在 Fragment。行為與原本實作**逐字相同**。
 */
internal object ReaderLinkRouter {

    /** 只有這兩個 scheme 允許交給系統開啟。 */
    val ALLOWED_EXTERNAL_SCHEMES = setOf("http", "https")

    fun isAllowedExternalScheme(scheme: String?): Boolean =
            scheme != null && scheme.lowercase() in ALLOWED_EXTERNAL_SCHEMES

    /**
     * 分類連結。
     *
     * 「內部」的判定與原本一致：以 `#` 開頭，**或**不含 `://`。
     */
    fun classify(url: String, currentResourceHref: String?): ReaderLinkTarget {
        if (url.startsWith("#") || !url.contains("://")) {
            val href =
                    if (url.startsWith("#")) {
                        currentResourceHref?.let { "$it$url" } ?: url
                    } else {
                        url
                    }
            // 注意：只有在「原始字串」含 # 時才取 fragment（與原本行為一致）
            val fragmentId =
                    if (url.contains("#")) {
                        href.split("#").getOrNull(1)
                    } else {
                        null
                    }
            val resourceHref = href.split("#")[0]
            return ReaderLinkTarget.Internal(href = href, resourceHref = resourceHref, fragmentId = fragmentId)
        }

        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        return ReaderLinkTarget.External(url = url, scheme = scheme)
    }

    /**
     * 從 HTML 取出 `id` 對應元素的可見內容（給 footnote popup 用）。
     *
     * 與原本相同的簡單 regex：第一個符合的 id 元素，內容允許跨行。
     * 找不到回 null。
     */
    fun extractElementById(html: String, id: String): String? {
        val pattern = Regex("<[^>]*id=\"$id\"[^>]*>(.*?)</[^>]*>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(html)?.groupValues?.get(1)
    }
}
