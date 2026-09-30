package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.toMessages
import com.google.gson.Gson
import com.google.gson.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

sealed class ApiResult<out T> {
    data class Success<T>(val data: T, val message: String) : ApiResult<T>()
    data class Error(
        val message: String,
        val fieldErrors: Map<String, List<String>> = emptyMap(),
        val statusCode: Int? = null
    ) : ApiResult<Nothing>() {
        /** The backend's stable machine-readable `errors.code`, when it sent one. */
        val errorCode: String?
            get() = fieldErrors["code"]?.firstOrNull()
    }
}

/**
 * Mirrors the backend's error envelope shape, used only to parse
 * Response.errorBody() - Retrofit never runs the success converter on it.
 */
private data class ApiErrorBody(
    val message: String? = null,
    val errors: Map<String, JsonElement>? = null
)

private val gson = Gson()

/** Runs one API call and maps the backend envelope (or a failure) to [ApiResult]. */
internal suspend fun <T> safeApiCall(block: suspend () -> Response<ApiResponse<T>>): ApiResult<T> {
    return try {
        toApiResult(block())
    } catch (e: IOException) {
        ApiResult.Error("Unable to reach the server. Check your connection.")
    } catch (e: Exception) {
        ApiResult.Error("Something went wrong. Please try again.")
    }
}

/**
 * Like [safeApiCall], for endpoints that return a raw file (e.g. a QR image)
 * instead of the JSON envelope. Errors still arrive as the envelope and are
 * mapped the same way.
 */
internal suspend fun safeBytesCall(block: suspend () -> Response<ResponseBody>): ApiResult<ByteArray> {
    return try {
        val response = block()
        val body = response.body()
        if (response.isSuccessful && body != null) {
            ApiResult.Success(body.use { it.bytes() }, "")
        } else {
            errorResult(response, fallbackMessage = null)
        }
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
    return errorResult(response, fallbackMessage = body?.message)
}

private fun errorResult(response: Response<*>, fallbackMessage: String?): ApiResult.Error {
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
        ?: fallbackMessage
        ?: "Something went wrong. Please try again."
    val fieldErrors = errorBody?.errors?.mapValues { (_, value) -> value.toMessages() } ?: emptyMap()
    return ApiResult.Error(message, fieldErrors, response.code())
}
