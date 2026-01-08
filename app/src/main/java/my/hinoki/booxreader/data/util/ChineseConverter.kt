package my.hinoki.booxreader.data.util

import android.text.SpannableStringBuilder
import android.text.Spanned
import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * Chinese character converter for Simplified <-> Traditional Chinese conversion. Uses opencc4j
 * library for efficient local conversion.
 *
 * Note: This converter preserves spans from Spanned input when the converted text has the same
 * length as the original. If lengths differ (which can happen with some conversions), spans are
 * still copied but may not align perfectly.
 */
object ChineseConverter {

    /**
     * Convert Simplified Chinese to Traditional Chinese.
     * @param text The input text to convert
     * @return The converted text in Traditional Chinese, or original text if conversion fails.
     * ```
     *         If input is Spanned, the result will preserve spans.
     * ```
     */
    fun toTraditional(text: CharSequence): CharSequence {
        if (text.isEmpty()) return text
        return try {
            val converted = ZhConverterUtil.toTraditional(text.toString()) ?: return text
            preserveSpans(text, converted)
        } catch (e: Exception) {
            // Return original text if conversion fails (e.g., for pure ASCII/English text)
            text
        }
    }

    /**
     * Convert Traditional Chinese to Simplified Chinese.
     * @param text The input text to convert
     * @return The converted text in Simplified Chinese, or original text if conversion fails.
     * ```
     *         If input is Spanned, the result will preserve spans.
     * ```
     */
    fun toSimplified(text: CharSequence): CharSequence {
        if (text.isEmpty()) return text
        return try {
            val converted = ZhConverterUtil.toSimple(text.toString()) ?: return text
            preserveSpans(text, converted)
        } catch (e: Exception) {
            // Return original text if conversion fails
            text
        }
    }

    /**
     * If the original text is Spanned, copy spans to the converted text. Simplified <-> Traditional
     * conversion typically preserves character count, so spans should align correctly.
     */
    private fun preserveSpans(original: CharSequence, converted: String): CharSequence {
        if (original !is Spanned) {
            return converted
        }

        // If lengths differ, spans may not align perfectly, but we still try to copy them
        val result = SpannableStringBuilder(converted)
        val spans = original.getSpans(0, original.length, Any::class.java)

        for (span in spans) {
            val start = original.getSpanStart(span)
            val end = original.getSpanEnd(span)
            val flags = original.getSpanFlags(span)

            // Only copy span if it fits within the converted text bounds
            if (start >= 0 && end <= converted.length && start <= end) {
                try {
                    result.setSpan(span, start, end, flags)
                } catch (e: Exception) {
                    // Ignore span copy failures (e.g., if span is already attached elsewhere)
                }
            }
        }

        return result
    }
}
