package my.hinoki.booxreader.data.util

import org.junit.Assert.*
import org.junit.Test

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

    @Test
    fun testToTraditional_EnglishOnly() {
        // English text should be returned unchanged without crashing
        val testCases =
                listOf("PaaS", "IaaS", "Hello World", "Test123", "API v2.0", "HTTPS://example.com")
        for (input in testCases) {
            val result = ChineseConverter.toTraditional(input)
            assertEquals("English text '$input' should remain unchanged", input, result)
        }
    }

    @Test
    fun testToTraditional_EmptyString() {
        val input = ""
        val result = ChineseConverter.toTraditional(input)
        assertEquals("Empty string should remain empty", "", result)
    }

    @Test
    fun testToTraditional_MixedContent() {
        // Mixed Chinese and English should convert only Chinese parts
        val input = "这是PaaS服务"
        val result = ChineseConverter.toTraditional(input)
        assertTrue("Result should contain 'PaaS'", result.contains("PaaS"))
        assertTrue(
                "Result should be in Traditional Chinese",
                result.contains("這是") || result.contains("服務")
        )
    }

    @Test
    fun testToSimplified_EnglishOnly() {
        val input = "SaaS Platform"
        val result = ChineseConverter.toSimplified(input)
        assertEquals("English text should remain unchanged", input, result)
    }

    @Test
    fun testToSimplified_EmptyString() {
        val input = ""
        val result = ChineseConverter.toSimplified(input)
        assertEquals("Empty string should remain empty", "", result)
    }
}
