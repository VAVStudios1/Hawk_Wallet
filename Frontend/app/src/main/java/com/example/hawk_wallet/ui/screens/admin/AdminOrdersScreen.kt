package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.AdminOrderUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Every member's orders for an admin; read-only in this phase. */
@Composable
fun AdminOrdersScreen(
    viewModel: AdminOrdersViewModel,
    onBackClick: () -> Unit,
    onOrderClick: (orderId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. back from an order's details.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminOrdersContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onOrderClick = onOrderClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminOrdersContent(
    uiState: AdminOrdersUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onOrderClick: (Long) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Orders", onBackClick = onBackClick) }
    ) { innerPadding ->
        val firstLoad = uiState.isLoading && !uiState.loaded
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && uiState.loaded,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (firstLoad) {
                CenteredLoading()
                return@PullToRefreshBox
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val error = uiState.error
                when {
                    uiState.orders.isEmpty() && error != null -> item {
                        CenteredMessage(
                            title = "Couldn't load orders",
                            message = error,
                            actionLabel = "Retry",
                            onAction = onRefresh,
                            modifier = Modifier.padding(top = 96.dp)
                        )
                    }
                    uiState.orders.isEmpty() -> item {
                        CenteredMessage(
                            title = "No orders yet",
                            message = "Orders appear here as soon as members claim them. Pull down to refresh.",
                            modifier = Modifier.padding(top = 96.dp)
                        )
                    }
                    else -> {
                        if (error != null) {
                            item {
                                AuthErrorBanner(message = error)
                                TextButton(onClick = onRefresh) { Text("Retry") }
                            }
                        }
                        items(uiState.orders, key = { it.id }) { order ->
                            AdminOrderSummaryCard(order = order, onClick = { onOrderClick(order.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminOrderSummaryCard(order: AdminOrderUiModel, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = order.reference,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            OrderStatusChip(status = order.status)
        }
        Text(
            text = order.memberName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        AmountRow(label = "Order amount", amount = order.orderAmount)
        AmountRow(label = "Income (${DisplayFormat.percent(order.incomeRate)})", amount = order.incomeAmount)
        AmountRow(label = "Payout target", amount = order.payoutTarget, emphasized = true)
        Text(
            text = "Claimed ${DisplayFormat.dateTime(order.claimedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

internal fun previewAdminOrder(status: OrderStatus = OrderStatus.PAYMENT_UNDER_REVIEW) = AdminOrderUiModel(
    id = 7,
    reference = "HW-3KD9QX2M7P",
    status = status,
    memberId = 12,
    memberName = "Asha Kumar",
    orderAmount = "255.00",
    incomeRate = "4.00",
    incomeAmount = "10.00",
    payoutTarget = "265.00",
    payoutCommittedTotal = "0.00",
    payoutPaidTotal = "0.00",
    claimedAt = "2026-09-26T10:00:00Z",
    expiresAt = null,
    activatedAt = null,
    completedAt = null
)

@Preview(showBackground = true)
@Composable
private fun AdminOrdersPreview() {
    Hawk_WalletTheme {
        AdminOrdersContent(
            uiState = AdminOrdersUiState(
                orders = listOf(previewAdminOrder(), previewAdminOrder(OrderStatus.COMPLETED).copy(id = 8)),
                loaded = true
            ),
            onBackClick = {},
            onRefresh = {},
            onOrderClick = {}
        )
    }
}
