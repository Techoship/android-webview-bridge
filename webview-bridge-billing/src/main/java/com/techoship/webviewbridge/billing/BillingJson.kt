package com.techoship.webviewbridge.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import org.json.JSONArray
import org.json.JSONObject

/** Converts Play Billing objects into the JSON the page receives. */
internal object BillingJson {
    fun product(details: ProductDetails): JSONObject {
        val json = JSONObject()
            .put("productId", details.productId)
            .put("type", details.productType)
            .put("title", details.title)
            .put("name", details.name)
            .put("description", details.description)
        details.oneTimePurchaseOfferDetailsList?.let { offers ->
            json.put("oneTimeOffers", JSONArray(offers.map(::oneTimeOffer)))
        }
        details.subscriptionOfferDetails?.let { offers ->
            json.put("subscriptionOffers", JSONArray(offers.map(::subscriptionOffer)))
        }
        return json
    }

    private fun oneTimeOffer(offer: ProductDetails.OneTimePurchaseOfferDetails): JSONObject = JSONObject()
        .put("offerId", offer.offerId ?: JSONObject.NULL)
        .put("offerToken", offer.offerToken)
        .put("offerTags", JSONArray(offer.offerTags))
        .put("formattedPrice", offer.formattedPrice)
        .put("priceAmountMicros", offer.priceAmountMicros)
        .put("priceCurrencyCode", offer.priceCurrencyCode)

    private fun subscriptionOffer(offer: ProductDetails.SubscriptionOfferDetails): JSONObject = JSONObject()
        .put("basePlanId", offer.basePlanId)
        .put("offerId", offer.offerId ?: JSONObject.NULL)
        .put("offerToken", offer.offerToken)
        .put("offerTags", JSONArray(offer.offerTags))
        .put(
            "pricingPhases",
            JSONArray(
                offer.pricingPhases.pricingPhaseList.map { phase ->
                    JSONObject()
                        .put("formattedPrice", phase.formattedPrice)
                        .put("priceAmountMicros", phase.priceAmountMicros)
                        .put("priceCurrencyCode", phase.priceCurrencyCode)
                        .put("billingPeriod", phase.billingPeriod)
                        .put("billingCycleCount", phase.billingCycleCount)
                        .put("recurrenceMode", phase.recurrenceMode)
                },
            ),
        )

    /**
     * A purchase, including what a server needs to verify it with the Google Play Developer
     * API: `purchaseToken`, and `originalJson` with `signature`.
     */
    fun purchase(purchase: Purchase): JSONObject = JSONObject()
        .put("products", JSONArray(purchase.products))
        .put("purchaseToken", purchase.purchaseToken)
        .put("orderId", purchase.orderId ?: JSONObject.NULL)
        .put("purchaseState", purchaseState(purchase.purchaseState))
        .put("purchaseTime", purchase.purchaseTime)
        .put("quantity", purchase.quantity)
        .put("isAcknowledged", purchase.isAcknowledged)
        .put("isAutoRenewing", purchase.isAutoRenewing)
        .put("packageName", purchase.packageName)
        .put("obfuscatedAccountId", purchase.accountIdentifiers?.obfuscatedAccountId ?: JSONObject.NULL)
        .put("obfuscatedProfileId", purchase.accountIdentifiers?.obfuscatedProfileId ?: JSONObject.NULL)
        .put("originalJson", purchase.originalJson)
        .put("signature", purchase.signature)

    fun purchases(list: List<Purchase>): JSONArray = JSONArray(list.map(::purchase))

    fun purchaseState(state: Int): String = when (state) {
        Purchase.PurchaseState.PURCHASED -> "purchased"
        Purchase.PurchaseState.PENDING -> "pending"
        else -> "unspecified"
    }

    /** Play's response code as the `error.code` string the page sees. */
    fun responseCode(code: Int): String = when (code) {
        BillingResponseCode.OK -> "ok"
        BillingResponseCode.USER_CANCELED -> "user_canceled"
        BillingResponseCode.SERVICE_UNAVAILABLE -> "service_unavailable"
        BillingResponseCode.BILLING_UNAVAILABLE -> "billing_unavailable"
        BillingResponseCode.ITEM_UNAVAILABLE -> "item_unavailable"
        BillingResponseCode.DEVELOPER_ERROR -> "developer_error"
        BillingResponseCode.ERROR -> "error"
        BillingResponseCode.ITEM_ALREADY_OWNED -> "item_already_owned"
        BillingResponseCode.ITEM_NOT_OWNED -> "item_not_owned"
        BillingResponseCode.NETWORK_ERROR -> "network_error"
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> "feature_not_supported"
        BillingResponseCode.SERVICE_DISCONNECTED -> "service_disconnected"
        else -> "error_$code"
    }
}
