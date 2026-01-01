package my.hinoki.booxreader.data.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.QueryProductDetailsParams

data class ProductSpec(val id: String, val type: String)

object BillingProducts {
    const val MONTHLY_SUBS_ID = "ai_monthly_4"
    const val LIFETIME_INAPP_ID = "ai_lifetime_25"

    fun productSpecs(): List<ProductSpec> =
            listOf(
                    ProductSpec(MONTHLY_SUBS_ID, BillingClient.ProductType.SUBS),
                    ProductSpec(LIFETIME_INAPP_ID, BillingClient.ProductType.INAPP)
            )

    fun buildQueryProductList(): List<QueryProductDetailsParams.Product> =
            productSpecs().map { spec ->
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(spec.id)
                        .setProductType(spec.type)
                        .build()
            }
}
