package com.example.hawk_wallet.ui.screens.orders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.AuthErrorBanner
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.components.OrderStatusChip
import com.example.hawk_wallet.ui.components.PayInStatusChip
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OrderPayoutMethodUiModel
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.OrderUiModel
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayInSummaryUiModel
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** One order's backend status, amounts, payout destination and latest pay-in. */
@Composable
fun OrderStatusScreen(
    viewModel: OrderStatusViewModel,
    onBackClick: () -> Unit,
    onPayClick: (orderId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Reloads each time the screen opens, e.g. after returning from Pay-In.
    LaunchedEffect(Unit) { viewModel.refresh() }

    OrderStatusContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onRefresh = viewModel::refresh,
        onPayClick = onPayClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderStatusContent(
    uiState: OrderStatusUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onPayClick: (Long) -> Unit
) {
    val order = uiState.order
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = order?.reference ?: "Order Status", onBackClick = onBackClick) },
        bottomBar = {
            if (order?.status == OrderStatus.PAYMENT_REQUIRED) {
                val rejected = order.latestPayIn?.status == PayInStatus.REJECTED
                PrimaryActionButton(
                    text = if (rejected) "Submit New UTR" else "Pay Now",
                    onClick = { onPayClick(order.id) },
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
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
                    StatusCard(order)
                    order.latestPayIn?.let {
                        Spacer(modifier = Modifier.height(12.dp))
                        LatestPayInCard(it)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "AMOUNTS") {
                        AmountRow(label = "You pay (order amount)", amount = order.orderAmount)
                        AmountRow(label = "Income (${DisplayFormat.percent(order.incomeRate)})", amount = order.incomeAmount)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        AmountRow(label = "Payout target", amount = order.payoutTarget, emphasized = true)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OrderCard(tag = "PAYOUT TO YOU", accent = HawkEmerald) {
                        PayoutDestination(method = order.payoutMethod)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(order: OrderUiModel) {
    OrderCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            OrderStatusChip(status = order.status)
        }
        Text(
            text = order.status.description(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        DetailRow(label = "Claimed", value = DisplayFormat.dateTime(order.claimedAt))
        order.activatedAt?.let { DetailRow(label = "Payment confirmed", value = DisplayFormat.dateTime(it)) }
        order.completedAt?.let { DetailRow(label = "Completed", value = DisplayFormat.dateTime(it)) }
        order.expiresAt?.let { DetailRow(label = "Expires", value = DisplayFormat.dateTime(it)) }
    }
}

@Composable
private fun LatestPayInCard(payIn: PayInSummaryUiModel) {
    OrderCard(tag = "YOUR PAYMENT") {
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
        DetailRow(label = "Submitted", value = DisplayFormat.dateTime(payIn.submittedAt))
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

private fun OrderStatus.description(): String = when (this) {
    OrderStatus.PAYMENT_REQUIRED -> "Pay the order amount to Hawk Wallet and submit your UTR."
    OrderStatus.PAYMENT_UNDER_REVIEW -> "Your payment is being reviewed. Pull down to check for updates."
    OrderStatus.ACTIVE -> "Your payment is confirmed. Your payout will be sent to your payout method."
    OrderStatus.PAYOUT_IN_PROGRESS -> "Your payout is being sent to your payout method."
    OrderStatus.COMPLETED -> "Your payout has been paid in full."
    OrderStatus.EXPIRED -> "This order has expired."
    OrderStatus.CANCELLED -> "This order was cancelled."
    OrderStatus.UNKNOWN -> "Pull down to refresh this order."
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun OrderStatusPreview() {
    Hawk_WalletTheme {
        OrderStatusContent(
            uiState = OrderStatusUiState(
                order = OrderUiModel(
                    7, "HW-3KD9QX2M7P", OrderStatus.PAYMENT_REQUIRED, "255.00", "4.00", "10.00", "265.00",
                    OrderPayoutMethodUiModel("PHONEPE", "PhonePe", "lucky@ybl"),
                    "2026-09-26T10:00:00Z", null, null, null,
                    latestPayIn = PayInSummaryUiModel(
                        PayInStatus.REJECTED, "255.00", "012345678901",
                        "UTR not found in bank statement.", "2026-09-26T10:05:00Z"
                    )
                )
            ),
            onBackClick = {},
            onRefresh = {},
            onPayClick = {}
        )
    }
}
