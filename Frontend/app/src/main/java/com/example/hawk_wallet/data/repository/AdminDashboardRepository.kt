package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AdminDashboardApi
import com.example.hawk_wallet.data.remote.model.AdminDashboardDto

/** Fetches the admin dashboard. Counts are returned exactly as the backend computed them. */
class AdminDashboardRepository(private val api: AdminDashboardApi) {

    suspend fun getDashboard(): ApiResult<AdminDashboardDto> = safeApiCall { api.dashboard() }
}
