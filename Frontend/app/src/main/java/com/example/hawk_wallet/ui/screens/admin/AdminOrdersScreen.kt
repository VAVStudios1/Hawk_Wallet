package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.AdminOrderUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

private val ORDER_TEXTS = AdminListTexts(
    title = "Orders",
    searchPlaceholder = "Reference, member name or mobile",
    emptyTitle = "No orders",
    emptyMessage = "Orders appear here as soon as members claim them. Pull down to refresh.",
    errorTitle = "Couldn't load orders"
)

/**
 * Every member's orders for an admin: server-side status filter, search and
 * paging. As an admin bottom-navigation tab it has no back button
 * ([onBackClick] null) and shows [bottomBar]. [onCreateOfferClick] adds a
 * top-bar action for Create Offer.
 */
@Composable
fun AdminOrdersScreen(
    viewModel: AdminOrdersViewModel,
    onBackClick: (() -> Unit)?,
    onOrderClick: (orderId: Long) -> Unit,
    onCreateOfferClick: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. back from an order's details.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminOrdersContent(
        state = uiState,
        onBackClick = onBackClick,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOrderClick = onOrderClick,
        onCreateOfferClick = onCreateOfferClick,
        bottomBar = bottomBar
    )
}

@Composable
private fun AdminOrdersContent(
    state: AdminListUiState<AdminOrderUiModel, AdminOrderFilter>,
    onBackClick: (() -> Unit)?,
    onFilterSelect: (AdminOrderFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOrderClick: (Long) -> Unit,
    onCreateOfferClick: (() -> Unit)?,
    bottomBar: @Composable () -> Unit
) {
    AdminListScaffold(
        state = state,
        texts = ORDER_TEXTS,
        filters = AdminOrderFilter.entries,
        filterLabel = { it.label },
        onBackClick = onBackClick,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id },
        topBarActions = {
            if (onCreateOfferClick != null) {
                IconButton(onClick = onCreateOfferClick) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = "Create Offer")
                }
            }
        },
        bottomBar = bottomBar
    ) { order ->
        AdminOrderSummaryCard(order = order, onClick = { onOrderClick(order.id) })
    }
}

/** Reference + status, member, the three stored amounts and the claim time. */
@Composable
private fun AdminOrderSummaryCard(order: AdminOrderUiModel, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = order.reference,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OrderStatusChip(status = order.status)
        }
        MemberLine(
            name = "${order.memberName} · ${DisplayFormat.dateTime(order.claimedAt)}",
            maskedMobile = null,
            modifier = Modifier.padding(top = 2.dp)
        )
        CompactAmountRow(
            Triple("You Pay", order.orderAmount, false),
            Triple("Income", order.incomeAmount, false),
            Triple("Payout Target", order.payoutTarget, true)
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
            state = AdminListUiState(
                filter = AdminOrderFilter.ALL,
                items = listOf(previewAdminOrder(), previewAdminOrder(OrderStatus.COMPLETED).copy(id = 8)),
                loaded = true,
                total = 2
            ),
            onBackClick = null,
            onFilterSelect = {},
            onSearchInputChange = {},
            onSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onOrderClick = {},
            onCreateOfferClick = {},
            bottomBar = {}
        )
    }
}
