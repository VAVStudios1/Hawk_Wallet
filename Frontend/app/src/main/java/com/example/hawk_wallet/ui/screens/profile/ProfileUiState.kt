package com.example.hawk_wallet.ui.screens.profile

import com.example.hawk_wallet.data.remote.model.ProfileDto

/** GET /api/profile/, formatted for display only; the backend's values are the truth. */
data class ProfileUi(
    val name: String,
    val mobileNumber: String,
    val role: String,
    val isVerified: Boolean,
    val status: String,
    val referralCode: String
)

/** The Profile tab: header data plus the logout flow. */
data class ProfileUiState(
    val isLoading: Boolean = false,
    val profile: ProfileUi? = null,
    val errorMessage: String? = null,
    val isLoggingOut: Boolean = false,
    /** Set once the local session is cleared; the screen then opens Login. */
    val loggedOut: Boolean = false
)

/** Personal Information: read-only account details and the editable name. */
data class PersonalInfoUiState(
    val isLoading: Boolean = false,
    val profile: ProfileUi? = null,
    val loadError: String? = null,
    val name: String = "",
    val nameError: String? = null,
    val saveError: String? = null,
    val isSaving: Boolean = false,
    /** One-shot snackbar text after a successful save. */
    val savedMessage: String? = null
) {
    /** Save only when the trimmed name is non-blank and actually different. */
    val canSave: Boolean
        get() = profile != null && !isSaving && name.trim().let { it.isNotEmpty() && it != profile.name }
}

data class ChangePasswordUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val currentPasswordError: String? = null,
    val newPasswordError: String? = null,
    val confirmPasswordError: String? = null,
    val generalError: String? = null,
    val isSubmitting: Boolean = false,
    /** The backend's success message; set once the new tokens are stored. */
    val successMessage: String? = null
)

// Mirrors the backend's User.Role values (core/models.py); unknown values are shown as sent.
internal fun roleLabel(role: String): String = when (role) {
    "MEMBER" -> "Member"
    "ADMIN" -> "Admin"
    "SUPER_ADMIN" -> "Super Admin"
    else -> role
}

internal fun ProfileDto.toUi() = ProfileUi(
    name = name,
    mobileNumber = mobile_number,
    role = roleLabel(role),
    isVerified = is_verified,
    status = if (is_verified) "Verified" else "Not verified",
    referralCode = referral_code
)
