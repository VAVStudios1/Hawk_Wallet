package com.example.hawk_wallet.data.remote.model

// Admin pay-in, payout and user lists/details. Every amount is the backend's
// decimal string, kept as-is; every count is computed by the server. Mobile
// numbers only ever arrive masked (******3210).

/** The member a record belongs to. */
data class AdminMemberSummaryDto(
    val id: Long,
    val name: String,
    val mobile_number_masked: String
)

// --- Pay-ins: GET /api/admin/pay-ins/ and /api/admin/pay-ins/{id}/ -----------

data class AdminPayInListData(
    val pay_ins: List<AdminPayInItemDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** The order a pay-in is for, with its stored amounts. */
data class AdminPayInOrderDto(
    val id: Long,
    val reference: String,
    val status: String,
    val order_amount: String,
    val payout_target: String
)

/** A pay-in as listed; the detail-only fields are null in the list. */
data class AdminPayInItemDto(
    val id: Long,
    val status: String,
    val amount: String,
    val utr: String,
    val submitted_at: String,
    val reviewed_at: String?,
    val order: AdminPayInOrderDto,
    val member: AdminMemberSummaryDto,
    /** Detail only: the receiving account the member says they paid. */
    val payee_vpa: String? = null,
    /** Detail only (audited): the member's own account the money came from; null on older pay-ins. */
    val buy_method: BuyMethodSummaryDto? = null,
    val rejection_reason: String? = null,
    val reviewed_by_name: String? = null
)

// --- Payouts: GET /api/admin/payouts/ and /api/admin/payouts/{id}/ ----------

data class AdminPayoutListData(
    val payouts: List<AdminPayoutItemDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

/** The order a payout settles, with its stored totals and the server's remaining_to_commit. */
data class AdminPayoutOrderDto(
    val id: Long,
    val reference: String,
    val status: String,
    val payout_target: String,
    val payout_committed_total: String,
    val payout_paid_total: String,
    val remaining_to_commit: String
)

/** A payout as listed (provider only); the detail adds the UPI ID and audit-relevant fields. */
data class AdminPayoutItemDto(
    val id: Long,
    val status: String,
    val amount: String,
    val provider_code: String,
    val provider_name: String,
    val created_at: String,
    val paid_at: String?,
    val order: AdminPayoutOrderDto,
    val member: AdminMemberSummaryDto,
    /** Detail only (audited). */
    val upi_id: String? = null,
    /** Detail only; blank until PAID. */
    val utr: String? = null,
    /** Detail only; set on CANCELLED payouts. */
    val cancellation_reason: String? = null,
    val created_by_name: String? = null,
    val paid_by_name: String? = null
)

// --- Users: GET /api/admin/users/ and /api/admin/users/{id}/ ----------------

data class AdminUserListData(
    val users: List<AdminUserDto>,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)

data class AdminUserDto(
    val id: Long,
    val name: String,
    val mobile_number_masked: String,
    val role: String,
    val is_verified: Boolean,
    val is_active: Boolean,
    val date_joined: String
)

data class AdminUserOrderDto(
    val id: Long,
    val reference: String,
    val status: String,
    val order_amount: String,
    val income_amount: String,
    val payout_target: String,
    val claimed_at: String
)

/** One member with server-counted activity: counts keyed by the backend's status values. */
data class AdminUserDetailDto(
    val user: AdminUserDto,
    val orders_total: Int,
    val order_counts: Map<String, Int>,
    val pay_in_counts: Map<String, Int>,
    val recent_orders: List<AdminUserOrderDto>
)
