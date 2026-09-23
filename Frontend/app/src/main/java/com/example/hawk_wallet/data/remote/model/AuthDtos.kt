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

data class LoginData(
    val mobile_number: String
)

data class VerifyOtpRequest(
    val mobile_number: String,
    val otp: String
)

data class ResendOtpRequest(
    val mobile_number: String,
    val purpose: String
)

data class UserDto(
    val id: Long,
    val name: String,
    val mobile_number: String,
    val role: String
)

data class VerifyLoginOtpData(
    val access: String,
    val refresh: String,
    val user: UserDto
)
