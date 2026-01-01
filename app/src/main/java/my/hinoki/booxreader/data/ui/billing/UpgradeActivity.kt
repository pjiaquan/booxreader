package my.hinoki.booxreader.data.ui.billing

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.R
import my.hinoki.booxreader.data.billing.BillingProducts
import my.hinoki.booxreader.data.billing.BillingStatusParser
import my.hinoki.booxreader.data.prefs.TokenManager
import my.hinoki.booxreader.data.remote.AuthInterceptor
import my.hinoki.booxreader.data.settings.ReaderSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class UpgradeActivity : AppCompatActivity(), PurchasesUpdatedListener {
    private lateinit var billingClient: BillingClient
    private var monthlyDetails: ProductDetails? = null
    private var lifetimeDetails: ProductDetails? = null

    private lateinit var btnMonthly: Button
    private lateinit var btnLifetime: Button
    private lateinit var btnRestore: Button
    private lateinit var btnManageSubscription: Button
    private lateinit var tvMonthlyPrice: TextView
    private lateinit var tvLifetimePrice: TextView
    private lateinit var tvCurrentPlan: TextView
    private lateinit var tvRemainingCredits: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade)

        btnMonthly = findViewById(R.id.btnMonthly)
        btnLifetime = findViewById(R.id.btnLifetime)
        btnRestore = findViewById(R.id.btnRestore)
        btnManageSubscription = findViewById(R.id.btnManageSubscription)
        tvMonthlyPrice = findViewById(R.id.tvMonthlyPrice)
        tvLifetimePrice = findViewById(R.id.tvLifetimePrice)
        tvCurrentPlan = findViewById(R.id.tvCurrentPlan)
        tvRemainingCredits = findViewById(R.id.tvRemainingCredits)

        btnMonthly.setOnClickListener { launchPurchase(monthlyDetails) }
        btnLifetime.setOnClickListener { launchPurchase(lifetimeDetails) }
        btnRestore.setOnClickListener { restorePurchases() }
        btnManageSubscription.setOnClickListener { openManageSubscription() }

        billingClient =
                BillingClient.newBuilder(this)
                        .setListener(this)
                        .enablePendingPurchases()
                        .build()

        billingClient.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            queryProducts()
                            fetchPlanStatus()
                        } else {
                            toast(getString(R.string.upgrade_billing_unavailable))
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        toast(getString(R.string.upgrade_billing_unavailable))
                    }
                }
        )
    }

    private fun queryProducts() {
        val products = BillingProducts.buildQueryProductList()

        val params =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(products)
                        .build()

        billingClient.queryProductDetailsAsync(params) { _, details ->
            monthlyDetails = details.firstOrNull { it.productId == BillingProducts.MONTHLY_SUBS_ID }
            lifetimeDetails =
                    details.firstOrNull { it.productId == BillingProducts.LIFETIME_INAPP_ID }

            tvMonthlyPrice.text = priceText(monthlyDetails)
            tvLifetimePrice.text = priceText(lifetimeDetails)
            btnMonthly.isEnabled = monthlyDetails != null
            btnLifetime.isEnabled = lifetimeDetails != null
        }
    }

    private fun priceText(details: ProductDetails?): String {
        if (details == null) return getString(R.string.upgrade_price_loading)
        return if (details.productType == BillingClient.ProductType.SUBS) {
            details.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull()
                    ?.formattedPrice
                    ?: getString(R.string.upgrade_price_loading)
        } else {
            details.oneTimePurchaseOfferDetails?.formattedPrice
                    ?: getString(R.string.upgrade_price_loading)
        }
    }

    private fun launchPurchase(details: ProductDetails?) {
        if (details == null) {
            toast(getString(R.string.upgrade_product_unavailable))
            return
        }
        val params =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .apply {
                            if (details.productType == BillingClient.ProductType.SUBS) {
                                val offerToken =
                                        details.subscriptionOfferDetails
                                                ?.firstOrNull()
                                                ?.offerToken
                                if (!offerToken.isNullOrBlank()) {
                                    setOfferToken(offerToken)
                                }
                            }
                        }
                        .build()

        val flowParams =
                BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(params))
                        .build()

        billingClient.launchBillingFlow(this, flowParams)
    }

    override fun onPurchasesUpdated(
        result: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                toast(getString(R.string.upgrade_purchase_failed))
            }
            return
        }

        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build()
                    ) {}
                }
                sendToBackend(purchase)
            }
        }
    }

    private fun restorePurchases() {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
        ) { _, purchases ->
            purchases.forEach { sendToBackend(it) }
        }

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
        ) { _, purchases ->
            purchases.forEach { sendToBackend(it) }
        }
    }

    private fun fetchPlanStatus() {
        val settings =
                ReaderSettings.fromPrefs(
                        getSharedPreferences(ReaderSettings.PREFS_NAME, MODE_PRIVATE)
                )
        val baseUrl = settings.serverBaseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            tvCurrentPlan.setText(R.string.upgrade_missing_server)
            return
        }

        lifecycleScope.launch {
            val tokenManager = (application as BooxReaderApp).tokenManager
            val client = buildAuthClient(tokenManager)

            val status =
                    withContext(Dispatchers.IO) {
                        val request =
                                Request.Builder()
                                        .url("$baseUrl/billing/status")
                                        .get()
                                        .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@withContext null
                            val body = response.body?.string().orEmpty()
                            val status = BillingStatusParser.parse(body) ?: return@withContext null
                            return@withContext Pair(status.planType, status.dailyRemaining)
                        }
                    }

            updatePlanUi(status?.first)
            updateRemainingUi(status?.second)
        }
    }

    private fun updatePlanUi(planType: String?) {
        when (planType?.lowercase()) {
            "monthly" -> {
                tvCurrentPlan.setText(R.string.upgrade_plan_monthly)
                btnManageSubscription.visibility = View.VISIBLE
            }
            "lifetime" -> {
                tvCurrentPlan.setText(R.string.upgrade_plan_lifetime)
                btnManageSubscription.visibility = View.GONE
            }
            else -> {
                tvCurrentPlan.setText(R.string.upgrade_plan_free)
                btnManageSubscription.visibility = View.GONE
            }
        }
    }

    private fun updateRemainingUi(remaining: Int?) {
        if (remaining == null) {
            tvRemainingCredits.setText(R.string.upgrade_remaining_unknown)
            return
        }
        tvRemainingCredits.text = getString(R.string.upgrade_remaining_format, remaining)
    }

    private fun openManageSubscription() {
        val uri =
                android.net.Uri.parse(
                        "https://play.google.com/store/account/subscriptions" +
                                "?package=$packageName&sku=${BillingProducts.MONTHLY_SUBS_ID}"
                )
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    }

    private fun sendToBackend(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return
        val token = purchase.purchaseToken
        val settings =
                ReaderSettings.fromPrefs(
                        getSharedPreferences(ReaderSettings.PREFS_NAME, MODE_PRIVATE)
                )
        val baseUrl = settings.serverBaseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            toast(getString(R.string.upgrade_missing_server))
            return
        }

        lifecycleScope.launch {
            val ok =
                    withContext(Dispatchers.IO) {
                        val tokenManager = (application as BooxReaderApp).tokenManager
                        val client = buildAuthClient(tokenManager)
                        val body =
                                JSONObject()
                                        .put("product_id", productId)
                                        .put("purchase_token", token)
                                        .toString()
                                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                        val request =
                                Request.Builder()
                                        .url("$baseUrl/billing/verify")
                                        .post(body)
                                        .build()
                        client.newCall(request).execute().use { response ->
                            response.isSuccessful
                        }
                    }
            if (ok) {
                toast(getString(R.string.upgrade_purchase_success))
                fetchPlanStatus()
            } else {
                toast(getString(R.string.upgrade_purchase_failed))
            }
        }
    }

    private fun buildAuthClient(tokenManager: TokenManager): OkHttpClient {
        return OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(tokenManager))
                .build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        billingClient.endConnection()
        super.onDestroy()
    }

}
