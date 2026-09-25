package com.example.hawk_wallet.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector

/** Home quick-action entry points; their features are built in later steps. */
enum class QuickAction(val label: String, val icon: ImageVector) {
    USDT("USDT", Icons.Filled.CurrencyExchange),
    TASK("Task", Icons.Filled.TaskAlt),
    TEAM("Team", Icons.Filled.Groups),
    ORDER("Order", Icons.Filled.ReceiptLong)
}
