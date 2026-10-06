package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.HomeData
import com.example.hawk_wallet.data.remote.model.TransactionHistoryData
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface HomeApi {
    // Authenticated by the shared client's JWT interceptor; the backend
    // always returns the dashboard of the signed-in member.
    @GET("api/home/")
    suspend fun getHome(): Response<ApiResponse<HomeData>>

    /**
     * The member's own transaction history. [range] is 7d / 15d / 30d /
     * custom; [startDate] / [endDate] (YYYY-MM-DD) are sent only for custom.
     * [type] is all / buy (pay-ins) / sell (payouts).
     */
    @GET("api/home/transactions/")
    suspend fun getTransactions(
        @Query("range") range: String,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("type") type: String,
        @Query("page") page: Int
    ): Response<ApiResponse<TransactionHistoryData>>
}
