package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.HomeApi
import com.example.hawk_wallet.data.remote.model.HomeData
import com.example.hawk_wallet.data.remote.model.TransactionHistoryData

/** Fetches the member dashboard. Values are returned exactly as the backend computed them. */
class HomeRepository(private val homeApi: HomeApi) {

    suspend fun getHome(): ApiResult<HomeData> = safeApiCall { homeApi.getHome() }

    suspend fun getTransactions(
        range: String,
        startDate: String?,
        endDate: String?,
        type: String,
        page: Int
    ): ApiResult<TransactionHistoryData> =
        safeApiCall { homeApi.getTransactions(range, startDate, endDate, type, page) }
}
