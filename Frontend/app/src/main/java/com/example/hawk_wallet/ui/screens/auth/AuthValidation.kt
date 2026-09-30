package com.example.hawk_wallet.ui.screens.auth

// Client-side checks only, for quick feedback. The backend validates
// everything again and its answer is the one that counts.

// Assumes 10-digit Indian mobile numbers (matches the backend's validator).
private val MOBILE_NUMBER_REGEX = Regex("^[6-9]\\d{9}$")

internal fun validateMobileNumber(mobileNumber: String): String? = when {
    mobileNumber.isBlank() -> "Mobile number is required."
    !MOBILE_NUMBER_REGEX.matches(mobileNumber) -> "Enter a valid 10-digit mobile number."
    else -> null
}

/** The same quick checks registration uses; Django's validators run on the server. */
internal fun validateNewPassword(password: String): String? = when {
    password.isBlank() -> "Password is required."
    password.length < 8 -> "Password must be at least 8 characters."
    password.all { it.isDigit() } -> "Password cannot be entirely numeric."
    else -> null
}

internal fun validatePasswordConfirmation(password: String, confirmation: String): String? =
    if (confirmation != password) "Passwords do not match." else null

/** Minutes, rounded up, for "try again in N minutes" style messages. */
internal fun minutesUntil(seconds: Int): Int = ((seconds + 59) / 60).coerceAtLeast(1)
