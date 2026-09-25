package com.example.hawk_wallet.ui.screens.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.BalanceCard
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.components.HawkTopBar
import com.example.hawk_wallet.ui.components.InviteEarnCard
import com.example.hawk_wallet.ui.components.QuickActionItem
import com.example.hawk_wallet.ui.components.SummaryCard
import com.example.hawk_wallet.ui.components.TransactionItem
import com.example.hawk_wallet.ui.model.QuickAction
import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ScreenHorizontalPadding = 20.dp

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    HomeScreenContent(uiState = uiState, onRefresh = viewModel::refresh, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            HawkBottomNavigation(selected = HawkNavDestination.HOME, onSelect = {})
        }
    ) { innerPadding ->
        val content = uiState.content
        val screenModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            content != null -> PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = screenModifier
            ) {
                HomeDashboard(
                    content = content,
                    errorMessage = uiState.errorMessage,
                    onRetry = onRefresh
                )
            }
            uiState.isLoading -> HomeLoading(screenModifier)
            else -> HomeError(
                message = uiState.errorMessage ?: "Unable to load your dashboard.",
                onRetry = onRefresh,
                modifier = screenModifier
            )
        }
    }
}

@Composable
private fun HomeDashboard(
    content: HomeContent,
    errorMessage: String?,
    onRetry: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            HawkTopBar(
                greetingName = content.greetingName,
                // No notifications API exists yet, so nothing is unread.
                hasUnreadNotifications = false,
                onNotificationClick = {}
            )
        }

        if (errorMessage != null) {
            // A refresh failed: keep the last good data and say so.
            item {
                Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    AuthErrorBanner(message = errorMessage)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        item {
            BalanceCard(
                availableBalance = content.availableBalance,
                pendingBalance = content.pendingBalance,
                onAddMoneyClick = {},
                onWithdrawClick = {},
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
            )
        }

        item {
            StatRow(stats = content.summaryStats, topPadding = 20.dp)
        }

        item {
            StatRow(
                stats = listOf(
                    SummaryStat(label = "Deposit", value = content.deposit),
                    SummaryStat(label = "Withdrawal", value = content.withdrawal)
                ),
                topPadding = 12.dp
            )
        }

        item {
            SectionHeader(title = "Quick Actions")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickAction.entries.forEach { action ->
                    QuickActionItem(action = action, onClick = {})
                }
            }
        }

        item {
            InviteEarnCard(
                onInviteClick = {},
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp)
            )
        }

        item {
            SectionHeader(title = "Recent Transactions", onSeeAllClick = {})
        }

        if (content.recentTransactions.isEmpty()) {
            item {
                Text(
                    text = "No transactions yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
                )
            }
        } else {
            items(content.recentTransactions, key = { it.id }) { transaction ->
                Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    TransactionItem(transaction = transaction)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
private fun StatRow(stats: List<SummaryStat>, topPadding: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, top = topPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        stats.forEach { stat ->
            SummaryCard(stat = stat, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeLoading(modifier: Modifier = Modifier) {
    // No figures are shown until the backend has answered.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HomeError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Couldn't load your dashboard",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAllClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = ScreenHorizontalPadding,
                top = 8.dp,
                end = ScreenHorizontalPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (onSeeAllClick != null) {
            TextButton(onClick = onSeeAllClick) {
                Text("See All")
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(
            uiState = HomeUiState(isLoading = false, content = previewHomeContent()),
            onRefresh = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(
            uiState = HomeUiState(isLoading = false, errorMessage = "Unable to reach the server."),
            onRefresh = {}
        )
    }
}
