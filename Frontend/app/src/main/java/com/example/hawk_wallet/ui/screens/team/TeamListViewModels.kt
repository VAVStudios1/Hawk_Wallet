package com.example.hawk_wallet.ui.screens.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.TeamRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

/**
 * Page-by-page loading shared by the Team Members and Commission History
 * screens. The backend owns paging (page / has_next / total); this only
 * asks for the next page and appends it.
 */
abstract class PagedListViewModel<T> : ViewModel() {

    private val _uiState = MutableStateFlow(PagedListUiState<T>())
    val uiState: StateFlow<PagedListUiState<T>> = _uiState

    private var loadJob: Job? = null

    /** [offset] is how many items are already shown, for unique list keys. */
    protected abstract suspend fun fetch(page: Int, pageSize: Int, offset: Int): ApiResult<Page<T>>

    /** One fetched page: its items plus the backend's paging fields. */
    protected data class Page<E>(val items: List<E>, val page: Int, val hasNext: Boolean, val total: Int)

    /**
     * Reloads from the first page (screen open, pull-to-refresh). [clear]
     * drops the current items first - used when the query itself changes,
     * so another filter's items are never shown under the new one.
     */
    fun refresh(clear: Boolean = false) {
        loadJob?.cancel()
        _uiState.update {
            if (clear) PagedListUiState(isLoading = true) else it.copy(isLoading = true, isLoadingMore = false)
        }
        loadJob = viewModelScope.launch {
            when (val result = fetch(page = 1, pageSize = PAGE_SIZE, offset = 0)) {
                is ApiResult.Success -> _uiState.update {
                    PagedListUiState(
                        items = result.data.items,
                        loaded = true,
                        page = result.data.page,
                        hasNext = result.data.hasNext,
                        total = result.data.total
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoading = false, loaded = true, errorMessage = result.userMessage())
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasNext || state.isLoading || state.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            when (val result = fetch(page = state.page + 1, pageSize = PAGE_SIZE, offset = state.items.size)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        items = it.items + result.data.items,
                        isLoadingMore = false,
                        page = result.data.page,
                        hasNext = result.data.hasNext,
                        total = result.data.total
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoadingMore = false, errorMessage = result.userMessage())
                }
            }
        }
    }
}

/** GET /api/team/members/, optionally one level; the level list comes from /summary/. */
class TeamMembersViewModel(
    private val repository: TeamRepository,
    initialLevel: Int?
) : PagedListViewModel<TeamMemberUi>() {

    private val _level = MutableStateFlow(initialLevel)
    val level: StateFlow<Int?> = _level

    /** Levels that have members, from the backend summary - used for the filter chips. */
    private val _levels = MutableStateFlow<List<TeamLevelUi>>(emptyList())
    val levels: StateFlow<List<TeamLevelUi>> = _levels

    init {
        refresh()
        viewModelScope.launch {
            (repository.summary() as? ApiResult.Success)?.let { result ->
                _levels.value = result.data.levels.filter { it.member_count > 0 }.map { it.toUi() }
            }
        }
    }

    fun selectLevel(level: Int?) {
        if (level == _level.value) return
        _level.value = level
        refresh(clear = true)
    }

    override suspend fun fetch(page: Int, pageSize: Int, offset: Int): ApiResult<Page<TeamMemberUi>> =
        when (val result = repository.members(_level.value, page, pageSize)) {
            is ApiResult.Success -> ApiResult.Success(
                Page(
                    items = result.data.members.mapIndexed { index, member -> member.toUi(offset + index) },
                    page = result.data.page,
                    hasNext = result.data.has_next,
                    total = result.data.total
                ),
                result.message
            )
            is ApiResult.Error -> result
        }
}

/** GET /api/team/commissions/, newest first. */
class CommissionHistoryViewModel(private val repository: TeamRepository) : PagedListViewModel<CommissionUi>() {

    init {
        refresh()
    }

    override suspend fun fetch(page: Int, pageSize: Int, offset: Int): ApiResult<Page<CommissionUi>> =
        when (val result = repository.commissions(page, pageSize)) {
            is ApiResult.Success -> ApiResult.Success(
                Page(
                    items = result.data.commissions.map { it.toUi() },
                    page = result.data.page,
                    hasNext = result.data.has_next,
                    total = result.data.total
                ),
                result.message
            )
            is ApiResult.Error -> result
        }
}
