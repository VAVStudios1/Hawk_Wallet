package com.example.hawk_wallet.ui.screens.home

import com.example.hawk_wallet.ui.model.QuickAction
import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.model.TransactionUi

data class HomeUiState(
    val greetingName: String = "",
    val hasUnreadNotifications: Boolean = false,
    val availableBalance: String = "",
    val pendingBalance: String = "",
    val summaryStats: List<SummaryStat> = emptyList(),
    val quickActions: List<QuickAction> = emptyList(),
    val recentTransactions: List<TransactionUi> = emptyList()
)
