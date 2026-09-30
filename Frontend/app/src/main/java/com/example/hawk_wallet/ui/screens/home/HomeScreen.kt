package com.example.hawk_wallet.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.example.hawk_wallet.ui.components.DepositWithdrawalCard
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.components.HawkTopBar
import com.example.hawk_wallet.ui.components.InviteEarnCard
import com.example.hawk_wallet.ui.components.QuickActions
import com.example.hawk_wallet.ui.components.SummaryStrip
import com.example.hawk_wallet.ui.components.TransactionItem
import com.example.hawk_wallet.ui.model.QuickAction
import com.example.hawk_wallet.ui.model.TransactionUi
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ScreenHorizontalPadding = 20.dp
private val SectionSpacing = 16.dp

/**
 * Entry points whose screens are not built yet. Every callback defaults to
 * a no-op so the Home screen works today and later steps only need to pass
 * real navigation in.
 */
data class HomeActions(
    val onNotificationClick: () -> Unit = {},
    val onBalanceDetailClick: () -> Unit = {},
    val onQuickActionClick: (QuickAction) -> Unit = {},
    val onSeeAllTransactionsClick: () -> Unit = {},
    val onInviteClick: () -> Unit = {},
    val onSupportClick: () -> Unit = {},
    val onBottomNavSelect: (HawkNavDestination) -> Unit = {}
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    actions: HomeActions = HomeActions(),
    /** The backend's unread support-message count, shown on the bell. */
    supportUnreadCount: Int = 0
) {
    val uiState by viewModel.uiState.collectAsState()
    HomeScreenContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        actions = actions,
        supportUnreadCount = supportUnreadCount,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    actions: HomeActions,
    supportUnreadCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            HawkBottomNavigation(selected = HawkNavDestination.HOME, onSelect = actions.onBottomNavSelect)
        },
        floatingActionButton = {
            SupportButton(onClick = actions.onSupportClick)
        }
    ) { innerPadding ->
        // innerPadding carries the status bar and bottom navigation insets,
        // so no content ever sits underneath either.
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
                    onRetry = onRefresh,
                    actions = actions,
                    supportUnreadCount = supportUnreadCount
                )
            }
            uiState.isLoading -> HomeSkeleton(screenModifier)
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
    onRetry: () -> Unit,
    actions: HomeActions,
    supportUnreadCount: Int = 0
) {
    val sectionModifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Extra bottom space keeps the last card clear of the support button.
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing)
    ) {
        item {
            HawkTopBar(
                greetingName = content.greetingName,
                // No general notifications API exists yet; the count is unread support messages.
                hasUnreadNotifications = false,
                onNotificationClick = actions.onNotificationClick,
                unreadCount = supportUnreadCount
            )
        }

        if (errorMessage != null) {
            // A refresh failed: keep the last good data and say so.
            item {
                Column(modifier = sectionModifier) {
                    AuthErrorBanner(message = errorMessage)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        item {
            BalanceCard(
                availableBalance = content.availableBalance,
                pendingBalance = content.pendingBalance,
                onDetailClick = actions.onBalanceDetailClick,
                modifier = sectionModifier
            )
        }

        item {
            DepositWithdrawalCard(
                deposit = content.deposit,
                withdrawal = content.withdrawal,
                modifier = sectionModifier
            )
        }

        item {
            QuickActions(onActionClick = actions.onQuickActionClick, modifier = sectionModifier)
        }

        item {
            SummaryStrip(stats = content.summaryStats, modifier = sectionModifier)
        }

        item {
            TransactionsSection(
                transactions = content.recentTransactions,
                onSeeAllClick = actions.onSeeAllTransactionsClick,
                modifier = sectionModifier
            )
        }

        item {
            InviteEarnCard(onInviteClick = actions.onInviteClick, modifier = sectionModifier)
        }
    }
}

@Composable
private fun TransactionsSection(
    transactions: List<TransactionUi>,
    onSeeAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSeeAllClick) { Text("See All") }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (transactions.isEmpty()) {
                EmptyTransactions()
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    transactions.forEachIndexed { index, transaction ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        }
                        TransactionItem(transaction = transaction)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTransactions() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(HawkIndigo.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Outlined.ReceiptLong, contentDescription = null, tint = HawkIndigo)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No transactions yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Your order payments and payouts will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SupportButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = HawkIndigo,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
    ) {
        Icon(imageVector = Icons.Filled.SupportAgent, contentDescription = "Support")
    }
}

/** Placeholder shapes only - no figures are shown until the backend answers. */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    Column(
        modifier = modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).background(block, CircleShape))
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkeletonBlock(width = 60.dp, height = 12.dp)
                SkeletonBlock(width = 120.dp, height = 18.dp)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(HawkIndigo.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
        )
        SkeletonBlock(height = 70.dp)
        SkeletonBlock(height = 92.dp)
        SkeletonBlock(height = 64.dp)
        SkeletonBlock(height = 180.dp)
    }
}

@Composable
private fun SkeletonBlock(height: Dp, width: Dp? = null) {
    val base = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Box(
        modifier = base
            .height(height)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
    )
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

@Preview(showBackground = true, heightDp = 1300)
@Composable
private fun HomeScreenPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(
            uiState = HomeUiState(isLoading = false, content = previewHomeContent()),
            onRefresh = {},
            actions = HomeActions()
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenEmptyPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                isLoading = false,
                content = previewHomeContent().copy(recentTransactions = emptyList())
            ),
            onRefresh = {},
            actions = HomeActions()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenLoadingPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(uiState = HomeUiState(), onRefresh = {}, actions = HomeActions())
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenErrorPreview() {
    Hawk_WalletTheme {
        HomeScreenContent(
            uiState = HomeUiState(isLoading = false, errorMessage = "Unable to reach the server."),
            onRefresh = {},
            actions = HomeActions()
        )
    }
}
