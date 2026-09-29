package com.example.hawk_wallet.ui.screens.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

// --- Team Members ---------------------------------------------------------------

@Composable
fun TeamMembersScreen(viewModel: TeamMembersViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val level by viewModel.level.collectAsState()
    val levels by viewModel.levels.collectAsState()
    TeamMembersContent(
        uiState = uiState,
        selectedLevel = level,
        levels = levels,
        onSelectLevel = viewModel::selectLevel,
        onRefresh = { viewModel.refresh() },
        onLoadMore = viewModel::loadMore,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamMembersContent(
    uiState: PagedListUiState<TeamMemberUi>,
    selectedLevel: Int?,
    levels: List<TeamLevelUi>,
    onSelectLevel: (Int?) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Team Members", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LevelFilter(levels = levels, selected = selectedLevel, onSelect = onSelectLevel)
            PagedList(
                uiState = uiState,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                // The backend's own total for this query; shown above a non-empty list only.
                header = (if (uiState.total == 1) "1 member" else "${uiState.total} members") +
                    (if (selectedLevel == null) " in your team" else " at Level $selectedLevel"),
                emptyTitle = if (selectedLevel == null) "No team members yet" else "No members at Level $selectedLevel",
                emptyMessage = "Members appear here when people register with your referral code, or with a code from your team.",
                errorTitle = "Couldn't load your team members"
            ) {
                items(uiState.items, key = { it.key }) { member ->
                    OrderCard { MemberRow(member = member) }
                }
            }
        }
    }
}

/** "All" plus each level the backend reports members at (levels beyond 21 included). */
@Composable
private fun LevelFilter(levels: List<TeamLevelUi>, selected: Int?, onSelect: (Int?) -> Unit) {
    // Keep a level opened from the Team tab selectable even before the summary arrives.
    val shown = levels.map { it.level }.let { if (selected != null && selected !in it) listOf(selected) + it else it }
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { LevelChip(label = "All", selected = selected == null, onClick = { onSelect(null) }) }
        items(shown, key = { it }) { level ->
            LevelChip(label = "Level $level", selected = selected == level, onClick = { onSelect(level) })
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            selectedContainerColor = HawkIndigo.copy(alpha = 0.12f),
            selectedLabelColor = HawkIndigo
        )
    )
}

// --- Commission History -----------------------------------------------------------

@Composable
fun CommissionHistoryScreen(viewModel: CommissionHistoryViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    CommissionHistoryContent(
        uiState = uiState,
        onRefresh = { viewModel.refresh() },
        onLoadMore = viewModel::loadMore,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommissionHistoryContent(
    uiState: PagedListUiState<CommissionUi>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Commission History", onBackClick = onBackClick) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PagedList(
                uiState = uiState,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                header = null,
                emptyTitle = "No commission yet",
                emptyMessage = "Commission appears here after a team member's payment is confirmed.",
                errorTitle = "Couldn't load your commission history"
            ) {
                items(uiState.items, key = { it.id }) { commission ->
                    CommissionCard(commission)
                }
            }
        }
    }
}

/** One commission with every field the API returns (it deliberately has no UTR). */
@Composable
private fun CommissionCard(commission: CommissionUi) {
    OrderCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Outlined.Payments, tint = HawkEmerald, background = HawkEmerald.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "+${commission.amount}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HawkEmerald
                )
                Text(
                    text = commission.createdAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LevelTag(level = commission.level)
        }
        Spacer(modifier = Modifier.height(10.dp))
        DetailRow(label = "Rate", value = commission.rate)
        DetailRow(label = "Confirmed payment", value = commission.baseAmount)
        DetailRow(label = "From", value = "${commission.sourceName} · ${commission.sourceMobileMasked}")
        DetailRow(label = "Order", value = commission.orderReference)
        DetailRow(label = "Pay-in", value = "#${commission.payInId}")
    }
}

// --- shared paged list --------------------------------------------------------------

/**
 * Pull-to-refresh list with loading / empty / error states and a "Load more"
 * footer while the backend reports another page (has_next).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PagedList(
    uiState: PagedListUiState<T>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    header: String?,
    emptyTitle: String,
    emptyMessage: String,
    errorTitle: String,
    items: LazyListScope.() -> Unit
) {
    val firstLoad = uiState.isLoading && uiState.items.isEmpty()
    PullToRefreshBox(
        isRefreshing = uiState.isLoading && uiState.items.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        if (firstLoad || !uiState.loaded) {
            CenteredLoading()
        } else LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val error = uiState.errorMessage
            when {
                uiState.items.isEmpty() && error != null -> item {
                    CenteredMessage(
                        title = errorTitle,
                        message = error,
                        actionLabel = "Retry",
                        onAction = onRefresh,
                        modifier = Modifier.padding(top = 96.dp)
                    )
                }
                uiState.items.isEmpty() -> item {
                    CenteredMessage(title = emptyTitle, message = emptyMessage, modifier = Modifier.padding(top = 96.dp))
                }
                else -> {
                    if (header != null) {
                        item {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items()
                    item {
                        LoadMoreFooter(
                            hasNext = uiState.hasNext,
                            isLoadingMore = uiState.isLoadingMore,
                            error = error,
                            onLoadMore = onLoadMore
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(hasNext: Boolean, isLoadingMore: Boolean, error: String?, onLoadMore: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (error != null) {
            AuthErrorBanner(message = error)
            if (hasNext) TextButton(onClick = onLoadMore) { Text("Retry") }
        }
        when {
            isLoadingMore -> CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(28.dp), strokeWidth = 2.dp)
            hasNext && error == null -> OutlinedButton(onClick = onLoadMore) { Text("Load more") }
        }
    }
}

// Previews use placeholder values only; the real screens show nothing but backend data.

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun TeamMembersPreview() {
    Hawk_WalletTheme {
        TeamMembersContent(
            uiState = PagedListUiState(
                items = listOf(
                    TeamMemberUi("1", 1, "Ravi Kumar", "******3210", "Today, 10:24 AM"),
                    TeamMemberUi("2", 22, "Asha", "******7788", "25 Sep 2026, 6:48 PM")
                ),
                loaded = true,
                page = 1,
                hasNext = true,
                total = 2
            ),
            selectedLevel = null,
            levels = listOf(TeamLevelUi(1, true, "0.3%", 1, 0, "₹0.00"), TeamLevelUi(22, false, null, 1, 0, "₹0.00")),
            onSelectLevel = {},
            onRefresh = {},
            onLoadMore = {},
            onBackClick = {}
        )
    }
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun CommissionHistoryPreview() {
    Hawk_WalletTheme {
        CommissionHistoryContent(
            uiState = PagedListUiState(
                items = listOf(
                    CommissionUi(1, "₹3.00", 1, "0.3%", "₹1,000.00", "Ravi Kumar", "******3210", 12, "HW-3KD9QX2M7P", "Today, 11:02 AM")
                ),
                loaded = true,
                page = 1,
                total = 1
            ),
            onRefresh = {},
            onLoadMore = {},
            onBackClick = {}
        )
    }
}
