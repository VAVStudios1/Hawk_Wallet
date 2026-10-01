package com.example.hawk_wallet.data.remote.model

/**
 * GET /api/admin/dashboard/ response data: counts computed by the backend.
 * Every field is nullable so a missing value stays missing instead of
 * Gson silently filling in 0. orders_awaiting_action is null by contract
 * until its business rule is defined.
 */
data class AdminDashboardDto(
    val total_users: Int?,
    val active_orders: Int?,
    val pending_payins: Int?,
    val pending_payouts: Int?,
    val open_support: Int?,
    val orders_awaiting_action: Int?
)
