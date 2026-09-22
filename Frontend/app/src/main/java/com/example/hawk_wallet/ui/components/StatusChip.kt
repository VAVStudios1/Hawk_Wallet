package com.example.hawk_wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.hawk_wallet.ui.model.TransactionStatus
import com.example.hawk_wallet.ui.theme.HawkAmberDark
import com.example.hawk_wallet.ui.theme.HawkAmberSoft
import com.example.hawk_wallet.ui.theme.HawkCoral
import com.example.hawk_wallet.ui.theme.HawkCoralSoft
import com.example.hawk_wallet.ui.theme.HawkEmerald
import com.example.hawk_wallet.ui.theme.HawkEmeraldSoft
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme

@Composable
fun StatusChip(status: TransactionStatus, modifier: Modifier = Modifier) {
    val (containerColor, contentColor, label) = when (status) {
        TransactionStatus.SUCCESS -> Triple(HawkEmeraldSoft, HawkEmerald, "Success")
        TransactionStatus.PENDING -> Triple(HawkAmberSoft, HawkAmberDark, "Pending")
        TransactionStatus.FAILED -> Triple(HawkCoralSoft, HawkCoral, "Failed")
    }
    Text(
        text = label,
        color = contentColor,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun StatusChipPreview() {
    Hawk_WalletTheme {
        StatusChip(status = TransactionStatus.PENDING)
    }
}
