package my.hinoki.booxreader.ui.notes


import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.chip.Chip
import kotlin.math.roundToInt
import my.hinoki.booxreader.R
import my.hinoki.booxreader.databinding.ActivityAiNoteDetailBinding

/**
 * AI 筆記詳情頁的 View 樣式套用（自 `AiNoteDetailActivity` 抽出的樣式叢集）。
 *
 * 只依賴 `binding` 與 `NoteDetailPalette`（色彩計算已抽到那裡），不碰任何 Activity 狀態，
 * 因此與 `NoteDetailPalette` 一起構成可測試的樣式層。
 *
 * 系統列 / ActionBar 樣式仍在 Activity 內（需要 `window` 與 `supportActionBar`）。
 */

internal data class ButtonVisualStyle(
        val fillColor: Int,
        val pressedFillColor: Int,
        val disabledFillColor: Int,
        val strokeColor: Int,
        val textColor: Int,
        val disabledTextColor: Int
)

internal class NoteDetailStyler(
        private val activity: androidx.appcompat.app.AppCompatActivity,
        private val binding: ActivityAiNoteDetailBinding,
        private val palette: NoteDetailPalette
) {

    fun applyBaseViewColors() {
        binding.root.setBackgroundColor(palette.backgroundColor)
        binding.scrollView.setBackgroundColor(palette.backgroundColor)
        binding.llInputArea.setBackgroundColor(palette.backgroundColor)

        binding.tvOriginalLabel.setTextColor(palette.textColor)
        binding.tvResponseLabel.setTextColor(palette.textColor)
        binding.tvOriginalText.setTextColor(palette.textColor)
        binding.tvAiResponse.setTextColor(palette.textColor)
        binding.btnCopyAiResponse.imageTintList = ColorStateList.valueOf(palette.textColor)
        binding.tvAiModelInfo.setTextColor(palette.secondaryTextColor)
        binding.tvAiDisclaimer.setTextColor(palette.secondaryTextColor)
        binding.tvAiInputDisclaimer.setTextColor(palette.secondaryTextColor)
        binding.tvRelatedNotesLabel.setTextColor(palette.textColor)
        binding.tvRelatedNotesHint.setTextColor(palette.secondaryTextColor)
        binding.tvRelatedNotesStatus.setTextColor(palette.secondaryTextColor)
        binding.tvAutoScrollHint.setTextColor(palette.secondaryTextColor)
        binding.etFollowUp.setTextColor(palette.textColor)
        binding.etFollowUp.setHintTextColor(palette.hintColor)
        binding.etFollowUp.backgroundTintList =
                ColorStateList.valueOf(
                        ColorUtils.blendARGB(
                                palette.backgroundColor,
                                palette.textColor,
                                palette.inputUnderlineBlend
                        )
                )
    }

    fun applyMainButtonStyles() {
        val primaryStyle =
                buttonStyle(
                        fillColor = palette.accentColor,
                        textColor = palette.primaryButtonTextColor,
                        backgroundColor = palette.backgroundColor,
                        darkMode = palette.darkMode
                )
        val secondaryFill =
                ColorUtils.blendARGB(
                        palette.backgroundColor,
                        palette.textColor,
                        palette.secondaryFillBlend
                )
        val secondaryStyle =
                buttonStyle(
                        fillColor = secondaryFill,
                        textColor = palette.textColor,
                        backgroundColor = palette.backgroundColor,
                        darkMode = palette.darkMode,
                        strokeColor =
                                ColorUtils.setAlphaComponent(
                                        palette.textColor,
                                        palette.secondaryStrokeAlpha
                                )
                )
        applyButtonStyle(binding.btnFollowUp, primaryStyle)
        applyButtonStyle(binding.btnPublish, primaryStyle)
        applyButtonStyle(binding.btnRepublishSelection, secondaryStyle)
        applyButtonStyle(binding.btnGoToPage, secondaryStyle)
        applyButtonStyle(binding.btnBackToLinkedNote, secondaryStyle)
        binding.btnCopyAiResponse.background =
                createRoundedBackground(
                        fillColor =
                                ColorUtils.blendARGB(
                                        palette.backgroundColor,
                                        palette.textColor,
                                        palette.copyButtonFillBlend
                                ),
                        strokeColor =
                                ColorUtils.setAlphaComponent(
                                        palette.textColor,
                                        palette.copyButtonStrokeAlpha
                                ),
                        cornerRadiusDp = 0f
                )
    }

    private fun buttonStyle(
            fillColor: Int,
            textColor: Int,
            backgroundColor: Int,
            darkMode: Boolean,
            strokeColor: Int = Color.TRANSPARENT
    ): ButtonVisualStyle {
        val pressedFillColor =
                if (darkMode) {
                    ColorUtils.blendARGB(fillColor, Color.WHITE, 0.10f)
                } else {
                    ColorUtils.blendARGB(fillColor, Color.BLACK, 0.10f)
                }
        val disabledFillColor = ColorUtils.blendARGB(fillColor, backgroundColor, 0.55f)
        val disabledTextColor = ColorUtils.setAlphaComponent(textColor, if (darkMode) 160 else 140)
        return ButtonVisualStyle(
                fillColor = fillColor,
                pressedFillColor = pressedFillColor,
                disabledFillColor = disabledFillColor,
                strokeColor = strokeColor,
                textColor = textColor,
                disabledTextColor = disabledTextColor
        )
    }

    private fun applyButtonStyle(button: Button, style: ButtonVisualStyle) {
        val normal = createRoundedBackground(style.fillColor, style.strokeColor)
        val pressed = createRoundedBackground(style.pressedFillColor, style.strokeColor)
        val disabled = createRoundedBackground(style.disabledFillColor, style.strokeColor)
        button.background =
                StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), disabled)
                    addState(intArrayOf(android.R.attr.state_pressed), pressed)
                    addState(intArrayOf(android.R.attr.state_focused), pressed)
                    addState(intArrayOf(), normal)
                }
        button.setTextColor(
                ColorStateList(
                        arrayOf(
                                intArrayOf(-android.R.attr.state_enabled),
                                intArrayOf()
                        ),
                        intArrayOf(style.disabledTextColor, style.textColor)
                )
        )
    }

    private fun createRoundedBackground(
            fillColor: Int,
            strokeColor: Int,
            cornerRadiusDp: Float = 0f
    ): GradientDrawable {
        val strokeWidthPx = (binding.root.resources.displayMetrics.density * 1f).roundToInt().coerceAtLeast(1)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = binding.root.resources.displayMetrics.density * cornerRadiusDp
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    fun applySystemUiStyles() {
        updateMagicTagStyles()
        activity.supportActionBar?.setBackgroundDrawable(palette.topBarColor.toDrawable())
        applyActionBarContentColor(palette.topBarContentColor)

        @Suppress("DEPRECATION")
        run {
            activity.window.setBackgroundDrawable(palette.topBarColor.toDrawable())
            activity.window.decorView.setBackgroundColor(palette.backgroundColor)
            activity.window.statusBarColor = palette.topBarColor
            activity.window.navigationBarColor = palette.backgroundColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        insetsController.isAppearanceLightStatusBars = palette.useLightStatusIcons
        insetsController.isAppearanceLightNavigationBars = palette.useLightNavIcons
        // minSdk 24，SDK_INT 一定 >= M，因此直接套用狀態列圖示亮暗旗標。
        val flags = activity.window.decorView.systemUiVisibility
        activity.window.decorView.systemUiVisibility =
                if (palette.useLightStatusIcons) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
    }

    private fun applyActionBarContentColor(contentColor: Int) {
        val actionTitle = activity.supportActionBar?.title?.toString()?.takeIf { it.isNotBlank() } ?: activity.title.toString()
        if (actionTitle.isNotBlank()) {
            val styledTitle = SpannableString(actionTitle).apply {
                setSpan(ForegroundColorSpan(contentColor), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            activity.supportActionBar?.title = styledTitle
        }
        val actionSubtitle = activity.supportActionBar?.subtitle?.toString().orEmpty()
        if (actionSubtitle.isNotBlank()) {
            val styledSubtitle = SpannableString(actionSubtitle).apply {
                setSpan(
                        ForegroundColorSpan(contentColor),
                        0,
                        length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            activity.supportActionBar?.subtitle = styledSubtitle
        }
        val backDrawable =
                AppCompatResources.getDrawable(activity, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                        ?.mutate()
                        ?.let { drawable ->
                            DrawableCompat.setTint(drawable, contentColor)
                            drawable
                        }
        if (backDrawable != null) {
            activity.supportActionBar?.setHomeAsUpIndicator(backDrawable)
        }
    }

    private fun updateMagicTagStyles() {
        for (i in 0 until binding.cgMagicTags.childCount) {
            val view = binding.cgMagicTags.getChildAt(i)
            if (view is Chip) {
                view.setTextColor(palette.magicTagTextColor)
                view.chipBackgroundColor = ColorStateList.valueOf(palette.magicTagBackgroundColor)
            }
        }
    }
}
