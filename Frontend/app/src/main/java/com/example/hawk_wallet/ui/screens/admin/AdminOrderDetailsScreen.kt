package com.example.hawk_wallet.ui.screens.admin

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * One order for an admin: amounts, member, the BUY side (pay-in) with its
 * review, and the SELL side (payout method and payouts) with payout actions.
 */
@Composable
fun AdminOrderDetailsScreen(
    viewModel: AdminOrderDetailsViewModel,
    onBackClick: () -> Unit
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
        onRefresh = viewModel::refresh
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
    payoutActions: PayoutActions = PayoutActions()
) {
    val order = uiState.order
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            actions.onMessageShown()
        }
    }
    when (uiState.reviewDialog) {
        PayInReviewDialog.CONFIRM -> ConfirmPayInDialog(uiState, actions)
        PayInReviewDialog.REJECT -> RejectPayInDialog(uiState, actions)
        null -> Unit
    }
    when (val dialog = uiState.payoutDialog) {
        PayoutDialog.Create -> CreatePayoutDialog(uiState, payoutActions)
        is PayoutDialog.MarkPaid -> uiState.payout(dialog.payoutId)?.let { MarkPayoutPaidDialog(it, uiState, payoutActions) }
        is PayoutDialog.Cancel -> uiState.payout(dialog.payoutId)?.let { CancelPayoutDialog(it, uiState, payoutActions) }
        is PayoutDialog.Qr -> PayoutQrDialog(uiState, payoutActions)
        null -> Unit
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = order?.reference ?: "Order Details", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { if (uiState.canReview) ReviewBar(actions) }
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
                    OrderSummary(order)
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "AMOUNTS") {
                        AmountRow(label = "Order amount", amount = order.orderAmount)
                        AmountRow(label = "Income (${DisplayFormat.percent(order.incomeRate)})", amount = order.incomeAmount)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        AmountRow(label = "Payout target", amount = order.payoutTarget, emphasized = true)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    order.payInUpiId?.let {
                        PayInReceivingAccountCard(upiId = it)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    PayInCard(order.latestPayIn)
                    order.payoutMethod?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        PayoutMethodCard(method = it, label = order.payoutMethodLabel)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PayoutSettlementCard(
                        order = order,
                        canCreatePayout = uiState.canCreatePayout,
                        actions = payoutActions
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun OrderSummary(order: AdminOrderUiModel) {
    OrderCard(tag = "ORDER") {
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
        DetailRow(label = "Member", value = order.memberName)
        DetailRow(label = "Member ID", value = order.memberId.toString())
        DetailRow(label = "Claimed", value = DisplayFormat.dateTime(order.claimedAt))
        order.activatedAt?.let { DetailRow(label = "Payment confirmed", value = DisplayFormat.dateTime(it)) }
        order.completedAt?.let { DetailRow(label = "Completed", value = DisplayFormat.dateTime(it)) }
        order.expiresAt?.let { DetailRow(label = "Expires", value = DisplayFormat.dateTime(it)) }
    }
}

@Composable
private fun PayInCard(payIn: AdminPayInUiModel?) {
    OrderCard(tag = "PAY-IN") {
        if (payIn == null) {
            Text(
                text = "The member has not submitted a payment yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            label = "Paid from (BUY)",
            value = payIn.paidFrom?.let { "${it.providerName} · ${it.upiId}" } ?: "Not recorded"
        )
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

@Composable
private fun ReviewBar(actions: PayInReviewActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
    ) {
        OutlinedButton(
            onClick = actions.onOpenReject,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HawkCoral),
            border = BorderStroke(1.dp, HawkCoral),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Text("Reject", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        PrimaryActionButton(
            text = "Confirm",
            onClick = actions.onOpenConfirm,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfirmPayInDialog(uiState: AdminOrderDetailsUiState, actions: PayInReviewActions) {
    val payIn = uiState.order?.latestPayIn ?: return
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Confirm pay-in?") },
        text = {
            Column {
                Text(
                    text = "Only confirm after checking that ${DisplayFormat.rupees(payIn.amount)} with UTR " +
                        "${payIn.utr} has reached the platform account. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                uiState.reviewError?.let {
                    Text(text = it, color = HawkCoral, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm, enabled = !uiState.isReviewing) {
                if (uiState.isReviewing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Confirm", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !uiState.isReviewing) { Text("Cancel") }
        }
    )
}

@Composable
private fun RejectPayInDialog(uiState: AdminOrderDetailsUiState, actions: PayInReviewActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Reject pay-in?") },
        text = {
            Column {
                Text(
                    text = "The member will see this reason and can submit a new UTR.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.rejectionReason,
                    onValueChange = actions.onReasonChange,
                    label = { Text("Reason") },
                    isError = uiState.rejectionReasonError != null,
                    supportingText = {
                        Text(uiState.rejectionReasonError ?: "${uiState.rejectionReason.length}/$REJECTION_REASON_MAX_LENGTH")
                    },
                    enabled = !uiState.isReviewing,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                uiState.reviewError?.let {
                    Text(text = it, color = HawkCoral, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onReject,
                enabled = !uiState.isReviewing,
                colors = ButtonDefaults.textButtonColors(contentColor = HawkCoral)
            ) {
                if (uiState.isReviewing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Reject", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !uiState.isReviewing) { Text("Cancel") }
        }
    )
}

/** Where the member pays the platform. Deliberately separate from [PayoutMethodCard]. */
@Composable
private fun PayInReceivingAccountCard(upiId: String) {
    OrderCard(tag = "PAY-IN RECEIVING ACCOUNT") {
        DetailRow(label = "UPI ID", value = upiId)
    }
}

/** Where the platform pays the member (SELL). Never a pay-in account. */
@Composable
private fun PayoutMethodCard(method: OrderPayoutMethodUiModel, label: String) {
    OrderCard(tag = "PAYOUT METHOD · SELL", accent = HawkEmerald) {
        PayoutDestination(method = method)
        if (label.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            DetailRow(label = "Label", value = label)
        }
    }
}

/** SELL settlement: the server's payout totals, the Create Payout action and payout history. */
@Composable
private fun PayoutSettlementCard(order: AdminOrderUiModel, canCreatePayout: Boolean, actions: PayoutActions) {
    OrderCard(tag = "PAYOUT / SELL", accent = HawkEmerald) {
        AmountRow(label = "Payout target", amount = order.payoutTarget, emphasized = true)
        AmountRow(label = "Committed", amount = order.payoutCommittedTotal)
        AmountRow(label = "Paid", amount = order.payoutPaidTotal)
        order.remainingToCommit?.let { AmountRow(label = "Remaining to commit", amount = it) }
        if (canCreatePayout) {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryActionButton(text = "Create Payout", onClick = actions.onOpenCreate, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 12.dp))
        Text(
            text = "Payout history",
            style = MaterialTheme.typography.titleSmall,
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

@Composable
private fun PayoutDialogError(uiState: AdminOrderDetailsUiState) {
    uiState.payoutError?.let {
        Text(text = it, color = HawkCoral, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PayoutSubmitButton(label: String, uiState: AdminOrderDetailsUiState, onClick: () -> Unit, color: Color? = null) {
    TextButton(
        onClick = onClick,
        enabled = !uiState.isPayoutSubmitting,
        colors = if (color != null) ButtonDefaults.textButtonColors(contentColor = color) else ButtonDefaults.textButtonColors()
    ) {
        if (uiState.isPayoutSubmitting) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CreatePayoutDialog(uiState: AdminOrderDetailsUiState, actions: PayoutActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Create payout") },
        text = {
            Column {
                Text(
                    text = "A pending payout to the member's SELL payout method. The server checks the amount " +
                        "against what is left to commit" +
                        (uiState.order?.remainingToCommit?.let { " (${DisplayFormat.rupees(it)})" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.payoutAmount,
                    onValueChange = actions.onAmountChange,
                    label = { Text("Amount (₹)") },
                    isError = uiState.payoutAmountError != null,
                    supportingText = { uiState.payoutAmountError?.let { Text(it) } },
                    enabled = !uiState.isPayoutSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                PayoutDialogError(uiState)
            }
        },
        confirmButton = { PayoutSubmitButton("Create", uiState, actions.onCreate) },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !uiState.isPayoutSubmitting) { Text("Close") }
        }
    )
}

@Composable
private fun MarkPayoutPaidDialog(payout: AdminPayoutUiModel, uiState: AdminOrderDetailsUiState, actions: PayoutActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Mark payout paid?") },
        text = {
            Column {
                Text(
                    text = "Only mark paid after ${DisplayFormat.rupees(payout.amount)} has been sent to " +
                        "${payout.destination.upiId}. Enter the bank UTR of that transfer. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.payoutUtr,
                    onValueChange = actions.onUtrChange,
                    label = { Text("UTR") },
                    isError = uiState.payoutUtrError != null,
                    supportingText = { Text(uiState.payoutUtrError ?: "${uiState.payoutUtr.length}/$UTR_LENGTH digits") },
                    enabled = !uiState.isPayoutSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                PayoutDialogError(uiState)
            }
        },
        confirmButton = { PayoutSubmitButton("Mark Paid", uiState, actions.onMarkPaid) },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !uiState.isPayoutSubmitting) { Text("Close") }
        }
    )
}

@Composable
private fun CancelPayoutDialog(payout: AdminPayoutUiModel, uiState: AdminOrderDetailsUiState, actions: PayoutActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Cancel payout?") },
        text = {
            Column {
                Text(
                    text = "Cancel the pending ${DisplayFormat.rupees(payout.amount)} payout if it was created by " +
                        "mistake. Its amount becomes available to commit again. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.cancellationReason,
                    onValueChange = actions.onReasonChange,
                    label = { Text("Reason") },
                    isError = uiState.cancellationReasonError != null,
                    supportingText = {
                        Text(uiState.cancellationReasonError ?: "${uiState.cancellationReason.length}/$CANCELLATION_REASON_MAX_LENGTH")
                    },
                    enabled = !uiState.isPayoutSubmitting,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                PayoutDialogError(uiState)
            }
        },
        confirmButton = { PayoutSubmitButton("Cancel Payout", uiState, actions.onCancel, color = HawkCoral) },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !uiState.isPayoutSubmitting) { Text("Keep") }
        }
    )
}

/** The SELL destination's QR, as bytes from the authorized endpoint - never a URL or storage path. */
@Composable
private fun PayoutQrDialog(uiState: AdminOrderDetailsUiState, actions: PayoutActions) {
    val bytes = uiState.qrImage
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = { Text("Payout QR") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isQrLoading -> CircularProgressIndicator()
                    uiState.qrError != null -> Text(uiState.qrError, color = HawkCoral, style = MaterialTheme.typography.bodyMedium)
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = "Member's payout QR code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    )
                    bytes != null -> Text(
                        "This QR image couldn't be displayed.",
                        color = HawkCoral,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onDismiss) { Text("Close") } }
    )
}

@Preview(showBackground = true, heightDp = 1100)
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
