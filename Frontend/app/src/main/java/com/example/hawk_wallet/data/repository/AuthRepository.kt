package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AuthApi
import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.LoginData
import com.example.hawk_wallet.data.remote.model.LoginRequest
import com.example.hawk_wallet.data.remote.model.RegisterData
import com.example.hawk_wallet.data.remote.model.RegisterRequest
import com.example.hawk_wallet.data.remote.model.ResendOtpRequest
import com.example.hawk_wallet.data.remote.model.VerifyLoginOtpData
import com.example.hawk_wallet.data.remote.model.VerifyOtpRequest
import com.example.hawk_wallet.data.remote.model.toMessages
import com.google.gson.Gson
import com.google.gson.JsonElement
import retrofit2.Response
import java.io.IOException

sealed class ApiResult<out T> {
    data class Success<T>(val data: T, val message: String) : ApiResult<T>()
    data class Error(
        val message: String,
        val fieldErrors: Map<String, List<String>> = emptyMap(),
        val statusCode: Int? = null
    ) : ApiResult<Nothing>()
}

/**
 * Mirrors the backend's error envelope shape, used only to parse
 * Response.errorBody() - Retrofit never runs the success converter on it.
 */
private data class ApiErrorBody(
    val message: String? = null,
    val errors: Map<String, JsonElement>? = null
)

class AuthRepository(private val authApi: AuthApi) {

    private val gson = Gson()

    suspend fun register(
        name: String,
        mobileNumber: String,
        password: String,
        confirmPassword: String
    ): ApiResult<RegisterData> = safeCall {
        authApi.register(RegisterRequest(name, mobileNumber, password, confirmPassword))
    }

    suspend fun verifyRegistrationOtp(mobileNumber: String, otp: String): ApiResult<Unit> =
        safeCall { authApi.verifyRegistrationOtp(VerifyOtpRequest(mobileNumber, otp)) }.mapToUnit()

    suspend fun login(mobileNumber: String, password: String): ApiResult<LoginData> = safeCall {
        authApi.login(LoginRequest(mobileNumber, password))
    }

    suspend fun verifyLoginOtp(mobileNumber: String, otp: String): ApiResult<VerifyLoginOtpData> = safeCall {
        authApi.verifyLoginOtp(VerifyOtpRequest(mobileNumber, otp))
    }

    suspend fun resendOtp(mobileNumber: String, purpose: String): ApiResult<Unit> =
        safeCall { authApi.resendOtp(ResendOtpRequest(mobileNumber, purpose)) }.mapToUnit()

    private suspend fun <T> safeCall(block: suspend () -> Response<ApiResponse<T>>): ApiResult<T> {
        return try {
            toApiResult(block())
        } catch (e: IOException) {
            ApiResult.Error("Unable to reach the server. Check your connection.")
        } catch (e: Exception) {
            ApiResult.Error("Something went wrong. Please try again.")
        }
    }

    private fun <T> toApiResult(response: Response<ApiResponse<T>>): ApiResult<T> {
        val body = response.body()
        if (response.isSuccessful && body != null && body.success) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Success(body.data as T, body.message)
        }

        // Retrofit only runs the converter on body() for 2xx responses; a
        // 4xx/5xx's real message/errors live in errorBody() and must be
        // parsed manually, or we fall back to a raw "Request failed (400)".
        val errorBody = try {
            response.errorBody()?.charStream()?.use { reader ->
                gson.fromJson(reader, ApiErrorBody::class.java)
            }
        } catch (e: Exception) {
            null
        }

        val message = errorBody?.message
            ?: body?.message
            ?: "Something went wrong. Please try again."
        val fieldErrors = errorBody?.errors?.mapValues { (_, value) -> value.toMessages() } ?: emptyMap()
        return ApiResult.Error(message, fieldErrors, response.code())
    }

    private fun <T> ApiResult<T>.mapToUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit, message)
        is ApiResult.Error -> this
    }
}
