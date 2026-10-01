package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.AdminOrderRepository
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.components.PayoutStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.PayoutDestination
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class PayoutDetailDialog { MARK_PAID, CANCEL, QR }

data class AdminPayoutDetailUiState(
    val payout: AdminPayoutRowUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val dialog: PayoutDetailDialog? = null,
    val utr: String = "",
    val utrError: String? = null,
    val cancellationReason: String = "",
    val cancellationReasonError: String? = null,
    val isSubmitting: Boolean = false,
    /** Shown inside the open dialog. */
    val actionError: String? = null,
    /** QR bytes exactly as the authorized endpoint returned them. */
    val qrImage: ByteArray? = null,
    val isQrLoading: Boolean = false,
    val qrError: String? = null,
    val message: String? = null
) {
    /** Only a pending payout can be marked paid or cancelled; the backend enforces this too. */
    val isPending: Boolean
        get() = payout?.status == PayoutStatus.PENDING
}

/**
 * One payout, from GET /api/admin/payouts/{id}/ (audited by the backend),
 * with Mark Paid / Cancel / View QR through the existing payout endpoints.
 * Same rules as Order Details: one idempotency key per attempt, a new key
 * when a dialog opens or its input changes, stale-state errors close the
 * dialog and reload, and nothing is calculated here.
 */
class AdminPayoutDetailViewModel(
    private val repository: AdminOrderRepository,
    private val payoutId: Long,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminPayoutDetailUiState())
    val uiState: StateFlow<AdminPayoutDetailUiState> = _uiState

    private var actionKey: String = newIdempotencyKey()

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.payout(payoutId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, payout = result.data.toUi()) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }

    fun openMarkPaid() {
        if (_uiState.value.isPending) openDialog(PayoutDetailDialog.MARK_PAID)
    }

    fun openCancel() {
        if (_uiState.value.isPending) openDialog(PayoutDetailDialog.CANCEL)
    }

    private fun openDialog(dialog: PayoutDetailDialog) {
        if (_uiState.value.isSubmitting) return
        actionKey = newIdempotencyKey()
        _uiState.update {
            it.copy(
                dialog = dialog,
                utr = "", utrError = null,
                cancellationReason = "", cancellationReasonError = null,
                actionError = null,
                qrImage = null, isQrLoading = false, qrError = null
            )
        }
    }

    fun dismissDialog() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(dialog = null, actionError = null, qrImage = null, isQrLoading = false, qrError = null) }
    }

    fun onUtrChange(value: String) {
        if (value == _uiState.value.utr) return
        actionKey = newIdempotencyKey()
        _uiState.update { it.copy(utr = value, utrError = null) }
    }

    fun onCancellationReasonChange(value: String) {
        if (value == _uiState.value.cancellationReason) return
        actionKey = newIdempotencyKey()
        _uiState.update { it.copy(cancellationReason = value, cancellationReasonError = null) }
    }

    fun markPaid() {
        val state = _uiState.value
        if (state.dialog != PayoutDetailDialog.MARK_PAID || state.isSubmitting) return
        val error = payoutUtrError(state.utr)
        if (error != null) {
            _uiState.update { it.copy(utrError = error) }
            return
        }
        submit("Payout marked paid.") { repository.markPayoutPaid(payoutId, state.utr, actionKey) }
    }

    fun cancel() {
        val state = _uiState.value
        if (state.dialog != PayoutDetailDialog.CANCEL || state.isSubmitting) return
        val error = cancellationReasonError(state.cancellationReason)
        if (error != null) {
            _uiState.update { it.copy(cancellationReasonError = error) }
            return
        }
        submit("Payout cancelled.") { repository.cancelPayout(payoutId, state.cancellationReason, actionKey) }
    }

    /** The destination's QR through the authorized, audited endpoint. */
    fun openQr() {
        if (_uiState.value.payout == null) return
        openDialog(PayoutDetailDialog.QR)
        _uiState.update { it.copy(isQrLoading = true) }
        viewModelScope.launch {
            val result = repository.payoutQr(payoutId)
            // Ignore a late reply if the dialog was closed meanwhile.
            if (_uiState.value.dialog != PayoutDetailDialog.QR) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update { it.copy(isQrLoading = false, qrImage = result.data) }
                is ApiResult.Error -> _uiState.update { it.copy(isQrLoading = false, qrError = result.userMessage()) }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun submit(successMessage: String, call: suspend () -> ApiResult<*>) {
        _uiState.update { it.copy(isSubmitting = true, actionError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    actionKey = newIdempotencyKey()
                    _uiState.update { it.copy(isSubmitting = false, dialog = null, message = successMessage) }
                    refresh()
                }
                is ApiResult.Error -> when (result.code) {
                    in STALE_PAYOUT_CODES -> {
                        _uiState.update { it.copy(isSubmitting = false, dialog = null, message = result.message) }
                        refresh()
                    }
                    else -> _uiState.update { it.copy(isSubmitting = false, actionError = result.userMessage()) }
                }
            }
        }
    }
}

