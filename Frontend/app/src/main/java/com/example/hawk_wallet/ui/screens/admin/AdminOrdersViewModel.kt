package com.example.hawk_wallet.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
private val STALE_PAYOUT_CODES = setOf(
    "payout_not_pending",
    "payout_not_cancellable",
    "payout_already_paid",
    "payout_already_cancelled",
    "order_not_in_payout",
    "order_not_payable"
)

/** Every member's orders, from GET /api/admin/orders/. */
class AdminOrdersViewModel(private val repository: AdminOrderRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminOrdersUiState())
    val uiState: StateFlow<AdminOrdersUiState> = _uiState

    /** Called whenever the screen opens and on pull-to-refresh. A failed refresh keeps the last list. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.orders()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, loaded = true, orders = result.data.map { dto -> dto.toUi() })
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loaded = true, error = result.userMessage())
                }
            }
        }
    }
}

/**
 * One order, from GET /api/admin/orders/{id}/, including its latest pay-in,
 * payout method and payouts, plus confirming or rejecting that pay-in.
 *
 * One idempotency key covers every attempt at one review decision, so a
 * retry after a lost response returns the original review instead of a
 * conflict. Opening a dialog or editing the rejection reason starts a new
 * decision (and key); the backend refuses a second review either way.
 *
 * Payouts (create, mark paid, cancel) follow the same rule with their own
 * key: opening a payout dialog or editing its input starts a new attempt.
 * Amounts are never calculated here - the amount is sent as typed, the
 * backend decides whether it fits, and the order is reloaded after every change.
 */
