package com.example.hawk_wallet.data.remote.model

import com.google.gson.JsonElement

data class ApiResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T?,
    val errors: Map<String, JsonElement>?
)

fun JsonElement?.toMessages(): List<String> {
    if (this == null || isJsonNull) return emptyList()
    return when {
        isJsonArray -> asJsonArray.mapNotNull { element ->
            if (element.isJsonPrimitive) element.asString else null
        }
        isJsonPrimitive -> listOf(asString)
        else -> emptyList()
    }
}

data class RegisterRequest(
    val name: String,
    val mobile_number: String,
    val password: String,
    val confirm_password: String
)

data class RegisterData(
    val mobile_number: String
)

data class LoginRequest(
    val mobile_number: String,
    val password: String
)

data class UserDto(
    val id: Long,
    val name: String,
    val mobile_number: String,
    val role: String
)

/** POST api/auth/login/ - password login issues the session directly (no OTP). */
data class LoginData(
    val access: String,
    val refresh: String,
    val user: UserDto
)

data class VerifyOtpRequest(
    val mobile_number: String,
    val otp: String
)

data class ResendOtpRequest(
    val mobile_number: String,
    val purpose: String
)

/**
 * POST api/auth/token/refresh/. idle_seconds is the time since the user's
 * last real interaction; null (omitted by Gson) claims no activity.
 */
data class TokenRefreshRequest(
    val refresh: String,
    val idle_seconds: Long?
)

data class PasswordResetRequest(
    val mobile_number: String
)

data class PasswordResetVerifyData(
    val reset_token: String,
    val expires_in_seconds: Int
)

data class PasswordResetConfirmRequest(
    val reset_token: String,
    val new_password: String,
    val confirm_password: String
)
