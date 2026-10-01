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
import com.example.hawk_wallet.data.remote.model.AdminPayInItemDto
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.BuyMethodSummaryUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** Pay-In Review filters; [apiStatus] is the backend's exact PayIn.Status value (null = all). */
enum class AdminPayInFilter(val apiStatus: String?, val label: String) {
    UNDER_REVIEW("UNDER_REVIEW", "Under Review"),
    CONFIRMED("CONFIRMED", "Confirmed"),
    REJECTED("REJECTED", "Rejected"),
    ALL(null, "All");

    companion object {
        /** A route argument (backend status value) to its filter; unknown or absent -> Under Review. */
        fun fromApi(value: String?): AdminPayInFilter =
            if (value == null) UNDER_REVIEW else entries.firstOrNull { it.apiStatus == value } ?: UNDER_REVIEW
    }
}

/** A pay-in as the backend sent it; the detail-only fields stay null / blank in the list. */
data class AdminPayInRowUi(
    val id: Long,
    val status: PayInStatus,
    val amount: String,
    val utr: String,
    val submittedAt: String,
    val reviewedAt: String?,
    val orderId: Long,
    val orderReference: String,
    val orderStatus: OrderStatus,
    val orderAmount: String,
    val payoutTarget: String,
    val memberId: Long,
    val memberName: String,
    val memberMobileMasked: String,
    /** Detail only: the receiving account the member says they paid. */
    val payeeVpa: String? = null,
    /** Detail only: the member's own BUY account; null on older pay-ins. */
    val paidFrom: BuyMethodSummaryUiModel? = null,
    val rejectionReason: String = "",
    val reviewedByName: String? = null
)

internal fun AdminPayInItemDto.toUi() = AdminPayInRowUi(
    id = id,
    status = PayInStatus.fromApi(status),
    amount = amount,
    utr = utr,
    submittedAt = submitted_at,
    reviewedAt = reviewed_at,
    orderId = order.id,
    orderReference = order.reference,
    orderStatus = OrderStatus.fromApi(order.status),
    orderAmount = order.order_amount,
    payoutTarget = order.payout_target,
    memberId = member.id,
    memberName = member.name,
    memberMobileMasked = member.mobile_number_masked,
    payeeVpa = payee_vpa,
    paidFrom = buy_method?.let { BuyMethodSummaryUiModel(it.provider_code, it.provider_name, it.label, it.upi_id) },
    rejectionReason = rejection_reason.orEmpty(),
    reviewedByName = reviewed_by_name
)

/** Every pay-in, one server page at a time, from GET /api/admin/pay-ins/. */
class AdminPayInsViewModel(
    private val repository: AdminOrderRepository,
    initialFilter: AdminPayInFilter = AdminPayInFilter.UNDER_REVIEW
) : AdminListViewModel<AdminPayInRowUi, AdminPayInFilter>(initialFilter) {

    override suspend fun fetch(filter: AdminPayInFilter, search: String, page: Int): ApiResult<AdminPage<AdminPayInRowUi>> =
        when (val result = repository.payIns(filter.apiStatus, search, page)) {
            is ApiResult.Success -> ApiResult.Success(
                AdminPage(result.data.pay_ins.map { it.toUi() }, result.data.page, result.data.has_next, result.data.total),
                result.message
            )
            is ApiResult.Error -> result
        }

    override fun idOf(item: AdminPayInRowUi): Long = item.id
}

private val PAY_IN_TEXTS = AdminListTexts(
    title = "Pay-In Review",
    searchPlaceholder = "Reference, member, mobile or UTR",
    emptyTitle = "No pay-ins",
    emptyMessage = "Pay-ins appear here as soon as members submit a UTR. Pull down to refresh.",
    errorTitle = "Couldn't load pay-ins"
)

@Composable
fun AdminPayInsScreen(
    viewModel: AdminPayInsViewModel,
    onBackClick: () -> Unit,
    onPayInClick: (payInId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. back from reviewing one.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminPayInsContent(
        state = uiState,
        onBackClick = onBackClick,
        onFilterSelect = viewModel::selectFilter,
        onSearchInputChange = viewModel::onSearchInputChange,
        onSearch = viewModel::submitSearch,
        onClearSearch = viewModel::clearSearch,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onPayInClick = onPayInClick
    )
}

@Composable
private fun AdminPayInsContent(
    state: AdminListUiState<AdminPayInRowUi, AdminPayInFilter>,
    onBackClick: () -> Unit,
    onFilterSelect: (AdminPayInFilter) -> Unit,
    onSearchInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPayInClick: (Long) -> Unit
) {
    AdminListScaffold(
        state = state,
        texts = PAY_IN_TEXTS,
        filters = AdminPayInFilter.entries,
        filterLabel = { it.label },
        onBackClick = onBackClick,
        onFilterSelect = onFilterSelect,
        onSearchInputChange = onSearchInputChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onRefresh = onRefresh,
        onLoadMore = onLoadMore,
        itemKey = { it.id }
    ) { payIn ->
        PayInCard(payIn = payIn, onClick = { onPayInClick(payIn.id) })
    }
}

/** Order reference + status, member, amount, UTR and submission time. */
@Composable
private fun PayInCard(payIn: AdminPayInRowUi, onClick: () -> Unit) {
    OrderCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = payIn.orderReference,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            PayInStatusChip(status = payIn.status)
        }
        MemberLine(name = payIn.memberName, maskedMobile = payIn.memberMobileMasked, modifier = Modifier.padding(top = 2.dp))
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            CompactAmount(label = "Amount", amount = payIn.amount, emphasized = true, modifier = Modifier.weight(1f))
            Column(modifier = Modifier.weight(1.4f)) {
                Text(
                    text = "UTR ${payIn.utr}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "Submitted ${DisplayFormat.dateTime(payIn.submittedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

internal fun previewPayIn(id: Long = 9, status: PayInStatus = PayInStatus.UNDER_REVIEW) = AdminPayInRowUi(
    id = id,
    status = status,
    amount = "255.00",
    utr = "012345678901",
    submittedAt = "2026-09-26T10:05:00Z",
    reviewedAt = null,
    orderId = 7,
    orderReference = "HW-3KD9QX2M7P",
    orderStatus = OrderStatus.PAYMENT_UNDER_REVIEW,
    orderAmount = "255.00",
    payoutTarget = "265.00",
    memberId = 12,
    memberName = "Asha Kumar",
    memberMobileMasked = "******3210"
)

@Preview(showBackground = true)
@Composable
private fun AdminPayInsPreview() {
    Hawk_WalletTheme {
        AdminPayInsContent(
            state = AdminListUiState(
                filter = AdminPayInFilter.UNDER_REVIEW,
                items = listOf(previewPayIn(), previewPayIn(10)),
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
            onPayInClick = {}
        )
    }
}
