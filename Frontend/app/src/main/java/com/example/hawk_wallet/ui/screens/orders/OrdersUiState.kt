package com.example.hawk_wallet.ui.screens.orders

import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.model.OrderUiModel
import com.example.hawk_wallet.ui.model.PayInIntentUiModel
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel

/**
 * Orders tab: offers the member can claim and the member's own orders. The two
 * lists load independently; [amountFilter] applies to [offers] only.
 */
data class OrdersUiState(
    val offers: List<OfferUiModel> = emptyList(),
    val orders: List<OrderUiModel> = emptyList(),
    val offersLoading: Boolean = false,
    val ordersLoading: Boolean = false,
    val offersError: String? = null,
    val ordersError: String? = null,
    /** Set once offers for the current [amountFilter] have been requested; cleared when the filter changes. */
    val offersLoaded: Boolean = false,
    /** Set once the member's orders have been requested at least once. */
    val ordersLoaded: Boolean = false,
    /** The Available filter on "You pay"; the backend applies it. */
    val amountFilter: OfferAmountFilter = OfferAmountFilter.ALL,
    /** Non-null while the Custom amount dialog is open. */
    val customAmount: CustomAmountInput? = null
) {
    /** True while either list is loading. */
    val isLoading: Boolean
        get() = offersLoading || ordersLoading

    /** Set once both lists have been requested at least once. */
    val loaded: Boolean
        get() = offersLoaded && ordersLoaded
}

/** Offer Details -> Choose Payment Method -> Claim, for one offer. */
data class ClaimUiState(
    val offer: OfferUiModel? = null,
    /** The member's ACTIVE payout methods only. */
    val methods: List<PaymentMethodUiModel> = emptyList(),
    val methodsLoading: Boolean = false,
    val methodsLoaded: Boolean = false,
    val methodsError: String? = null,
    val selectedMethodId: Long? = null,
    val isClaiming: Boolean = false,
    val claimError: String? = null,
    /** The backend says the offer can no longer be claimed (gone, unavailable or sold out). */
    val offerUnavailable: Boolean = false,
    /** Set once the backend created the order; the screen then opens its Pay-In. */
    val claimedOrderId: Long? = null
)

/** Pay-In for one order: MEMBER -> PLATFORM, then the UTR and the BUY account paid from. */
data class PayInUiState(
    val order: OrderUiModel? = null,
    val intent: PayInIntentUiModel? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    /** The member's ACTIVE PERSONAL / BUY accounts, backend order (default first). */
    val buyMethods: List<PaymentMethodUiModel> = emptyList(),
    /** False until the BUY accounts have loaded at least once. */
    val buyMethodsLoaded: Boolean = false,
    val buyMethodsError: String? = null,
    /** The BUY account the member says they paid from; sent with the UTR. */
    val selectedBuyMethodId: Long? = null,
    /** Submit error that belongs under the BUY account chooser. */
    val buyMethodError: String? = null,
    /** The backend says the order is not awaiting payment; the member should check its status. */
    val notPayableMessage: String? = null,
    val utr: String = "",
    val utrError: String? = null,
    val submitError: String? = null,
    val isSubmitting: Boolean = false,
    /** Set once the backend accepted the UTR; the screen then opens the order status. */
    val submitted: Boolean = false,
    /** One-shot feedback (e.g. no UPI app installed, UPI ID copied). */
    val message: String? = null
)

data class OrderStatusUiState(
    val order: OrderUiModel? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)
