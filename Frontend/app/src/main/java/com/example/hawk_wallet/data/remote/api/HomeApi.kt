package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.HomeData
import retrofit2.Response
import retrofit2.http.GET

interface HomeApi {
    // Authenticated by the shared client's JWT interceptor; the backend
    // always returns the dashboard of the signed-in member.
    @GET("api/home/")
    suspend fun getHome(): Response<ApiResponse<HomeData>>
}
