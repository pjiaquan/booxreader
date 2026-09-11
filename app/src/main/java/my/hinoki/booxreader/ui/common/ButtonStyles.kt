package my.hinoki.booxreader.ui.common

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import kotlin.math.roundToInt

/**
 * 按鈕樣式的共用實作。
 *
 * `ButtonVisualStyle`、`applyButtonStyle` 與 `createRoundedBackground` 原本在
 * `NoteDetailStyler` / `AiProfileListActivity` / `ReaderSettingsActivity` 各有一份
 * **私有複本**（欄位與實作逐字相同）。這裡收斂成一份：
 * - 改一處就三頁同步，不會再出現「改了 A 頁忘了 B 頁」
 * - 資料類別相同，所以各頁自己的 style builder 不需要改
 *
 * 唯一刻意的行為調整：描邊寬度改用 `roundToInt()`（見 [createRoundedBackground]）。
 */

internal data class ButtonVisualStyle(
        val fillColor: Int,
        val pressedFillColor: Int,
        val disabledFillColor: Int,
        val strokeColor: Int,
        val textColor: Int,
        val disabledTextColor: Int
)

/** 依 style 設定一般 / 按下 / 停用狀態的背景與文字色。 */
internal fun applyButtonStyle(button: Button, style: ButtonVisualStyle) {
    val normal = createRoundedBackground(button.resources, style.fillColor, style.strokeColor)
    val pressed = createRoundedBackground(button.resources, style.pressedFillColor, style.strokeColor)
    val disabled = createRoundedBackground(button.resources, style.disabledFillColor, style.strokeColor)
    button.background =
            StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), disabled)
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(android.R.attr.state_focused), pressed)
                addState(intArrayOf(), normal)
            }
    button.setTextColor(
            ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(style.disabledTextColor, style.textColor)
            )
    )
}

/**
 * 帶 1dp 描邊的圓角背景（`cornerRadiusDp = 0f` 即直角）。
 *
 * 描邊寬度取**最近整數**：`NoteDetailStyler` 原本就是如此，另外兩處用 `toInt()`
 * （截斷）。在 density 1.5 這類裝置上，截斷會讓 1dp 描邊只剩 1px 而不是 2px，
 * 統一後那兩頁的描邊會正確地變粗 1px（其餘情況完全相同）。
 */
internal fun createRoundedBackground(
        resources: Resources,
        fillColor: Int,
        strokeColor: Int,
        cornerRadiusDp: Float = 0f
): GradientDrawable {
    val density = resources.displayMetrics.density
    val strokeWidthPx = (density * 1f).roundToInt().coerceAtLeast(1)
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = density * cornerRadiusDp
        setColor(fillColor)
        if (strokeColor != Color.TRANSPARENT) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }
}
