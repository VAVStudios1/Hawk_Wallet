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
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.BuyMethodSummaryUiModel
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AdminPayInDetailUiState(
    val payIn: AdminPayInRowUi? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val reviewDialog: PayInReviewDialog? = null,
    val rejectionReason: String = "",
    val rejectionReasonError: String? = null,
    val isReviewing: Boolean = false,
    /** Shown inside the open dialog. */
    val reviewError: String? = null,
    /** One-shot feedback (e.g. pay-in confirmed). */
    val message: String? = null
) {
    /** Only a pay-in still under review can be confirmed or rejected; the backend enforces this too. */
    val canReview: Boolean
        get() = payIn?.status == PayInStatus.UNDER_REVIEW
}

/**
 * One pay-in, from GET /api/admin/pay-ins/{id}/ (audited by the backend),
 * with Confirm / Reject through the existing review endpoints. Same
 * idempotency rule as Order Details: one key per decision, a new key when a
 * dialog opens or the reason changes; the pay-in is reloaded after a review.
 */
class AdminPayInDetailViewModel(
    private val repository: AdminOrderRepository,
    private val payInId: Long,
    private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminPayInDetailUiState())
    val uiState: StateFlow<AdminPayInDetailUiState> = _uiState

    private var reviewKey: String = newIdempotencyKey()

    fun refresh() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.payIn(payInId)) {
                is ApiResult.Success -> _uiState.update { it.copy(isLoading = false, payIn = result.data.toUi()) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.userMessage()) }
            }
        }
    }

    fun openConfirm() = openDialog(PayInReviewDialog.CONFIRM)

    fun openReject() = openDialog(PayInReviewDialog.REJECT)

    private fun openDialog(dialog: PayInReviewDialog) {
        if (!_uiState.value.canReview || _uiState.value.isReviewing) return
        reviewKey = newIdempotencyKey()
        _uiState.update {
            it.copy(reviewDialog = dialog, rejectionReason = "", rejectionReasonError = null, reviewError = null)
        }
    }

    fun dismissDialog() {
        if (_uiState.value.isReviewing) return
        _uiState.update { it.copy(reviewDialog = null, reviewError = null, rejectionReasonError = null) }
    }

    fun onRejectionReasonChange(value: String) {
        if (value == _uiState.value.rejectionReason) return
        // A different reason is a different decision: the old key would conflict.
        reviewKey = newIdempotencyKey()
        _uiState.update { it.copy(rejectionReason = value, rejectionReasonError = null) }
    }

    fun confirm() {
        val state = _uiState.value
        if (state.reviewDialog != PayInReviewDialog.CONFIRM || state.isReviewing) return
        review("Pay-in confirmed.") { repository.confirmPayIn(payInId, reviewKey) }
    }

    fun reject() {
        val state = _uiState.value
        if (state.reviewDialog != PayInReviewDialog.REJECT || state.isReviewing) return
        val reason = state.rejectionReason
        val error = rejectionReasonError(reason)
        if (error != null) {
            _uiState.update { it.copy(rejectionReasonError = error) }
            return
        }
        review("Pay-in rejected.") { repository.rejectPayIn(payInId, reason, reviewKey) }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun review(successMessage: String, call: suspend () -> ApiResult<*>) {
        _uiState.update { it.copy(isReviewing = true, reviewError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    reviewKey = newIdempotencyKey()
                    _uiState.update { it.copy(isReviewing = false, reviewDialog = null, message = successMessage) }
                    refresh()
                }
                is ApiResult.Error -> when (result.code) {
                    // Someone already reviewed it: show the current state instead.
                    PAYIN_ALREADY_REVIEWED -> {
                        _uiState.update { it.copy(isReviewing = false, reviewDialog = null, message = result.message) }
                        refresh()
                    }
                    else -> _uiState.update { it.copy(isReviewing = false, reviewError = result.userMessage()) }
                }
            }
        }
    }
}

