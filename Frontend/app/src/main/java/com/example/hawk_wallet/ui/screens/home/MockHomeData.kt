package com.example.hawk_wallet.ui.screens.home

import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.model.TransactionStatus
import com.example.hawk_wallet.ui.model.TransactionType
import com.example.hawk_wallet.ui.model.TransactionUi

/**
 * @Preview sample only - never shown in the running app, which displays
 * GET /api/home/ data. The backend is the source of truth for every figure.
 */
internal fun previewHomeContent(): HomeContent = HomeContent(
    greetingName = "Lucky",
    availableBalance = "₹165.00",
    pendingBalance = "₹100.00",
    summaryStats = listOf(
        SummaryStat(label = "Total Earnings", value = "₹0.00"),
        SummaryStat(label = "Active Orders", value = "1"),
        SummaryStat(label = "Pending Payouts", value = "₹100.00")
    ),
    deposit = "₹255.00",
    withdrawal = "₹0.00",
    recentTransactions = listOf(
        TransactionUi(
            id = "preview_1",
            type = TransactionType.PAYOUT,
            title = "Payout",
            dateTime = "25 Sep 2026, 10:24 AM",
            amount = "₹100.00",
            isCredit = true,
            status = TransactionStatus.PENDING
        ),
        TransactionUi(
            id = "preview_2",
            type = TransactionType.ORDER_PAYMENT,
            title = "Order Payment",
            dateTime = "25 Sep 2026, 9:10 AM",
            amount = "₹255.00",
            isCredit = false,
            status = TransactionStatus.SUCCESS
        )
    )
)
