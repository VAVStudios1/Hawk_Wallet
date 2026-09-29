package com.example.hawk_wallet.ui.model

/**
 * UI-only views of offers, orders and pay-ins. Every amount is the backend's
 * decimal string, shown as-is (formatted for display only) - never
 * recomputed on the device.
 */

/** Mirrors the backend's Order.Status values (orders/models.py). */
enum class OrderStatus(val label: String) {
    PAYMENT_REQUIRED("Payment required"),
    PAYMENT_UNDER_REVIEW("Payment under review"),
    ACTIVE("Active"),
    PAYOUT_IN_PROGRESS("Payout in progress"),
    COMPLETED("Completed"),
    EXPIRED("Expired"),
    CANCELLED("Cancelled"),
    UNKNOWN("Unknown");

    companion object {
        fun fromApi(value: String): OrderStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Mirrors the backend's PayIn.Status values (payins/models.py). */
enum class PayInStatus(val label: String) {
    UNDER_REVIEW("Under review"),
    CONFIRMED("Confirmed"),
    REJECTED("Rejected"),
    UNKNOWN("Unknown");

    companion object {
        fun fromApi(value: String): PayInStatus = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class OfferUiModel(
    val id: Long,
    /** What the member will pay the platform. */
    val orderAmount: String,
    val incomeRate: String,
    val incomeAmount: String,
    /** What the platform will pay the member. */
    val payoutTarget: String,
    val validTo: String?
)

/** The member's payout method fixed on an order: where the PLATFORM pays the MEMBER. */
data class OrderPayoutMethodUiModel(
    val providerCode: String,
    val providerName: String,
    val upiId: String
)

data class PayInSummaryUiModel(
    val status: PayInStatus,
    val amount: String,
    val utr: String,
    val rejectionReason: String,
    val submittedAt: String
)

data class OrderUiModel(
    val id: Long,
    val reference: String,
    val status: OrderStatus,
    val orderAmount: String,
    val incomeRate: String,
    val incomeAmount: String,
    val payoutTarget: String,
    val payoutMethod: OrderPayoutMethodUiModel,
    val claimedAt: String,
    val expiresAt: String?,
    val activatedAt: String?,
    val completedAt: String?,
    /** Only set when loaded from the order detail endpoint. */
    val latestPayIn: PayInSummaryUiModel? = null
)

/** The PLATFORM's receiving details for one order: where the MEMBER pays. */
data class PayInIntentUiModel(
    val orderReference: String,
    val amount: String,
    val payeeVpa: String,
    val payeeName: String,
    val upiUri: String,
    val intentToken: String
)