@Composable
fun AdminPayInDetailScreen(
    viewModel: AdminPayInDetailViewModel,
    onBackClick: () -> Unit,
    onOpenOrder: (orderId: Long) -> Unit,
    onOpenUser: (userId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Loads once per visit; the backend audits each load.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminPayInDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onOpenOrder = onOpenOrder,
        onOpenUser = onOpenUser,
        actions = PayInReviewActions(
            onOpenConfirm = viewModel::openConfirm,
            onOpenReject = viewModel::openReject,
            onDismiss = viewModel::dismissDialog,
            onReasonChange = viewModel::onRejectionReasonChange,
            onConfirm = viewModel::confirm,
            onReject = viewModel::reject,
            onMessageShown = viewModel::messageShown
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminPayInDetailContent(
    uiState: AdminPayInDetailUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenOrder: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
    actions: PayInReviewActions
) {
    val payIn = uiState.payIn
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    if (payIn != null) {
        when (uiState.reviewDialog) {
            PayInReviewDialog.CONFIRM -> ConfirmPayInDialog(
                amount = payIn.amount,
                utr = payIn.utr,
                isSubmitting = uiState.isReviewing,
                error = uiState.reviewError,
                onConfirm = actions.onConfirm,
                onDismiss = actions.onDismiss
            )
            PayInReviewDialog.REJECT -> RejectPayInDialog(
                reason = uiState.rejectionReason,
                reasonError = uiState.rejectionReasonError,
                isSubmitting = uiState.isReviewing,
                error = uiState.reviewError,
                onReasonChange = actions.onReasonChange,
                onReject = actions.onReject,
                onDismiss = actions.onDismiss
            )
            null -> Unit
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Pay-In", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (uiState.canReview) {
                AdminActionBar(
                    secondaryLabel = "Reject",
                    onSecondary = actions.onOpenReject,
                    primaryLabel = "Confirm",
                    onPrimary = actions.onOpenConfirm
                )
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when {
            payIn == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this pay-in",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            payIn == null -> CenteredLoading(modifier)
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
                    PaymentSection(payIn)
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "ORDER") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = payIn.orderReference,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            OrderStatusChip(status = payIn.orderStatus)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        AmountRow(label = "You Pay", amount = payIn.orderAmount)
                        AmountRow(label = "Payout Target", amount = payIn.payoutTarget)
                        CardLinkRow(text = "Open order", onClick = { onOpenOrder(payIn.orderId) })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "USER") {
                        DetailRow(label = "Name", value = payIn.memberName)
                        DetailRow(label = "Mobile", value = payIn.memberMobileMasked)
                        CardLinkRow(text = "View user", onClick = { onOpenUser(payIn.memberId) })
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PaymentSection(payIn: AdminPayInRowUi) {
    OrderCard(tag = "PAYMENT") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DisplayFormat.rupees(payIn.amount),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PayInStatusChip(status = payIn.status)
        }
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "UTR", value = payIn.utr)
        DetailRow(
            label = "Paid from",
            value = payIn.paidFrom?.let { "${it.providerName} · ${it.upiId}" } ?: "Not recorded"
        )
        payIn.payeeVpa?.let { DetailRow(label = "Receiving account", value = it) }
        DetailRow(label = "Submitted", value = DisplayFormat.dateTime(payIn.submittedAt))
        payIn.reviewedAt?.let { DetailRow(label = "Reviewed", value = DisplayFormat.dateTime(it)) }
        payIn.reviewedByName?.let { DetailRow(label = "Reviewed by", value = it) }
        if (payIn.status == PayInStatus.REJECTED) {
            Text(
                text = if (payIn.rejectionReason.isNotBlank()) "Rejected: ${payIn.rejectionReason}" else "Rejected",
                style = MaterialTheme.typography.bodyMedium,
                color = HawkCoral,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AdminPayInDetailPreview() {
    Hawk_WalletTheme {
        AdminPayInDetailContent(
            uiState = AdminPayInDetailUiState(
                payIn = previewPayIn().copy(
                    payeeVpa = "collect@ybl",
                    paidFrom = BuyMethodSummaryUiModel("GOOGLE_PAY", "Google Pay", "", "asha@okaxis")
                )
            ),
            onBackClick = {},
            onRefresh = {},
            onOpenOrder = {},
            onOpenUser = {},
            actions = PayInReviewActions()
        )
    }
}
