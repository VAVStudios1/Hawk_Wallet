package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AdminAccountDto
import com.example.hawk_wallet.data.remote.model.AdminAccountListData
import com.example.hawk_wallet.data.remote.model.AdminReportDto
import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.AuditEventDto
import com.example.hawk_wallet.data.remote.model.AuditEventListData
import com.example.hawk_wallet.data.remote.model.CreateAdminRequest
import com.example.hawk_wallet.data.remote.model.SystemReportDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Admin management, audit log and reports; authenticated by the shared JWT
 * interceptor. Everything except reports() is SUPER_ADMIN-only on the backend.
 */
interface AdminManagementApi {
    @GET("api/admin/admins/")
    suspend fun admins(
        @Query("is_active") isActive: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminAccountListData>>

    @GET("api/admin/admins/{id}/")
    suspend fun admin(@Path("id") adminId: Long): Response<ApiResponse<AdminAccountDto>>

    @POST("api/admin/admins/")
    suspend fun createAdmin(@Body request: CreateAdminRequest): Response<ApiResponse<AdminAccountDto>>

    @POST("api/admin/admins/{id}/activate/")
    suspend fun activateAdmin(@Path("id") adminId: Long): Response<ApiResponse<AdminAccountDto>>

    @POST("api/admin/admins/{id}/deactivate/")
    suspend fun deactivateAdmin(@Path("id") adminId: Long): Response<ApiResponse<AdminAccountDto>>

    @GET("api/admin/audit-events/")
    suspend fun auditEvents(
        @Query("action") action: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AuditEventListData>>

    @GET("api/admin/audit-events/{id}/")
    suspend fun auditEvent(@Path("id") eventId: Long): Response<ApiResponse<AuditEventDto>>

    /** ADMIN and SUPER_ADMIN. [range] is today / 7d / 30d / custom (with start / end dates, YYYY-MM-DD). */
    @GET("api/admin/reports/")
    suspend fun report(
        @Query("range") range: String,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null
    ): Response<ApiResponse<AdminReportDto>>

    @GET("api/admin/system-report/")
    suspend fun systemReport(): Response<ApiResponse<SystemReportDto>>
}
