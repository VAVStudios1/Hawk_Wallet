package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.AdminReportDto
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.components.PayoutStatusChip
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The report periods the backend supports; [apiValue] is sent as `range`. */
enum class ReportRange(val apiValue: String, val label: String) {
    TODAY("today", "Today"),
    LAST_7_DAYS("7d", "Last 7 Days"),
    LAST_30_DAYS("30d", "Last 30 Days"),
    CUSTOM("custom", "Custom")
}

/** The selected period: a preset, or CUSTOM with both YYYY-MM-DD dates. */
data class ReportPeriodSelection(
    val range: ReportRange = ReportRange.TODAY,
    val startDate: String? = null,
    val endDate: String? = null
)

data class AdminReportsUiState(
    val selection: ReportPeriodSelection = ReportPeriodSelection(),
    /** Null until the backend answered for the current selection - never fake zeros. */
    val report: AdminReportDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** GET /api/admin/reports/: every figure is computed by the backend for the chosen period. */
class AdminReportsViewModel(private val repository: AdminManagementRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminReportsUiState())
    val uiState: StateFlow<AdminReportsUiState> = _uiState

    private var job: Job? = null
    private var generation = 0

    fun refresh() {
        job?.cancel()
        val current = ++generation
        val selection = _uiState.value.selection
        _uiState.update { it.copy(isLoading = true, error = null) }
        job = viewModelScope.launch {
            val result = repository.report(selection.range.apiValue, selection.startDate, selection.endDate)
            if (current != generation) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, report = result.data) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }

    fun selectPreset(range: ReportRange) {
        if (range == ReportRange.CUSTOM) return
        select(ReportPeriodSelection(range))
    }

    fun selectCustom(startDate: String, endDate: String) =
        select(ReportPeriodSelection(ReportRange.CUSTOM, startDate, endDate))

    private fun select(selection: ReportPeriodSelection) {
        if (selection == _uiState.value.selection) return
        // A different period: drop the old figures rather than show them under the new label.
        _uiState.update { it.copy(selection = selection, report = null) }
        refresh()
    }
}

@Composable
fun AdminReportsScreen(viewModel: AdminReportsViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    AdminReportsContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onPresetSelect = viewModel::selectPreset,
        onCustomSelect = viewModel::selectCustom
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminReportsContent(
    uiState: AdminReportsUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onPresetSelect: (ReportRange) -> Unit,
    onCustomSelect: (String, String) -> Unit
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    if (showDatePicker) {
        AdminDateRangeDialog(
            onDismiss = { showDatePicker = false },
            onConfirm = { start, end ->
                showDatePicker = false
                onCustomSelect(start, end)
            }
        )
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Reports", onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AdminFilterChips(
                filters = ReportRange.entries,
                selected = uiState.selection.range,
                label = { it.label },
                onSelect = { range -> if (range == ReportRange.CUSTOM) showDatePicker = true else onPresetSelect(range) }
            )
            val report = uiState.report
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && report != null,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                when {
                    report == null && uiState.error != null -> CenteredMessage(
                        title = "Couldn't load the report",
                        message = uiState.error,
                        actionLabel = "Retry",
                        onAction = onRefresh
                    )
                    report == null -> CenteredLoading()
                    else -> ReportBody(report = report, error = uiState.error, onRetry = onRefresh)
                }
            }
        }
    }
}

@Composable
private fun ReportBody(report: AdminReportDto, error: String?, onRetry: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (error != null) {
            item {
                Column {
                    AuthErrorBanner(message = error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        item {
            Text(
                text = "${displayIsoDateRange(report.start_date, report.end_date)} · ${report.timezone}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Each section counts what was created in this period, by its current status.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            ReportSection(title = "ORDERS", totalLabel = "Orders claimed", total = report.orders.total) {
                OrderStatus.entries.forEach { status ->
                    report.orders.by_status[status.name]?.takeIf { it > 0 }?.let { StatusCount(it) { OrderStatusChip(status) } }
                }
            }
        }
        item {
            ReportSection(title = "PAY-INS", totalLabel = "Pay-ins submitted", total = report.pay_ins.total) {
                PayInStatus.entries.forEach { status ->
                    report.pay_ins.by_status[status.name]?.takeIf { it > 0 }?.let { StatusCount(it) { PayInStatusChip(status) } }
                }
                ReportDivider()
                AmountRow(label = "Confirmed amount", amount = report.pay_ins.confirmed_amount, emphasized = true)
            }
        }
        item {
            ReportSection(title = "PAYOUTS", totalLabel = "Payouts created", total = report.payouts.total) {
                PayoutStatus.entries.forEach { status ->
                    report.payouts.by_status[status.name]?.takeIf { it > 0 }?.let { StatusCount(it) { PayoutStatusChip(status) } }
                }
                ReportDivider()
                AmountRow(label = "Paid amount", amount = report.payouts.paid_amount, emphasized = true)
                AmountRow(label = "Pending amount", amount = report.payouts.pending_amount)
            }
        }
        item {
            OrderCard(tag = "MEMBERS") {
                DetailRow(label = "New members", value = report.members.new.toString(), emphasized = true)
                DetailRow(label = "of whom verified", value = report.members.verified.toString())
                DetailRow(label = "of whom active", value = report.members.active.toString())
            }
        }
        item {
            OrderCard(tag = "SUPPORT") {
                DetailRow(label = "Conversations started", value = report.support.total.toString(), emphasized = true)
                DetailRow(label = "Still open", value = (report.support.by_status["OPEN"] ?: 0).toString())
                DetailRow(label = "Closed", value = (report.support.by_status["CLOSED"] ?: 0).toString())
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun ReportSection(title: String, totalLabel: String, total: Int, content: @Composable () -> Unit) {
    OrderCard(tag = title) {
        DetailRow(label = totalLabel, value = total.toString(), emphasized = true)
        if (total == 0) {
            Text(
                text = "None in this period.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun StatusCount(count: Int, chip: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun ReportDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun AdminReportsPreview() {
    Hawk_WalletTheme {
        AdminReportsContent(
            uiState = AdminReportsUiState(
                selection = ReportPeriodSelection(ReportRange.LAST_7_DAYS),
                report = AdminReportDto(
                    range = "7d", start_date = "2026-09-25", end_date = "2026-10-01", timezone = "UTC",
                    orders = com.example.hawk_wallet.data.remote.model.CountsByStatusDto(4, mapOf("ACTIVE" to 2, "COMPLETED" to 2)),
                    pay_ins = com.example.hawk_wallet.data.remote.model.PayInReportDto(3, mapOf("CONFIRMED" to 3), "765.00"),
                    payouts = com.example.hawk_wallet.data.remote.model.PayoutReportDto(2, mapOf("PAID" to 1, "PENDING" to 1), "265.00", "50.00"),
                    members = com.example.hawk_wallet.data.remote.model.MemberReportDto(5, 4, 5),
                    support = com.example.hawk_wallet.data.remote.model.CountsByStatusDto(2, mapOf("OPEN" to 1, "CLOSED" to 1))
                )
            ),
            onBackClick = {},
            onRefresh = {},
            onPresetSelect = {},
            onCustomSelect = { _, _ -> }
        )
    }
}
