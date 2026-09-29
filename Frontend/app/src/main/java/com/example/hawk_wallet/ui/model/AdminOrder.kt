package com.example.hawk_wallet.ui.model

/**
 * UI-only admin views of any member's order. Every amount is the backend's
 * decimal string, shown as-is (formatted for display only) - never
 * recomputed on the device.
 */
data class AdminOrderUiModel(
    val id: Long,
    val reference: String,
    val status: OrderStatus,
    val memberId: Long,
    val memberName: String,
    val orderAmount: String,
    val incomeRate: String,
    val incomeAmount: String,
    val payoutTarget: String,
    val payoutCommittedTotal: String,
    val payoutPaidTotal: String,
    val claimedAt: String,
    val expiresAt: String?,
    val activatedAt: String?,
    val completedAt: String?,
    /** Detail only: where the MEMBER pays for this order (Pay-In). Null when unknown. */
    val payInUpiId: String? = null,
    /** Detail only: where the PLATFORM pays the MEMBER. */
    val payoutMethod: OrderPayoutMethodUiModel? = null,
    /** Detail only; blank when the member gave the method no label. */
    val payoutMethodLabel: String = "",
    /** Detail only. */
    val latestPayIn: AdminPayInUiModel? = null,
    /** Detail only: what new payouts may still commit, as the server computed it. Null when unknown. */
    val remainingToCommit: String? = null,
    /** Detail only: payouts to the member's SELL method, oldest first. */
    val payouts: List<AdminPayoutUiModel> = emptyList()
)

data class AdminPayInUiModel(
    val id: Long,
    val status: PayInStatus,
    val amount: String,
    val utr: String,
    val rejectionReason: String,
    val submittedAt: String,
    val reviewedAt: String?,
    /** BUY side: the member's PERSONAL account the payment came from. Null on older pay-ins. */
    val paidFrom: BuyMethodSummaryUiModel? = null
)

/**
 * A member's PERSONAL / BUY account as recorded on a pay-in. Never a payout
 * destination - that is the order's payout method (SELL).
 */
data class BuyMethodSummaryUiModel(
    val providerCode: String,
    val providerName: String,
    val label: String,
    val upiId: String
)

/** Mirrors the backend's Payout.Status values (payouts/models.py). */
enum class PayoutStatus(val label: String) {
    PENDING("Pending"),
    PAID("Paid"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    UNKNOWN("Unknown");

    companion object {
        fun fromApi(value: String): PayoutStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** One payout from the platform to the member (SELL side). Amounts are the backend's strings. */
data class AdminPayoutUiModel(
    val id: Long,
    val amount: String,
    val status: PayoutStatus,
    /** Blank until PAID. */
    val utr: String,
    val createdBy: Long,
    val paidBy: Long?,
    val createdAt: String,
    val paidAt: String?,
    /** Where this payout goes: the SELL method fixed on the order, as snapshotted on the payout. */
    val destination: OrderPayoutMethodUiModel,
    /** Set only on CANCELLED payouts. */
    val cancellationReason: String
)
