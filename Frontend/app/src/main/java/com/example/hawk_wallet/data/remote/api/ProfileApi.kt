package com.example.hawk_wallet.data.remote.api

import com.example.hawk_wallet.data.remote.model.ApiResponse
import com.example.hawk_wallet.data.remote.model.ChangePasswordRequest
import com.example.hawk_wallet.data.remote.model.ProfileDto
import com.example.hawk_wallet.data.remote.model.TokenPairDto
import com.example.hawk_wallet.data.remote.model.UpdateProfileRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

/** The signed-in user's own profile; the backend never answers for anyone else. */
interface ProfileApi {
    @GET("api/profile/")
    suspend fun getProfile(): Response<ApiResponse<ProfileDto>>

    @PATCH("api/profile/")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<ApiResponse<ProfileDto>>

    @POST("api/profile/change-password/")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<ApiResponse<TokenPairDto>>
}
