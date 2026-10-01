package com.example.hawk_wallet.ui.screens.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminDashboardRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Admin Dashboard, backed by GET /api/admin/dashboard/. */
class AdminDashboardViewModel(private val repository: AdminDashboardRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminDashboardUiState())
    val uiState: StateFlow<AdminDashboardUiState> = _uiState

    /** Called whenever the Dashboard opens, on pull-to-refresh and on Retry. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            when (val result = repository.getDashboard()) {
                is ApiResult.Success -> {
                    val metrics = result.data.toMetrics()
                    _uiState.update {
                        if (metrics != null) {
                            it.copy(isLoading = false, metrics = metrics, error = null)
                        } else {
                            it.copy(isLoading = false, error = INCOMPLETE_RESPONSE)
                        }
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.userMessage())
                }
            }
        }
    }
}

private const val INCOMPLETE_RESPONSE = "The dashboard couldn't be loaded. Please try again."
