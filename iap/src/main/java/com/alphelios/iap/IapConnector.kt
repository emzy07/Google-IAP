package com.alphelios.iap

import android.app.Activity
import android.content.Context
import android.util.Log
import com.alphelios.iap.DataWrappers.*
import com.alphelios.iap.DataWrappers.SkuProductType.*
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode.*
import com.android.billingclient.api.BillingClient.FeatureType.SUBSCRIPTIONS
import com.android.billingclient.api.BillingClient.SkuType.INAPP
import com.android.billingclient.api.BillingClient.SkuType.SUBS
import java.lang.IllegalArgumentException

/**
 * Wrapper class for Google In-App purchases.
 * Handles vital processes while dealing with IAP.
 * Works around a listener [InAppEventsListener] for delivering events back to the caller.
 */
class IapConnector(context: Context, private val base64Key: String) {
    private var shouldAutoAcknowledge: Boolean = false
    private var fetchedSkuInfosList = mutableListOf<SkuInfo>()
    private val tag = "InAppLog"
    private var inAppEventsListener: InAppEventsListener? = null

    private var inAppIds: List<String>? = null
    private var subIds: List<String>? = null
    private var consumableIds: List<String>? = null

    private lateinit var iapClient: BillingClient

    private var connected = false
    private var checkedForPurchasesAtStart = false

    init {
        init(context)
    }


    fun isReady(): Boolean {
        if (!connected) {
            Log.d(tag, "Billing client : is not ready because no connection is established yet")
        }

        if (!iapClient.isReady) {
            Log.d(tag, "Billing client : is not ready because iapClient is not ready yet")
        }

        if (fetchedSkuInfosList.isEmpty()) {
            Log.d(
                tag,
                "Billing client : is not ready because fetchedSkuDetailsList is empty or not fetched yet"
            )
        }

        if (!connected) {
            Log.d(tag, "Billing client : is not ready because no connection is established yet")
        }

        return connected && iapClient.isReady && fetchedSkuInfosList.isNotEmpty()
    }

    /**
     * To set INAPP product IDs.
     */
    fun setInAppProductIds(inAppIds: List<String>): IapConnector {
        this.inAppIds = inAppIds
        return this
    }

    /**
     * To set consumable product IDs.
     * Rest of the IDs will be considered non-consumable.
     */
    fun setConsumableProductIds(consumableIds: List<String>): IapConnector {
        this.consumableIds = consumableIds
        return this
    }

    /**
     * Iap will auto acknowledge the purchase
     */
    fun autoAcknowledge(): IapConnector {
        shouldAutoAcknowledge = true
        return this
    }

    /**
     * To set SUBS product IDs.
     */
    fun setSubscriptionIds(subIds: List<String>): IapConnector {
        this.subIds = subIds
        return this
    }

