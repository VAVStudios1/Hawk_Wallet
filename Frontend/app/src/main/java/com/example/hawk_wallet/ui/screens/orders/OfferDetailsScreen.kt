package com.example.hawk_wallet.ui.screens.orders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.format.DisplayFormat
import com.example.hawk_wallet.ui.model.OfferUiModel
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/** One offer as listed by the backend; Claim continues to the payout-method chooser. */
@Composable
fun OfferDetailsScreen(
    viewModel: ClaimViewModel,
    onBackClick: () -> Unit,
    onClaimClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    OfferDetailsContent(offer = uiState.offer, onBackClick = onBackClick, onClaimClick = onClaimClick)
}

@Composable
private fun OfferDetailsContent(offer: OfferUiModel?, onBackClick: () -> Unit, onClaimClick: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Order Details", onBackClick = onBackClick) },
        bottomBar = {
            if (offer != null) {
                PrimaryActionButton(
                    text = "Claim",
                    onClick = onClaimClick,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp)
                )
            }
        }
    ) { innerPadding ->
        if (offer == null) {
            // e.g. the app was restarted mid-claim: the offer must be picked again.
            CenteredMessage(
                title = "This order is no longer open",
                message = "Go back to Orders to see what is available now.",
                actionLabel = "Back to Orders",
                onAction = onBackClick,
                modifier = Modifier.padding(innerPadding)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
        ) {
            OrderCard(tag = "YOU PAY HAWK WALLET") {
                Text(
                    text = DisplayFormat.rupees(offer.orderAmount),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "The order amount, paid once to Hawk Wallet's UPI account after you claim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OrderCard(tag = "YOU RECEIVE", accent = HawkEmerald) {
                AmountRow(label = "Order amount", amount = offer.orderAmount)
                AmountRow(label = "Income (${DisplayFormat.percent(offer.incomeRate)})", amount = offer.incomeAmount)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                AmountRow(label = "Payout target", amount = offer.payoutTarget, emphasized = true)
                Text(
                    text = "Paid to the payment method you choose next, after your payment is confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            offer.validTo?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Available until ${DisplayFormat.dateTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun OfferDetailsPreview() {
    Hawk_WalletTheme {
        OfferDetailsContent(
            offer = OfferUiModel(1, "255.00", "4.00", "10.00", "265.00", "2026-09-30T18:00:00Z"),
            onBackClick = {},
            onClaimClick = {}
        )
    }
}
