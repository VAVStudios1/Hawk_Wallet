package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AdminUserDetailDto
import com.example.hawk_wallet.data.remote.model.AdminUserListData
import com.example.hawk_wallet.data.remote.model.ApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Admin-only, read-only member directory; authenticated by the shared JWT interceptor. */
interface AdminUserApi {
    /** One page of member accounts; [isActive] / [isVerified] are "true" / "false". */
    @GET("api/admin/users/")
    suspend fun users(
        @Query("is_active") isActive: String? = null,
        @Query("is_verified") isVerified: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminUserListData>>

    @GET("api/admin/users/{id}/")
    suspend fun user(@Path("id") userId: Long): Response<ApiResponse<AdminUserDetailDto>>
}
