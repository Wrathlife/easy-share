package com.netshare.app.billing

import android.app.Activity
import android.app.Application
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
import com.netshare.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play Billing for a one-time non-consumable [BuildConfig.BILLING_REMOVE_ADS_PRODUCT_ID].
 * Create that product in Play Console (In-app products → one-time → Managed / non-consumable).
 */
object NoAdsBilling : PurchasesUpdatedListener {
    private const val TAG = "NoAdsBilling"

    private lateinit var app: Application
    private lateinit var store: NoAdsStore
    private var client: BillingClient? = null
    private var productDetails: ProductDetails? = null

    private val _adsRemoved = MutableStateFlow(false)
    val adsRemoved: StateFlow<Boolean> = _adsRemoved.asStateFlow()

    private val _priceLabel = MutableStateFlow<String?>(null)
    val priceLabel: StateFlow<String?> = _priceLabel.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun start(application: Application) {
        if (this::app.isInitialized) return
        app = application
        store = NoAdsStore(application)
        _adsRemoved.value = store.adsRemoved

        val productId = BuildConfig.BILLING_REMOVE_ADS_PRODUCT_ID.trim()
        if (productId.isEmpty()) {
            Log.w(TAG, "BILLING_REMOVE_ADS_PRODUCT_ID blank; billing disabled")
            return
        }

        val billing = BillingClient.newBuilder(application)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()
        client = billing
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing connected")
                    _ready.value = true
                    _lastError.value = null
                    queryProduct()
                    refreshPurchases()
                } else {
                    _ready.value = false
                    _lastError.value = "Billing unavailable (${result.responseCode})"
                    Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                _ready.value = false
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails
        val billing = client
        if (billing == null || !billing.isReady || details == null) {
            _lastError.value = "Remove ads isn’t available yet (Play product / signed build)"
            return
        }
        if (_adsRemoved.value) return

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billing.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _lastError.value = "Could not start purchase (${result.responseCode})"
            Log.w(TAG, "launchBillingFlow: ${result.responseCode} ${result.debugMessage}")
        }
    }

    fun refreshPurchases() {
        val billing = client ?: return
        if (!billing.isReady) return
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${result.responseCode}")
                return@queryPurchasesAsync
            }
            handlePurchases(purchases.orEmpty())
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.i(TAG, "Purchase canceled")
            else -> {
                _lastError.value = "Purchase failed (${result.responseCode})"
                Log.w(TAG, "onPurchasesUpdated: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun queryProduct() {
        val billing = client ?: return
        val productId = BuildConfig.BILLING_REMOVE_ADS_PRODUCT_ID.trim()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        billing.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _lastError.value = "Could not load product (${result.responseCode})"
                Log.w(TAG, "queryProductDetails: ${result.responseCode} ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            val details = detailsList?.firstOrNull()
            productDetails = details
            _priceLabel.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
            if (details == null) {
                _lastError.value = "Product “$productId” not found in Play Console"
                Log.w(TAG, "No ProductDetails for $productId")
            } else {
                _lastError.value = null
                Log.i(TAG, "Product ready: $productId ${_priceLabel.value}")
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val productId = BuildConfig.BILLING_REMOVE_ADS_PRODUCT_ID.trim()
        var owned = false
        for (purchase in purchases) {
            if (!purchase.products.contains(productId)) continue
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            owned = true
            acknowledgeIfNeeded(purchase)
        }
        if (owned) {
            store.adsRemoved = true
            _adsRemoved.value = true
            _lastError.value = null
            Log.i(TAG, "Remove ads entitlement active")
        }
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val billing = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billing.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            } else {
                Log.w(TAG, "Acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }
}
