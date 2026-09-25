package com.example.hawk_wallet.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.HomeData
import com.example.hawk_wallet.data.remote.model.HomeTransactionDto
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.model.TransactionStatus
import com.example.hawk_wallet.ui.model.TransactionType
import com.example.hawk_wallet.ui.model.TransactionUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val HTTP_UNAUTHORIZED = 401

class HomeViewModel(private val homeRepository: HomeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    private var loadJob: Job? = null

    init {
        // Loaded once per ViewModel, not per recomposition.
        refresh()
    }

    /** Re-fetches the dashboard; a request already in flight is not duplicated. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            when (val result = homeRepository.getHome()) {
                is ApiResult.Success -> _uiState.update {
                    HomeUiState(isLoading = false, content = result.data.toContent())
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, errorMessage = result.userMessage())
                }
            }
        }
    }

    private fun ApiResult.Error.userMessage(): String =
        if (statusCode == HTTP_UNAUTHORIZED) {
            "Your session has expired. Please log in again."
        } else {
            message
        }
}

// Formatting only: every figure is shown exactly as the backend computed it.
private fun HomeData.toContent() = HomeContent(
    greetingName = user.name,
    availableBalance = DisplayFormat.rupees(balance.available),
    pendingBalance = DisplayFormat.rupees(balance.pending),
    summaryStats = listOf(
        SummaryStat(label = "Total Earnings", value = DisplayFormat.rupees(summary.total_earnings)),
        SummaryStat(label = "Active Orders", value = summary.active_orders.toString()),
        SummaryStat(label = "Pending Payouts", value = DisplayFormat.rupees(summary.pending_payouts))
    ),
    deposit = DisplayFormat.rupees(deposit_withdrawal.deposit),
    withdrawal = DisplayFormat.rupees(deposit_withdrawal.withdrawal),
    recentTransactions = recent_transactions.mapIndexed { index, item -> item.toUi(index) }
)

private fun HomeTransactionDto.toUi(index: Int) = TransactionUi(
    // The API has no per-item id; position + event data keeps list keys unique.
    id = "$index-$type-$reference-$created_at",
    type = when (type) {
        "ORDER_PAYMENT" -> TransactionType.ORDER_PAYMENT
        "PAYOUT" -> TransactionType.PAYOUT
        else -> TransactionType.OTHER
    },
    title = title,
    dateTime = DisplayFormat.dateTime(created_at),
    amount = DisplayFormat.rupees(amount),
    isCredit = DisplayFormat.isCredit(amount),
    status = when (status) {
        "SUCCESS" -> TransactionStatus.SUCCESS
        "REJECTED" -> TransactionStatus.REJECTED
        "CANCELLED" -> TransactionStatus.CANCELLED
        "FAILED" -> TransactionStatus.FAILED
        else -> TransactionStatus.PENDING
    }
)
