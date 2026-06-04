package uk.co.twinscrollgridbalancer.tsgbheater.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin wrapper over Google Play Billing. Owns the [BillingClient], surfaces the
 * resolved [products] and the set of [ownedProductIds], and exposes a single
 * [launchPurchase] entry point. Degrades silently to "no products / nothing
 * owned" when Play is unavailable or the products aren't configured yet, so the
 * rest of the app (Free / Trial / Legacy) keeps working.
 */
class BillingManager(private val appContext: Context) {

    data class ProductInfo(
        val productId: String,
        val title: String,
        val formattedPrice: String,
        val isSubscription: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _products = MutableStateFlow<List<ProductInfo>>(emptyList())
    val products: StateFlow<List<ProductInfo>> = _products.asStateFlow()

    private val _owned = MutableStateFlow<Set<String>>(emptySet())
    val ownedProductIds: StateFlow<Set<String>> = _owned.asStateFlow()

    // ProductDetails cache, needed to launch a billing flow.
    private val detailsById = mutableMapOf<String, ProductDetails>()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch {
                purchases.forEach { acknowledgeIfNeeded(it) }
                refreshPurchases()
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    /** Idempotent. Safe to call from Application.onCreate. */
    fun connect() {
        val state = client.connectionState
        if (state == BillingClient.ConnectionState.CONNECTED ||
            state == BillingClient.ConnectionState.CONNECTING
        ) return

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryProducts()
                        refreshPurchases()
                    }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Lazy reconnect: next connect() call re-establishes.
            }
        })
    }

    private suspend fun queryProducts() {
        val collected = mutableListOf<ProductInfo>()

        val groups = listOf(
            BillingClient.ProductType.INAPP to ProductIds.ONE_TIME,
            BillingClient.ProductType.SUBS to ProductIds.SUBS,
        )
        for ((type, ids) in groups) {
            if (ids.isEmpty()) continue
            val productList = ids.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(type)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()
            val result = client.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails($type) failed: ${result.billingResult.debugMessage}")
                continue
            }
            result.productDetailsList?.forEach { pd ->
                detailsById[pd.productId] = pd
                val price = if (type == BillingClient.ProductType.SUBS) {
                    pd.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.pricingPhases?.pricingPhaseList?.firstOrNull()
                        ?.formattedPrice
                } else {
                    pd.oneTimePurchaseOfferDetails?.formattedPrice
                }
                collected += ProductInfo(
                    productId = pd.productId,
                    title = pd.title,
                    formattedPrice = price.orEmpty(),
                    isSubscription = type == BillingClient.ProductType.SUBS,
                )
            }
        }
        _products.value = collected
    }

    /** Re-read what's owned from Play. Also used by the "Restore" button. */
    suspend fun refreshPurchases() {
        val owned = mutableSetOf<String>()
        for (type in listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
            val result = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build()
            )
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) continue
            result.purchasesList.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    owned += purchase.products
                    acknowledgeIfNeeded(purchase)
                }
            }
        }
        _owned.value = owned
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            )
        }
    }

    /** Launch the Play purchase sheet. No-op if the product isn't loaded yet. */
    fun launchPurchase(activity: Activity, productId: String) {
        val pd = detailsById[productId] ?: run {
            Log.w(TAG, "launchPurchase: product $productId not loaded")
            return
        }
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(pd)
        if (pd.productType == BillingClient.ProductType.SUBS) {
            val offerToken = pd.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: run {
                Log.w(TAG, "launchPurchase: no offer token for sub $productId")
                return
            }
            paramsBuilder.setOfferToken(offerToken)
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()
        client.launchBillingFlow(activity, flowParams)
    }

    private companion object {
        const val TAG = "BillingManager"
    }
}
