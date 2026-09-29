package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.AdminOrderApi
import com.example.hawk_wallet.data.remote.model.AdminOfferDto
import com.example.hawk_wallet.data.remote.model.AdminOrderDto
import com.example.hawk_wallet.data.remote.model.CancelPayoutRequest
import com.example.hawk_wallet.data.remote.model.ConfirmPayInRequest
import com.example.hawk_wallet.data.remote.model.CreatePayoutRequest
import com.example.hawk_wallet.data.remote.model.MarkPayoutPaidRequest
import com.example.hawk_wallet.data.remote.model.PayoutDto
import com.example.hawk_wallet.data.remote.model.RejectPayInRequest
import com.example.hawk_wallet.data.remote.model.ReviewedPayInDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private val TEXT = "text/plain".toMediaType()

/**
 * Admin views of every member's orders, offer creation, pay-in review and
 * payouts (the SELL side: platform -> member).
 * The backend decides who may act and owns every amount and status;
 * values are returned as it sent them.
 */
class AdminOrderRepository(
    private val api: AdminOrderApi,
    private val qrImageReader: QrImageReader
) {

    suspend fun orders(): ApiResult<List<AdminOrderDto>> =
        when (val result = safeApiCall { api.orders() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.orders, result.message)
            is ApiResult.Error -> result
        }

    suspend fun order(id: Long): ApiResult<AdminOrderDto> = safeApiCall { api.order(id) }

    /**
     * POST multipart. [payInQrUri] is the picked Pay-In QR image; the backend
     * decodes it and owns the payment details. Retrying with the same
     * [idempotencyKey] and values returns the original offer instead of a new one.
     */
    suspend fun createOffer(
        orderAmount: String,
        incomeRate: String,
        quantityAvailable: Int?,
        isActive: Boolean,
        payInQrUri: String,
        idempotencyKey: String
    ): ApiResult<AdminOfferDto> {
        val upload = try {
            qrImageReader.read(payInQrUri)
        } catch (e: QrImageException) {
            val message = e.message ?: "We couldn't read that image."
            return ApiResult.Error(message, fieldErrors = mapOf("pay_in_qr" to listOf(message)))
        }
        val qrPart = MultipartBody.Part.createFormData(
            "pay_in_qr",
            upload.fileName,
            upload.bytes.toRequestBody(upload.mimeType.toMediaType())
        )
        return safeApiCall {
            api.createOffer(
                orderAmount = orderAmount.toRequestBody(TEXT),
                incomeRate = incomeRate.toRequestBody(TEXT),
                quantityAvailable = quantityAvailable?.toString()?.toRequestBody(TEXT),
                isActive = isActive.toString().toRequestBody(TEXT),
                idempotencyKey = idempotencyKey.toRequestBody(TEXT),
                payInQr = qrPart
            )
        }
    }

    /** Retrying with the same [idempotencyKey] returns the same review instead of reviewing twice. */
    suspend fun confirmPayIn(payInId: Long, idempotencyKey: String): ApiResult<ReviewedPayInDto> =
        safeApiCall { api.confirmPayIn(payInId, ConfirmPayInRequest(idempotencyKey)) }

    /** Retrying with the same [idempotencyKey] and reason returns the same review instead of reviewing twice. */
    suspend fun rejectPayIn(payInId: Long, reason: String, idempotencyKey: String): ApiResult<ReviewedPayInDto> =
        safeApiCall { api.rejectPayIn(payInId, RejectPayInRequest(reason, idempotencyKey)) }

    /**
     * [amount] is sent exactly as typed; the backend validates it against what
     * is still uncommitted. Retrying with the same [idempotencyKey] and amount
     * returns the original payout instead of creating a second one.
     */
    suspend fun createPayout(orderId: Long, amount: String, idempotencyKey: String): ApiResult<PayoutDto> =
        safeApiCall { api.createPayout(orderId, CreatePayoutRequest(amount, idempotencyKey)) }

    /** Retrying with the same UTR returns the already-paid payout instead of paying twice. */
    suspend fun markPayoutPaid(payoutId: Long, utr: String, idempotencyKey: String): ApiResult<PayoutDto> =
        safeApiCall { api.markPayoutPaid(payoutId, MarkPayoutPaidRequest(utr, idempotencyKey)) }

    /** Retrying with the same reason returns the already-cancelled payout. */
    suspend fun cancelPayout(payoutId: Long, reason: String, idempotencyKey: String): ApiResult<PayoutDto> =
        safeApiCall { api.cancelPayout(payoutId, CancelPayoutRequest(reason, idempotencyKey)) }

    /** The payout destination's QR image bytes, fetched through the authorized, audited endpoint. */
    suspend fun payoutQr(payoutId: Long): ApiResult<ByteArray> =
        safeBytesCall { api.payoutQr(payoutId) }
}
