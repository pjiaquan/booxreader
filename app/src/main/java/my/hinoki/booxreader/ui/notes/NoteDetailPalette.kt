package my.hinoki.booxreader.ui.notes

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.settings.ContrastMode

/**
 * AI 筆記詳情頁的主題調色盤。
 *
 * 這裡只做**純色彩計算**，不碰任何 View，因此可以單獨測試（見 `NoteDetailPaletteTest`）。
 * 抽出它的目的是：原本這些 `when (mode)` 階梯與 `mode == DARK || mode == HIGH_CONTRAST`
 * 判斷散落在 `AiNoteDetailActivity` 的多個私有方法裡（約 330 行的樣式叢集），
 * 任何一處寫錯都只會表現成「某一種對比模式顏色不對」，沒有測試就抓不到。
 *
 * 各項數值是原本程式碼的行為，**刻意保持一致**（改動會改變外觀）。
 */
internal data class NoteDetailPalette(
        val darkMode: Boolean,
        val backgroundColor: Int,
        val topBarColor: Int,
        val topBarContentColor: Int,
        val textColor: Int,
        val secondaryTextColor: Int,
        val hintColor: Int,
        val magicTagTextColor: Int,
        val magicTagBackgroundColor: Int,
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

        fun of(mode: ContrastMode, context: Context): NoteDetailPalette {
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
                        else -> ContextCompat.getColor(context, R.color.ai_note_top_bar)
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

            return NoteDetailPalette(
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
                    accentColor = accentColor,
                    primaryButtonTextColor =
                            if (ColorUtils.calculateLuminance(accentColor) > 0.5) Color.BLACK
                            else Color.WHITE,
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
                    useLightNavIcons = mode == ContrastMode.NORMAL || mode == ContrastMode.SEPIA
            )
        }
    }
}
