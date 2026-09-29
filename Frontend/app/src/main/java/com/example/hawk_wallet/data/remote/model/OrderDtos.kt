package com.example.hawk_wallet.data.remote.model

// Every amount is the backend's decimal string (e.g. "255.00"), kept as-is:
// the server computes income, payout target and pay-in amount.

/** GET /api/orders/offers/ -> data. */
data class OfferListData(
    val offers: List<OfferDto>
)

/** A claimable offer; income_amount and payout_target are server-computed previews. */
data class OfferDto(
    val id: Long,
    val currency: String,
    val order_amount: String,
    val income_rate: String,
    val income_amount: String,
    val payout_target: String,
    val valid_to: String?
)

/** GET /api/orders/ -> data. */
data class OrderListData(
    val orders: List<OrderDto>
)

/**
 * One of the member's orders. Returned by the list, the detail and the
 * claim endpoints; only the detail carries [latest_pay_in].
 */
data class OrderDto(
    val id: Long,
    val reference: String,
    val status: String,
    val currency: String,
    /** What the member pays the platform. */
    val order_amount: String,
    val income_rate: String,
    val income_amount: String,
    /** What the platform eventually pays the member (order_amount + income_amount). */
    val payout_target: String,
    /** Where the platform pays the member; fixed at claim time. */
    val payout_method: OrderPayoutMethodDto,
    val claimed_at: String,
    val expires_at: String?,
    val activated_at: String?,
    val completed_at: String?,
    val created_at: String,
    val updated_at: String,
    val latest_pay_in: MemberPayInDto? = null
)

data class OrderPayoutMethodDto(
    val id: Long,
    val provider_code: String,
    val provider_name: String,
    val label: String,
    val upi_id: String
)

/** The member's latest pay-in on an order (GET /api/orders/{id}/ only). */
data class MemberPayInDto(
    val id: Long,
    val status: String,
    val amount: String,
    val utr: String,
    val rejection_reason: String,
    /** PERSONAL / BUY account the member paid from; null on older pay-ins. */
    val buy_method: BuyMethodSummaryDto? = null,
    val submitted_at: String,
    val updated_at: String
)

/** POST /api/orders/claim/ body. */
data class ClaimOrderRequest(
    val offer_id: Long,
    val idempotency_key: String,
    val payout_method_id: Long
)

/**
 * GET /api/orders/{id}/pay-in-intent/ -> data. The PLATFORM's receiving
 * details: where the member pays, never the member's own payout method.
 */
data class PayInIntentDto(
    val order_reference: String,
    val amount: String,
    val currency: String,
    val payee_vpa: String,
    val payee_name: String,
    val upi_uri: String,
    val intent_token: String
)

/**
 * POST /api/orders/{id}/pay-in/ body. [buy_method_id] is one of the member's
 * own PERSONAL / BUY methods (never a payout method). payer_app is
 * intentionally not sent.
 */
data class SubmitPayInRequest(
    val utr: String,
    val idempotency_key: String,
    val intent_token: String,
    val buy_method_id: Long
)

/** POST /api/orders/{id}/pay-in/ -> data. */
data class PayInDto(
    val id: Long,
    val order: Long,
    val order_reference: String,
    val amount: String,
    val status: String,
    val utr: String,
    val payee_vpa: String,
    val rejection_reason: String,
    val buy_method: BuyMethodSummaryDto? = null,
    val submitted_at: String,
    val updated_at: String
)
