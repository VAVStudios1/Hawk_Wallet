package com.example.hawk_wallet.ui.screens.admin

import com.example.hawk_wallet.data.remote.model.AdminDashboardDto

/** GET /api/admin/dashboard/: counts exactly as the backend computed them; nothing is counted on the device. */
data class AdminDashboardMetrics(
    val totalUsers: Int,
    val activeOrders: Int,
    val pendingPayIns: Int,
    val pendingPayouts: Int,
    val openSupport: Int,
    /** Orders an admin has a pending step on (backend-defined); null only if the backend omits it. */
    val ordersAwaitingAction: Int?
)

/**
 * Admin Dashboard. [metrics] is null until the first successful load, so a
 * failure never shows made-up values; a failed refresh after a good load
 * keeps the last data and shows [error] beside it.
 */
data class AdminDashboardUiState(
    val metrics: AdminDashboardMetrics? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

/** Null when a required count is missing from the response, which is then treated as an error. */
internal fun AdminDashboardDto.toMetrics(): AdminDashboardMetrics? {
    return AdminDashboardMetrics(
        totalUsers = total_users ?: return null,
        activeOrders = active_orders ?: return null,
        pendingPayIns = pending_payins ?: return null,
        pendingPayouts = pending_payouts ?: return null,
        openSupport = open_support ?: return null,
        ordersAwaitingAction = orders_awaiting_action
    )
}
