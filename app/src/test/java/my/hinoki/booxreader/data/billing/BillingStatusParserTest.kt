package my.hinoki.booxreader.data.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingStatusParserTest {

    @Test
    fun parseReturnsNullOnBlank() {
        assertNull(BillingStatusParser.parse(""))
        assertNull(BillingStatusParser.parse("   "))
    }

    @Test
    fun parseHandlesMissingFields() {
        val status = BillingStatusParser.parse("""{ "plan_type": "monthly" }""")
        assertEquals("monthly", status?.planType)
        assertNull(status?.dailyRemaining)
    }

    @Test
    fun parseReturnsNullPlanForBlank() {
        val status = BillingStatusParser.parse("""{ "plan_type": "  ", "daily_remaining": 10 }""")
        assertNull(status?.planType)
        assertEquals(10, status?.dailyRemaining)
    }

    @Test
    fun parseReadsDailyRemaining() {
        val status = BillingStatusParser.parse("""{ "plan_type": "free", "daily_remaining": 20 }""")
        assertEquals("free", status?.planType)
        assertEquals(20, status?.dailyRemaining)
    }
}
