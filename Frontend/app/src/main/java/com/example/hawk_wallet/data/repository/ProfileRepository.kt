package com.example.hawk_wallet.data.repository

import com.example.hawk_wallet.data.remote.api.ProfileApi
import com.example.hawk_wallet.data.remote.model.ChangePasswordRequest
import com.example.hawk_wallet.data.remote.model.ProfileDto
import com.example.hawk_wallet.data.remote.model.TokenPairDto
import com.example.hawk_wallet.data.remote.model.UpdateProfileRequest

/** Profile read, name change and password change; the backend validates everything. */
class ProfileRepository(private val api: ProfileApi) {

    suspend fun getProfile(): ApiResult<ProfileDto> = safeApiCall { api.getProfile() }

    /** Sends the name only - mobile, role and referral code are never editable. */
    suspend fun updateName(name: String): ApiResult<ProfileDto> =
        safeApiCall { api.updateProfile(UpdateProfileRequest(name)) }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String
    ): ApiResult<TokenPairDto> = safeApiCall {
        api.changePassword(ChangePasswordRequest(currentPassword, newPassword, confirmPassword))
    }
}
