package com.example.hawk_wallet.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.OrderRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Orders tab, backed by GET /api/orders/offers/ and GET /api/orders/.
 * Both lists are shown exactly as the backend returned them.
 */
class OrdersViewModel(private val repository: OrderRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState

    /** Loads both lists; called whenever the Orders screen opens. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val offers = async { repository.offers() }
            val orders = async { repository.orders() }
            val offersResult = offers.await()
            val ordersResult = orders.await()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    loaded = true,
                    offers = (offersResult as? ApiResult.Success)?.data?.map { it.toUi() } ?: state.offers,
                    offersError = (offersResult as? ApiResult.Error)?.userMessage(),
                    orders = (ordersResult as? ApiResult.Success)?.data?.map { it.toUi() } ?: state.orders,
                    ordersError = (ordersResult as? ApiResult.Error)?.userMessage()
                )
            }
        }
    }
}
