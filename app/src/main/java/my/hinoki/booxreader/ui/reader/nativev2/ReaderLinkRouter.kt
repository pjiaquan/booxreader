package my.hinoki.booxreader.ui.reader.nativev2

import androidx.core.net.toUri

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

    /**
     * 允許交給系統開啟的 scheme。
     *
     * `http`/`https` 開瀏覽器，`mailto`/`tel` 開郵件或電話 App —— 這四種都不會執行內容。
     * 其餘（`javascript`、`data`、`file`、`content`、`intent` …）一律封鎖。
     */
    val ALLOWED_EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")

    /**
     * 形如 `scheme:` 的開頭（沒有 `//`）。用來把 `mailto:`、`tel:`、`javascript:` 這類
     * 連結認出來 —— 它們不含 `://`，但**不是**出版物內部連結。
     */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

    fun isAllowedExternalScheme(scheme: String?): Boolean =
            scheme != null && scheme.lowercase() in ALLOWED_EXTERNAL_SCHEMES

    /**
     * 分類連結。
     *
     * 內部連結：以 `#` 開頭，或**既不含 `://` 也不是 `scheme:` 形式**。
     *
     * 修正：原本只檢查「不含 `://`」，導致 `mailto:`、`tel:`（以及 `javascript:`）全被當成
     * 內部連結，實際結果是點了沒反應。現在它們會走外部路徑並由白名單決定放行或封鎖。
     */
    fun classify(url: String, currentResourceHref: String?): ReaderLinkTarget {
        if (isInternalLink(url)) {
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

        val scheme = runCatching { url.toUri().scheme?.lowercase() }.getOrNull()
        return ReaderLinkTarget.External(url = url, scheme = scheme)
    }

    /** 內部連結的判定（相對於「外部」）。 */
    fun isInternalLink(url: String): Boolean =
            url.startsWith("#") || (!url.contains("://") && !SCHEME_PREFIX.containsMatchIn(url))

    /**
     * 從 HTML 取出 `id` 對應元素的可見內容（給 footnote popup 用）。
     *
     * 與原本相同的簡單 regex：第一個符合的 id 元素，內容允許跨行。
     * 找不到回 null。
     */
    fun extractElementById(html: String, id: String): String? {
        // id 用 Regex.escape：EPUB 常見帶 . 的 id（如 "sec.1"），未 escape 會變成「任意字元」
        // 而命中錯的元素、顯示錯的 footnote。對沒有特殊字元的 id 行為完全相同。
        val pattern =
                Regex("<[^>]*id=\"${Regex.escape(id)}\"[^>]*>(.*?)</[^>]*>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(html)?.groupValues?.get(1)
    }
}
