package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.LoginData
import com.example.hawk_wallet.data.remote.model.LoginRequest
import com.example.hawk_wallet.data.remote.model.LogoutRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetConfirmRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetVerifyData
import com.example.hawk_wallet.data.remote.model.RegisterData
import com.example.hawk_wallet.data.remote.model.RegisterRequest
import com.example.hawk_wallet.data.remote.model.ResendOtpRequest
import com.example.hawk_wallet.data.remote.model.TokenPairDto
import com.example.hawk_wallet.data.remote.model.TokenRefreshRequest
import com.example.hawk_wallet.data.remote.model.VerifyOtpRequest
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** Every api/auth/ endpoint is anonymous: NetworkModule never attaches a JWT to them. */
interface AuthApi {
    @POST("api/auth/register/")
    suspend fun register(@Body request: RegisterRequest): Response<ApiResponse<RegisterData>>

    @POST("api/auth/verify-otp/")
    suspend fun verifyRegistrationOtp(@Body request: VerifyOtpRequest): Response<ApiResponse<Map<String, Any?>>>

    /** Registration OTPs only (the backend refuses any other purpose). */
    @POST("api/auth/resend-otp/")
    suspend fun resendOtp(@Body request: ResendOtpRequest): Response<ApiResponse<Map<String, Any?>>>

    @POST("api/auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginData>>

    /** Blacklists this device's refresh token. Needs no access token. */
    @POST("api/auth/logout/")
    suspend fun logout(@Body request: LogoutRequest): Response<ApiResponse<Map<String, Any?>>>

    /** Always answers the same way, whether or not the number has an account. */
    @POST("api/auth/password-reset/request/")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequest): Response<ApiResponse<Map<String, Any?>>>

    @POST("api/auth/password-reset/verify/")
    suspend fun verifyPasswordReset(@Body request: VerifyOtpRequest): Response<ApiResponse<PasswordResetVerifyData>>

    @POST("api/auth/password-reset/confirm/")
    suspend fun confirmPasswordReset(@Body request: PasswordResetConfirmRequest): Response<ApiResponse<Map<String, Any?>>>
}

/**
 * Token refresh, on its own client (see NetworkModule): it runs inside the
 * OkHttp authenticator, so it is a blocking Call and must never itself go
 * through the authenticator.
 */
interface TokenRefreshApi {
    @POST("api/auth/token/refresh/")
    fun refresh(@Body request: TokenRefreshRequest): Call<ApiResponse<TokenPairDto>>
}
