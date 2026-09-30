package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AuthApi
import com.example.hawk_wallet.data.remote.model.LoginData
import com.example.hawk_wallet.data.remote.model.LoginRequest
import com.example.hawk_wallet.data.remote.model.LogoutRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetConfirmRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetRequest
import com.example.hawk_wallet.data.remote.model.PasswordResetVerifyData
import com.example.hawk_wallet.data.remote.model.RegisterData
import com.example.hawk_wallet.data.remote.model.RegisterRequest
import com.example.hawk_wallet.data.remote.model.ResendOtpRequest
import com.example.hawk_wallet.data.remote.model.VerifyOtpRequest

private const val PURPOSE_REGISTRATION = "registration"

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

    suspend fun resendRegistrationOtp(mobileNumber: String): ApiResult<Unit> =
        safeApiCall { authApi.resendOtp(ResendOtpRequest(mobileNumber, PURPOSE_REGISTRATION)) }.mapToUnit()

    suspend fun login(mobileNumber: String, password: String): ApiResult<LoginData> = safeApiCall {
        authApi.login(LoginRequest(mobileNumber, password))
    }

    suspend fun logout(refreshToken: String): ApiResult<Unit> =
        safeApiCall { authApi.logout(LogoutRequest(refreshToken)) }.mapToUnit()

    suspend fun requestPasswordReset(mobileNumber: String): ApiResult<Unit> =
        safeApiCall { authApi.requestPasswordReset(PasswordResetRequest(mobileNumber)) }.mapToUnit()

    suspend fun verifyPasswordReset(mobileNumber: String, otp: String): ApiResult<PasswordResetVerifyData> =
        safeApiCall { authApi.verifyPasswordReset(VerifyOtpRequest(mobileNumber, otp)) }

    suspend fun confirmPasswordReset(
        resetToken: String,
        newPassword: String,
        confirmPassword: String
    ): ApiResult<Unit> = safeApiCall {
        authApi.confirmPasswordReset(PasswordResetConfirmRequest(resetToken, newPassword, confirmPassword))
    }.mapToUnit()

    private fun <T> ApiResult<T>.mapToUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit, message)
        is ApiResult.Error -> this
    }
}
