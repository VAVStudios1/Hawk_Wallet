package com.example.hawk_wallet.ui.screens.admin

// Input checks shared by every admin pay-in / payout action. UX only: each
// one mirrors a backend rule, and the backend re-validates everything.

/** Mirrors payins.services.REJECTION_REASON_MAX_LENGTH. */
internal const val REJECTION_REASON_MAX_LENGTH = 500

/** Mirrors payouts.services.CANCELLATION_REASON_MAX_LENGTH. */
internal const val CANCELLATION_REASON_MAX_LENGTH = 500

/** Mirrors payouts.services.STRICT_UTR: exactly 12 ASCII digits, nothing else. */
internal const val UTR_LENGTH = 12
private val STRICT_UTR = Regex("[0-9]{$UTR_LENGTH}")

/** Whole rupees, optionally written with zero paise ("150", "150.00"). The backend re-validates. */
private val WHOLE_RUPEES = Regex("[0-9]+(\\.0{1,2})?")

/**
 * Payout errors meaning the payout or order changed under the admin (e.g.
 * another admin already paid or cancelled it): close the dialog, say why
 * and show the current state.
 */
internal val STALE_PAYOUT_CODES = setOf(
    "payout_not_pending",
    "payout_not_cancellable",
    "payout_already_paid",
    "payout_already_cancelled",
    "order_not_in_payout",
    "order_not_payable"
)

/** The pay-in was reviewed by someone else meanwhile. */
internal const val PAYIN_ALREADY_REVIEWED = "payin_already_reviewed"

internal fun rejectionReasonError(reason: String): String? = when {
    reason.isBlank() -> "Enter a reason for the member."
    reason.length > REJECTION_REASON_MAX_LENGTH -> "Keep the reason under $REJECTION_REASON_MAX_LENGTH characters."
    else -> null
}

internal fun cancellationReasonError(reason: String): String? = when {
    reason.isBlank() -> "Enter why this payout is being cancelled."
    reason.length > CANCELLATION_REASON_MAX_LENGTH -> "Keep the reason under $CANCELLATION_REASON_MAX_LENGTH characters."
    else -> null
}

/** Sent exactly as typed: the backend never strips spaces or dashes either. */
internal fun payoutUtrError(utr: String): String? =
    if (STRICT_UTR.matches(utr)) null else "UTR must be exactly $UTR_LENGTH digits, with no spaces."

/** A format check only; whether the amount fits what is left is the backend's decision. */
internal fun payoutAmountError(amount: String): String? = when {
    amount.isBlank() -> "Enter the payout amount."
    !WHOLE_RUPEES.matches(amount) -> "Enter a whole-rupee amount using digits only, e.g. 150."
    amount.toBigDecimal().signum() <= 0 -> "The amount must be greater than zero."
    else -> null
}
