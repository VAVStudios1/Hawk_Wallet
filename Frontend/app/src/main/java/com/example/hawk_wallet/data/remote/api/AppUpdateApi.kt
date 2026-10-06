package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.UpdateCheckDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Public endpoint (no JWT): an outdated or logged-out app must still reach it. */
interface AppUpdateApi {
    @GET("api/app/update-check/")
    suspend fun checkForUpdate(@Query("version") version: String): Response<ApiResponse<UpdateCheckDto>>
}
