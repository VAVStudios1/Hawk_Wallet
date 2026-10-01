package com.example.hawk_wallet.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.model.AdminOrderUiModel
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Admin Orders filters. [apiStatus] is the backend's exact Order.Status value
 * (null = any); AWAITING_ACTION asks the backend for the orders an admin has a
 * pending step on (its awaiting_action filter) - the app never decides which.
 */
enum class AdminOrderFilter(val apiStatus: String?, val label: String, val awaitingAction: Boolean = false) {
    ALL(null, "All"),
    AWAITING_ACTION(null, "Awaiting Action", awaitingAction = true),
    PAYMENT_REQUIRED("PAYMENT_REQUIRED", "Payment Required"),
    PAYMENT_UNDER_REVIEW("PAYMENT_UNDER_REVIEW", "Pay-In Under Review"),
    ACTIVE("ACTIVE", "Active"),
    PAYOUT_IN_PROGRESS("PAYOUT_IN_PROGRESS", "Payout in Progress"),
    COMPLETED("COMPLETED", "Completed"),
    EXPIRED("EXPIRED", "Expired"),
    CANCELLED("CANCELLED", "Cancelled");

    companion object {
        /** A route argument (enum name) to its filter; unknown or absent -> All. */
        fun fromRoute(value: String?): AdminOrderFilter = entries.firstOrNull { it.name == value } ?: ALL
    }
}

/** Every member's orders, one server page at a time, from GET /api/admin/orders/. */
class AdminOrdersViewModel(
    private val repository: AdminOrderRepository,
    initialFilter: AdminOrderFilter = AdminOrderFilter.ALL
) : AdminListViewModel<AdminOrderUiModel, AdminOrderFilter>(initialFilter) {

    override suspend fun fetch(filter: AdminOrderFilter, search: String, page: Int): ApiResult<AdminPage<AdminOrderUiModel>> =
        when (val result = repository.orders(filter.apiStatus, filter.awaitingAction, search, page)) {
            is ApiResult.Success -> ApiResult.Success(
                AdminPage(result.data.orders.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                result.message
            )
            is ApiResult.Error -> result
        }

    override fun idOf(item: AdminOrderUiModel): Long = item.id
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
        val error = rejectionReasonError(reason)
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
                    PAYIN_ALREADY_REVIEWED -> {
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
        val error = payoutAmountError(amount)
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
        val error = payoutUtrError(utr)
        if (error != null) {
            _uiState.update { it.copy(payoutUtrError = error) }
            return
        }
        submitPayout("Payout marked paid.") { repository.markPayoutPaid(dialog.payoutId, utr, payoutKey) }
    }

    fun cancelPayout() {
        val state = _uiState.value
        val dialog = state.payoutDialog as? PayoutDialog.Cancel ?: return
        if (state.isPayoutSubmitting) return
        val reason = state.cancellationReason
        val error = cancellationReasonError(reason)
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
