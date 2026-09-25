package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.PayoutMethodDto
import com.example.hawk_wallet.data.remote.model.PayoutMethodListData
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/** The member's own payout methods; authenticated by the shared JWT interceptor. */
interface PayoutMethodApi {
    @GET("api/payout-methods/")
    suspend fun list(): Response<ApiResponse<PayoutMethodListData>>

    // Part names must match the backend's AddPayoutMethodSerializer exactly.
    @Multipart
    @POST("api/payout-methods/")
    suspend fun add(
        @Part("provider_code") providerCode: RequestBody,
        @Part("upi_id") upiId: RequestBody,
        @Part("label") label: RequestBody,
        @Part qrImage: MultipartBody.Part
    ): Response<ApiResponse<PayoutMethodDto>>

    @POST("api/payout-methods/{id}/default/")
    suspend fun setDefault(@Path("id") id: Long): Response<ApiResponse<PayoutMethodDto>>

    @POST("api/payout-methods/{id}/deactivate/")
    suspend fun deactivate(@Path("id") id: Long): Response<ApiResponse<PayoutMethodDto>>
}