class AdminOrderDetailsViewModel(
    private val repository: AdminOrderRepository,
    private val orderId: Long,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminOrderDetailsUiState())
    val uiState: StateFlow<AdminOrderDetailsUiState> = _uiState

    private var reviewKey: String = newIdempotencyKey()
    private var payoutKey: String = newIdempotencyKey()

    fun openConfirm() = openDialog(PayInReviewDialog.CONFIRM)

    fun openReject() = openDialog(PayInReviewDialog.REJECT)

    private fun openDialog(dialog: PayInReviewDialog) {
        if (!_uiState.value.canReview || _uiState.value.isReviewing) return
        reviewKey = newIdempotencyKey()
        _uiState.update {
            it.copy(reviewDialog = dialog, rejectionReason = "", rejectionReasonError = null, reviewError = null)
        }
    }

    fun dismissDialog() {
        if (_uiState.value.isReviewing) return
        _uiState.update { it.copy(reviewDialog = null, reviewError = null, rejectionReasonError = null) }
    }

    fun onRejectionReasonChange(value: String) {
        if (value == _uiState.value.rejectionReason) return
        // A different reason is a different decision: the old key would conflict.
        reviewKey = newIdempotencyKey()
        _uiState.update { it.copy(rejectionReason = value, rejectionReasonError = null) }
    }

    fun confirm() {
        val payIn = reviewablePayIn(PayInReviewDialog.CONFIRM) ?: return
        review("Pay-in confirmed.") { repository.confirmPayIn(payIn.id, reviewKey) }
    }

    fun reject() {
        val payIn = reviewablePayIn(PayInReviewDialog.REJECT) ?: return
        val reason = _uiState.value.rejectionReason
        // UX only; the backend validates the reason the same way.
        val error = when {
            reason.isBlank() -> "Enter a reason for the member."
            reason.length > REJECTION_REASON_MAX_LENGTH -> "Keep the reason under $REJECTION_REASON_MAX_LENGTH characters."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(rejectionReasonError = error) }
            return
        }
        review("Pay-in rejected.") { repository.rejectPayIn(payIn.id, reason, reviewKey) }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun reviewablePayIn(dialog: PayInReviewDialog) = _uiState.value.let { state ->
        if (state.reviewDialog != dialog || state.isReviewing) null else state.order?.latestPayIn
    }

    private fun review(successMessage: String, call: suspend () -> ApiResult<*>) {
        _uiState.update { it.copy(isReviewing = true, reviewError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    reviewKey = newIdempotencyKey()
                    _uiState.update {
                        it.copy(isReviewing = false, reviewDialog = null, message = successMessage)
                    }
                    refresh()
                }
                is ApiResult.Error -> when (result.code) {
                    // Someone already reviewed it: show the current state instead.
                    "payin_already_reviewed" -> {
                        _uiState.update {
                            it.copy(isReviewing = false, reviewDialog = null, message = result.message)
                        }
                        refresh()
                    }
                    else -> _uiState.update { it.copy(isReviewing = false, reviewError = result.userMessage()) }
                }
            }
        }
    }

    // --- Payouts (SELL: platform -> member) -----------------------------------

    fun openCreatePayout() {
        if (!_uiState.value.canCreatePayout) return
        openPayoutDialog(PayoutDialog.Create)
    }

    fun openMarkPaid(payoutId: Long) {
        if (_uiState.value.payout(payoutId)?.status != PayoutStatus.PENDING) return
        openPayoutDialog(PayoutDialog.MarkPaid(payoutId))
    }

    fun openCancelPayout(payoutId: Long) {
        if (_uiState.value.payout(payoutId)?.status != PayoutStatus.PENDING) return
        openPayoutDialog(PayoutDialog.Cancel(payoutId))
    }

    private fun openPayoutDialog(dialog: PayoutDialog) {
        if (_uiState.value.isPayoutSubmitting) return
        payoutKey = newIdempotencyKey()
        _uiState.update {
            it.copy(
                payoutDialog = dialog,
                payoutAmount = "", payoutAmountError = null,
                payoutUtr = "", payoutUtrError = null,
                cancellationReason = "", cancellationReasonError = null,
                payoutError = null,
                qrImage = null, isQrLoading = false, qrError = null
            )
        }
    }

    fun dismissPayoutDialog() {
        if (_uiState.value.isPayoutSubmitting) return
        _uiState.update {
            it.copy(payoutDialog = null, payoutError = null, qrImage = null, isQrLoading = false, qrError = null)
        }
    }

    fun onPayoutAmountChange(value: String) {
        if (value == _uiState.value.payoutAmount) return
        // A different amount is a different payout: the old key would conflict.
        payoutKey = newIdempotencyKey()
        _uiState.update { it.copy(payoutAmount = value, payoutAmountError = null) }
    }

    fun onPayoutUtrChange(value: String) {
        if (value == _uiState.value.payoutUtr) return
        payoutKey = newIdempotencyKey()
        _uiState.update { it.copy(payoutUtr = value, payoutUtrError = null) }
    }

    fun onCancellationReasonChange(value: String) {
        if (value == _uiState.value.cancellationReason) return
        payoutKey = newIdempotencyKey()
        _uiState.update { it.copy(cancellationReason = value, cancellationReasonError = null) }
    }

    fun createPayout() {
        val state = _uiState.value
        if (state.payoutDialog != PayoutDialog.Create || state.isPayoutSubmitting) return
        val amount = state.payoutAmount
        // UX only; the backend validates the amount and what is left to commit.
        val error = when {
            amount.isBlank() -> "Enter the payout amount."
            !WHOLE_RUPEES.matches(amount) -> "Enter a whole-rupee amount using digits only, e.g. 150."
            amount.toBigDecimal().signum() <= 0 -> "The amount must be greater than zero."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(payoutAmountError = error) }
            return
        }
        submitPayout("Payout created.") { repository.createPayout(orderId, amount, payoutKey) }
    }

    fun markPayoutPaid() {
        val state = _uiState.value
        val dialog = state.payoutDialog as? PayoutDialog.MarkPaid ?: return
        if (state.isPayoutSubmitting) return
        val utr = state.payoutUtr
        // Sent exactly as typed: the backend never strips spaces or dashes either.
        if (!STRICT_UTR.matches(utr)) {
            _uiState.update { it.copy(payoutUtrError = "UTR must be exactly $UTR_LENGTH digits, with no spaces.") }
            return
        }
        submitPayout("Payout marked paid.") { repository.markPayoutPaid(dialog.payoutId, utr, payoutKey) }
    }

    fun cancelPayout() {
        val state = _uiState.value
        val dialog = state.payoutDialog as? PayoutDialog.Cancel ?: return
        if (state.isPayoutSubmitting) return
        val reason = state.cancellationReason
        val error = when {
            reason.isBlank() -> "Enter why this payout is being cancelled."
            reason.length > CANCELLATION_REASON_MAX_LENGTH ->
                "Keep the reason under $CANCELLATION_REASON_MAX_LENGTH characters."
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(cancellationReasonError = error) }
            return
        }
        submitPayout("Payout cancelled.") { repository.cancelPayout(dialog.payoutId, reason, payoutKey) }
    }

    /** Opens the QR of this payout's SELL destination through the authorized, audited endpoint. */
    fun openPayoutQr(payoutId: Long) {
        if (_uiState.value.payout(payoutId) == null || _uiState.value.isPayoutSubmitting) return
        openPayoutDialog(PayoutDialog.Qr(payoutId))
        _uiState.update { it.copy(isQrLoading = true) }
        viewModelScope.launch {
            val result = repository.payoutQr(payoutId)
            // Ignore a late reply if the dialog was closed or another QR opened meanwhile.
            if (_uiState.value.payoutDialog != PayoutDialog.Qr(payoutId)) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update { it.copy(isQrLoading = false, qrImage = result.data) }
                is ApiResult.Error -> _uiState.update { it.copy(isQrLoading = false, qrError = result.userMessage()) }
            }
        }
    }

    private fun submitPayout(successMessage: String, call: suspend () -> ApiResult<*>) {
        _uiState.update { it.copy(isPayoutSubmitting = true, payoutError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    payoutKey = newIdempotencyKey()
                    _uiState.update {
                        it.copy(isPayoutSubmitting = false, payoutDialog = null, message = successMessage)
                    }
                    refresh()
                }
                is ApiResult.Error -> when (result.code) {
                    in STALE_PAYOUT_CODES -> {
                        _uiState.update {
                            it.copy(isPayoutSubmitting = false, payoutDialog = null, message = result.message)
                        }
                        refresh()
                    }
                    else -> {
                        _uiState.update { it.copy(isPayoutSubmitting = false, payoutError = result.userMessage()) }
                        // The server's remaining amount may have moved; show the current one.
                        if (result.code == "payout_exceeds_remaining") refresh()
                    }
                }
            }
        }
    }

    /** Called when the screen opens and on pull-to-refresh. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.order(orderId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, order = result.data.toUi())
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.userMessage())
                }
            }
        }
    }
}
