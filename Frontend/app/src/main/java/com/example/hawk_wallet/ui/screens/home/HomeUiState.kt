package com.example.hawk_wallet.ui.screens.home

import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.model.TransactionUi

/**
 * Loading / Success / Error for the member dashboard. [content] survives a
 * failed refresh, so the last good data stays on screen alongside the error
 * instead of being replaced by placeholder figures.
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val content: HomeContent? = null,
    val errorMessage: String? = null
)

/** Display-ready dashboard; every value is formatted from the backend's response. */
data class HomeContent(
    val greetingName: String,
    val availableBalance: String,
    val pendingBalance: String,
    val summaryStats: List<SummaryStat>,
    val deposit: String,
    val withdrawal: String,
    val recentTransactions: List<TransactionUi>
)
