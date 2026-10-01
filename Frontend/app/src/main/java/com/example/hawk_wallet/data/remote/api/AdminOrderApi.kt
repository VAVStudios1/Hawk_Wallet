package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.AdminOfferDto
import com.example.hawk_wallet.data.remote.model.AdminOrderDto
import com.example.hawk_wallet.data.remote.model.AdminOrderListData
import com.example.hawk_wallet.data.remote.model.AdminPayInItemDto
import com.example.hawk_wallet.data.remote.model.AdminPayInListData
import com.example.hawk_wallet.data.remote.model.AdminPayoutItemDto
import com.example.hawk_wallet.data.remote.model.AdminPayoutListData
import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.CancelPayoutRequest
import com.example.hawk_wallet.data.remote.model.ConfirmPayInRequest
import com.example.hawk_wallet.data.remote.model.CreatePayoutRequest
import com.example.hawk_wallet.data.remote.model.MarkPayoutPaidRequest
import com.example.hawk_wallet.data.remote.model.PayoutDto
import com.example.hawk_wallet.data.remote.model.RejectPayInRequest
import com.example.hawk_wallet.data.remote.model.ReviewedPayInDto
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Admin-only order views, offer creation, pay-in review and payouts; authenticated by the shared JWT interceptor. */
interface AdminOrderApi {
    /** One page; [status] is a backend Order.Status value, [search] is matched by the server. */
    @GET("api/admin/orders/")
    suspend fun orders(
        @Query("status") status: String? = null,
        /** "true": only orders an admin has a pending step on (the backend defines which). */
        @Query("awaiting_action") awaitingAction: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminOrderListData>>

    /** One page of pay-ins; [status] is a backend PayIn.Status value. Carries no BUY-method details. */
    @GET("api/admin/pay-ins/")
    suspend fun payIns(
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminPayInListData>>

    /** Reveals the member's BUY account, so the backend audits the call. */
    @GET("api/admin/pay-ins/{id}/")
    suspend fun payIn(@Path("id") payInId: Long): Response<ApiResponse<AdminPayInItemDto>>

    /** One page of payouts; [status] is a backend Payout.Status value. Carries no UPI ID. */
    @GET("api/admin/payouts/")
    suspend fun payouts(
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_size") pageSize: Int? = null
    ): Response<ApiResponse<AdminPayoutListData>>

    /** Reveals the member's UPI ID, so the backend audits the call. */
    @GET("api/admin/payouts/{id}/")
    suspend fun payout(@Path("id") payoutId: Long): Response<ApiResponse<AdminPayoutItemDto>>

    /** Reveals the member's UPI ID, so the backend audits every call. */
    @GET("api/admin/orders/{id}/")
    suspend fun order(@Path("id") id: Long): Response<ApiResponse<AdminOrderDto>>

    // Part names must match the backend's CreateOfferSerializer exactly. Income,
    // payout target and the Pay-In UPI details are never sent: the server
    // computes them / decodes them from the QR. A null quantity part is
    // omitted, which the backend reads as no limit.
    @Multipart
    @POST("api/admin/offers/")
    suspend fun createOffer(
        @Part("order_amount") orderAmount: RequestBody,
        @Part("income_rate") incomeRate: RequestBody,
        @Part("quantity_available") quantityAvailable: RequestBody?,
        @Part("is_active") isActive: RequestBody,
        @Part("idempotency_key") idempotencyKey: RequestBody,
        @Part payInQr: MultipartBody.Part
    ): Response<ApiResponse<AdminOfferDto>>

    @POST("api/admin/pay-ins/{id}/confirm/")
    suspend fun confirmPayIn(
        @Path("id") payInId: Long,
        @Body request: ConfirmPayInRequest
    ): Response<ApiResponse<ReviewedPayInDto>>

    @POST("api/admin/pay-ins/{id}/reject/")
    suspend fun rejectPayIn(
        @Path("id") payInId: Long,
        @Body request: RejectPayInRequest
    ): Response<ApiResponse<ReviewedPayInDto>>

    @POST("api/admin/orders/{id}/payouts/")
    suspend fun createPayout(
        @Path("id") orderId: Long,
        @Body request: CreatePayoutRequest
    ): Response<ApiResponse<PayoutDto>>

    @POST("api/admin/payouts/{id}/paid/")
    suspend fun markPayoutPaid(
        @Path("id") payoutId: Long,
        @Body request: MarkPayoutPaidRequest
    ): Response<ApiResponse<PayoutDto>>

    @POST("api/admin/payouts/{id}/cancel/")
    suspend fun cancelPayout(
        @Path("id") payoutId: Long,
        @Body request: CancelPayoutRequest
    ): Response<ApiResponse<PayoutDto>>

    /**
     * The QR image of the SELL payout method fixed on this payout, as raw
     * image bytes (not the JSON envelope). The backend audits every call.
     */
    @GET("api/admin/payouts/{id}/payout-method/qr/")
    suspend fun payoutQr(@Path("id") payoutId: Long): Response<ResponseBody>
}
