package com.example.hawk_wallet.ui.screens.auth.login

import com.example.hawk_wallet.data.local.SavedLogin

data class LoginUiState(
    val mobileNumber: String = "",
    val password: String = "",
    val mobileError: String? = null,
    val passwordError: String? = null,
    val generalError: String? = null,
    /** An informational message, e.g. why the user is back at Login. */
    val notice: String? = null,
    val isLoading: Boolean = false,
    /** One-shot: signed in with this role; the screen routes and consumes it. */
    val loggedInRole: String? = null,
    /**
     * With [loggedInRole]: a typed-in login to offer to Credential Manager
     * before leaving the screen. Null when the password came from there.
     */
    val credentialToSave: SavedLogin? = null,
    /** One-shot: the account is unverified; open registration OTP for this number. */
    val verificationRequiredFor: String? = null
)
