package com.example.hawk_wallet.ui.screens.auth.login

data class LoginUiState(
    val mobileNumber: String = "",
    val password: String = "",
    val mobileError: String? = null,
    val passwordError: String? = null,
    val generalError: String? = null,
    val isLoading: Boolean = false,
    val otpRequestedFor: String? = null
)
