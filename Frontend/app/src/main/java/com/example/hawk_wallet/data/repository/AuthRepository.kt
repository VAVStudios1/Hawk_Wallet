package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AuthApi
import com.example.hawk_wallet.data.remote.model.LoginData
import com.example.hawk_wallet.data.remote.model.LoginRequest
import com.example.hawk_wallet.data.remote.model.LogoutRequest
import com.example.hawk_wallet.data.remote.model.RegisterData
import com.example.hawk_wallet.data.remote.model.RegisterRequest
import com.example.hawk_wallet.data.remote.model.ResendOtpRequest
import com.example.hawk_wallet.data.remote.model.VerifyLoginOtpData
import com.example.hawk_wallet.data.remote.model.VerifyOtpRequest

class AuthRepository(private val authApi: AuthApi) {

    suspend fun register(
        name: String,
        mobileNumber: String,
        password: String,
        confirmPassword: String
    ): ApiResult<RegisterData> = safeApiCall {
        authApi.register(RegisterRequest(name, mobileNumber, password, confirmPassword))
    }

    suspend fun verifyRegistrationOtp(mobileNumber: String, otp: String): ApiResult<Unit> =
        safeApiCall { authApi.verifyRegistrationOtp(VerifyOtpRequest(mobileNumber, otp)) }.mapToUnit()

    suspend fun login(mobileNumber: String, password: String): ApiResult<LoginData> = safeApiCall {
        authApi.login(LoginRequest(mobileNumber, password))
    }

    suspend fun verifyLoginOtp(mobileNumber: String, otp: String): ApiResult<VerifyLoginOtpData> = safeApiCall {
        authApi.verifyLoginOtp(VerifyOtpRequest(mobileNumber, otp))
    }

    suspend fun resendOtp(mobileNumber: String, purpose: String): ApiResult<Unit> =
        safeApiCall { authApi.resendOtp(ResendOtpRequest(mobileNumber, purpose)) }.mapToUnit()

    suspend fun logout(refreshToken: String): ApiResult<Unit> =
        safeApiCall { authApi.logout(LogoutRequest(refreshToken)) }.mapToUnit()

    private fun <T> ApiResult<T>.mapToUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit, message)
        is ApiResult.Error -> this
    }
}
