package com.example.hawk_wallet.ui.screens.admin

import com.example.hawk_wallet.data.remote.model.AdminOrderDto
import com.example.hawk_wallet.data.remote.model.AdminPayInDto
import com.example.hawk_wallet.data.remote.model.AdminPayoutDto
import com.example.hawk_wallet.ui.model.AdminOrderUiModel
import com.example.hawk_wallet.ui.model.AdminPayInUiModel
import com.example.hawk_wallet.ui.model.AdminPayoutUiModel
import com.example.hawk_wallet.ui.model.BuyMethodSummaryUiModel
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayoutStatus

/** Admin Orders: every member's orders, as the backend lists them. */
data class AdminOrdersUiState(
    val orders: List<AdminOrderUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Set once the list has been requested at least once. */
    val loaded: Boolean = false
)

/** The pay-in review dialog currently open on Admin Order Details. */
enum class PayInReviewDialog { CONFIRM, REJECT }

/** The payout dialog currently open on Admin Order Details. */
sealed interface PayoutDialog {
    data object Create : PayoutDialog
    data class MarkPaid(val payoutId: Long) : PayoutDialog
    data class Cancel(val payoutId: Long) : PayoutDialog
    data class Qr(val payoutId: Long) : PayoutDialog
}

/** Admin Order Details: one order, its latest pay-in and payout method, pay-in review and payouts. */
data class AdminOrderDetailsUiState(
    val order: AdminOrderUiModel? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val reviewDialog: PayInReviewDialog? = null,
    val rejectionReason: String = "",
    val rejectionReasonError: String? = null,
    val isReviewing: Boolean = false,
    /** Shown inside the open dialog. */
    val reviewError: String? = null,
    /** One-shot feedback (e.g. pay-in confirmed). */
    val message: String? = null,
    val payoutDialog: PayoutDialog? = null,
    /** Create Payout input, sent exactly as typed. */
    val payoutAmount: String = "",
    val payoutAmountError: String? = null,
    /** Mark Paid input. */
    val payoutUtr: String = "",
    val payoutUtrError: String? = null,
    /** Cancel input. */
    val cancellationReason: String = "",
    val cancellationReasonError: String? = null,
    val isPayoutSubmitting: Boolean = false,
    /** Shown inside the open payout dialog. */
    val payoutError: String? = null,
    /** QR dialog: the image bytes exactly as the authorized endpoint returned them. */
    val qrImage: ByteArray? = null,
    val isQrLoading: Boolean = false,
    val qrError: String? = null
) {
    /** Only a pay-in still awaiting review can be confirmed or rejected; the backend enforces this too. */
    val canReview: Boolean
        get() = order?.latestPayIn?.status == PayInStatus.UNDER_REVIEW

    /**
     * Offered only while the server says something is left to commit and the
     * order is in a payable state. A comparison of the server's value, not a
     * calculation; the backend enforces both rules itself.
     */
    val canCreatePayout: Boolean
        get() = order != null &&
            order.status in PAYABLE_ORDER_STATUSES &&
            (order.remainingToCommit?.trim()?.toBigDecimalOrNull()?.signum() ?: 0) > 0

    fun payout(id: Long): AdminPayoutUiModel? = order?.payouts?.firstOrNull { it.id == id }
}

/** Mirrors payouts.services.PAYABLE_STATUSES. */
private val PAYABLE_ORDER_STATUSES = setOf(OrderStatus.ACTIVE, OrderStatus.PAYOUT_IN_PROGRESS)

internal fun AdminOrderDto.toUi() = AdminOrderUiModel(
    id = id,
    reference = reference,
    status = OrderStatus.fromApi(status),
    memberId = member.id,
    memberName = member.name,
    orderAmount = order_amount,
    incomeRate = income_rate,
    incomeAmount = income_amount,
    payoutTarget = payout_target,
    payoutCommittedTotal = payout_committed_total,
    payoutPaidTotal = payout_paid_total,
    claimedAt = claimed_at,
    expiresAt = expires_at,
    activatedAt = activated_at,
    completedAt = completed_at,
    payInUpiId = pay_in_upi_id,
    payoutMethod = payout_method?.let {
        OrderPayoutMethodUiModel(providerCode = it.provider_code, providerName = it.provider_name, upiId = it.upi_id)
    },
    payoutMethodLabel = payout_method?.label.orEmpty(),
    latestPayIn = latest_pay_in?.toUi(),
    remainingToCommit = remaining_to_commit,
    payouts = payouts.orEmpty().map { it.toUi() }
)

internal fun AdminPayoutDto.toUi() = AdminPayoutUiModel(
    id = id,
    amount = amount,
    status = PayoutStatus.fromApi(status),
    utr = utr,
    createdBy = created_by,
    paidBy = paid_by,
    createdAt = created_at,
    paidAt = paid_at,
    destination = OrderPayoutMethodUiModel(providerCode = provider_code, providerName = provider_name, upiId = upi_id),
    cancellationReason = cancellation_reason
)

internal fun AdminPayInDto.toUi() = AdminPayInUiModel(
    id = id,
    status = PayInStatus.fromApi(status),
    amount = amount,
    utr = utr,
    rejectionReason = rejection_reason,
    submittedAt = submitted_at,
    reviewedAt = reviewed_at,
    paidFrom = buy_method?.let {
        BuyMethodSummaryUiModel(
            providerCode = it.provider_code,
            providerName = it.provider_name,
            label = it.label,
            upiId = it.upi_id
        )
    }
)
