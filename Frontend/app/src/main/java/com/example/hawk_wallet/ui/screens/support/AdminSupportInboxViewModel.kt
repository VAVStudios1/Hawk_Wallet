package com.example.hawk_wallet.ui.screens.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which conversations the inbox lists; mirrors the backend's status filter. */
enum class InboxFilter(val apiStatus: String, val label: String) {
    OPEN("OPEN", "Open"),
    CLOSED("CLOSED", "Closed")
}

/**
 * Admin support inbox. ADMIN: only conversations assigned to them.
 * SUPER_ADMIN: every conversation, read-only. The backend decides which
 * rows come back and sorts them by latest activity.
 */
data class AdminSupportInboxUiState(
    val filter: InboxFilter = InboxFilter.OPEN,
    val conversations: List<SupportConversationUi> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    val page: Int = 0,
    val hasNext: Boolean = false
)

class AdminSupportInboxViewModel(private val repository: SupportChatRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminSupportInboxUiState())
    val uiState: StateFlow<AdminSupportInboxUiState> = _uiState

    /** First page of the current filter; on open, pull-to-refresh and retry. A failure keeps the last list. */
    fun refresh() {
        if (_uiState.value.isLoading) return
        val filter = _uiState.value.filter
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repository.adminConversations(filter.apiStatus, page = 1)
            if (_uiState.value.filter != filter) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        loaded = true,
                        conversations = result.data.conversations.map { dto -> dto.toUi() },
                        page = result.data.page,
                        hasNext = result.data.has_next
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loaded = true, error = result.userMessage())
                }
            }
        }
    }

    fun selectFilter(filter: InboxFilter) {
        if (filter == _uiState.value.filter) return
        _uiState.update {
            it.copy(filter = filter, conversations = emptyList(), loaded = false, page = 0, hasNext = false, error = null, isLoading = false)
        }
        refresh()
    }

    /** Next page, when the list reaches its end. */
    fun loadMore() {
        val state = _uiState.value
        if (!state.hasNext || state.isLoading || state.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.adminConversations(state.filter.apiStatus, page = state.page + 1)) {
                is ApiResult.Success -> _uiState.update {
                    if (it.filter != state.filter) return@update it.copy(isLoadingMore = false)
                    val known = it.conversations.map { c -> c.id }.toSet()
                    it.copy(
                        isLoadingMore = false,
                        conversations = it.conversations + result.data.conversations.map { dto -> dto.toUi() }.filter { c -> c.id !in known },
                        page = result.data.page,
                        hasNext = result.data.has_next
                    )
                }
                is ApiResult.Error -> _uiState.update { it.copy(isLoadingMore = false, error = result.userMessage()) }
            }
        }
    }
}
