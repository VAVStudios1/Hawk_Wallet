package com.example.hawk_wallet.ui.screens.auth.register

data class RegisterUiState(
    val name: String = "",
    val mobileNumber: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    /** Optional; validated by the backend only. */
    val referralCode: String = "",
    val nameError: String? = null,
    val mobileError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val referralCodeError: String? = null,
    val generalError: String? = null,
    val isLoading: Boolean = false,
    val registeredMobileNumber: String? = null
)
