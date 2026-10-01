package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Mirrors core.listing.MAX_SEARCH_LENGTH; the backend rejects longer terms. */
internal const val ADMIN_SEARCH_MAX_LENGTH = 64

/** One page as the backend returned it: items plus its page / has_next / total meta. */
data class AdminPage<T>(val items: List<T>, val page: Int, val hasNext: Boolean, val total: Int)

/**
 * A server-paged admin list. [search] is the term actually applied (sent to
 * the backend); [searchInput] is what is typed but not yet submitted.
 * [total] is the backend's count for the current filter and search.
 */
data class AdminListUiState<T, F>(
    val filter: F,
    val searchInput: String = "",
    val search: String = "",
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val total: Int? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Set once the current filter / search has answered at least once (success or error). */
    val loaded: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null
)

/**
 * Shared logic for every admin list: the backend filters, searches and pages;
 * this only asks for page 1 (refresh) or the next page (load more) and keeps
 * what came back. A reply for an older filter / search is dropped, a failed
 * refresh keeps the last list and reports the error - it never becomes an
 * empty list.
 */
abstract class AdminListViewModel<T, F>(initialFilter: F) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminListUiState<T, F>(filter = initialFilter))
    val uiState: StateFlow<AdminListUiState<T, F>> = _uiState

    private var generation = 0
    private var loadJob: Job? = null

    protected abstract suspend fun fetch(filter: F, search: String, page: Int): ApiResult<AdminPage<T>>

    /** Stable id, so an item that shifts between pages is not listed twice. */
    protected abstract fun idOf(item: T): Long

    /** First page of the current filter and search: on open, pull-to-refresh and retry. */
    fun refresh() {
        loadJob?.cancel()
        val current = ++generation
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null, loadMoreError = null) }
        loadJob = viewModelScope.launch {
            val result = fetch(state.filter, state.search, 1)
            if (current != generation) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        loaded = true,
                        items = result.data.items,
                        page = result.data.page,
                        hasNext = result.data.hasNext,
                        total = result.data.total
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
            val result = fetch(state.filter, state.search, state.page + 1)
            if (current != generation) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    val known = it.items.map(::idOf).toSet()
                    it.copy(
                        isLoadingMore = false,
                        items = it.items + result.data.items.filter { item -> idOf(item) !in known },
                        page = result.data.page,
                        hasNext = result.data.hasNext,
                        total = result.data.total
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoadingMore = false, loadMoreError = result.userMessage())
                }
            }
        }
    }

    fun selectFilter(filter: F) {
        if (filter == _uiState.value.filter) return
        _uiState.update { it.reset().copy(filter = filter) }
        refresh()
    }

    fun onSearchInputChange(value: String) {
        _uiState.update { it.copy(searchInput = value.take(ADMIN_SEARCH_MAX_LENGTH)) }
    }

    /** Applies the typed term (keyboard Search action); the backend does the matching. */
    fun submitSearch() {
        val term = _uiState.value.searchInput.trim()
        if (term == _uiState.value.search) return
        _uiState.update { it.reset().copy(search = term) }
        refresh()
    }

    fun clearSearch() {
        val hadSearch = _uiState.value.search.isNotEmpty()
        _uiState.update { it.copy(searchInput = "") }
        if (hadSearch) {
            _uiState.update { it.reset().copy(search = "") }
            refresh()
        }
    }

    private fun AdminListUiState<T, F>.reset() =
        copy(items = emptyList(), page = 0, hasNext = false, total = null, loaded = false, error = null, loadMoreError = null)
}

/** The texts a list shows around its rows. */
internal data class AdminListTexts(
    val title: String,
    val searchPlaceholder: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val errorTitle: String
)

/**
 * Shared admin list screen: title bar, server-side search, filter chips and
 * a pull-to-refresh, paged list with its loading / error / empty states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T, F> AdminListScaffold(
    state: AdminListUiState<T, F>,
    texts: AdminListTexts,
    filters: List<F>,
    filterLabel: (F) -> String,
    onBackClick: (() -> Unit)?,
    onFilterSelect: (F) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    itemKey: (T) -> Any,
    topBarActions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    /** Optional row under the filter chips (e.g. an active date-range filter). */
    headerContent: @Composable () -> Unit = {},
    itemContent: @Composable (T) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = texts.title, onBackClick = onBackClick, actions = topBarActions) },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AdminSearchField(
                value = state.searchInput,
                placeholder = texts.searchPlaceholder,
                onValueChange = onSearchInputChange,
                onSearch = onSearch,
                onClear = onClearSearch,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
            )
            AdminFilterChips(
                filters = filters,
                selected = state.filter,
                label = filterLabel,
                onSelect = onFilterSelect
            )
            headerContent()
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.loaded,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                if (!state.loaded) {
                    CenteredLoading()
                    return@PullToRefreshBox
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val error = state.error
                    when {
                        state.items.isEmpty() && error != null -> item {
                            CenteredMessage(
                                title = texts.errorTitle,
                                message = error,
                                actionLabel = "Retry",
                                onAction = onRefresh,
                                modifier = Modifier.padding(top = 72.dp)
                            )
                        }
                        state.items.isEmpty() -> item {
                            CenteredMessage(
                                title = if (state.search.isNotEmpty()) "No matches" else texts.emptyTitle,
                                message = if (state.search.isNotEmpty()) {
                                    "Nothing matches \"${state.search}\" here. Try another search or filter."
                                } else {
                                    texts.emptyMessage
                                },
                                modifier = Modifier.padding(top = 72.dp)
                            )
                        }
                        else -> {
                            if (error != null) {
                                item(key = "refresh-error") {
                                    Column {
                                        AuthErrorBanner(message = error)
                                        TextButton(onClick = onRefresh) { Text("Retry") }
                                    }
                                }
                            }
                            state.total?.let { total ->
                                item(key = "total") {
                                    Text(
                                        text = if (total == 1) "1 result" else "$total results",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            items(state.items, key = itemKey) { item -> itemContent(item) }
                            if (state.hasNext) {
                                item(key = "load-more") {
                                    LoadMoreFooter(
                                        page = state.page,
                                        error = state.loadMoreError,
                                        onLoadMore = onLoadMore
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Requests the next page once it scrolls into view; a failed page offers Retry instead. */
@Composable
private fun LoadMoreFooter(page: Int, error: String?, onLoadMore: () -> Unit) {
    if (error != null) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onLoadMore) { Text("Retry") }
        }
        return
    }
    LaunchedEffect(page) { onLoadMore() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** Search box; the term is applied on the keyboard's Search action, not on every keystroke. */
@Composable
internal fun AdminSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = {
                    onClear()
                    focusManager.clearFocus()
                }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch()
            focusManager.clearFocus()
        }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun <F> AdminFilterChips(
    filters: List<F>,
    selected: F,
    label: (F) -> String,
    onSelect: (F) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters, key = { label(it) }) { filter ->
            // Same as the member Orders amount filter chips (OfferAmountFilter).
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(label(filter)) },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
