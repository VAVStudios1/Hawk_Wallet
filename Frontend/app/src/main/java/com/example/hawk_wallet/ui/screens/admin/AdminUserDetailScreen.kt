package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.AdminUserDetailDto
import com.example.hawk_wallet.data.repository.AdminUserRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.screens.profile.ProfileTag
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminUserOrderUi(
    val id: Long,
    val reference: String,
    val status: OrderStatus,
    val orderAmount: String,
    val incomeAmount: String,
    val payoutTarget: String,
    val claimedAt: String
)

/** One member with the server's activity counts; nothing is counted on the device. */
data class AdminUserDetailUi(
    val user: AdminUserUi,
    val ordersTotal: Int,
    /** Backend status -> count, only statuses with at least one order, in lifecycle order. */
    val orderCounts: List<Pair<OrderStatus, Int>>,
    val payInCounts: List<Pair<PayInStatus, Int>>,
    val recentOrders: List<AdminUserOrderUi>
)

internal fun AdminUserDetailDto.toUi() = AdminUserDetailUi(
    user = user.toUi(),
    ordersTotal = orders_total,
    orderCounts = OrderStatus.entries.mapNotNull { status ->
        order_counts[status.name]?.takeIf { it > 0 }?.let { status to it }
    },
    payInCounts = PayInStatus.entries.mapNotNull { status ->
        pay_in_counts[status.name]?.takeIf { it > 0 }?.let { status to it }
    },
    recentOrders = recent_orders.map {
        AdminUserOrderUi(
            id = it.id,
            reference = it.reference,
            status = OrderStatus.fromApi(it.status),
            orderAmount = it.order_amount,
            incomeAmount = it.income_amount,
            payoutTarget = it.payout_target,
            claimedAt = it.claimed_at
        )
    }
)

data class AdminUserDetailUiState(
    val detail: AdminUserDetailUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** One member, from GET /api/admin/users/{id}/. Read-only: this phase has no user actions. */
class AdminUserDetailViewModel(
    private val repository: AdminUserRepository,
    private val userId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUserDetailUiState())
    val uiState: StateFlow<AdminUserDetailUiState> = _uiState

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.user(userId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, detail = result.data.toUi()) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }
}

@Composable
fun AdminUserDetailScreen(
    viewModel: AdminUserDetailViewModel,
    onBackClick: () -> Unit,
    onOpenOrder: (orderId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminUserDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onOpenOrder = onOpenOrder
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminUserDetailContent(
    uiState: AdminUserDetailUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOrder: (Long) -> Unit
) {
    val detail = uiState.detail
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "User", onBackClick = onBackClick) }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            detail == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this user",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            detail == null -> CenteredLoading(modifier)
            else -> PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
                ) {
                    uiState.error?.let { AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 12.dp)) }
                    UserHeader(detail.user)
                    Spacer(modifier = Modifier.height(12.dp))
                    ActivitySection(detail)
                    Spacer(modifier = Modifier.height(12.dp))
                    RecentOrdersSection(detail.recentOrders, onOpenOrder)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun UserHeader(user: AdminUserUi) {
    OrderCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserInitial(name = user.name, size = 56.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = user.mobileMasked,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TagFlow(modifier = Modifier.padding(top = 12.dp)) {
            ProfileTag(text = user.role, color = HawkIndigo)
            UserStatusTagItems(isVerified = user.isVerified, isActive = user.isActive)
        }
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "User ID", value = user.id.toString())
        DetailRow(label = "Joined", value = DisplayFormat.dateTime(user.dateJoined))
    }
}

@Composable
private fun ActivitySection(detail: AdminUserDetailUi) {
    OrderCard(tag = "ACTIVITY") {
        DetailRow(label = "Orders", value = detail.ordersTotal.toString(), emphasized = true)
        detail.orderCounts.forEach { (status, count) -> CountRow(count = count) { OrderStatusChip(status = status) } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "Pay-ins",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (detail.payInCounts.isEmpty()) {
            Text(
                text = "No pay-ins submitted yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        detail.payInCounts.forEach { (status, count) -> CountRow(count = count) { PayInStatusChip(status = status) } }
    }
}

@Composable
private fun CountRow(count: Int, chip: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f)) { chip() }
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RecentOrdersSection(orders: List<AdminUserOrderUi>, onOpenOrder: (Long) -> Unit) {
    OrderCard(tag = "RECENT ORDERS") {
        if (orders.isEmpty()) {
            Text(
                text = "This member hasn't claimed any orders yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        orders.forEachIndexed { index, order ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
            Column(modifier = Modifier.clickable { onOpenOrder(order.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = order.reference,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    OrderStatusChip(status = order.status)
                }
                Text(
                    text = DisplayFormat.dateTime(order.claimedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompactAmountRow(
                    Triple("You Pay", order.orderAmount, false),
                    Triple("Income", order.incomeAmount, false),
                    Triple("Payout Target", order.payoutTarget, true)
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun AdminUserDetailPreview() {
    Hawk_WalletTheme {
        AdminUserDetailContent(
            uiState = AdminUserDetailUiState(
                detail = AdminUserDetailUi(
                    user = previewUser(),
                    ordersTotal = 3,
                    orderCounts = listOf(OrderStatus.ACTIVE to 1, OrderStatus.COMPLETED to 2),
                    payInCounts = listOf(PayInStatus.CONFIRMED to 3),
                    recentOrders = listOf(
                        AdminUserOrderUi(7, "HW-3KD9QX2M7P", OrderStatus.ACTIVE, "255.00", "10.00", "265.00", "2026-09-26T10:00:00Z")
                    )
                )
            ),
            onBackClick = {},
            onRefresh = {},
            onOpenOrder = {}
        )
    }
}
