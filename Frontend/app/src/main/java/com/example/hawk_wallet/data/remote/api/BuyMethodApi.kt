package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AddBuyMethodRequest
import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.BuyMethodDto
import com.example.hawk_wallet.data.remote.model.BuyMethodListData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The member's own PERSONAL / BUY methods (accounts they pay for orders
 * from). Separate from [PayoutMethodApi] (BUSINESS / SELL); authenticated by
 * the shared JWT interceptor.
 */
interface BuyMethodApi {
    @GET("api/buy-methods/")
    suspend fun list(): Response<ApiResponse<BuyMethodListData>>

    @POST("api/buy-methods/")
    suspend fun add(@Body request: AddBuyMethodRequest): Response<ApiResponse<BuyMethodDto>>

    @POST("api/buy-methods/{id}/default/")
    suspend fun setDefault(@Path("id") id: Long): Response<ApiResponse<BuyMethodDto>>

    @POST("api/buy-methods/{id}/deactivate/")
    suspend fun deactivate(@Path("id") id: Long): Response<ApiResponse<BuyMethodDto>>
}
