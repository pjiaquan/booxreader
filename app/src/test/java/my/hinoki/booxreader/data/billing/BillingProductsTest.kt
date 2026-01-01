package my.hinoki.booxreader.data.billing

import com.android.billingclient.api.BillingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingProductsTest {

    @Test
    fun productSpecsHaveExpectedIdsAndTypes() {
        val specs = BillingProducts.productSpecs()
        assertEquals(2, specs.size)

        val monthly = specs.first { it.id == BillingProducts.MONTHLY_SUBS_ID }
        assertEquals(BillingClient.ProductType.SUBS, monthly.type)

        val lifetime = specs.first { it.id == BillingProducts.LIFETIME_INAPP_ID }
        assertEquals(BillingClient.ProductType.INAPP, lifetime.type)

        val uniqueIds = specs.map { it.id }.toSet()
        assertTrue(uniqueIds.size == specs.size)
    }
}
