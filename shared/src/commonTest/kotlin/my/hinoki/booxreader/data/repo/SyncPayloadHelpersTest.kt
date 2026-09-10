package my.hinoki.booxreader.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import my.hinoki.booxreader.data.db.ApiKey

/**
 * `SyncPayloadHelpers` 的單元測試。
 *
 * 這些函式原本散落在 3000+ 行的 UserSyncRepository 裡且是 private，無法測試；
 * 抽出來之後在 `commonTest` 就能同時於 Android JVM 與 iOS 執行。
 */
class SyncPayloadHelpersTest {

    @Test
    fun longValueHandlesNumbersStringsJsonAndGarbage() {
        assertEquals(42L, longValue(42))
        assertEquals(42L, longValue(42L))
        assertEquals(1234L, longValue("1234"))
        assertEquals(0L, longValue("not-a-number"))
        assertEquals(99L, longValue(JsonPrimitive("99")))
        assertEquals(0L, longValue(null))
        assertEquals(0L, longValue(listOf(1, 2)))
    }

    @Test
    fun latestSettingsRecordPicksNewestRegardlessOfNumberType() {
        val older = buildJsonObject {
            put("id", "older")
            put("updatedAt", 100)
        }
        val newerAsString = buildJsonObject {
            put("id", "newer")
            put("updatedAt", "200")
        }

        assertEquals("newer", latestSettingsRecord(listOf(older, newerAsString))?.get("id")?.let { (it as JsonPrimitive).content })
        assertNull(latestSettingsRecord(emptyList()))
    }

    @Test
    fun escapeHtmlForEmailEscapesMarkupCharacters() {
        assertEquals("a &amp; b &lt;i&gt;", escapeHtmlForEmail("a & b <i>"))
        assertEquals("plain", escapeHtmlForEmail("plain"))
    }

    @Test
    fun profileNameKeyTrimsAndLowercases() {
        assertEquals("deepseek chat", profileNameKey("  DeepSeek Chat  "))
        assertEquals("", profileNameKey("   "))
    }

    @Test
    fun hasUsableApiKeyRejectsBlanksAndPlaceholders() {
        assertFalse(hasUsableApiKey(ApiKey("")))
        assertFalse(hasUsableApiKey(ApiKey("   ")))
        assertFalse(hasUsableApiKey(ApiKey("<YOUR_GEMINI_API_KEY>")))
        assertFalse(hasUsableApiKey(ApiKey("<YOUR_OPENAI_KEY>")))
        assertTrue(hasUsableApiKey(ApiKey("sk-live-123")))
    }

    @Test
    fun normalizeStoragePathStripsPrefixAndWhitespace() {
        assertNull(normalizeStoragePath(null))
        assertNull(normalizeStoragePath("   "))
        assertEquals("books/abc.epub", normalizeStoragePath("  books/abc.epub  "))
        assertEquals("books/abc.epub", normalizeStoragePath("pocketbase://books/abc.epub"))
    }

    @Test
    fun storagePathFromPseudoUriOnlyAcceptsPocketBaseUris() {
        assertNull(storagePathFromPseudoUri(null))
        assertNull(storagePathFromPseudoUri(""))
        assertNull(storagePathFromPseudoUri("https://example.com/book.epub"))
        assertNull(storagePathFromPseudoUri("content://media/1"))
        assertEquals("books/abc.epub", storagePathFromPseudoUri("pocketbase://books/abc.epub"))
    }

    @Test
    fun urlEncodePathKeepsSeparatorsAndEncodesSegments() {
        val encoded = urlEncodePath("record 1/a b.epub")

        assertTrue(encoded.contains("/"), "path separators must be preserved: $encoded")
        assertFalse(encoded.contains(" "), "spaces must be encoded: $encoded")
    }

    @Test
    fun urlEncodeQueryValueEncodesQueryReservedCharacters() {
        val encoded = urlEncodeQueryValue("a b&c=d")

        assertFalse(encoded.contains(" "), encoded)
        assertFalse(encoded.contains("&"), encoded)
        assertFalse(encoded.contains("="), encoded)
    }

    @Test
    fun truncateForRemoteTextOnlyTruncatesWhenTooLong() {
        assertEquals("short", truncateForRemoteText("short", 100))

        val truncated = truncateForRemoteText("x".repeat(500), 50)
        assertEquals(50, truncated.length)
        assertTrue(truncated.endsWith("[truncated for sync]"), truncated.takeLast(30))
    }

    @Test
    fun truncateForRemoteTextRespectsTinyLimits() {
        val truncated = truncateForRemoteText("x".repeat(500), 5)

        assertEquals(5, truncated.length)
        assertFalse(truncated.contains("[truncated for sync]"))
    }

    @Test
    fun extractMessageContentByRoleReturnsLastMatchingMessage() {
        val messages =
                """
                [
                  {"role":"user","content":"question"},
                  {"role":"assistant","content":"first answer"},
                  {"role":"assistant","content":"final answer"}
                ]
                """.trimIndent()

        assertEquals("final answer", extractMessageContentByRole(messages, "assistant"))
        assertEquals("question", extractMessageContentByRole(messages, "user"))
    }

    @Test
    fun extractMessageContentByRoleHandlesBadInput() {
        assertNull(extractMessageContentByRole("", "user"))
        assertNull(extractMessageContentByRole("not json", "user"))
        assertNull(extractMessageContentByRole("""{"role":"user"}""", "user"))
        assertNull(extractMessageContentByRole("""[{"role":"user","content":"  "}]""", "user"))
    }
}
