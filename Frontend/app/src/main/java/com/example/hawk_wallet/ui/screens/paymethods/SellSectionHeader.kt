package com.example.hawk_wallet.ui.screens.paymethods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Marks the member's BUSINESS / SELL methods: accounts Hawk Wallet sends
 * money TO. The member's PERSONAL / BUY methods (accounts they pay for
 * orders FROM) are separate - see BuySectionHeader.
 */
@Composable
internal fun SellSectionHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SellTag()
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The "SELL · Receive Money" pill, in the SELL accent used across Hawk Wallet. */
@Composable
internal fun SellTag(modifier: Modifier = Modifier) {
    Text(
        text = "SELL · Receive Money",
        color = HawkEmerald,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(HawkEmeraldSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun SellSectionHeaderPreview() {
    Hawk_WalletTheme {
        SellSectionHeader(
            title = "Your payout accounts",
            description = "Hawk Wallet sends your order payouts to these UPI accounts.",
            modifier = Modifier.padding(20.dp)
        )
    }
}
