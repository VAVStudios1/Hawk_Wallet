package com.example.hawk_wallet.data.remote.model

// Every amount is the backend's decimal string, kept as-is: the server owns
// income, payout target and the payout running totals.

/** GET /api/admin/orders/ -> data. */
data class AdminOrderListData(
    val orders: List<AdminOrderDto>
)

/**
 * Any member's order as an admin sees it. Returned by the list and the
 * detail; only the detail carries [payout_method] and [latest_pay_in].
 */
data class AdminOrderDto(
    val id: Long,
    val reference: String,
    val status: String,
    val currency: String,
    val member: AdminOrderMemberDto,
    val order_amount: String,
    val income_rate: String,
    val income_amount: String,
    val payout_target: String,
    /** Sum of the order's pending and paid payouts. */
    val payout_committed_total: String,
    /** Sum of the order's paid payouts. */
    val payout_paid_total: String,
    val claimed_at: String,
    val expires_at: String?,
    val activated_at: String?,
    val completed_at: String?,
    val created_at: String,
    val updated_at: String,
    /**
     * Pay-In receiving UPI (detail only): where the member pays for this order.
     * Unrelated to [payout_method], where the platform pays the member.
     */
    val pay_in_upi_id: String? = null,
    val payout_method: OrderPayoutMethodDto? = null,
    val latest_pay_in: AdminPayInDto? = null,
    /** Detail only: payout_target - payout_committed_total, computed by the server. */
    val remaining_to_commit: String? = null,
    /** Detail only: the order's payouts (SELL side), oldest first. */
    val payouts: List<AdminPayoutDto>? = null
)

data class AdminOrderMemberDto(
    val id: Long,
    val name: String
)

/** An order's latest pay-in (detail only). */
data class AdminPayInDto(
    val id: Long,
    val status: String,
    val amount: String,
    val utr: String,
    val rejection_reason: String,
    /** BUY side: the member's own account the payment came from; null on older pay-ins. */
    val buy_method: BuyMethodSummaryDto? = null,
    val submitted_at: String,
    val reviewed_at: String?,
    val updated_at: String
)

/**
 * One payout in an order's history: a transfer from the platform to the
 * member's SELL payout method. provider/upi_id are the payout's own
 * snapshot; the QR is only served by the audited QR endpoint.
 */
data class AdminPayoutDto(
    val id: Long,
    val amount: String,
    val status: String,
    /** Blank until the payout is PAID. */
    val utr: String,
    val created_by: Long,
    val paid_by: Long?,
    val created_at: String,
    val paid_at: String?,
    val provider_code: String,
    val provider_name: String,
    val upi_id: String,
    /** Set only on CANCELLED payouts. */
    val cancellation_reason: String
)

/** POST /api/admin/offers/ -> data. income_amount and payout_target are the server's claim preview. */
data class AdminOfferDto(
    val id: Long,
    val currency: String,
    val order_amount: String,
    val income_rate: String,
    val income_amount: String,
    val payout_target: String,
    val quantity_available: Int?,
    val is_active: Boolean,
    /** The UPI ID (pa) the server decoded from the uploaded Pay-In QR. */
    val pay_in_upi_id: String,
    val created_at: String
)

/** POST /api/admin/pay-ins/{id}/confirm/ body. */
data class ConfirmPayInRequest(
    val idempotency_key: String
)

/** POST /api/admin/pay-ins/{id}/reject/ body. */
data class RejectPayInRequest(
    val rejection_reason: String,
    val idempotency_key: String
)

/** Confirm/reject -> data: the reviewed pay-in and the order status the review produced. */
data class ReviewedPayInDto(
    val id: Long,
    val order: Long,
    val status: String,
    val order_status: String,
    val rejection_reason: String,
    val reviewed_at: String?
)

/** POST /api/admin/orders/{id}/payouts/ body. [amount] is sent exactly as the admin typed it. */
data class CreatePayoutRequest(
    val amount: String,
    val idempotency_key: String
)

/** POST /api/admin/payouts/{id}/paid/ body. */
data class MarkPayoutPaidRequest(
    val utr: String,
    val idempotency_key: String
)

/** POST /api/admin/payouts/{id}/cancel/ body. */
data class CancelPayoutRequest(
    val cancellation_reason: String,
    val idempotency_key: String
)

/** Create / mark paid / cancel -> data: the payout and its order's state after the change. */
data class PayoutDto(
    val id: Long,
    val order: Long,
    val order_reference: String,
    val order_status: String,
    val order_payout_committed_total: String,
    val order_payout_paid_total: String,
    val amount: String,
    val status: String,
    val utr: String,
    val created_by: Long,
    val paid_by: Long?,
    val created_at: String,
    val paid_at: String?,
    val updated_at: String
)
