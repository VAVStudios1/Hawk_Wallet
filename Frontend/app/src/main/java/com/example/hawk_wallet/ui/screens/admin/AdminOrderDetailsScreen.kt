package com.example.hawk_wallet.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.components.PayoutStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.AdminOrderUiModel
import com.example.hawk_wallet.ui.model.AdminPayInUiModel
import com.example.hawk_wallet.ui.model.AdminPayoutUiModel
import com.example.hawk_wallet.ui.model.BuyMethodSummaryUiModel
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.screens.orders.AmountRow
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.DetailRow
import com.example.hawk_wallet.ui.screens.orders.OrderCard
import com.example.hawk_wallet.ui.screens.orders.PayoutDestination
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * One order for an admin, in sections: status, customer, financial summary,
 * pay-in (BUY side) with its review, payout (SELL side) with payout actions,
 * and a timeline. Every amount and timestamp is the backend's.
 */
@Composable
fun AdminOrderDetailsScreen(
    viewModel: AdminOrderDetailsViewModel,
    onBackClick: () -> Unit,
    onOpenUser: (userId: Long) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Loads once per visit; each load is audited by the backend.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AdminOrderDetailsContent(
        uiState = uiState,
        actions = PayInReviewActions(
            onOpenConfirm = viewModel::openConfirm,
            onOpenReject = viewModel::openReject,
            onDismiss = viewModel::dismissDialog,
            onReasonChange = viewModel::onRejectionReasonChange,
            onConfirm = viewModel::confirm,
            onReject = viewModel::reject,
            onMessageShown = viewModel::messageShown
        ),
        payoutActions = PayoutActions(
            onOpenCreate = viewModel::openCreatePayout,
            onOpenMarkPaid = viewModel::openMarkPaid,
            onOpenCancel = viewModel::openCancelPayout,
            onOpenQr = viewModel::openPayoutQr,
            onDismiss = viewModel::dismissPayoutDialog,
            onAmountChange = viewModel::onPayoutAmountChange,
            onUtrChange = viewModel::onPayoutUtrChange,
            onReasonChange = viewModel::onCancellationReasonChange,
            onCreate = viewModel::createPayout,
            onMarkPaid = viewModel::markPayoutPaid,
            onCancel = viewModel::cancelPayout
        ),
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onOpenUser = onOpenUser
    )
}

