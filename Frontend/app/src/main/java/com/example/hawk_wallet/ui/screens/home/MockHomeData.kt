package com.example.hawk_wallet.ui.screens.home

import com.example.hawk_wallet.ui.model.QuickAction
import com.example.hawk_wallet.ui.model.SummaryStat
import com.example.hawk_wallet.ui.model.TransactionStatus
import com.example.hawk_wallet.ui.model.TransactionType
import com.example.hawk_wallet.ui.model.TransactionUi

/**
 * Local mock data only. The backend is the source of truth for every
 * financial figure here; this stands in until the real wallet/orders APIs
 * are wired up.
 */
fun mockHomeUiState(): HomeUiState = HomeUiState(
    greetingName = "Lucky",
    hasUnreadNotifications = true,
    availableBalance = "₹49,000.00",
    pendingBalance = "₹850.00",
    summaryStats = listOf(
        SummaryStat(label = "Total Earnings", value = "₹14,860.00"),
        SummaryStat(label = "Active Orders", value = "3"),
        SummaryStat(label = "Pending Payouts", value = "₹850.00")
    ),
    quickActions = QuickAction.entries,
    recentTransactions = listOf(
        TransactionUi(
            id = "txn_1",
            type = TransactionType.ORDER_PAYMENT,
            title = "Order Payment",
            dateTime = "Today, 10:24 AM",
            amount = "₹285.00",
            isCredit = true,
            status = TransactionStatus.SUCCESS
        ),
        TransactionUi(
            id = "txn_2",
            type = TransactionType.COMMISSION,
            title = "Commission",
            dateTime = "Today, 9:10 AM",
            amount = "₹11.40",
            isCredit = true,
            status = TransactionStatus.SUCCESS
        ),
        TransactionUi(
            id = "txn_3",
            type = TransactionType.WITHDRAWAL,
            title = "Withdrawal",
            dateTime = "Yesterday, 6:48 PM",
            amount = "₹500.00",
            isCredit = false,
            status = TransactionStatus.PENDING
        ),
        TransactionUi(
            id = "txn_4",
            type = TransactionType.BONUS,
            title = "Bonus",
            dateTime = "Yesterday, 2:05 PM",
            amount = "₹50.00",
            isCredit = true,
            status = TransactionStatus.SUCCESS
        )
    )
)
