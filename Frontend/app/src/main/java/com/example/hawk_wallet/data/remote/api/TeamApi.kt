package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.CommissionPageDto
import com.example.hawk_wallet.data.remote.model.ReferralInfoDto
import com.example.hawk_wallet.data.remote.model.TeamMembersPageDto
import com.example.hawk_wallet.data.remote.model.TeamSummaryDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Member-only and read-only; the backend always answers for the signed-in member's own team. */
interface TeamApi {
    @GET("api/team/referral/")
    suspend fun referral(): Response<ApiResponse<ReferralInfoDto>>

    @GET("api/team/summary/")
    suspend fun summary(): Response<ApiResponse<TeamSummaryDto>>

    /** [level] null = every level (the backend supports levels beyond 21). */
    @GET("api/team/members/")
    suspend fun members(
        @Query("level") level: Int?,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): Response<ApiResponse<TeamMembersPageDto>>

    @GET("api/team/commissions/")
    suspend fun commissions(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int
    ): Response<ApiResponse<CommissionPageDto>>
}
