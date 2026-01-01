package my.hinoki.booxreader.data.billing

import org.json.JSONObject

data class BillingStatus(
    val planType: String?,
    val dailyRemaining: Int?
)

object BillingStatusParser {
    fun parse(raw: String): BillingStatus? {
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        val planType = json.optString("plan_type", "").ifBlank { null }
        val remaining = json.optInt("daily_remaining", -1).takeIf { it >= 0 }
        return BillingStatus(planType, remaining)
    }
}
