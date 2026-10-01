package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AdminDashboardDto
import com.example.hawk_wallet.data.remote.model.ApiResponse
import retrofit2.Response
import retrofit2.http.GET

/** Admin-only operational counts; authenticated by the shared JWT interceptor. */
interface AdminDashboardApi {
    @GET("api/admin/dashboard/")
    suspend fun dashboard(): Response<ApiResponse<AdminDashboardDto>>
}
