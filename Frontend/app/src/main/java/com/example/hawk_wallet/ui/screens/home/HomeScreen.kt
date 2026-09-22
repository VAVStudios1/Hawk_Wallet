package com.example.hawk_wallet.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.BalanceCard
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.components.HawkTopBar
import com.example.hawk_wallet.ui.components.InviteEarnCard
import com.example.hawk_wallet.ui.components.QuickActionItem
import com.example.hawk_wallet.ui.components.SummaryCard
import com.example.hawk_wallet.ui.components.TransactionItem
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ScreenHorizontalPadding = 20.dp

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier
) {
    HomeScreenContent(uiState = mockHomeUiState(), modifier = modifier)
}

@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            HawkBottomNavigation(selected = HawkNavDestination.HOME, onSelect = {})
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                HawkTopBar(
                    greetingName = uiState.greetingName,
                    hasUnreadNotifications = uiState.hasUnreadNotifications,
                    onNotificationClick = {}
                )
            }

            item {
                BalanceCard(
                    availableBalance = uiState.availableBalance,
                    pendingBalance = uiState.pendingBalance,
                    onAddMoneyClick = {},
                    onWithdrawClick = {},
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    uiState.summaryStats.forEach { stat ->
                        SummaryCard(stat = stat, modifier = Modifier.weight(1f))
                    }
                }
            }

            item {
                SectionHeader(title = "Quick Actions")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    uiState.quickActions.forEach { action ->
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

            items(uiState.recentTransactions, key = { it.id }) { transaction ->
                Column(modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)) {
                    TransactionItem(transaction = transaction)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
        }
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
        HomeScreenContent(uiState = mockHomeUiState())
    }
}
