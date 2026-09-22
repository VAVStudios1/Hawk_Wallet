package com.example.hawk_wallet.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

enum class QuickAction(val label: String, val icon: ImageVector) {
    ORDERS("Orders", Icons.Filled.ReceiptLong),
    WALLET("Wallet", Icons.Filled.AccountBalanceWallet),
    TEAM("Team", Icons.Filled.Groups),
    TRANSACTIONS("Transactions", Icons.Filled.SwapHoriz)
}
