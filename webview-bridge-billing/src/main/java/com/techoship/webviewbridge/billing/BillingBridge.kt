package com.techoship.webviewbridge.billing

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.techoship.webviewbridge.WebViewBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exposes Google Play Billing to the page as `window.AndroidBilling`, so a web app inside a
 * WebView can sell in-app products and subscriptions through Play.
 *
 * ```kotlin
 * val bridge = WebViewBridge.Builder(webView).allowOrigin("https://app.example.com").install()
 * val billing = BillingBridge.install(bridge, activity)
 * ```
 *
 * Only the top-level page can call billing, never an iframe. Verify every purchase on your
 * server before granting it: send `purchaseToken` to your backend, check it with the Google
 * Play Developer API, then acknowledge it (from the server, or with `AndroidBilling.acknowledge`).
 * Play refunds purchases that are not acknowledged within three days.
 */
class BillingBridge private constructor(
    private val bridge: WebViewBridge,
    private val activity: Activity,
    private val options: Options,
) : PurchasesUpdatedListener {

    /** Billing behaviour. */
    class Options @JvmOverloads constructor(
        /**
         * Acknowledge non-consumable purchases on the device as soon as Play reports them.
         * Off by default, because a server should verify a purchase first. Turn it on only for
         * apps without a backend.
         */
        val autoAcknowledge: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val client: BillingClient = BillingClient.newBuilder(activity.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()
    private val productCache = HashMap<String, ProductDetails>()
    private val waiting = ArrayList<Pair<() -> Unit, (BillingResult) -> Unit>>()
    private var connecting = false
    private var purchaseCall: WebViewBridge.Call? = null
    private var destroyed = false

    private fun install() {
        handle("billing.connect") { call -> connected(call) { call.resolve(JSONObject().put("ready", true)) } }
        handle("billing.getProducts", ::getProducts)
        handle("billing.purchase", ::purchase)
        handle("billing.getPurchases", ::getPurchases)
        handle("billing.acknowledge", ::acknowledge)
        handle("billing.consume", ::consume)
        val source = activity.resources.openRawResource(R.raw.webview_bridge_billing).bufferedReader().use { it.readText() }
        bridge.addScript("$source(${JSONObject().put("version", WebViewBridge.VERSION)});")
    }

    /** Ends the Play connection. Call from your Activity's `onDestroy`. */
    @MainThread
    fun destroy() {
        destroyed = true
        purchaseCall?.reject("destroyed", "The app closed the billing bridge")
        purchaseCall = null
        if (client.isReady) client.endConnection()
    }

    // ---- Page methods ------------------------------------------------------------------------

    /** `{ inapp: [ids], subs: [ids] }` → `{ products: [...], unfetched: [ids] }`. */
    private fun getProducts(call: WebViewBridge.Call) {
        val inapp = strings(call.params.optJSONArray("inapp"))
        val subs = strings(call.params.optJSONArray("subs"))
        if (inapp.isEmpty() && subs.isEmpty()) {
            call.reject("invalid_argument", "Pass at least one product id in inapp or subs")
            return
        }
        connected(call) {
            val queries = listOf(ProductType.INAPP to inapp, ProductType.SUBS to subs).filter { it.second.isNotEmpty() }
            val products = JSONArray()
            val unfetched = JSONArray()
            var remaining = queries.size
            var failure: BillingResult? = null
            queries.forEach { (type, ids) ->
                queryProducts(type, ids) { result, found, missing ->
                    if (result.responseCode != BillingResponseCode.OK) failure = result
                    found.forEach { products.put(BillingJson.product(it)) }
                    missing.forEach { unfetched.put(it) }
                    remaining -= 1
                    if (remaining == 0) {
                        val error = failure
                        if (error != null && products.length() == 0) {
                            call.reject(BillingJson.responseCode(error.responseCode), error.debugMessage)
                        } else {
                            call.resolve(JSONObject().put("products", products).put("unfetched", unfetched))
                        }
                    }
                }
            }
        }
    }

    /**
     * `{ productId, type?, offerToken?, obfuscatedAccountId?, obfuscatedProfileId? }` →
     * `{ status: "purchased" | "pending" | "cancelled" | "already_owned", purchases: [...] }`.
     */
    private fun purchase(call: WebViewBridge.Call) {
        val productId = call.params.optString("productId")
        if (productId.isBlank()) {
            call.reject("invalid_argument", "productId is required")
            return
        }
        if (purchaseCall != null) {
            call.reject("busy", "Another purchase is in progress")
            return
        }
        connected(call) {
            val cached = productCache[productId]
            if (cached != null) {
                launch(call, cached)
                return@connected
            }
            val type = call.params.optString("type").ifBlank { null }
            if (type != ProductType.INAPP && type != ProductType.SUBS) {
                call.reject("unknown_product", "Call getProducts for $productId first, or pass type: 'inapp' or 'subs'")
                return@connected
            }
            queryProducts(type, listOf(productId)) { result, found, _ ->
                val details = found.firstOrNull()
                when {
                    details != null -> launch(call, details)
                    result.responseCode != BillingResponseCode.OK ->
                        call.reject(BillingJson.responseCode(result.responseCode), result.debugMessage)
                    else -> call.reject("item_unavailable", "Play does not know the product $productId")
                }
            }
        }
    }

    private fun launch(call: WebViewBridge.Call, details: ProductDetails) {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        if (details.productType == ProductType.SUBS) {
            val offers = details.subscriptionOfferDetails.orEmpty()
            val requested = call.params.optString("offerToken").ifBlank { null }
            val token = requested ?: offers.firstOrNull()?.offerToken
            if (token == null || (requested != null && offers.none { it.offerToken == requested })) {
                call.reject("invalid_argument", "No matching subscription offer for ${details.productId}")
                return
            }
            productParams.setOfferToken(token)
        } else {
            call.params.optString("offerToken").ifBlank { null }?.let(productParams::setOfferToken)
        }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams.build()))
        call.params.optString("obfuscatedAccountId").ifBlank { null }?.let(flow::setObfuscatedAccountId)
        call.params.optString("obfuscatedProfileId").ifBlank { null }?.let(flow::setObfuscatedProfileId)
        purchaseCall = call
        val result = client.launchBillingFlow(activity, flow.build())
        if (result.responseCode != BillingResponseCode.OK) {
            purchaseCall = null
            call.reject(BillingJson.responseCode(result.responseCode), result.debugMessage)
        }
    }

    /** `{ type?: "inapp" | "subs" }` → `{ purchases: [...] }` for what the user owns now. */
    private fun getPurchases(call: WebViewBridge.Call) {
        val requested = call.params.optString("type").ifBlank { null }
        val types = if (requested != null) listOf(requested) else listOf(ProductType.INAPP, ProductType.SUBS)
        if (types.any { it != ProductType.INAPP && it != ProductType.SUBS }) {
            call.reject("invalid_argument", "type must be 'inapp' or 'subs'")
            return
        }
        connected(call) {
            val all = ArrayList<Purchase>()
            var remaining = types.size
            var failure: BillingResult? = null
            types.forEach { type ->
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, purchases ->
                    onMain {
                        if (result.responseCode == BillingResponseCode.OK) all += purchases else failure = result
                        remaining -= 1
                        if (remaining == 0) {
                            val error = failure
                            if (error != null && all.isEmpty()) {
                                call.reject(BillingJson.responseCode(error.responseCode), error.debugMessage)
                            } else {
                                autoAcknowledge(all)
                                call.resolve(JSONObject().put("purchases", BillingJson.purchases(all)))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun acknowledge(call: WebViewBridge.Call) {
        val token = call.params.optString("purchaseToken")
        if (token.isBlank()) {
            call.reject("invalid_argument", "purchaseToken is required")
            return
        }
        connected(call) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
                answer(call, result)
            }
        }
    }

    private fun consume(call: WebViewBridge.Call) {
        val token = call.params.optString("purchaseToken")
        if (token.isBlank()) {
            call.reject("invalid_argument", "purchaseToken is required")
            return
        }
        connected(call) {
            client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { result, _ ->
                answer(call, result)
            }
        }
    }

    // ---- Play callbacks ----------------------------------------------------------------------

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val list = purchases.orEmpty().toList()
        onMain {
            if (destroyed) return@onMain
            autoAcknowledge(list)
            val json = BillingJson.purchases(list)
            bridge.emit(
                "billing.purchasesUpdated",
                JSONObject().put("responseCode", BillingJson.responseCode(result.responseCode)).put("purchases", json),
            )
            val call = purchaseCall ?: return@onMain
            purchaseCall = null
            when (result.responseCode) {
                BillingResponseCode.OK -> {
                    val status = when {
                        list.any { it.purchaseState == Purchase.PurchaseState.PURCHASED } -> "purchased"
                        list.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> "pending"
                        else -> "unspecified"
                    }
                    call.resolve(JSONObject().put("status", status).put("purchases", json))
                }
                BillingResponseCode.USER_CANCELED -> call.resolve(JSONObject().put("status", "cancelled").put("purchases", JSONArray()))
                BillingResponseCode.ITEM_ALREADY_OWNED -> call.resolve(JSONObject().put("status", "already_owned").put("purchases", json))
                else -> call.reject(BillingJson.responseCode(result.responseCode), result.debugMessage)
            }
        }
    }

    private fun autoAcknowledge(purchases: List<Purchase>) {
        if (!options.autoAcknowledge) return
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledge failed: ${result.responseCode} ${result.debugMessage}")
                }
            }
        }
    }

    // ---- Plumbing ----------------------------------------------------------------------------

    /** Registers a page method that only the top-level page may call. */
    private fun handle(method: String, handler: (WebViewBridge.Call) -> Unit) {
        bridge.registerHandler(method) { call ->
            if (destroyed) {
                call.reject("destroyed", "The app closed the billing bridge")
            } else if (!call.isMainFrame) {
                call.reject("forbidden", "Billing is only available to the top-level page")
            } else {
                handler(call)
            }
        }
    }

    private fun queryProducts(
        type: String,
        ids: List<String>,
        done: (BillingResult, List<ProductDetails>, List<String>) -> Unit,
    ) {
        val products = ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, response ->
            val found = response.productDetailsList
            val missing = response.unfetchedProductList.map { it.productId }
            onMain {
                found.forEach { productCache[it.productId] = it }
                done(result, found, missing)
            }
        }
    }

    /** Runs [action] once Play Billing is connected, or rejects [call] if it cannot connect. */
    private fun connected(call: WebViewBridge.Call, action: () -> Unit) {
        if (client.isReady) {
            action()
            return
        }
        waiting += action to { result: BillingResult -> call.reject(BillingJson.responseCode(result.responseCode), result.debugMessage) }
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = onMain {
                connecting = false
                val pending = waiting.toList()
                waiting.clear()
                if (result.responseCode == BillingResponseCode.OK) {
                    pending.forEach { (run, _) -> run() }
                } else {
                    Log.w(TAG, "Billing unavailable: ${result.responseCode} ${result.debugMessage}")
                    pending.forEach { (_, fail) -> fail(result) }
                }
            }

            override fun onBillingServiceDisconnected() = onMain {
                // With automatic reconnection Play reconnects on the next call; fail what is waiting now.
                connecting = false
                val pending = waiting.toList()
                waiting.clear()
                val result = BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED)
                    .setDebugMessage("Play Billing disconnected").build()
                pending.forEach { (_, fail) -> fail(result) }
            }
        })
    }

    private fun answer(call: WebViewBridge.Call, result: BillingResult) {
        if (result.responseCode == BillingResponseCode.OK) {
            call.resolve()
        } else {
            call.reject(BillingJson.responseCode(result.responseCode), result.debugMessage)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun strings(array: JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.trim()?.takeIf(String::isNotEmpty) }.distinct()

    companion object {
        private const val TAG = "BillingBridge"

        /**
         * Adds `window.AndroidBilling` to pages that can use [bridge]. Call on the main thread,
         * right after installing the bridge and before the page loads.
         */
        @JvmStatic
        @JvmOverloads
        @MainThread
        fun install(bridge: WebViewBridge, activity: Activity, options: Options = Options()): BillingBridge =
            BillingBridge(bridge, activity, options).also { it.install() }
    }
}
