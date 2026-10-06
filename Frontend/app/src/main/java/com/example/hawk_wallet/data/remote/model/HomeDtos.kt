package com.example.hawk_wallet.data.remote.model

/**
 * GET /api/home/ response data. Every money value is the backend's decimal
 * string (e.g. "49000.00") and is only ever formatted for display - never
 * parsed into Float/Double or recalculated on the device.
 */
data class HomeData(
    val user: HomeUserDto,
    val balance: HomeBalanceDto,
    val summary: HomeSummaryDto,
    val deposit_withdrawal: HomeDepositWithdrawalDto,
    val recent_transactions: List<HomeTransactionDto>
)

data class HomeUserDto(
    val id: Long,
    val name: String
)

data class HomeBalanceDto(
    val available: String,
    val pending: String
)

data class HomeSummaryDto(
    val total_earnings: String,
    val active_orders: Int,
    val pending_payouts: String
)

data class HomeDepositWithdrawalDto(
    val deposit: String,
    val withdrawal: String
)

/** [amount] is signed from the member's side: "-255.00" is money paid out by the member. */
data class HomeTransactionDto(
    val type: String,
    val title: String,
    val amount: String,
    val status: String,
    val created_at: String,
    val reference: String,
    /** Stable record id ("PAYIN-12"); only GET /api/home/transactions/ sends it. */
    val id: String? = null
)

/**
 * GET /api/home/transactions/ response data: one page, newest first. The
 * backend resolves the period ([range], [start_date] / [end_date] as
 * YYYY-MM-DD) and does all filtering, ordering and paging.
 */
data class TransactionHistoryData(
    val transactions: List<HomeTransactionDto>,
    val range: String,
    val start_date: String,
    val end_date: String,
    /** The applied type filter: all / buy / sell. */
    val type: String,
    val page: Int,
    val page_size: Int,
    val total: Int,
    val has_next: Boolean
)
