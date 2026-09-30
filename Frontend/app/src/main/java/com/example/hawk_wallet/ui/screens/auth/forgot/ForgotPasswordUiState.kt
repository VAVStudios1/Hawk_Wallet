package com.example.hawk_wallet.ui.screens.auth.forgot

import com.example.hawk_wallet.ui.screens.auth.otp.OtpUiState

/** Step 1: the mobile number to send the reset OTP to. */
data class ForgotPasswordMobileUiState(
    val mobileNumber: String = "",
    val mobileError: String? = null,
    val generalError: String? = null,
    val isSending: Boolean = false,
    /** One-shot: the request went through; open the OTP step. */
    val otpRequested: Boolean = false
)

/** Step 2: the reset OTP. */
data class ForgotPasswordOtpUiState(
    val otp: OtpUiState = OtpUiState(),
    /** One-shot: OTP accepted and a reset token is held; open the new-password step. */
    val verified: Boolean = false
)

/** Step 3: the new password. */
data class ResetPasswordUiState(
    val newPassword: String = "",
    val confirmPassword: String = "",
    val newPasswordError: String? = null,
    val confirmPasswordError: String? = null,
    val generalError: String? = null,
    val isSaving: Boolean = false,
    /** The reset token is gone (expired, used, or lost); only starting again helps. */
    val mustRestart: Boolean = false,
    /** One-shot: password reset; return to Login with this message. */
    val completedMessage: String? = null
)
