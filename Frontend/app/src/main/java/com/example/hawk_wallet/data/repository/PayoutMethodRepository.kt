package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.PayoutMethodApi
import com.example.hawk_wallet.data.remote.model.PayoutMethodListItemDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private val TEXT = "text/plain".toMediaType()

/** The member's payout methods. The backend owns every rule (default, duplicates, validation). */
class PayoutMethodRepository(
    private val api: PayoutMethodApi,
    private val qrImageReader: QrImageReader
) {

    suspend fun list(): ApiResult<List<PayoutMethodListItemDto>> =
        when (val result = safeApiCall { api.list() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.methods, result.message)
            is ApiResult.Error -> result
        }

    /** POST multipart: provider_code, upi_id, label, qr_image (the provider is identified by its code). */
    suspend fun add(providerCode: String, upiId: String, label: String, qrImageUri: String): ApiResult<Unit> {
        val upload = try {
            qrImageReader.read(qrImageUri)
        } catch (e: QrImageException) {
            val message = e.message ?: "We couldn't read that image."
            return ApiResult.Error(message, fieldErrors = mapOf("qr_image" to listOf(message)))
        }
        val qrPart = MultipartBody.Part.createFormData(
            "qr_image",
            upload.fileName,
            upload.bytes.toRequestBody(upload.mimeType.toMediaType())
        )
        return safeApiCall {
            api.add(
                providerCode = providerCode.toRequestBody(TEXT),
                upiId = upiId.toRequestBody(TEXT),
                label = label.toRequestBody(TEXT),
                qrImage = qrPart
            )
        }.toUnit()
    }

    suspend fun setDefault(id: Long): ApiResult<Unit> = safeApiCall { api.setDefault(id) }.toUnit()

    suspend fun deactivate(id: Long): ApiResult<Unit> = safeApiCall { api.deactivate(id) }.toUnit()

    private fun <T> ApiResult<T>.toUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit, message)
        is ApiResult.Error -> this
    }
}
