package com.example.hawk_wallet.ui.screens.auth.otp

/** OTP entry state. Used by registration verification and the password-reset flow. */
data class OtpUiState(
    val mobileNumber: String = "",
    val otp: String = "",
    val otpError: String? = null,
    val generalError: String? = null,
    val isVerifying: Boolean = false,
    val isResending: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val registrationVerified: Boolean = false
)
