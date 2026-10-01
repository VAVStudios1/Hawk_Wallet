package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.remote.model.AdminWorkloadRowDto
import com.example.hawk_wallet.data.remote.model.SystemReportDto
import com.example.hawk_wallet.data.remote.model.TotalActiveDto
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.screens.profile.ProfileTag
import com.example.hawk_wallet.ui.screens.profile.roleLabel
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemReportUiState(
    /** Null until the backend answered - never fake zeros. */
    val report: SystemReportDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** GET /api/admin/system-report/ (SUPER_ADMIN only): platform totals and each admin's workload. */
class SystemReportViewModel(private val repository: AdminManagementRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemReportUiState())
    val uiState: StateFlow<SystemReportUiState> = _uiState

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.systemReport()) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, report = result.data) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }
}

@Composable
fun SystemReportScreen(viewModel: SystemReportViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    SystemReportContent(uiState = uiState, onBackClick = onBackClick, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemReportContent(uiState: SystemReportUiState, onBackClick: () -> Unit, onRefresh: () -> Unit) {
    val report = uiState.report
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "System Reports", onBackClick = onBackClick) }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            report == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load the system report",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            report == null -> CenteredLoading(modifier)
            else -> PullToRefreshBox(isRefreshing = uiState.isLoading, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    uiState.error?.let { error ->
                        item {
                            Column {
                                AuthErrorBanner(message = error)
                                TextButton(onClick = onRefresh) { Text("Retry") }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TotalActiveCard("ADMINS", report.admins, Modifier.weight(1f))
                            TotalActiveCard("MEMBERS", report.members, Modifier.weight(1f))
                        }
                    }
                    item {
                        OrderCard(tag = "PLATFORM NOW") {
                            DetailRow(label = "Active orders", value = report.active_orders.toString())
                            DetailRow(label = "Pending pay-ins (under review)", value = report.pending_payins.toString())
                            DetailRow(label = "Pending payouts", value = report.pending_payouts.toString())
                            DetailRow(label = "Open support conversations", value = report.open_support.toString())
                            if (report.stranded_open_support > 0) {
                                AuthErrorBanner(
                                    title = "${report.stranded_open_support} open chat(s) have no active admin",
                                    message = "Their admin was deactivated and no other active admin was available. " +
                                        "Add or reactivate an admin to answer them.",
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                    item {
                        Column {
                            Text(
                                text = "Admin workload",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Listed by name. Counts only what is attributed to each admin; members, orders and " +
                                    "pending pay-ins are shared by all admins.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (report.workload.isEmpty()) {
                        item {
                            Text(
                                text = "No admin accounts yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(report.workload, key = { it.id }) { row -> WorkloadRowCard(row) }
                }
            }
        }
    }
}

@Composable
private fun TotalActiveCard(title: String, counts: TotalActiveDto, modifier: Modifier = Modifier) {
    OrderCard(modifier = modifier, tag = title) {
        Text(
            text = counts.total.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "${counts.active} active",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkloadRowCard(row: AdminWorkloadRowDto) {
    OrderCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ProfileTag(text = roleLabel(row.role), color = if (isSuperAdmin(row.role)) HawkVioletBlue else HawkIndigo)
            if (!row.is_active) {
                Spacer(modifier = Modifier.width(6.dp))
                ProfileTag(text = "Deactivated", color = HawkCoral)
            }
        }
        WorkloadStrip(
            workload = AdminWorkloadUi(
                openSupport = row.open_support_conversations,
                pendingPayoutsCreated = row.pending_payouts_created,
                payInsReviewed = row.pay_ins_reviewed,
                payoutsPaid = row.payouts_paid
            ),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun SystemReportPreview() {
    Hawk_WalletTheme {
        SystemReportContent(
            uiState = SystemReportUiState(
                report = SystemReportDto(
                    admins = TotalActiveDto(3, 2),
                    members = TotalActiveDto(128, 120),
                    active_orders = 14,
                    pending_payins = 3,
                    pending_payouts = 5,
                    open_support = 2,
                    stranded_open_support = 0,
                    workload = listOf(
                        AdminWorkloadRowDto(2, "Neha Admin", "ADMIN", true, 2, 1, 12, 8),
                        AdminWorkloadRowDto(1, "Jordan", "SUPER_ADMIN", true, 0, 0, 3, 1)
                    )
                )
            ),
            onBackClick = {},
            onRefresh = {}
        )
    }
}
