package com.example.hawk_wallet.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.OrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One of the member's orders, from GET /api/orders/{id}/, including its latest pay-in. */
class OrderStatusViewModel(
    private val repository: OrderRepository,
    private val orderId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderStatusUiState())
    val uiState: StateFlow<OrderStatusUiState> = _uiState

    /** Called whenever the screen opens and on pull-to-refresh. */
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