/** Pay-in review callbacks, bundled to keep the content signature short. */
internal class PayInReviewActions(
    val onOpenConfirm: () -> Unit = {},
    val onOpenReject: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onReasonChange: (String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onMessageShown: () -> Unit = {}
)

/** Payout (SELL) callbacks. */
internal class PayoutActions(
    val onOpenCreate: () -> Unit = {},
    val onOpenMarkPaid: (Long) -> Unit = {},
    val onOpenCancel: (Long) -> Unit = {},
    val onOpenQr: (Long) -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onAmountChange: (String) -> Unit = {},
    val onUtrChange: (String) -> Unit = {},
    val onReasonChange: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onMarkPaid: () -> Unit = {},
    val onCancel: () -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminOrderDetailsContent(
    uiState: AdminOrderDetailsUiState,
    actions: PayInReviewActions,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    payoutActions: PayoutActions = PayoutActions(),
    onOpenUser: (Long) -> Unit = {}
) {
    val order = uiState.order
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    OrderDialogs(uiState, actions, payoutActions)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = order?.reference ?: "Order Details", onBackClick = onBackClick) },
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
            order == null && uiState.error != null -> CenteredMessage(
                title = "Couldn't load this order",
                message = uiState.error,
                actionLabel = "Retry",
                onAction = onRefresh,
                modifier = modifier
            )
            order == null -> CenteredLoading(modifier)
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
                    uiState.error?.let {
                        AuthErrorBanner(message = it, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    OrderStatusSection(order)
                    Spacer(modifier = Modifier.height(12.dp))
                    CustomerSection(order, onOpenUser = { onOpenUser(order.memberId) })
                    Spacer(modifier = Modifier.height(12.dp))
                    FinancialSummarySection(order)
                    Spacer(modifier = Modifier.height(12.dp))
                    PayInSection(order.latestPayIn, receivingUpiId = order.payInUpiId)
                    Spacer(modifier = Modifier.height(12.dp))
                    PayoutSection(order = order, canCreatePayout = uiState.canCreatePayout, actions = payoutActions)
                    Spacer(modifier = Modifier.height(12.dp))
                    TimelineSection(order)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun OrderDialogs(uiState: AdminOrderDetailsUiState, actions: PayInReviewActions, payoutActions: PayoutActions) {
    val payIn = uiState.order?.latestPayIn
    when (uiState.reviewDialog) {
        PayInReviewDialog.CONFIRM -> if (payIn != null) {
            ConfirmPayInDialog(
                amount = payIn.amount,
                utr = payIn.utr,
                isSubmitting = uiState.isReviewing,
                error = uiState.reviewError,
                onConfirm = actions.onConfirm,
                onDismiss = actions.onDismiss
            )
        }
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
    when (val dialog = uiState.payoutDialog) {
        PayoutDialog.Create -> CreatePayoutDialog(
            amount = uiState.payoutAmount,
            amountError = uiState.payoutAmountError,
            remainingToCommit = uiState.order?.remainingToCommit,
            isSubmitting = uiState.isPayoutSubmitting,
            error = uiState.payoutError,
            onAmountChange = payoutActions.onAmountChange,
            onCreate = payoutActions.onCreate,
            onDismiss = payoutActions.onDismiss
        )
        is PayoutDialog.MarkPaid -> uiState.payout(dialog.payoutId)?.let { payout ->
            MarkPayoutPaidDialog(
                amount = payout.amount,
                upiId = payout.destination.upiId,
                utr = uiState.payoutUtr,
                utrError = uiState.payoutUtrError,
                isSubmitting = uiState.isPayoutSubmitting,
                error = uiState.payoutError,
                onUtrChange = payoutActions.onUtrChange,
                onMarkPaid = payoutActions.onMarkPaid,
                onDismiss = payoutActions.onDismiss
            )
        }
        is PayoutDialog.Cancel -> uiState.payout(dialog.payoutId)?.let { payout ->
            CancelPayoutDialog(
                amount = payout.amount,
                reason = uiState.cancellationReason,
                reasonError = uiState.cancellationReasonError,
                isSubmitting = uiState.isPayoutSubmitting,
                error = uiState.payoutError,
                onReasonChange = payoutActions.onReasonChange,
                onCancel = payoutActions.onCancel,
                onDismiss = payoutActions.onDismiss
            )
        }
        is PayoutDialog.Qr -> PayoutQrDialog(
            isLoading = uiState.isQrLoading,
            error = uiState.qrError,
            image = uiState.qrImage,
            onDismiss = payoutActions.onDismiss
        )
        null -> Unit
    }
}

// --- 1. Order status ---------------------------------------------------------

@Composable
private fun OrderStatusSection(order: AdminOrderUiModel) {
    OrderCard(tag = "ORDER STATUS") {
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
        DetailRow(label = "Claimed", value = DisplayFormat.dateTime(order.claimedAt))
        order.expiresAt?.let { DetailRow(label = "Payment due by", value = DisplayFormat.dateTime(it)) }
    }
}

// --- 2. Customer -------------------------------------------------------------

@Composable
private fun CustomerSection(order: AdminOrderUiModel, onOpenUser: () -> Unit) {
    OrderCard(tag = "CUSTOMER") {
        DetailRow(label = "Name", value = order.memberName)
        DetailRow(label = "Member ID", value = order.memberId.toString())
        CardLinkRow(text = "View user", onClick = onOpenUser)
    }
}

// --- 3. Offer / financial summary --------------------------------------------

/** The order's stored amounts and the server's payout totals; nothing is computed here. */
@Composable
private fun FinancialSummarySection(order: AdminOrderUiModel) {
    OrderCard(tag = "FINANCIAL SUMMARY") {
        AmountRow(label = "You Pay", amount = order.orderAmount)
        AmountRow(label = "Income (${DisplayFormat.percent(order.incomeRate)})", amount = order.incomeAmount)
        AmountRow(label = "Payout Target", amount = order.payoutTarget, emphasized = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 6.dp))
        AmountRow(label = "Paid", amount = order.payoutPaidTotal)
        AmountRow(label = "Committed (pending + paid)", amount = order.payoutCommittedTotal)
        order.remainingToCommit?.let { AmountRow(label = "Remaining to commit", amount = it, emphasized = true) }
    }
}

// --- 4. Pay-in ----------------------------------------------------------------

@Composable
private fun PayInSection(payIn: AdminPayInUiModel?, receivingUpiId: String?) {
    OrderCard(tag = "PAY-IN") {
        if (payIn == null) {
            Text(
                text = "The member has not submitted a payment yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            receivingUpiId?.let { DetailRow(label = "Receiving account", value = it) }
            return@OrderCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DisplayFormat.rupees(payIn.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PayInStatusChip(status = payIn.status)
        }
        Spacer(modifier = Modifier.height(4.dp))
        DetailRow(label = "UTR", value = payIn.utr)
        // BUY side: the member's own account the money came from (not the SELL payout method).
        DetailRow(
            label = "Paid from",
            value = payIn.paidFrom?.let { "${it.providerName} · ${it.upiId}" } ?: "Not recorded"
        )
        receivingUpiId?.let { DetailRow(label = "Receiving account", value = it) }
        DetailRow(label = "Submitted", value = DisplayFormat.dateTime(payIn.submittedAt))
        payIn.reviewedAt?.let { DetailRow(label = "Reviewed", value = DisplayFormat.dateTime(it)) }
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

// --- 5. Payout ----------------------------------------------------------------

/** SELL side: where the platform pays the member, the Create Payout action and the payout history. */
@Composable
private fun PayoutSection(order: AdminOrderUiModel, canCreatePayout: Boolean, actions: PayoutActions) {
    OrderCard(tag = "PAYOUT", accent = HawkEmerald) {
        order.payoutMethod?.let { method ->
            PayoutDestination(method = method)
            if (order.payoutMethodLabel.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                DetailRow(label = "Label", value = order.payoutMethodLabel)
            }
        }
        AmountRow(label = "Payout Target", amount = order.payoutTarget)
        AmountRow(label = "Total paid", amount = order.payoutPaidTotal)
        order.remainingToCommit?.let { AmountRow(label = "Remaining to commit", amount = it, emphasized = true) }
        if (canCreatePayout) {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryActionButton(text = "Create Payout", onClick = actions.onOpenCreate, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 12.dp))
        Text(
            text = "Payout history",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (order.payouts.isEmpty()) {
            Text(
                text = "No payouts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        order.payouts.forEachIndexed { index, payout ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
            }
            PayoutRow(payout = payout, actions = actions)
        }
    }
}

@Composable
private fun PayoutRow(payout: AdminPayoutUiModel, actions: PayoutActions) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DisplayFormat.rupees(payout.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            PayoutStatusChip(status = payout.status)
        }
        Spacer(modifier = Modifier.height(4.dp))
        DetailRow(label = "Payout ID", value = payout.id.toString())
        DetailRow(label = "To", value = "${payout.destination.providerName} · ${payout.destination.upiId}")
        DetailRow(label = "Created", value = DisplayFormat.dateTime(payout.createdAt))
        DetailRow(label = "Created by", value = "Admin #${payout.createdBy}")
        if (payout.status == PayoutStatus.PAID) {
            DetailRow(label = "UTR", value = payout.utr)
            payout.paidAt?.let { DetailRow(label = "Paid", value = DisplayFormat.dateTime(it)) }
            payout.paidBy?.let { DetailRow(label = "Paid by", value = "Admin #$it") }
        }
        if (payout.status == PayoutStatus.CANCELLED) {
            Text(
                text = if (payout.cancellationReason.isNotBlank()) "Cancelled: ${payout.cancellationReason}" else "Cancelled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { actions.onOpenQr(payout.id) }) { Text("View QR") }
            Spacer(modifier = Modifier.weight(1f))
            if (payout.status == PayoutStatus.PENDING) {
                TextButton(
                    onClick = { actions.onOpenCancel(payout.id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = HawkCoral)
                ) { Text("Cancel") }
                TextButton(onClick = { actions.onOpenMarkPaid(payout.id) }) {
                    Text("Mark Paid", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// --- 6. Timeline --------------------------------------------------------------

private data class TimelineEvent(val title: String, val timestamp: String, val color: Color)

/**
 * The order's lifecycle in the order it happens, from the timestamps the
 * backend returned. Only the latest pay-in is part of the order detail, so
 * earlier rejected attempts are not listed.
 */
private fun timelineOf(order: AdminOrderUiModel): List<TimelineEvent> = buildList {
    add(TimelineEvent("Order claimed", order.claimedAt, HawkIndigo))
    order.latestPayIn?.let { payIn ->
        add(TimelineEvent("Pay-in submitted · UTR ${payIn.utr}", payIn.submittedAt, HawkAmberDark))
        payIn.reviewedAt?.let {
            val rejected = payIn.status == PayInStatus.REJECTED
            add(TimelineEvent(if (rejected) "Pay-in rejected" else "Pay-in confirmed", it, if (rejected) HawkCoral else HawkEmerald))
        }
    }
    order.payouts.forEach { payout ->
        add(TimelineEvent("Payout #${payout.id} created · ${DisplayFormat.rupees(payout.amount)}", payout.createdAt, HawkAmberDark))
        payout.paidAt?.let { add(TimelineEvent("Payout #${payout.id} paid", it, HawkEmerald)) }
    }
    order.completedAt?.let { add(TimelineEvent("Order completed", it, HawkEmerald)) }
}

@Composable
private fun TimelineSection(order: AdminOrderUiModel) {
    OrderCard(tag = "TIMELINE") {
        timelineOf(order).forEach { event ->
            Row(modifier = Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(event.color, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = DisplayFormat.dateTime(event.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun AdminOrderDetailsPreview() {
    Hawk_WalletTheme {
        AdminOrderDetailsContent(
            uiState = AdminOrderDetailsUiState(
                order = previewAdminOrder().copy(
                    payInUpiId = "collect@ybl",
                    payoutMethod = OrderPayoutMethodUiModel("PHONEPE", "PhonePe", "asha@ybl"),
                    payoutMethodLabel = "Main",
                    latestPayIn = AdminPayInUiModel(
                        9, PayInStatus.UNDER_REVIEW, "255.00", "012345678901",
                        "", "2026-09-26T10:05:00Z", null,
                        BuyMethodSummaryUiModel("GOOGLE_PAY", "Google Pay", "", "asha@okaxis")
                    ),
                    remainingToCommit = "115.00",
                    payouts = listOf(
                        previewPayout(1, "100.00", PayoutStatus.PAID, utr = "012345678901"),
                        previewPayout(2, "50.00", PayoutStatus.PENDING),
                        previewPayout(3, "65.00", PayoutStatus.CANCELLED, reason = "Wrong amount")
                    )
                )
            ),
            actions = PayInReviewActions(),
            onBackClick = {},
            onRefresh = {}
        )
    }
}

private fun previewPayout(id: Long, amount: String, status: PayoutStatus, utr: String = "", reason: String = "") =
    AdminPayoutUiModel(
        id = id,
        amount = amount,
        status = status,
        utr = utr,
        createdBy = 1,
        paidBy = if (status == PayoutStatus.PAID) 1 else null,
        createdAt = "2026-09-26T12:00:00Z",
        paidAt = if (status == PayoutStatus.PAID) "2026-09-26T12:30:00Z" else null,
        destination = OrderPayoutMethodUiModel("PHONEPE", "PhonePe", "asha@ybl"),
        cancellationReason = reason
    )
