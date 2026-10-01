package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminManagementRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuditEventDetailUiState(
    val event: AuditEventUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** One audit event, from GET /api/admin/audit-events/{id}/. Read-only. */
class AuditEventDetailViewModel(
    private val repository: AdminManagementRepository,
    private val eventId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuditEventDetailUiState())
    val uiState: StateFlow<AuditEventDetailUiState> = _uiState

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.auditEvent(eventId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, event = result.data.toUi()) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }
}

@Composable
fun AuditEventDetailScreen(viewModel: AuditEventDetailViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    AuditEventDetailContent(uiState = uiState, onBackClick = onBackClick, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuditEventDetailContent(uiState: AuditEventDetailUiState, onBackClick: () -> Unit, onRefresh: () -> Unit) {
    val event = uiState.event
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Audit Event", onBackClick = onBackClick) }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            event == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this event",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            event == null -> CenteredLoading(modifier)
            else -> PullToRefreshBox(isRefreshing = uiState.isLoading, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
                ) {
                    uiState.error?.let { AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 12.dp)) }
                    OrderCard(tag = "EVENT") {
                        Text(
                            text = event.actionLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        DetailRow(label = "Action", value = event.action)
                        DetailRow(label = "When", value = DisplayFormat.dateTime(event.createdAt))
                        DetailRow(label = "Actor", value = event.actorText)
                        event.actorMobileMasked?.let { DetailRow(label = "Actor mobile", value = it) }
                        DetailRow(label = "Target", value = event.target)
                        DetailRow(label = "IP address", value = event.ipAddress ?: "Not recorded")
                        DetailRow(label = "Event ID", value = event.id.toString())
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "DETAILS") {
                        if (event.metadata.isEmpty()) {
                            Text(
                                text = "No further details were recorded.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        event.metadata.forEach { (key, value) -> DetailRow(label = key, value = value) }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AuditEventDetailPreview() {
    Hawk_WalletTheme {
        AuditEventDetailContent(
            uiState = AuditEventDetailUiState(
                event = AuditEventUi(
                    1, "ADMIN_DEACTIVATED", "Admin deactivated", "Jordan (Super Admin)", "******1111",
                    "core.User #12", "2026-10-01T09:00:00Z", "10.0.0.2",
                    listOf("role" to "ADMIN", "revoked_refresh_tokens" to "1")
                )
            ),
            onBackClick = {},
            onRefresh = {}
        )
    }
}
