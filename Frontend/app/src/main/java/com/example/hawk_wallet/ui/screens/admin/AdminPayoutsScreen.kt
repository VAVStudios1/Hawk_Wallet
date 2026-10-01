package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.example.hawk_wallet.data.remote.model.AdminPayoutItemDto
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.PayoutStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Payout filters; [apiStatus] is the backend's exact Payout.Status value
 * (null = all). FAILED exists in the model but no backend flow sets it, so
 * it has no tab; such a payout still shows under All.
 */
enum class AdminPayoutFilter(val apiStatus: String?, val label: String) {
    PENDING("PENDING", "Pending"),
    PAID("PAID", "Paid"),
    CANCELLED("CANCELLED", "Cancelled"),
    ALL(null, "All");

    companion object {
        /** A route argument (backend status value) to its filter; unknown or absent -> Pending. */
        fun fromApi(value: String?): AdminPayoutFilter =
            if (value == null) PENDING else entries.firstOrNull { it.apiStatus == value } ?: PENDING
    }
}

/** A payout as the backend sent it; the detail-only fields stay null / blank in the list. */
data class AdminPayoutRowUi(
    val id: Long,
    val status: PayoutStatus,
    val amount: String,
    val providerCode: String,
    val providerName: String,
    val createdAt: String,
    val paidAt: String?,
    val orderId: Long,
    val orderReference: String,
    val orderStatus: OrderStatus,
    val payoutTarget: String,
    val payoutCommittedTotal: String,
    val payoutPaidTotal: String,
    val remainingToCommit: String,
    val memberId: Long,
    val memberName: String,
    val memberMobileMasked: String,
    /** Detail only (audited). */
    val upiId: String? = null,
    val utr: String = "",
    val cancellationReason: String = "",
    val createdByName: String? = null,
    val paidByName: String? = null
)

internal fun AdminPayoutItemDto.toUi() = AdminPayoutRowUi(
    id = id,
    status = PayoutStatus.fromApi(status),
    amount = amount,
    providerCode = provider_code,
    providerName = provider_name,
    createdAt = created_at,
    paidAt = paid_at,
    orderId = order.id,
    orderReference = order.reference,
    orderStatus = OrderStatus.fromApi(order.status),
    payoutTarget = order.payout_target,
    payoutCommittedTotal = order.payout_committed_total,
    payoutPaidTotal = order.payout_paid_total,
    remainingToCommit = order.remaining_to_commit,
    memberId = member.id,
    memberName = member.name,
    memberMobileMasked = member.mobile_number_masked,
    upiId = upi_id,
    utr = utr.orEmpty(),
    cancellationReason = cancellation_reason.orEmpty(),
    createdByName = created_by_name,
    paidByName = paid_by_name
)

/** Every payout, one server page at a time, from GET /api/admin/payouts/. */
class AdminPayoutsViewModel(
    private val repository: AdminOrderRepository,
    initialFilter: AdminPayoutFilter = AdminPayoutFilter.PENDING
) : AdminListViewModel<AdminPayoutRowUi, AdminPayoutFilter>(initialFilter) {

    override suspend fun fetch(filter: AdminPayoutFilter, search: String, page: Int): ApiResult<AdminPage<AdminPayoutRowUi>> =
        when (val result = repository.payouts(filter.apiStatus, search, page)) {
            is ApiResult.Success -> ApiResult.Success(
                AdminPage(result.data.payouts.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                result.message
            )
            is ApiResult.Error -> result
        }

    override fun idOf(item: AdminPayoutRowUi): Long = item.id
}

private val PAYOUT_TEXTS = AdminListTexts(
    title = "Payouts",
    searchPlaceholder = "Reference, member, mobile or UTR",
    emptyTitle = "No payouts",
    emptyMessage = "Payouts appear here once an admin creates them on an order. Pull down to refresh.",
    errorTitle = "Couldn't load payouts"
)

@Composable
fun AdminPayoutsScreen(
    viewModel: AdminPayoutsViewModel,
    onBackClick: () -> Unit,
    onPayoutClick: (payoutId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. back from marking one paid.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminPayoutsContent(
        state = uiState,
        onBackClick = onBackClick,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onPayoutClick = onPayoutClick
    )
}

@Composable
private fun AdminPayoutsContent(
    state: AdminListUiState<AdminPayoutRowUi, AdminPayoutFilter>,
    onBackClick: () -> Unit,
    onFilterSelect: (AdminPayoutFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPayoutClick: (Long) -> Unit
) {
    AdminListScaffold(
        state = state,
        texts = PAYOUT_TEXTS,
        filters = AdminPayoutFilter.entries,
        filterLabel = { it.label },
        onBackClick = onBackClick,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id }
    ) { payout ->
        PayoutCard(payout = payout, onClick = { onPayoutClick(payout.id) })
    }
}

/** Order reference + status, member, amount, provider and creation time. */
@Composable
private fun PayoutCard(payout: AdminPayoutRowUi, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = payout.orderReference,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            PayoutStatusChip(status = payout.status)
        }
        MemberLine(name = payout.memberName, maskedMobile = payout.memberMobileMasked, modifier = Modifier.padding(top = 2.dp))
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            CompactAmount(label = "Amount", amount = payout.amount, emphasized = true, modifier = Modifier.weight(1f))
            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    text = "To ${payout.providerName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "Created ${DisplayFormat.dateTime(payout.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

internal fun previewPayoutRow(id: Long = 3, status: PayoutStatus = PayoutStatus.PENDING) = AdminPayoutRowUi(
    id = id,
    status = status,
    amount = "50.00",
    providerCode = "PHONEPE",
    providerName = "PhonePe",
    createdAt = "2026-09-26T12:00:00Z",
    paidAt = null,
    orderId = 7,
    orderReference = "HW-3KD9QX2M7P",
    orderStatus = OrderStatus.PAYOUT_IN_PROGRESS,
    payoutTarget = "265.00",
    payoutCommittedTotal = "50.00",
    payoutPaidTotal = "0.00",
    remainingToCommit = "215.00",
    memberId = 12,
    memberName = "Ravi Shah",
    memberMobileMasked = "******1234"
)

@Preview(showBackground = true)
@Composable
private fun AdminPayoutsPreview() {
    Hawk_WalletTheme {
        AdminPayoutsContent(
            state = AdminListUiState(
                filter = AdminPayoutFilter.PENDING,
                items = listOf(previewPayoutRow(), previewPayoutRow(4)),
                loaded = true,
                total = 2
            ),
            onBackClick = {},
            onFilterSelect = {},
            onSearchInputChange = {},
            onSearch = {},
            onClearSearch = {},
            onRefresh = {},
            onLoadMore = {},
            onPayoutClick = {}
        )
    }
}
