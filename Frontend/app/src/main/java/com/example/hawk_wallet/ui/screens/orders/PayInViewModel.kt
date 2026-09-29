package com.example.hawk_wallet.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.BuyMethodListItemDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.BuyMethodRepository
import com.example.hawk_wallet.data.repository.OrderRepository
import com.example.hawk_wallet.ui.model.PaymentMethodUiModel
import com.example.hawk_wallet.ui.screens.paymethods.toUi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The backend's UTR length (payins: exactly 12 ASCII digits); input is limited to it. */
private const val UTR_LENGTH = 12

/** Submit errors that belong under the UTR field. */
private val UTR_CODES = setOf("invalid_utr", "utr_already_submitted")

/** Submit errors that belong under the BUY account chooser. */
private val BUY_METHOD_CODES = setOf("buy_method_required", "buy_method_unavailable")

/**
 * Pay-In for one order: the member pays the PLATFORM's receiving account
 * (from GET /api/orders/{id}/pay-in-intent/), then submits the UTR with
 * POST /api/orders/{id}/pay-in/ together with the member's PERSONAL / BUY
 * account they paid from (from /api/buy-methods/). The order's payout method
 * (SELL) is shown only as where the platform will later pay the member; it is
 * never offered as the account paid from.
 *
 * One idempotency key covers every attempt at this submission, so a retry
 * after a lost response returns the original pay-in instead of a second one.
 */
class PayInViewModel(
    private val repository: OrderRepository,
    private val buyMethodRepository: BuyMethodRepository,
    private val orderId: Long,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(PayInUiState())
    val uiState: StateFlow<PayInUiState> = _uiState

    private var idempotencyKey: String = newIdempotencyKey()

    /** Loads the order, the platform's receiving details and the member's BUY accounts. */
    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, loadError = null, notPayableMessage = null) }
        viewModelScope.launch {
            val order = async { repository.order(orderId) }
            val intent = async { repository.payInIntent(orderId) }
            val buyMethods = async { buyMethodRepository.list() }
            val orderResult = order.await()
            val intentResult = intent.await()
            val buyMethodsResult = buyMethods.await()
            _uiState.update { state ->
                var next = state.copy(isLoading = false).withBuyMethods(buyMethodsResult)
                when (orderResult) {
                    is ApiResult.Success -> next = next.copy(order = orderResult.data.toUi())
                    is ApiResult.Error -> next = next.copy(loadError = orderResult.userMessage())
                }
                when (intentResult) {
                    is ApiResult.Success -> next = next.copy(intent = intentResult.data.toUi())
                    is ApiResult.Error -> next = if (intentResult.code == "order_not_payable") {
                        next.copy(intent = null, notPayableMessage = intentResult.message)
                    } else {
                        next.copy(intent = null, loadError = next.loadError ?: intentResult.userMessage())
                    }
                }
                next
            }
        }
    }

    /** Keeps only ASCII digits, up to the backend's UTR length; the backend still validates. */
    fun onUtrChange(value: String) {
        val digits = value.filter { it in '0'..'9' }.take(UTR_LENGTH)
        _uiState.update { it.copy(utr = digits, utrError = null, submitError = null) }
    }

    /** Reloads only the BUY accounts (e.g. after the member added one). */
    fun refreshBuyMethods() {
        viewModelScope.launch {
            val result = buyMethodRepository.list()
            _uiState.update { it.withBuyMethods(result) }
        }
    }

    /** The member picks which of their BUY accounts they paid from. */
    fun selectBuyMethod(id: Long) {
        _uiState.update { state ->
            if (state.buyMethods.none { it.id == id }) state
            else state.copy(selectedBuyMethodId = id, buyMethodError = null, submitError = null)
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting || state.submitted) return
        val intent = state.intent ?: return
        val utrError = if (state.utr.length != UTR_LENGTH) "Enter the 12-digit UTR from your UPI app." else null
        val buyMethodId = state.selectedBuyMethodId
        val buyMethodError = if (buyMethodId == null) "Choose the UPI account you paid from." else null
        if (utrError != null || buyMethodId == null) {
            _uiState.update { it.copy(utrError = utrError, buyMethodError = buyMethodError) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, utrError = null, buyMethodError = null, submitError = null) }
        viewModelScope.launch {
            when (val result = repository.submitPayIn(orderId, state.utr, idempotencyKey, intent.intentToken, buyMethodId)) {
                is ApiResult.Success -> {
                    idempotencyKey = newIdempotencyKey()
                    _uiState.update { it.copy(isSubmitting = false, submitted = true) }
                }
                is ApiResult.Error -> handleSubmitError(result)
            }
        }
    }

    fun onUpiAppMissing() {
        _uiState.update {
            it.copy(message = "No UPI app found. Copy the UPI ID and pay from any UPI app.")
        }
    }

    fun onUpiIdCopied() {
        _uiState.update { it.copy(message = "UPI ID copied") }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun handleSubmitError(error: ApiResult.Error) {
        val code = error.code
        val utrFieldError = error.fieldErrors["utr"]?.firstOrNull()
        _uiState.update {
            when {
                error.statusCode == HTTP_UNAUTHORIZED ->
                    it.copy(isSubmitting = false, submitError = SESSION_EXPIRED)
                utrFieldError != null -> it.copy(isSubmitting = false, utrError = utrFieldError)
                code in UTR_CODES -> it.copy(isSubmitting = false, utrError = error.message)
                code in BUY_METHOD_CODES -> it.copy(isSubmitting = false, buyMethodError = error.message)
                code == "order_not_payable" ->
                    it.copy(isSubmitting = false, notPayableMessage = error.message)
                // The key is only ever used for this order, so an earlier attempt
                // (whose response was lost) already recorded a pay-in for it.
                code == "idempotency_key_conflict" -> it.copy(
                    isSubmitting = false,
                    notPayableMessage = "A payment for this order was already submitted. Check the order status."
                )
                else -> it.copy(isSubmitting = false, submitError = error.message)
            }
        }
        // The payment request no longer matches this order: fetch fresh receiving details.
        if (code == "invalid_pay_in_intent") load()
        // The chosen BUY account is gone or inactive: refresh the choices.
        if (code == "buy_method_unavailable") refreshBuyMethods()
    }
}

/**
 * Applies a BUY account list result: only ACTIVE accounts can be chosen. A
 * still-available choice is kept; otherwise the default (or the only
 * account) is pre-selected.
 */
private fun PayInUiState.withBuyMethods(result: ApiResult<List<BuyMethodListItemDto>>): PayInUiState =
    when (result) {
        is ApiResult.Success -> {
            val active: List<PaymentMethodUiModel> = result.data.map { it.toUi() }.filter { it.isActive }
            val keep = selectedBuyMethodId?.takeIf { id -> active.any { it.id == id } }
            val selected = keep ?: active.firstOrNull { it.isDefault }?.id ?: active.singleOrNull()?.id
            copy(
                buyMethods = active,
                buyMethodsLoaded = true,
                buyMethodsError = null,
                selectedBuyMethodId = selected
            )
        }
        is ApiResult.Error -> copy(buyMethodsError = result.userMessage())
    }
