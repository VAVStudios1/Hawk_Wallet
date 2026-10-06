package com.example.hawk_wallet.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.TransactionHistoryData
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.HomeRepository
import com.example.hawk_wallet.ui.model.TransactionUi
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The periods GET /api/home/transactions/ supports; [apiValue] is sent as `range`. */
enum class HistoryRange(val apiValue: String, val label: String) {
    DAYS_7("7d", "7 Days"),
    DAYS_15("15d", "15 Days"),
    DAYS_30("30d", "30 Days"),
    CUSTOM("custom", "Custom")
}

/** The type filter; [apiValue] is sent as `type`. Buy = pay-ins, Sell = payouts, applied by the backend. */
enum class HistoryType(val apiValue: String, val label: String) {
    ALL("all", "All"),
    BUY("buy", "Buy"),
    SELL("sell", "Sell")
}

/** What the member picked. [startDate] / [endDate] (YYYY-MM-DD) are set for [HistoryRange.CUSTOM] only. */
data class HistoryPeriod(
    val range: HistoryRange = HistoryRange.DAYS_7,
    val startDate: String? = null,
    val endDate: String? = null
)

/**
 * A server-paged history. [resolvedStartDate] / [resolvedEndDate] are the
 * backend's own dates for the period, shown as-is.
 */
data class TransactionHistoryUiState(
    val period: HistoryPeriod = HistoryPeriod(),
    val type: HistoryType = HistoryType.ALL,
    val items: List<TransactionUi> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val total: Int? = null,
    val resolvedStartDate: String? = null,
    val resolvedEndDate: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Set once the current period has answered at least once (success or error). */
    val loaded: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null
)

/**
 * The backend filters, orders and pages; this only asks for page 1 (open,
 * refresh, period or type change) or the next page - always with both
 * filters - and keeps what came back. A reply for an older filter is dropped, and a failed page never clears the
 * rows already shown.
 */
class TransactionHistoryViewModel(private val homeRepository: HomeRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionHistoryUiState())
    val uiState: StateFlow<TransactionHistoryUiState> = _uiState

    private var generation = 0
    private var loadJob: Job? = null

    init {
        refresh()
    }

    /** First page of the current period: on open, pull-to-refresh and retry. */
    fun refresh() {
        loadJob?.cancel()
        val current = ++generation
        val period = _uiState.value.period
        val type = _uiState.value.type
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            val result = fetch(period, type, 1)
            if (current != generation) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        loaded = true,
                        items = result.data.toUi(),
                        page = result.data.page,
                        hasNext = result.data.has_next,
                        total = result.data.total,
                        resolvedStartDate = result.data.start_date,
                        resolvedEndDate = result.data.end_date
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loaded = true, error = result.userMessage())
                }
            }
        }
    }

    /** Next page, when the list reaches its end (or on Retry after a failed page). */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasNext || state.isLoading || state.isLoadingMore) return
        val current = generation
        _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        viewModelScope.launch {
            val result = fetch(state.period, state.type, state.page + 1)
            if (current != generation) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    // A record that shifted between pages is not listed twice.
                    val known = it.items.map(TransactionUi::id).toSet()
                    it.copy(
                        isLoadingMore = false,
                        items = it.items + result.data.toUi().filter { item -> item.id !in known },
                        page = result.data.page,
                        hasNext = result.data.has_next,
                        total = result.data.total
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoadingMore = false, loadMoreError = result.userMessage())
                }
            }
        }
    }

    fun selectPreset(range: HistoryRange) {
        if (range == HistoryRange.CUSTOM) return
        select(HistoryPeriod(range), _uiState.value.type)
    }

    /** [startDate] <= [endDate] is checked by the dialog; the backend validates again. */
    fun selectCustom(startDate: String, endDate: String) =
        select(HistoryPeriod(HistoryRange.CUSTOM, startDate, endDate), _uiState.value.type)

    /** Keeps the current period. */
    fun selectType(type: HistoryType) = select(_uiState.value.period, type)

    /** A new filter combination starts over from page 1, replacing the list. */
    private fun select(period: HistoryPeriod, type: HistoryType) {
        val state = _uiState.value
        if (period == state.period && type == state.type) return
        _uiState.update { TransactionHistoryUiState(period = period, type = type) }
        refresh()
    }

    private suspend fun fetch(period: HistoryPeriod, type: HistoryType, page: Int): ApiResult<TransactionHistoryData> =
        homeRepository.getTransactions(period.range.apiValue, period.startDate, period.endDate, type.apiValue, page)
}

private fun TransactionHistoryData.toUi(): List<TransactionUi> = transactions.map { item ->
    item.toTransactionUi(
        id = item.id ?: "${item.type}-${item.reference}-${item.created_at}",
        reference = item.reference
    )
}
