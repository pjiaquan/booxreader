package my.hinoki.booxreader.ui.common

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import my.hinoki.booxreader.data.settings.ContrastMode

/**
 * 對比模式 → 整頁配色的**純色彩計算**（不碰任何 View，可單獨測試）。
 *
 * 這些 `when (mode)` 階梯原本各自散落在 `AiNoteDetailActivity`（那裡的樣式叢集約 330 行）
 * 與 `AiNoteListActivity`（93 行）。兩份比對後確認底色 / 文字 / secondary / hint / 系統列
 * 的數值**完全相同**，只有 topBar 用的 color resource 不同，因此收斂成這一份：
 *
 * - 任何一處寫錯只會表現成「某種對比模式顏色怪怪的」，沒有測試抓不到
 * - 兩頁共用同一組數值，不會再出現「改了 A 頁忘了 B 頁」
 *
 * 各項數值都是原本程式碼的行為，**刻意保持一致**（改動會改變外觀），
 * 並由 `ContrastPaletteTest` 逐項釘住。
 */
internal data class ContrastPalette(
        val darkMode: Boolean,
        val backgroundColor: Int,
        val topBarColor: Int,
        val topBarContentColor: Int,
        val textColor: Int,
        val secondaryTextColor: Int,
        val hintColor: Int,
        /** 畫線 / 註記 chip 的文字色。 */
        val magicTagTextColor: Int,
        /** 畫線 / 註記 chip 的底色。 */
        val magicTagBackgroundColor: Int,
        /** 輸入框（語意搜尋、追問）的表面色。 */
        val inputSurfaceColor: Int,
        /** 次要按鈕 / 次要表面色（例如語意搜尋按鈕）。 */
        val secondarySurfaceColor: Int,
        val accentColor: Int,
        val primaryButtonTextColor: Int,
        /** 輸入框底線：與 textColor 混色的比例。 */
        val inputUnderlineBlend: Float,
        /** 次要按鈕填色：與 textColor 混色的比例。 */
        val secondaryFillBlend: Float,
        /** 次要按鈕描邊：textColor 的 alpha。 */
        val secondaryStrokeAlpha: Int,
        /** 複製按鈕填色：與 textColor 混色的比例。 */
        val copyButtonFillBlend: Float,
        /** 複製按鈕描邊：textColor 的 alpha。 */
        val copyButtonStrokeAlpha: Int,
        /** 按下狀態：與白或黑混色的比例。 */
        val pressedBlendFraction: Float,
        /** 停用狀態：與 backgroundColor 混色的比例。 */
        val disabledFillFraction: Float,
        /** 停用文字：textColor 的 alpha。 */
        val disabledTextAlpha: Int,
        val useLightStatusIcons: Boolean,
        val useLightNavIcons: Boolean
) {

    companion object {

        private const val ALPHA_SECONDARY_LIGHT = 170
        private const val ALPHA_SECONDARY_DARK = 215
        private const val ALPHA_HINT_LIGHT = 140
        private const val ALPHA_HINT_DARK = 190

        /**
         * 在 [background] 上可讀的文字色（亮底配黑字、暗底配白字）。
         * 原本在兩頁各寫了一次同樣的 luminance 判斷。
         */
        fun readableTextColorOn(background: Int): Int =
                if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE

        /**
         * @param topBarColorRes 非 DARK / HIGH_CONTRAST 時使用的 topBar 顏色資源
         *   （詳情頁用 `R.color.ai_note_top_bar`、列表頁用 `R.color.action_bar_surface`，
         *   這是兩頁唯一真正不同的地方）。
         */
        fun of(mode: ContrastMode, context: Context, topBarColorRes: Int): ContrastPalette {
            val darkMode = mode == ContrastMode.DARK || mode == ContrastMode.HIGH_CONTRAST

            val backgroundColor =
                    when (mode) {
                        ContrastMode.NORMAL -> "#F3F2F2".toColorInt()
                        ContrastMode.DARK -> "#1A1817".toColorInt()
                        ContrastMode.SEPIA -> "#F2E7D0".toColorInt()
                        ContrastMode.HIGH_CONTRAST -> Color.BLACK
                    }
            val topBarColor =
                    when (mode) {
                        ContrastMode.DARK -> "#0A0D12".toColorInt()
                        ContrastMode.HIGH_CONTRAST -> Color.BLACK
                        else -> ContextCompat.getColor(context, topBarColorRes)
                    }
            val topBarContentColor =
                    when (mode) {
                        ContrastMode.DARK, ContrastMode.HIGH_CONTRAST -> Color.WHITE
                        else ->
                                if (ColorUtils.calculateLuminance(topBarColor) > 0.5) Color.BLACK
                                else Color.WHITE
                    }
            val textColor =
                    when (mode) {
                        ContrastMode.NORMAL -> "#201E1D".toColorInt()
                        ContrastMode.DARK -> "#F0EDEA".toColorInt()
                        ContrastMode.SEPIA -> "#5B4636".toColorInt()
                        ContrastMode.HIGH_CONTRAST -> Color.WHITE
                    }
            val accentColor =
                    when (mode) {
                        ContrastMode.NORMAL -> "#EC3013".toColorInt()
                        ContrastMode.DARK -> "#EC3013".toColorInt()
                        ContrastMode.SEPIA -> "#8A6740".toColorInt()
                        ContrastMode.HIGH_CONTRAST -> "#F2F2F2".toColorInt()
                    }

            return ContrastPalette(
                    darkMode = darkMode,
                    backgroundColor = backgroundColor,
                    topBarColor = topBarColor,
                    topBarContentColor = topBarContentColor,
                    textColor = textColor,
                    secondaryTextColor =
                            ColorUtils.setAlphaComponent(
                                    textColor,
                                    if (darkMode) ALPHA_SECONDARY_DARK else ALPHA_SECONDARY_LIGHT
                            ),
                    hintColor =
                            ColorUtils.setAlphaComponent(
                                    textColor,
                                    if (darkMode) ALPHA_HINT_DARK else ALPHA_HINT_LIGHT
                            ),
                    magicTagTextColor = textColor,
                    magicTagBackgroundColor =
                            when (mode) {
                                ContrastMode.NORMAL -> "#E6E0D6".toColorInt()
                                ContrastMode.DARK -> "#1F1F1F".toColorInt()
                                ContrastMode.SEPIA -> "#E6D9BE".toColorInt()
                                ContrastMode.HIGH_CONTRAST -> "#202020".toColorInt()
                            },
                    inputSurfaceColor =
                            when (mode) {
                                ContrastMode.NORMAL -> Color.WHITE
                                ContrastMode.DARK -> "#1E232C".toColorInt()
                                ContrastMode.SEPIA -> "#F6EEDC".toColorInt()
                                ContrastMode.HIGH_CONTRAST -> "#0D0D0D".toColorInt()
                            },
                    secondarySurfaceColor =
                            when (mode) {
                                ContrastMode.NORMAL -> "#E4E9F2".toColorInt()
                                ContrastMode.DARK -> "#2B3240".toColorInt()
                                ContrastMode.SEPIA -> "#E8D8BB".toColorInt()
                                ContrastMode.HIGH_CONTRAST -> "#1F1F1F".toColorInt()
                            },
                    accentColor = accentColor,
                    primaryButtonTextColor = readableTextColorOn(accentColor),
                    inputUnderlineBlend = if (darkMode) 0.22f else 0.12f,
                    secondaryFillBlend = if (darkMode) 0.22f else 0.11f,
                    secondaryStrokeAlpha = if (darkMode) 80 else 56,
                    copyButtonFillBlend = if (darkMode) 0.20f else 0.10f,
                    copyButtonStrokeAlpha = if (darkMode) 72 else 44,
                    pressedBlendFraction = 0.10f,
                    disabledFillFraction = 0.55f,
                    disabledTextAlpha = if (darkMode) 160 else 140,
                    useLightStatusIcons =
                            !darkMode && ColorUtils.calculateLuminance(topBarColor) > 0.5,
                    // 列表頁原本以背景亮度判斷、詳情頁原本以模式判斷；兩者對四種模式的結果
                    // 一致（見 ContrastPaletteTest 的等價性斷言），這裡採用亮度版本。
                    useLightNavIcons = ColorUtils.calculateLuminance(backgroundColor) > 0.5
            )
        }
    }
}
