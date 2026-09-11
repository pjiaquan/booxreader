package my.hinoki.booxreader.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LocaleHelper` 的語言對應測試（純 JVM）。
 *
 * 這裡最重要的是**迴歸保護**：`"zh"` 這個既有使用者已經存在偏好設定裡的值，
 * 必須繼續代表繁體中文。若把它改成簡體（或改成 `"zh-Hant"` 之類的新值），
 * 所有既有使用者的介面語言都會被悄悄換掉。
 *
 * 簡體是新增的 `"zh-Hans"`。
 */
class LocaleHelperTest {

    @Test
    fun traditionalChineseValueIsUnchanged() {
        assertEquals(Locale.TRADITIONAL_CHINESE, LocaleHelper.localeFor("zh"))
        assertEquals("zh", LocaleHelper.LANGUAGE_TRADITIONAL_CHINESE)
    }

    @Test
    fun simplifiedChineseIsANewValue() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleHelper.localeFor("zh-Hans"))
        assertEquals("zh-Hans", LocaleHelper.LANGUAGE_SIMPLIFIED_CHINESE)
        assertNotEquals(
                "簡體與繁體必須是不同的語言值",
                LocaleHelper.LANGUAGE_TRADITIONAL_CHINESE,
                LocaleHelper.LANGUAGE_SIMPLIFIED_CHINESE
        )
    }

    @Test
    fun englishAndSystemMapAsBefore() {
        assertEquals(Locale.ENGLISH, LocaleHelper.localeFor("en"))
        assertEquals(Locale.getDefault(), LocaleHelper.localeFor("system"))
        assertEquals(Locale.getDefault(), LocaleHelper.localeFor("not-a-language"))
    }

    /**
     * 兩種中文的 [Locale] 語言碼都必須是 `zh`，否則 Android 不會去解析
     * `values-zh` / `values-zh-rTW`，而是退回英文。
     */
    @Test
    fun bothChineseLocalesKeepTheZhLanguageCode() {
        assertEquals("zh", LocaleHelper.localeFor(LocaleHelper.LANGUAGE_TRADITIONAL_CHINESE).language)
        assertEquals("zh", LocaleHelper.localeFor(LocaleHelper.LANGUAGE_SIMPLIFIED_CHINESE).language)
    }

    @Test
    fun theFourLanguageValuesAreDistinct() {
        val values =
                listOf(
                        LocaleHelper.LANGUAGE_SYSTEM,
                        LocaleHelper.LANGUAGE_ENGLISH,
                        LocaleHelper.LANGUAGE_TRADITIONAL_CHINESE,
                        LocaleHelper.LANGUAGE_SIMPLIFIED_CHINESE
                )
        assertEquals("語言值不可重複", values.size, values.toSet().size)
    }

    @Test
    fun unknownValuesFallBackToTheSystemLocaleRatherThanThrowing() {
        listOf("", "  ", "ZH", "zh_CN", null.toString())
                .forEach { value ->
                    // 不丟例外即可（"ZH" 大小寫不同 -> 目前視為未知、走系統預設）
                    assertTrue(LocaleHelper.localeFor(value) is Locale)
                }
    }
}
