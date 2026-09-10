package my.hinoki.booxreader.ui.notes

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import my.hinoki.booxreader.data.settings.ContrastMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `NoteDetailPalette` 的行為測試。
 *
 * 這些數值原本散在 `AiNoteDetailActivity` 四個私有方法裡的 `when (mode)` 階梯，
 * 寫錯只會表現成「某個對比模式顏色怪怪的」而沒有任何測試會發現。
 * 這裡把每種模式的顏色與衍生 alpha 都固定下來，作為樣式重構的回歸網。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NoteDetailPaletteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun palette(mode: ContrastMode) = NoteDetailPalette.of(mode, context)

    @Test
    fun normalModeMatchesDocumentedColors() {
        val p = palette(ContrastMode.NORMAL)

        assertFalse(p.darkMode)
        assertEquals(Color.parseColor("#F3F2F2"), p.backgroundColor)
        assertEquals(Color.parseColor("#201E1D"), p.textColor)
        assertEquals(p.textColor, p.magicTagTextColor)
        assertEquals(Color.parseColor("#E6E0D6"), p.magicTagBackgroundColor)
        assertEquals(Color.parseColor("#EC3013"), p.accentColor)
        assertEquals(170, Color.alpha(p.secondaryTextColor))
        assertEquals(140, Color.alpha(p.hintColor))
        assertEquals(0.12f, p.inputUnderlineBlend, 0.0001f)
        assertEquals(0.11f, p.secondaryFillBlend, 0.0001f)
        assertEquals(56, p.secondaryStrokeAlpha)
        assertEquals(0.10f, p.copyButtonFillBlend, 0.0001f)
        assertEquals(44, p.copyButtonStrokeAlpha)
        assertEquals(140, p.disabledTextAlpha)
        assertTrue(p.useLightNavIcons)
    }

    @Test
    fun darkModeMatchesDocumentedColors() {
        val p = palette(ContrastMode.DARK)

        assertTrue(p.darkMode)
        assertEquals(Color.parseColor("#1A1817"), p.backgroundColor)
        assertEquals(Color.parseColor("#0A0D12"), p.topBarColor)
        assertEquals(Color.WHITE, p.topBarContentColor)
        assertEquals(Color.parseColor("#F0EDEA"), p.textColor)
        assertEquals(Color.parseColor("#1F1F1F"), p.magicTagBackgroundColor)
        assertEquals(215, Color.alpha(p.secondaryTextColor))
        assertEquals(190, Color.alpha(p.hintColor))
        assertEquals(0.22f, p.inputUnderlineBlend, 0.0001f)
        assertEquals(0.22f, p.secondaryFillBlend, 0.0001f)
        assertEquals(80, p.secondaryStrokeAlpha)
        assertEquals(0.20f, p.copyButtonFillBlend, 0.0001f)
        assertEquals(72, p.copyButtonStrokeAlpha)
        assertEquals(160, p.disabledTextAlpha)
        assertFalse(p.useLightStatusIcons)
        assertFalse(p.useLightNavIcons)
    }

    @Test
    fun sepiaModeMatchesDocumentedColors() {
        val p = palette(ContrastMode.SEPIA)

        assertFalse(p.darkMode)
        assertEquals(Color.parseColor("#F2E7D0"), p.backgroundColor)
        assertEquals(Color.parseColor("#5B4636"), p.textColor)
        assertEquals(Color.parseColor("#E6D9BE"), p.magicTagBackgroundColor)
        assertEquals(Color.parseColor("#8A6740"), p.accentColor)
        assertTrue(p.useLightNavIcons)
    }

    @Test
    fun highContrastModeMatchesDocumentedColors() {
        val p = palette(ContrastMode.HIGH_CONTRAST)

        assertTrue(p.darkMode)
        assertEquals(Color.BLACK, p.backgroundColor)
        assertEquals(Color.BLACK, p.topBarColor)
        assertEquals(Color.WHITE, p.textColor)
        assertEquals(Color.parseColor("#202020"), p.magicTagBackgroundColor)
        assertEquals(Color.parseColor("#F2F2F2"), p.accentColor)
        // 亮色底需要黑色按鈕文字
        assertEquals(Color.BLACK, p.primaryButtonTextColor)
        assertFalse(p.useLightStatusIcons)
        assertFalse(p.useLightNavIcons)
    }

    @Test
    fun secondaryAndHintPreserveTextColorChannels() {
        ContrastMode.values().forEach { mode ->
            val p = palette(mode)
            assertEquals("mode=$mode", Color.red(p.textColor), Color.red(p.secondaryTextColor))
            assertEquals("mode=$mode", Color.green(p.textColor), Color.green(p.secondaryTextColor))
            assertEquals("mode=$mode", Color.blue(p.textColor), Color.blue(p.secondaryTextColor))
            assertEquals("mode=$mode", Color.red(p.textColor), Color.red(p.hintColor))
            assertEquals("mode=$mode", Color.blue(p.textColor), Color.blue(p.hintColor))
        }
    }

    @Test
    fun primaryButtonTextFollowsAccentLuminance() {
        ContrastMode.values().forEach { mode ->
            val p = palette(mode)
            val expected =
                    if (ColorUtils.calculateLuminance(p.accentColor) > 0.5) Color.BLACK
                    else Color.WHITE
            assertEquals("mode=$mode", expected, p.primaryButtonTextColor)
        }
    }

    @Test
    fun lightStatusIconsOnlyForLightTopBarOnNonDarkModes() {
        ContrastMode.values().forEach { mode ->
            val p = palette(mode)
            val expected =
                    !p.darkMode && ColorUtils.calculateLuminance(p.topBarColor) > 0.5
            assertEquals("mode=$mode", expected, p.useLightStatusIcons)
        }
    }
}
