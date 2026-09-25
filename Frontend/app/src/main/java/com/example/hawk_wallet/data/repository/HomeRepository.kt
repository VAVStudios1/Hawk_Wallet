package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.HomeApi
import com.example.hawk_wallet.data.remote.model.HomeData

/** Fetches the member dashboard. Values are returned exactly as the backend computed them. */
class HomeRepository(private val homeApi: HomeApi) {

    suspend fun getHome(): ApiResult<HomeData> = safeApiCall { homeApi.getHome() }
}
