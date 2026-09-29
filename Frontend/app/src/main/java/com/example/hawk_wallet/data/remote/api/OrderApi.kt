package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.ClaimOrderRequest
import com.example.hawk_wallet.data.remote.model.OfferListData
import com.example.hawk_wallet.data.remote.model.OrderDto
import com.example.hawk_wallet.data.remote.model.OrderListData
import com.example.hawk_wallet.data.remote.model.PayInDto
import com.example.hawk_wallet.data.remote.model.PayInIntentDto
import com.example.hawk_wallet.data.remote.model.SubmitPayInRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Member offers, orders and pay-ins; authenticated by the shared JWT interceptor. */
interface OrderApi {
    @GET("api/orders/offers/")
    suspend fun offers(): Response<ApiResponse<OfferListData>>

    @GET("api/orders/")
    suspend fun orders(): Response<ApiResponse<OrderListData>>

    @GET("api/orders/{id}/")
    suspend fun order(@Path("id") id: Long): Response<ApiResponse<OrderDto>>

    @POST("api/orders/claim/")
    suspend fun claim(@Body request: ClaimOrderRequest): Response<ApiResponse<OrderDto>>

    @GET("api/orders/{id}/pay-in-intent/")
    suspend fun payInIntent(@Path("id") orderId: Long): Response<ApiResponse<PayInIntentDto>>

    @POST("api/orders/{id}/pay-in/")
    suspend fun submitPayIn(
        @Path("id") orderId: Long,
        @Body request: SubmitPayInRequest
    ): Response<ApiResponse<PayInDto>>
}
