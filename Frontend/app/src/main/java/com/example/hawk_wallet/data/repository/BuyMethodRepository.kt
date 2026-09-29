package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.BuyMethodApi
import com.example.hawk_wallet.data.remote.model.AddBuyMethodRequest
import com.example.hawk_wallet.data.remote.model.BuyMethodListItemDto

/**
 * The member's PERSONAL / BUY methods. The backend owns every rule (default,
 * duplicates, which providers are allowed, validation).
 */
class BuyMethodRepository(private val api: BuyMethodApi) {

    suspend fun list(): ApiResult<List<BuyMethodListItemDto>> =
        when (val result = safeApiCall { api.list() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.methods, result.message)
            is ApiResult.Error -> result
        }

    suspend fun add(providerCode: String, upiId: String, label: String): ApiResult<Unit> =
        safeApiCall { api.add(AddBuyMethodRequest(providerCode, upiId, label)) }.toUnit()

    suspend fun setDefault(id: Long): ApiResult<Unit> = safeApiCall { api.setDefault(id) }.toUnit()

    suspend fun deactivate(id: Long): ApiResult<Unit> = safeApiCall { api.deactivate(id) }.toUnit()

    private fun <T> ApiResult<T>.toUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit, message)
        is ApiResult.Error -> this
    }
}
