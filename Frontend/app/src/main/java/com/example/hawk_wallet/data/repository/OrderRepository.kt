package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.OrderApi
import com.example.hawk_wallet.data.remote.model.ClaimOrderRequest
import com.example.hawk_wallet.data.remote.model.OfferDto
import com.example.hawk_wallet.data.remote.model.OrderDto
import com.example.hawk_wallet.data.remote.model.PayInDto
import com.example.hawk_wallet.data.remote.model.PayInIntentDto
import com.example.hawk_wallet.data.remote.model.SubmitPayInRequest

/**
 * Offers, the member's orders and pay-ins. The backend owns every rule and
 * every amount; values are returned exactly as it sent them.
 */
class OrderRepository(private val api: OrderApi) {

    suspend fun offers(): ApiResult<List<OfferDto>> =
        when (val result = safeApiCall { api.offers() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.offers, result.message)
            is ApiResult.Error -> result
        }

    suspend fun orders(): ApiResult<List<OrderDto>> =
        when (val result = safeApiCall { api.orders() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.orders, result.message)
            is ApiResult.Error -> result
        }

    suspend fun order(id: Long): ApiResult<OrderDto> = safeApiCall { api.order(id) }

    /** Retrying with the same [idempotencyKey] returns the original order instead of a new one. */
    suspend fun claim(offerId: Long, payoutMethodId: Long, idempotencyKey: String): ApiResult<OrderDto> =
        safeApiCall { api.claim(ClaimOrderRequest(offerId, idempotencyKey, payoutMethodId)) }

    suspend fun payInIntent(orderId: Long): ApiResult<PayInIntentDto> = safeApiCall { api.payInIntent(orderId) }

    /**
     * Retrying with the same [idempotencyKey] returns the original pay-in instead of a new one.
     * [buyMethodId] is the member's PERSONAL / BUY method they paid from.
     */
    suspend fun submitPayIn(
        orderId: Long,
        utr: String,
        idempotencyKey: String,
        intentToken: String,
        buyMethodId: Long
    ): ApiResult<PayInDto> =
        safeApiCall { api.submitPayIn(orderId, SubmitPayInRequest(utr, idempotencyKey, intentToken, buyMethodId)) }
}
