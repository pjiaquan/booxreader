package my.hinoki.booxreader.ui.reader.nativev2

/**
 * 文字選取的邊界判斷（長按選取詞 / 詞組）。
 *
 * 這三個函式原本是 `NativeReaderView` 的私有方法。它們是**純文字邏輯**（完全不碰 View），
 * 卻是最容易出現 off-by-one 的地方：CJK 要逐字選取、標點要單獨選取、英數字要擴展到
 * 整個詞。抽出來之後可以完整測試（見 `ReaderTextSelectionTest`），
 * 而 `NativeReaderView` 從 1,324 行的自訂 View 稍微瘦身。
 *
 * 行為與原本實作**逐字相同**：包含 CJK 的碼位範圍判斷（為了相容 API < 26，
 * 不用 `UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E/F`）與例外時退回單字元。
 */
internal object ReaderTextSelection {

    /** 構成「詞」的字元：字母、數字、單引號（直線與彎引號）、底線。 */
    fun isWordChar(ch: Char): Boolean =
            ch.isLetterOrDigit() || ch == '\'' || ch == '’' || ch == '_'

    /** 需要逐字選取的字元（中日韓越與假名、諺文、注音）。 */
    fun isCjk(ch: Char): Boolean {
        // 用碼位範圍以相容舊版 Android
        // （UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E/F 在 API 26 之前不存在）
        val codePoint = ch.code
        return when {
            // CJK Unified Ideographs (U+4E00 - U+9FFF)
            codePoint in 0x4E00..0x9FFF -> true
            // CJK Unified Ideographs Extension A (U+3400 - U+4DBF)
            codePoint in 0x3400..0x4DBF -> true
            // CJK Unified Ideographs Extension B (U+20000 - U+2A6DF) - 需 surrogate pair
            codePoint in 0x20000..0x2A6DF -> true
            // CJK Unified Ideographs Extension C (U+2A700 - U+2B73F)
            codePoint in 0x2A700..0x2B73F -> true
            // CJK Unified Ideographs Extension D (U+2B740 - U+2B81F)
            codePoint in 0x2B740..0x2B81F -> true
            // CJK Compatibility Ideographs (U+F900 - U+FAFF)
            codePoint in 0xF900..0xFAFF -> true
            // CJK Symbols and Punctuation (U+3000 - U+303F)
            codePoint in 0x3000..0x303F -> true
            // Hiragana (U+3040 - U+309F)
            codePoint in 0x3040..0x309F -> true
            // Katakana (U+30A0 - U+30FF)
            codePoint in 0x30A0..0x30FF -> true
            // Hangul Syllables (U+AC00 - U+D7AF)
            codePoint in 0xAC00..0xD7AF -> true
            // Bopomofo (U+3100 - U+312F)
            codePoint in 0x3100..0x312F -> true
            else -> false
        }
    }

    /**
     * [offset] 所屬詞的 `(start, end)`，`end` 為 exclusive。
     *
     * - CJK 或非詞字元（標點、空白）→ 單一字元
     * - 英數字 → 向左向右擴展到整個詞
     * - [offset] 會先夾進文字範圍（空字串則回 `0 to 0`）
     */
    fun wordBoundariesAt(text: CharSequence, offset: Int): Pair<Int, Int> {
        try {
            if (text.isEmpty()) return offset.coerceAtLeast(0) to offset.coerceAtLeast(0)
            val string = text.toString()
            if (string.isEmpty()) return 0 to 0

            val safeOffset = offset.coerceIn(0, string.length - 1)
            val ch = string[safeOffset]

            // CJK：逐字選取
            if (isCjk(ch)) {
                return safeOffset to (safeOffset + 1).coerceAtMost(string.length)
            }

            // 非詞字元（標點、空白）：逐字選取
            if (!isWordChar(ch)) {
                return safeOffset to (safeOffset + 1).coerceAtMost(string.length)
            }

            // 詞字元：擴展到詞的邊界
            var start = safeOffset
            while (start > 0 && isWordChar(string[start - 1])) {
                start--
            }
            var end = safeOffset + 1
            while (end < string.length && isWordChar(string[end])) {
                end++
            }

            start = start.coerceIn(0, string.length)
            end = end.coerceIn(start, string.length)
            return start to end
        } catch (ex: Exception) {
            // 退回單字元選取（與原本實作相同）
            return offset.coerceAtLeast(0) to (offset + 1).coerceAtLeast(1)
        }
    }
}