    /**
     * Called to purchase an item.
     * Its result is received in [PurchasesUpdatedListener] which further is handled
     * by [handleConsumableProducts] / [handleNonConsumableProducts].
     */
    fun makePurchase(activity: Activity, skuId: String) {
        if (fetchedSkuInfosList.isEmpty())
            inAppEventsListener?.onError(this, BillingResponse("Products not fetched"))
        else {
            val skuDetails = fetchedSkuInfosList.find { it.skuId == skuId }!!.skuDetails
            iapClient.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                    .setSkuDetails(skuDetails)
                    .build()
            )
        }
    }

    /**
     * To attach an event listener to establish a bridge with the caller.
     */
    fun setOnInAppEventsListener(inAppEventsListener: InAppEventsListener) {
        this.inAppEventsListener = inAppEventsListener
    }

    /**
     * To initialise IapConnector.
     */
    private fun init(context: Context) {
        iapClient = BillingClient.newBuilder(context)
            .enablePendingPurchases()
            .setListener { billingResult, purchases ->
                /**
                 * Only recent purchases are received here
                 */

                when (billingResult.responseCode) {
                    OK -> purchases?.let { processPurchases(purchases) }
                    ITEM_ALREADY_OWNED -> inAppEventsListener?.onError(
                        this,
                        billingResult.run {
                            BillingResponse(
                                debugMessage,
                                responseCode
                            )
                        }
                    )
                    SERVICE_DISCONNECTED -> connect()
                    else -> Log.i(tag, "Purchase update : ${billingResult.debugMessage}")
                }
            }.build()
    }

    /**
     * Connects billing client with Play console to start working with IAP.
     */
    fun connect(): IapConnector {

        // Before we start, check input params we set empty list to null so we only have to deal with lists who are null (not provided) or not empty.
        if (inAppIds.isNullOrEmpty())
            inAppIds = null
        if (subIds.isNullOrEmpty())
            subIds = null
        if (consumableIds.isNullOrEmpty())
            consumableIds = null

        if (inAppIds == null && subIds == null) {
            if (consumableIds != null) {
                // Only Consumables are provided here, so lets adjust the inAppIds list here
                inAppIds = consumableIds!!.toList()
            } else
                throw IllegalArgumentException("At least one list of Subscriptions or InAppIds is needed")
        }


        Log.d(tag, "Billing service : Connecting...")
        if (!iapClient.isReady) {
            iapClient.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    connected = false
                    inAppEventsListener?.onError(
                        this@IapConnector,
                        BillingResponse("Billing service : Disconnected")
                    )
                    Log.d(tag, "Billing service : Trying to establish to reconnect...")
                    iapClient.startConnection(this)
                }

                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    connected = false
                    when (billingResult.responseCode) {
                        OK -> {
                            connected = true
                            Log.d(tag, "Billing service : Connected")
                            inAppIds?.let {
                                querySku(INAPP, it)
                            }
                            subIds?.let {
                                querySku(SUBS, it)
                            }
                            if (!checkedForPurchasesAtStart) {
                                getAllPurchases()
                                checkedForPurchasesAtStart = true
                            }
                        }
                        BILLING_UNAVAILABLE -> Log.d(tag, "Billing service : Unavailable")
                        else -> Log.d(tag, "Billing service : Setup error")
                    }
                }
            })
        }
        return this
    }

    /**
     * Fires a query in Play console to get [SkuDetails] for provided type and IDs.
     */
    private fun querySku(skuType: String, ids: List<String>) {
        iapClient.querySkuDetailsAsync(
            SkuDetailsParams.newBuilder()
                .setSkusList(ids).setType(skuType).build()
        ) { billingResult, skuDetailsList ->
            when (billingResult.responseCode) {
                OK -> {
                    if (skuDetailsList!!.isEmpty()) {
                        Log.d(
                            tag,
                            "Query SKU : Data not found (List empty) seems like no SkuIDs are configured on Google Playstore!"
                        )
                        inAppEventsListener?.onError(
                            this,
                            billingResult.run {
                                BillingResponse(
                                    debugMessage,
                                    responseCode
                                )
                            })
                    } else {
                        Log.d(tag, "Query SKU : Data found")

                        val fetchedSkuInfos = skuDetailsList.map {
                            generateSkuInfo(it)
                        }

                        fetchedSkuInfosList.addAll(fetchedSkuInfos)

                        when (skuType) {
                            SUBS -> {
                                inAppEventsListener?.onSubscriptionsFetched(fetchedSkuInfos)
                            }
                            INAPP -> {
                                inAppEventsListener?.onInAppProductsFetched(fetchedSkuInfos)
                            }
                            else -> {
                                throw IllegalStateException("Not implemented SkuType")
                            }
                        }
                    }
                }
                else -> {
                    Log.d(tag, "Query SKU : Failed")
                    inAppEventsListener?.onError(
                        this,
                        billingResult.run {
                            BillingResponse(
                                debugMessage,
                                responseCode
                            )
                        }
                    )
                }
            }
        }
    }

    private fun generateSkuInfo(skuDetails: SkuDetails): SkuInfo {

        fun isSkuIdConsumable(skuId: String): Boolean {
            if (consumableIds.isNullOrEmpty()) return false
            return consumableIds!!.contains(skuId)
        }

        val skuProductType: SkuProductType = when (skuDetails.type) {
            SUBS -> SUBSCRIPTION
            INAPP -> {
                val consumable = isSkuIdConsumable(skuDetails.sku)
                if (consumable)
                    CONSUMABLE
                else
                    NON_CONSUMABLE
            }
            else -> throw IllegalStateException("Not implemented SkuType")
        }

        return SkuInfo(skuProductType, skuDetails)
    }

    /**
     * Returns all the **non-consumable** purchases of the user and trigger the listener.
     */
    private fun getAllPurchases() {
        if (iapClient.isReady) {
            val allPurchases = mutableListOf<Purchase>()
            allPurchases.addAll(iapClient.queryPurchases(INAPP).purchasesList!!)
            if (isSubSupportedOnDevice())
                allPurchases.addAll(iapClient.queryPurchases(SUBS).purchasesList!!)
            processPurchases(allPurchases)
        } else {
            inAppEventsListener?.onError(
                this,
                BillingResponse("Client not initialized yet.")
            )
        }
    }

    /**
     * Checks purchase signature for more security.
     */
    private fun processPurchases(allPurchases: List<Purchase>) {
        if (allPurchases.isNotEmpty()) {
            val validPurchases = allPurchases.filter {
                isPurchaseSignatureValid(it)
            }.map { purchase ->
                val skuDetails = fetchedSkuInfosList.find { it.skuId == purchase.sku }!!.skuDetails
                PurchaseInfo(
                    generateSkuInfo(skuDetails),
                    purchase
                )
            }

            inAppEventsListener?.onProductsPurchased(validPurchases)

            if (shouldAutoAcknowledge)
                validPurchases.forEach {
                    acknowledgePurchase(it)
                }
        }
    }

    /**
     * Purchase of non-consumable products must be acknowledged to Play console.
     * This will avoid refunding for these products to users by Google.
     *
     * Consumable products might be brought/consumed by users multiple times (for eg. diamonds, coins).
     * Hence, it is necessary to notify Play console about such products.
     */
    private fun acknowledgePurchase(purchaseInfo: PurchaseInfo) {
        purchaseInfo.run {

            when (skuInfo.skuProductType) {
                CONSUMABLE -> {
                    iapClient.consumeAsync(
                        ConsumeParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken).build()
                    ) { billingResult, purchaseToken ->
                        when (billingResult.responseCode) {
                            OK -> inAppEventsListener?.onPurchaseAcknowledged(this)
                            else -> {
                                Log.d(
                                    tag,
                                    "Handling consumables : Error -> ${billingResult.debugMessage}"
                                )
                                inAppEventsListener?.onError(
                                    this@IapConnector,
                                    billingResult.run {
                                        BillingResponse(
                                            debugMessage,
                                            responseCode
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                NON_CONSUMABLE, SUBSCRIPTION -> {
                    iapClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(
                            purchase.purchaseToken
                        ).build()
                    ) { billingResult ->
                        when (billingResult.responseCode) {
                            OK -> inAppEventsListener?.onPurchaseAcknowledged(this)
                            else -> {
                                Log.d(
                                    tag,
                                    "Handling non consumables : Error -> ${billingResult.debugMessage}"
                                )
                                inAppEventsListener?.onError(
                                    this@IapConnector,
                                    billingResult.run {
                                        BillingResponse(
                                            debugMessage,
                                            responseCode
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Before using subscriptions, device-support must be checked.
     */
    private fun isSubSupportedOnDevice(): Boolean {
        var isSupported = false
        when (iapClient.isFeatureSupported(SUBSCRIPTIONS).responseCode) {
            OK -> {
                isSupported = true
                Log.d(tag, "Subs support check : Success")
            }
            SERVICE_DISCONNECTED -> connect()
            else -> Log.d(tag, "Subs support check : Error")
        }
        return isSupported
    }

    /**
     * Checks purchase signature validity
     */
    private fun isPurchaseSignatureValid(purchase: Purchase): Boolean {
        return Security.verifyPurchase(
            base64Key, purchase.originalJson, purchase.signature
        )
    }
}