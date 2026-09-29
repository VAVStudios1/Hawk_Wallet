package com.example.hawk_wallet.ui.screens.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.HawkBottomNavigation
import com.example.hawk_wallet.ui.components.HawkNavDestination
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.OrderUiModel
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private const val TAB_AVAILABLE = 0
private const val TAB_MY_ORDERS = 1

@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel,
    onBackClick: () -> Unit,
    onOfferClick: (OfferUiModel) -> Unit,
    onOrderClick: (Long) -> Unit,
    onBottomNavSelect: (HawkNavDestination) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Both lists reload each time the screen opens (e.g. back from a claim).
    LaunchedEffect(Unit) { viewModel.refresh() }

    OrdersContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onBackClick = onBackClick,
        onOfferClick = onOfferClick,
        onOrderClick = onOrderClick,
        onBottomNavSelect = onBottomNavSelect
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrdersContent(
    uiState: OrdersUiState,
    onRefresh: () -> Unit,
    onBackClick: () -> Unit,
    onOfferClick: (OfferUiModel) -> Unit,
    onOrderClick: (Long) -> Unit,
    onBottomNavSelect: (HawkNavDestination) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_AVAILABLE) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Orders", onBackClick = onBackClick) },
        bottomBar = { HawkBottomNavigation(selected = HawkNavDestination.ORDERS, onSelect = onBottomNavSelect) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == TAB_AVAILABLE, onClick = { tab = TAB_AVAILABLE }, text = { Text("Available") })
                Tab(selected = tab == TAB_MY_ORDERS, onClick = { tab = TAB_MY_ORDERS }, text = { Text("My Orders") })
            }

            val firstLoad = uiState.isLoading && !uiState.loaded
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && uiState.loaded,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    firstLoad -> CenteredLoading()
                    tab == TAB_AVAILABLE -> ListOrMessage(
                        error = uiState.offersError,
                        isEmpty = uiState.offers.isEmpty(),
                        emptyTitle = "No orders available right now",
                        emptyMessage = "New orders appear here as soon as they are published. Pull down to refresh.",
                        errorTitle = "Couldn't load available orders",
                        onRetry = onRefresh
                    ) {
                        items(uiState.offers, key = { it.id }) { offer ->
                            OfferCard(offer = offer, onClick = { onOfferClick(offer) })
                        }
                    }
                    else -> ListOrMessage(
                        error = uiState.ordersError,
                        isEmpty = uiState.orders.isEmpty(),
                        emptyTitle = "No orders yet",
                        emptyMessage = "Orders you claim appear here.",
                        errorTitle = "Couldn't load your orders",
                        onRetry = onRefresh
                    ) {
                        items(uiState.orders, key = { it.id }) { order ->
                            OrderSummaryCard(order = order, onClick = { onOrderClick(order.id) })
                        }
                    }
                }
            }
        }
    }
}

/** The list; or, when it is empty, the error or empty message (still pull-to-refreshable). */
@Composable
private fun ListOrMessage(
    error: String?,
    isEmpty: Boolean,
    emptyTitle: String,
    emptyMessage: String,
    errorTitle: String,
    onRetry: () -> Unit,
    items: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            isEmpty && error != null -> item {
                CenteredMessage(
                    title = errorTitle,
                    message = error,
                    actionLabel = "Retry",
                    onAction = onRetry,
                    modifier = Modifier.padding(top = 96.dp)
                )
            }
            isEmpty -> item {
                CenteredMessage(title = emptyTitle, message = emptyMessage, modifier = Modifier.padding(top = 96.dp))
            }
            else -> {
                if (error != null) {
                    item {
                        AuthErrorBanner(message = error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                items()
            }
        }
    }
}

@Composable
private fun OfferCard(offer: OfferUiModel, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text = "You pay",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = DisplayFormat.rupees(offer.orderAmount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        AmountRow(label = "Income (${DisplayFormat.percent(offer.incomeRate)})", amount = offer.incomeAmount)
        AmountRow(label = "You receive", amount = offer.payoutTarget, emphasized = true)
        offer.validTo?.let {
            Text(
                text = "Available until ${DisplayFormat.dateTime(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClick) { Text("View & Claim", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun OrderSummaryCard(order: OrderUiModel, onClick: () -> Unit) {
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
        Spacer(modifier = Modifier.height(8.dp))
        AmountRow(label = "Order amount", amount = order.orderAmount)
        AmountRow(label = "Payout target", amount = order.payoutTarget, emphasized = true)
        Text(
            text = "Claimed ${DisplayFormat.dateTime(order.claimedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (order.status == OrderStatus.PAYMENT_REQUIRED) {
            Text(
                text = "Tap to complete your payment",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun OrdersPreview() {
    Hawk_WalletTheme {
        OrdersContent(
            uiState = OrdersUiState(
                offers = listOf(OfferUiModel(1, "255.00", "4.00", "10.00", "265.00", "2026-09-30T18:00:00Z")),
                orders = listOf(
                    OrderUiModel(
                        7, "HW-3KD9QX2M7P", OrderStatus.PAYMENT_REQUIRED, "255.00", "4.00", "10.00", "265.00",
                        OrderPayoutMethodUiModel("PHONEPE", "PhonePe", "lucky@ybl"),
                        "2026-09-26T10:00:00Z", null, null, null
                    )
                ),
                loaded = true
            ),
            onRefresh = {},
            onBackClick = {},
            onOfferClick = {},
            onOrderClick = {},
            onBottomNavSelect = {}
        )
    }
}
