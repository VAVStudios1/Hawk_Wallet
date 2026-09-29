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
import com.example.hawk_wallet.ui.theme.HawkIndigo
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

/**
 * Marks the member's PERSONAL / BUY methods: their own UPI accounts they pay
 * for orders FROM. Never a payout destination - see SellSectionHeader.
 */
@Composable
internal fun BuySectionHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        BuyTag()
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

/** The "BUY · Pay for Orders" pill, in the indigo accent (SELL uses emerald). */
@Composable
internal fun BuyTag(modifier: Modifier = Modifier) {
    Text(
        text = "BUY · Pay for Orders",
        color = HawkIndigo,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(HawkIndigo.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun BuySectionHeaderPreview() {
    Hawk_WalletTheme {
        BuySectionHeader(
            title = "Your personal UPI accounts",
            description = "The accounts you pay for orders from.",
            modifier = Modifier.padding(20.dp)
        )
    }
}