@Composable
fun AdminPayoutDetailScreen(
    viewModel: AdminPayoutDetailViewModel,
    onBackClick: () -> Unit,
    onOpenOrder: (orderId: Long) -> Unit,
    onOpenUser: (userId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Loads once per visit; the backend audits each load.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val payout = uiState.payout
    if (payout != null) {
        when (uiState.dialog) {
            PayoutDetailDialog.MARK_PAID -> MarkPayoutPaidDialog(
                amount = payout.amount,
                upiId = payout.upiId.orEmpty(),
                utr = uiState.utr,
                utrError = uiState.utrError,
                isSubmitting = uiState.isSubmitting,
                error = uiState.actionError,
                onUtrChange = viewModel::onUtrChange,
                onMarkPaid = viewModel::markPaid,
                onDismiss = viewModel::dismissDialog
            )
            PayoutDetailDialog.CANCEL -> CancelPayoutDialog(
                amount = payout.amount,
                reason = uiState.cancellationReason,
                reasonError = uiState.cancellationReasonError,
                isSubmitting = uiState.isSubmitting,
                error = uiState.actionError,
                onReasonChange = viewModel::onCancellationReasonChange,
                onCancel = viewModel::cancel,
                onDismiss = viewModel::dismissDialog
            )
            PayoutDetailDialog.QR -> PayoutQrDialog(
                isLoading = uiState.isQrLoading,
                error = uiState.qrError,
                image = uiState.qrImage,
                onDismiss = viewModel::dismissDialog
            )
            null -> Unit
        }
    }

    AdminPayoutDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onOpenOrder = onOpenOrder,
        onOpenUser = onOpenUser,
        onOpenQr = viewModel::openQr,
        onOpenMarkPaid = viewModel::openMarkPaid,
        onOpenCancel = viewModel::openCancel,
        onMessageShown = viewModel::messageShown
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminPayoutDetailContent(
    uiState: AdminPayoutDetailUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOrder: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
    onOpenQr: () -> Unit,
    onOpenMarkPaid: () -> Unit,
    onOpenCancel: () -> Unit,
    onMessageShown: () -> Unit
) {
    val payout = uiState.payout
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = payout?.let { "Payout #${it.id}" } ?: "Payout", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.isPending) {
                AdminActionBar(
                    secondaryLabel = "Cancel",
                    onSecondary = onOpenCancel,
                    primaryLabel = "Mark Paid",
                    onPrimary = onOpenMarkPaid
                )
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            payout == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this payout",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            payout == null -> CenteredLoading(modifier)
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
                    PayoutSummarySection(payout)
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "DESTINATION · SELL", accent = HawkEmerald) {
                        PayoutDestination(
                            method = OrderPayoutMethodUiModel(payout.providerCode, payout.providerName, payout.upiId.orEmpty())
                        )
                        CardLinkRow(text = "View QR", onClick = onOpenQr)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "ORDER") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = payout.orderReference,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            OrderStatusChip(status = payout.orderStatus)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        AmountRow(label = "Payout Target", amount = payout.payoutTarget)
                        AmountRow(label = "Total paid", amount = payout.payoutPaidTotal)
                        AmountRow(label = "Committed (pending + paid)", amount = payout.payoutCommittedTotal)
                        AmountRow(label = "Remaining to commit", amount = payout.remainingToCommit, emphasized = true)
                        CardLinkRow(text = "Open order", onClick = { onOpenOrder(payout.orderId) })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "USER") {
                        DetailRow(label = "Name", value = payout.memberName)
                        DetailRow(label = "Mobile", value = payout.memberMobileMasked)
                        CardLinkRow(text = "View user", onClick = { onOpenUser(payout.memberId) })
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PayoutSummarySection(payout: AdminPayoutRowUi) {
    OrderCard(tag = "PAYOUT") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DisplayFormat.rupees(payout.amount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PayoutStatusChip(status = payout.status)
        }
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Created", value = DisplayFormat.dateTime(payout.createdAt))
        payout.createdByName?.let { DetailRow(label = "Created by", value = it) }
        if (payout.utr.isNotBlank()) DetailRow(label = "UTR", value = payout.utr)
        payout.paidAt?.let { DetailRow(label = "Paid", value = DisplayFormat.dateTime(it)) }
        payout.paidByName?.let { DetailRow(label = "Paid by", value = it) }
        if (payout.status == PayoutStatus.CANCELLED) {
            Text(
                text = if (payout.cancellationReason.isNotBlank()) "Cancelled: ${payout.cancellationReason}" else "Cancelled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun AdminPayoutDetailPreview() {
    Hawk_WalletTheme {
        AdminPayoutDetailContent(
            uiState = AdminPayoutDetailUiState(payout = previewPayoutRow().copy(upiId = "ravi@ybl", createdByName = "Admin One")),
            onBackClick = {},
            onRefresh = {},
            onOpenOrder = {},
            onOpenUser = {},
            onOpenQr = {},
            onOpenMarkPaid = {},
            onOpenCancel = {},
            onMessageShown = {}
        )
    }
}
