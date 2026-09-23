package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.LoginData
import com.example.hawk_wallet.data.remote.model.LoginRequest
import com.example.hawk_wallet.data.remote.model.RegisterData
import com.example.hawk_wallet.data.remote.model.RegisterRequest
import com.example.hawk_wallet.data.remote.model.ResendOtpRequest
import com.example.hawk_wallet.data.remote.model.VerifyLoginOtpData
import com.example.hawk_wallet.data.remote.model.VerifyOtpRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {
    @POST("api/auth/register/")
    suspend fun register(@Body request: RegisterRequest): Response<ApiResponse<RegisterData>>

    @POST("api/auth/verify-otp/")
    suspend fun verifyRegistrationOtp(@Body request: VerifyOtpRequest): Response<ApiResponse<Map<String, Any?>>>

    @POST("api/auth/resend-otp/")
    suspend fun resendOtp(@Body request: ResendOtpRequest): Response<ApiResponse<Map<String, Any?>>>

    @POST("api/auth/login/")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginData>>

    @POST("api/auth/verify-login-otp/")
    suspend fun verifyLoginOtp(@Body request: VerifyOtpRequest): Response<ApiResponse<VerifyLoginOtpData>>
}
