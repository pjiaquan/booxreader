package my.hinoki.booxreader.data.util

import org.junit.Test
import org.junit.Assert.*

class ChineseConverterTest {

    @Test
    fun testToTraditional() {
        val input = "简体中文"
        val expected = "簡體中文"
        val result = ChineseConverter.toTraditional(input)
        assertEquals("Should convert to Traditional", expected, result)
    }

    @Test
    fun testToSimplified() {
        val input = "簡體中文"
        val expected = "简体中文"
        val result = ChineseConverter.toSimplified(input)
        assertEquals("Should convert to Simplified", expected, result)
    }

    @Test
    fun testToTraditional_TestEpubContent() {
        // Content from test.epub (index_split_002.html)
        val input = "华杉讲透《孙子兵法》"
        val expected = "華杉講透《孫子兵法》"
        val result = ChineseConverter.toTraditional(input)
        assertEquals("Should convert content from test.epub to Traditional", expected, result)
    }
}
