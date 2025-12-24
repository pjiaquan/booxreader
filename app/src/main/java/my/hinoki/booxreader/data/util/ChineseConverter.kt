package my.hinoki.booxreader.data.util

import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * Chinese character converter for Simplified <-> Traditional Chinese conversion.
 * Uses opencc4j library for efficient local conversion.
 */
object ChineseConverter {

    /**
     * Convert Simplified Chinese to Traditional Chinese.
     * @param text The input text to convert
     * @return The converted text in Traditional Chinese
     */
    fun toTraditional(text: CharSequence): CharSequence {
        return ZhConverterUtil.toTraditional(text.toString())
    }

    /**
     * Convert Traditional Chinese to Simplified Chinese.
     * @param text The input text to convert
     * @return The converted text in Simplified Chinese
     */
    fun toSimplified(text: CharSequence): CharSequence {
        return ZhConverterUtil.toSimple(text.toString())
    }
}
