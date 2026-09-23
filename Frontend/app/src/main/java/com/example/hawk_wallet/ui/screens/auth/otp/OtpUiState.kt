package com.example.hawk_wallet.ui.screens.auth.otp

data class OtpUiState(
    val mobileNumber: String = "",
    val purpose: String = "",
    val otp: String = "",
    val otpError: String? = null,
    val generalError: String? = null,
    val isVerifying: Boolean = false,
    val isResending: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val registrationVerified: Boolean = false,
    val loggedInRole: String? = null
)
