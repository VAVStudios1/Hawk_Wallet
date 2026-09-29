package com.example.hawk_wallet.data.remote.model

/**
 * GET/PATCH /api/profile/. The backend also sends the user id, which the app
 * never shows, so it is not mapped. No financial figures come from here.
 */
data class ProfileDto(
    val name: String,
    val mobile_number: String,
    val role: String,
    val is_verified: Boolean,
    val date_joined: String,
    val referral_code: String
)

/** PATCH /api/profile/ - name is the only editable field. */
data class UpdateProfileRequest(
    val name: String
)

data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
    val confirm_password: String
)

/** The fresh pair returned by a password change. */
data class TokenPairDto(
    val access: String,
    val refresh: String
)

data class LogoutRequest(
    val refresh: String
)
