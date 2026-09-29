package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.model.OrderStatus
import com.example.hawk_wallet.ui.model.PayInStatus
import com.example.hawk_wallet.ui.model.PayoutStatus
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkAmberSoft
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkCoralSoft
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.HawkVioletBlue
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** An order's backend status, in the same palette as [StatusChip]. */
@Composable
fun OrderStatusChip(status: OrderStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        OrderStatus.PAYMENT_REQUIRED, OrderStatus.PAYMENT_UNDER_REVIEW -> HawkAmberSoft to HawkAmberDark
        OrderStatus.ACTIVE -> HawkIndigo.copy(alpha = 0.10f) to HawkIndigo
        OrderStatus.PAYOUT_IN_PROGRESS -> HawkVioletBlue.copy(alpha = 0.12f) to HawkVioletBlue
        OrderStatus.COMPLETED -> HawkEmeraldSoft to HawkEmerald
        OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.UNKNOWN ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(text = status.label, container = container, content = content, modifier = modifier)
}

/** A pay-in's backend review status. */
@Composable
fun PayInStatusChip(status: PayInStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        PayInStatus.UNDER_REVIEW -> HawkAmberSoft to HawkAmberDark
        PayInStatus.CONFIRMED -> HawkEmeraldSoft to HawkEmerald
        PayInStatus.REJECTED -> HawkCoralSoft to HawkCoral
        PayInStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(text = status.label, container = container, content = content, modifier = modifier)
}

/** A payout's backend status (SELL side). */
@Composable
fun PayoutStatusChip(status: PayoutStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        PayoutStatus.PENDING -> HawkAmberSoft to HawkAmberDark
        PayoutStatus.PAID -> HawkEmeraldSoft to HawkEmerald
        PayoutStatus.FAILED -> HawkCoralSoft to HawkCoral
        PayoutStatus.CANCELLED, PayoutStatus.UNKNOWN ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(text = status.label, container = container, content = content, modifier = modifier)
}

@Composable
private fun Pill(text: String, container: Color, content: Color, modifier: Modifier) {
    Row(
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderStatusChipPreview() {
    Hawk_WalletTheme {
        Row {
            OrderStatusChip(OrderStatus.PAYMENT_REQUIRED, Modifier.padding(4.dp))
            OrderStatusChip(OrderStatus.COMPLETED, Modifier.padding(4.dp))
            PayInStatusChip(PayInStatus.REJECTED, Modifier.padding(4.dp))
        }
    }
}
