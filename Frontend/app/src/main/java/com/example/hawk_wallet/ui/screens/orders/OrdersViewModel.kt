package com.example.hawk_wallet.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.OrderRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val INVALID_AMOUNT_FILTER = "invalid_amount_filter"

/**
 * The Orders tab, backed by GET /api/orders/offers/ and GET /api/orders/.
 * Both lists are shown exactly as the backend returned them. The Available
 * amount filter is sent to the backend, which filters by order_amount; it
 * never touches My Orders.
 */
class OrdersViewModel(private val repository: OrderRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState

    private var offersJob: Job? = null

    /** Bumped per offers request; only the latest request's response is applied. */
    private var offersRequest = 0

    /** Loads both lists (offers with the current filter); called whenever the Orders screen opens. */
    fun refresh() {
        refreshOffers()
        refreshOrders()
    }

    /** Reloads Available with the current filter, keeping the list shown until the result arrives. */
    fun refreshOffers() = loadOffers(clearList = false)

    fun selectQuickRange(preset: OfferAmountPreset) = setFilter(preset.filter)

    fun clearFilter() = setFilter(OfferAmountFilter.ALL)

    fun openCustomFilter() {
        _uiState.update { it.copy(customAmount = CustomAmountInput.from(it.amountFilter)) }
    }

    fun onCustomMinChange(value: String) {
        _uiState.update { state -> state.copy(customAmount = state.customAmount?.copy(min = amountDigits(value))) }
    }

    fun onCustomMaxChange(value: String) {
        _uiState.update { state -> state.copy(customAmount = state.customAmount?.copy(max = amountDigits(value))) }
    }

    fun dismissCustomFilter() {
        _uiState.update { it.copy(customAmount = null) }
    }

    /** Min > Max is never sent: Apply does nothing until it is fixed. Both fields empty is All. */
    fun applyCustomFilter() {
        val input = _uiState.value.customAmount ?: return
        if (!input.canApply) return
        _uiState.update { it.copy(customAmount = null) }
        setFilter(input.toFilter())
    }

    private fun setFilter(filter: OfferAmountFilter) {
        if (filter == _uiState.value.amountFilter) return
        _uiState.update { it.copy(amountFilter = filter) }
        loadOffers(clearList = true)
    }

    /**
     * A new filter clears the list (so offers from the old range are never shown
     * under the new one) and supersedes any request still running.
     */
    private fun loadOffers(clearList: Boolean) {
        offersJob?.cancel()
        val request = ++offersRequest
        val filter = _uiState.value.amountFilter
        _uiState.update { state ->
            if (clearList) {
                state.copy(offersLoading = true, offersLoaded = false, offers = emptyList(), offersError = null)
            } else {
                state.copy(offersLoading = true)
            }
        }
        offersJob = viewModelScope.launch {
            val result = repository.offers(filter.minAmount, filter.maxAmount)
            if (request != offersRequest) return@launch
            _uiState.update { state ->
                state.copy(
                    offersLoading = false,
                    offersLoaded = true,
                    offers = (result as? ApiResult.Success)?.data?.map { it.toUi() } ?: state.offers,
                    offersError = (result as? ApiResult.Error)?.offersMessage()
                )
            }
        }
    }

    private fun refreshOrders() {
        if (_uiState.value.ordersLoading) return
        _uiState.update { it.copy(ordersLoading = true) }
        viewModelScope.launch {
            val result = repository.orders()
            _uiState.update { state ->
                state.copy(
                    ordersLoading = false,
                    ordersLoaded = true,
                    orders = (result as? ApiResult.Success)?.data?.map { it.toUi() } ?: state.orders,
                    ordersError = (result as? ApiResult.Error)?.userMessage()
                )
            }
        }
    }

    private fun ApiResult.Error.offersMessage(): String =
        if (code == INVALID_AMOUNT_FILTER) {
            "This amount range can't be used. Choose another range or show all offers."
        } else {
            userMessage()
        }
}
